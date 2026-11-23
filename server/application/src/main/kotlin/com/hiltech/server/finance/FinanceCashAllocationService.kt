package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

data class FinanceCashAllocationProposeRequest(
    val operationId:UUID,
    val bankStatementLineId:UUID,
    val allocatedAmount:String,
    val reasonReference:String,
)
data class FinanceCashAllocationOperation(val operationId:UUID)
data class FinanceCashAllocationReceipt(
    val proposalId:UUID,
    val organizationId:UUID,
    val sourceKind:String,
    val sourceJournalId:UUID,
    val bankStatementLineId:UUID,
    val amount:String,
    val currency:String,
    val state:String,
    val approvalRequestId:UUID?=null,
    val recordedAllocationId:UUID?=null,
    val replayed:Boolean=false,
    val cashAllocatedToAccountingSource:Boolean=false,
    val moneyTransferredByHiltech:Boolean=false,
    val bankReconciled:Boolean=false,
)
private data class FinanceCashSourceRow(
    val amount:BigDecimal,val currency:String,val hash:String,
)
private data class FinanceCashBankRow(
    val amount:BigDecimal,val currency:String,val flow:String,
    val statementHash:String,
)

/** Audited recording of an already occurred, independently reviewed bank movement. */
@Service
class FinanceCashAllocationService(
    private val jdbc:JdbcTemplate,
    private val subject:FinanceCashAllocationApprovalSubject,
    private val approvals:ApprovalPersistencePort,
    private val engine:ApprovalEngineService,
    private val idempotency:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,
    private val environment:Environment,
    private val clock:Clock,
) {
    @Transactional
    fun propose(
        actor:UUID,org:UUID,kind:String,sourceJournal:UUID,
        request:FinanceCashAllocationProposeRequest,correlation:String,
    ):FinanceCashAllocationReceipt {
        authority(actor,org,"hiltech.finance.cash-allocation.preparer-authority-key")
        if(kind !in setOf("AP","AR"))unavailable()
        val amount=runCatching {BigDecimal(request.allocatedAmount)}
            .getOrNull() ?: unavailable()
        val reason=request.reasonReference.trim()
        if(reason.length !in 8..180)unavailable()
        val key=subject.policyKey() ?: unavailable()
        val now=clock.instant()
        val policy=approvals.loadActivePolicy(org,key,now)?.takeIf {
            it.organizationId==org && it.policyKey==key &&
                it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: unavailable()
        val reviewer=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,now,
        ) ?: unavailable()
        if(reviewer.organizationId!=org ||
            approvals.actorMatchesAuthority(actor,reviewer,now))unavailable()
        val table=if(kind=="AP")"finance_ap_posted_journal"
            else "finance_ar_posted_journal"
        val source=jdbc.query(
            """SELECT gross_amount,currency,ledger_fingerprint_sha256
               FROM $table WHERE id=? AND organization_id=?""",
            {rs,_ ->FinanceCashSourceRow(
                rs.getBigDecimal("gross_amount"),rs.getString("currency"),
                rs.getString("ledger_fingerprint_sha256"))},
            sourceJournal,org,
        ).singleOrNull() ?: stale()
        if(kind=="AP" && jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_ap_journal_reversal
               WHERE organization_id=? AND original_journal_id=?)""",
            Boolean::class.java,org,sourceJournal,
        )==true)stale()
        val bank=jdbc.query(
            """SELECT l.amount,l.currency,l.flow,h.statement_sha256
               FROM finance_bank_statement_intake_line l
               JOIN finance_bank_statement_intake h
                 ON h.id=l.intake_id AND h.organization_id=l.organization_id
               WHERE l.id=? AND l.organization_id=?""",
            {rs,_ ->FinanceCashBankRow(
                rs.getBigDecimal("amount"),rs.getString("currency"),
                rs.getString("flow"),rs.getString("statement_sha256"))},
            request.bankStatementLineId,org,
        ).singleOrNull() ?: stale()
        if(bank.currency!=source.currency ||
            bank.flow!=if(kind=="AR")"CREDIT" else "DEBIT")unavailable()
        val validated=runCatching {
            FinanceCashAllocationRules.validate(FinanceCashAllocationPlan(
                organizationId=org,sourceId=sourceJournal,
                sourceKind=if(kind=="AR")
                    FinanceCashSourceKind.CUSTOMER_RECEIVABLE
                else FinanceCashSourceKind.SUPPLIER_PAYABLE,
                sourcePostAmount=source.amount,sourceRecognized=true,
                sourceCurrency=source.currency,
                bankAccountId=UUID(0,0),
                bankTransactionId=request.bankStatementLineId,
                externalBankTransactionId="SOURCE-BOUND-IN-STMT",
                bankVerifiedSourceSha256=bank.statementHash,
                bankEvidenceFinalized=true,
                bankTransactionAmount=bank.amount,
                bankFlow=if(bank.flow=="CREDIT")FinanceBankFlow.CREDIT
                    else FinanceBankFlow.DEBIT,
                bankCurrency=bank.currency,
                bankValueDate=java.time.LocalDate.ofInstant(
                    now,java.time.ZoneOffset.UTC),
                allocationAmount=amount,
                priorSourceAllocated=BigDecimal.ZERO,
                priorBankAllocated=BigDecimal.ZERO,
                separateApprovalConfirmed=true,
            ))
        }.getOrNull() ?: unavailable()
        val version=FinanceCashReviewVersion.derive(
            org,kind,sourceJournal,request.bankStatementLineId,
            source.hash,bank.statementHash,source.currency,
            amount,reason,policy.versionNumber,reviewer.id,reviewer.version,
        )
        val id=UUID.nameUUIDFromBytes(
            "hiltech:cash-allocation:${request.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,kind,sourceJournal,request.bankStatementLineId,
                source.hash,bank.statementHash,validated.fingerprintSha256,
                version,reason).joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            request.operationId,actor,"FINANCE_CASH_ALLOCATION_PROPOSED",
            "FINANCE_CASH_ALLOCATION_PROPOSAL",id,fingerprint,correlation,
        )) {
            // Lock both sides, and ensure the approved immutable source did not
            // get reversed or over-allocated while this command was prepared.
            lockSourceAndBank(org,kind,sourceJournal,request.bankStatementLineId)
            if(subjectExistingLimit(org,kind,sourceJournal,
                request.bankStatementLineId,amount,source.amount,bank.amount))
                stale()
            check(jdbc.update(
                """
                INSERT INTO finance_cash_allocation_proposal(
                  id,operation_id,organization_id,source_kind,source_journal_id,
                  bank_statement_line_id,currency,allocated_amount,
                  source_journal_sha256,bank_statement_sha256,
                  approval_subject_version,policy_key,allocation_reason,
                  prepared_by_user_id,created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,request.operationId,org,kind,sourceJournal,
                request.bankStatementLineId,source.currency,amount,source.hash,
                bank.statementHash,version,key,reason,actor,
                clock.instant().atOffset(java.time.ZoneOffset.UTC),
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_CASH_ALLOCATION_PROPOSED",
                targetType="FINANCE_CASH_ALLOCATION_PROPOSAL",targetId=id,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="MANUAL_CONFIRMED_BANK_SOURCE_ALLOCATION_NOT_RECORDED_YET",
            ))
            IdempotentCommandOutcome(
                "FINANCE_CASH_ALLOCATION_PROPOSED","""{"id":"$id"}""",
            )
        }
        return receipt(id,org,kind,sourceJournal,request.bankStatementLineId,
            amount,source.currency,"PROPOSED",replayed=outcome.replayed)
    }
    fun requestApproval(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceCashAllocationReceipt {
        authority(actor,org,"hiltech.finance.cash-allocation.preparer-authority-key")
        val snap=subject.current(org,id) ?: stale()
        if(snap.preparedBy!=actor)unavailable()
        val ref=ApprovalSubjectRef(
            org,FINANCE_AP_AR_CASH_ALLOCATION_SUBJECT,id,snap.subjectVersion,
        )
        val old=engine.subjectStatus(ref,snap.policyKey)
        if(old!=null) {
            if(old.state!=ApprovalRequestState.PENDING)unavailable()
            return receipt(snap,"PENDING",approval=old.approvalRequestId,replayed=true)
        }
        val requested=engine.requestException(
            actorUserId=actor,operationId=operation,subject=ref,
            policyKey=snap.policyKey,
            reasonCode="FINANCE_AP_AR_CASH_ALLOCATION_REVIEW",
            safeReasonSummary=null,correlationId=correlation,
            allowAuthorityBypass=false,
        )
        if(!requested.approvalRequired ||
            requested.approvalRequestId==null ||
            requested.state!=ApprovalRequestState.PENDING ||
            requested.policyKey!=snap.policyKey ||
            requested.policyVersion!=snap.policyVersion)unavailable()
        return receipt(snap,"PENDING",approval=requested.approvalRequestId,
            replayed=requested.replayed)
    }
    @Transactional
    fun record(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceCashAllocationReceipt {
        authority(actor,org,"hiltech.finance.cash-allocation.operator-authority-key")
        historic(org,id)?.let { old->
            val prior=jdbc.query(
                """SELECT operation_id,recorded_by_user_id
                   FROM finance_cash_allocation_record
                   WHERE id=? AND organization_id=?""",
                {rs,_ ->rs.getObject("operation_id",UUID::class.java) to
                    rs.getObject("recorded_by_user_id",UUID::class.java)},
                old.recordedAllocationId,org,
            ).singleOrNull() ?: unavailable()
            if(prior.first!=operation || prior.second!=actor)unavailable()
            return old.copy(replayed=true)
        }
        val snap=subject.current(org,id) ?: stale()
        if(snap.preparedBy==actor)unavailable()
        val ref=ApprovalSubjectRef(
            org,FINANCE_AP_AR_CASH_ALLOCATION_SUBJECT,id,snap.subjectVersion,
        )
        val approved=engine.subjectStatus(ref,snap.policyKey)?.takeIf {
            it.state==ApprovalRequestState.APPROVED &&
                it.subjectVersion==snap.subjectVersion
        } ?: unavailable()
        val allocationId=UUID.nameUUIDFromBytes(
            "hiltech:cash-allocated:$operation".toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,id,snap.subjectVersion,approved.approvalRequestId,
                snap.sourceSha256,snap.bankStatementSha256).joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            operation,actor,"FINANCE_CASH_ALLOCATION_RECORDED",
            "FINANCE_CASH_ALLOCATION_RECORD",allocationId,fingerprint,correlation,
        )) {
            lockSourceAndBank(org,snap.sourceKind,snap.sourceJournalId,
                snap.bankStatementLineId)
            if(subject.current(org,id)!=snap)stale()
            check(jdbc.update(
                """
                INSERT INTO finance_cash_allocation_record(
                  id,operation_id,organization_id,proposal_id,
                  source_kind,source_journal_id,bank_statement_line_id,
                  approval_request_id,approval_subject_version,policy_key,
                  currency,allocated_amount,recorded_by_user_id,
                  recorded_at,correlation_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                allocationId,operation,org,id,snap.sourceKind,snap.sourceJournalId,
                snap.bankStatementLineId,approved.approvalRequestId,
                snap.subjectVersion,snap.policyKey,snap.currency,
                snap.allocationAmount,actor,
                clock.instant().atOffset(java.time.ZoneOffset.UTC),correlation,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_CASH_ALLOCATION_RECORDED",
                targetType="FINANCE_CASH_ALLOCATION_RECORD",targetId=allocationId,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="BANK_SOURCE_MATCHED_ACCOUNTING_ONLY_NOT_BANK_RECONCILIATION",
            ))
            IdempotentCommandOutcome(
                "FINANCE_CASH_ALLOCATION_RECORDED","""{"id":"$allocationId"}""",
            )
        }
        return receipt(snap,"RECORDED",approval=approved.approvalRequestId,
            recorded=allocationId,replayed=outcome.replayed)
    }
    fun status(actor:UUID,org:UUID,id:UUID):FinanceCashAllocationReceipt {
        authority(actor,org,"hiltech.finance.cash-allocation.preparer-authority-key")
        historic(org,id)?.let{return it}
        val snap=subject.current(org,id) ?: stale()
        val status=engine.subjectStatus(
            ApprovalSubjectRef(org,FINANCE_AP_AR_CASH_ALLOCATION_SUBJECT,
                id,snap.subjectVersion),snap.policyKey,
        )
        return receipt(snap,status?.state?.name ?: "PROPOSED",
            approval=status?.approvalRequestId)
    }
    private fun historic(org:UUID,id:UUID):FinanceCashAllocationReceipt? =
        jdbc.query(
            """SELECT id,source_kind,source_journal_id,bank_statement_line_id,
               allocated_amount,currency,approval_request_id
               FROM finance_cash_allocation_record
               WHERE organization_id=? AND proposal_id=?""",
            {rs,_ ->receipt(id,org,rs.getString("source_kind"),
                rs.getObject("source_journal_id",UUID::class.java),
                rs.getObject("bank_statement_line_id",UUID::class.java),
                rs.getBigDecimal("allocated_amount"),rs.getString("currency"),
                "RECORDED",
                approval=rs.getObject("approval_request_id",UUID::class.java),
                recorded=rs.getObject("id",UUID::class.java))},org,id,
        ).singleOrNull()
    private fun receipt(
        s:FinanceCashVerifiedProposal,state:String,approval:UUID?=null,
        recorded:UUID?=null,replayed:Boolean=false,
    )=receipt(s.proposalId,s.organizationId,s.sourceKind,s.sourceJournalId,
        s.bankStatementLineId,s.allocationAmount,s.currency,state,
        approval,recorded,replayed)
    private fun receipt(
        id:UUID,org:UUID,kind:String,journal:UUID,bank:UUID,
        amount:BigDecimal,currency:String,state:String,
        approval:UUID?=null,recorded:UUID?=null,replayed:Boolean=false,
    )=FinanceCashAllocationReceipt(
        id,org,kind,journal,bank,amount.toPlainString(),currency,state,
        approvalRequestId=approval,recordedAllocationId=recorded,
        replayed=replayed,cashAllocatedToAccountingSource=recorded!=null,
    )
    private fun lockSourceAndBank(org:UUID,kind:String,journal:UUID,bank:UUID) {
        val table=when(kind){
            "AP"->"finance_ap_posted_journal"
            "AR"->"finance_ar_posted_journal"
            else->unavailable()
        }
        if(jdbc.query(
            """SELECT id FROM $table WHERE id=? AND organization_id=? FOR UPDATE""",
            {rs,_ ->rs.getObject("id",UUID::class.java)},journal,org,
        ).singleOrNull()!=journal)stale()
        if(jdbc.query(
            """SELECT id FROM finance_bank_statement_intake_line
               WHERE id=? AND organization_id=? FOR UPDATE""",
            {rs,_ ->rs.getObject("id",UUID::class.java)},bank,org,
        ).singleOrNull()!=bank)stale()
    }
    private fun subjectExistingLimit(
        org:UUID,kind:String,journal:UUID,bank:UUID,
        proposed:BigDecimal,sourceAmount:BigDecimal,bankAmount:BigDecimal,
    ):Boolean {
        val all=jdbc.queryForList(
            """SELECT source_kind,source_journal_id,bank_statement_line_id,
               allocated_amount FROM finance_cash_allocation_record
               WHERE organization_id=? AND
                 ((source_kind=? AND source_journal_id=?) OR bank_statement_line_id=?)""",
            org,kind,journal,bank,
        )
        val s=all.filter {
            it["source_kind"]==kind && it["source_journal_id"]==journal
        }.fold(BigDecimal.ZERO){a,v->a.add(v["allocated_amount"] as BigDecimal)}
        val b=all.filter {
            it["bank_statement_line_id"]==bank
        }.fold(BigDecimal.ZERO){a,v->a.add(v["allocated_amount"] as BigDecimal)}
        return s+proposed>sourceAmount || b+proposed>bankAmount
    }
    private fun authority(actor:UUID,org:UUID,property:String){
        if(!subject.enabled())unavailable()
        val key=environment.getProperty(property,"").trim()
        if(!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key))unavailable()
        val now=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,now))unavailable()
        val a=approvals.resolveCurrentAuthority(org,key,now) ?: unavailable()
        if(a.organizationId!=org || !approvals.actorMatchesAuthority(actor,a,now))
            unavailable()
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_CASH_ALLOCATION_NOT_AUTHORIZED",
        "Approved posted AP/AR and company bank source authority required.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing=throw ProductApiException(
        "FINANCE_CASH_ALLOCATION_STALE",
        "Bank transaction or accounts balance changed.",
        HttpStatus.CONFLICT,
    )
}
@RestController
class FinanceCashAllocationController(private val finance:FinanceCashAllocationService){
    @PostMapping("/v1/finance/organizations/{orgId}/posted-journals/{kind}/{journalId}/cash-allocation-proposals")
    fun propose(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable kind:String,@PathVariable journalId:UUID,
        @RequestBody body:FinanceCashAllocationProposeRequest,
    ):FinanceCashAllocationReceipt{
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return finance.propose(actor(http),orgId,kind,journalId,body,ctx.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/cash-allocation-proposals/{id}/request-approval")
    fun request(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceCashAllocationOperation,
    ):FinanceCashAllocationReceipt{
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return finance.requestApproval(actor(http),orgId,id,body.operationId,ctx.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/cash-allocation-proposals/{id}/record")
    fun record(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceCashAllocationOperation,
    ):FinanceCashAllocationReceipt{
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return finance.record(actor(http),orgId,id,body.operationId,ctx.correlationId)
    }
    @GetMapping("/v1/finance/organizations/{orgId}/cash-allocation-proposals/{id}")
    fun status(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID):FinanceCashAllocationReceipt =
        finance.status(actor(http),orgId,id)
    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let {runCatching{UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Finance login required.",HttpStatus.UNAUTHORIZED)
}
