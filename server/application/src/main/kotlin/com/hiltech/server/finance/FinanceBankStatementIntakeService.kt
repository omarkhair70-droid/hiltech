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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class FinanceBankIntakeLineRequest(
    val externalTransactionId:String,
    val bookedOn:LocalDate,
    val flow:FinanceBankFlow,
    val amount:String,
    val originalLineSha256:String,
    val currency:String?=null,
)
data class FinanceBankIntakePrepareRequest(
    val operationId:UUID,
    val bankAccountId:UUID,
    val statementEvidenceId:UUID,
    val statementSha256:String,
    val periodStart:LocalDate,
    val periodEnd:LocalDate,
    val openingBalance:String,
    val closingBalance:String,
    val lines:List<FinanceBankIntakeLineRequest>,
)
data class FinanceBankIntakeOperation(val operationId:UUID)
data class FinanceBankIntakeReceipt(
    val proposalId:UUID,val organizationId:UUID,
    val bankAccountId:UUID,val statementHash:String,
    val lineHash:String,val lineCount:Int,
    val state:String,val approvalRequestId:UUID?=null,
    val intakeId:UUID?=null,val replayed:Boolean=false,
    val internalBankStatementRecorded:Boolean=false,
    val bankTransactionInitiated:Boolean=false,
    val bankReconciled:Boolean=false,
)
private data class FinanceBankAccountLookup(
    val currency:String,val state:String,
)

/**
 * Manual bank statement ledger intake. This does NOT connect to a bank,
 * initiate payment, confirm a gateway response or reconcile funds.
 *
 * Evidence must have been registered against the deterministic proposal ID.
 * Source remains unavailable until uploaded bytes are finalized by evidence
 * service, and the exact immutable lines pass an independent review.
 */
@Service
class FinanceBankStatementIntakeService(
    private val jdbc:JdbcTemplate,
    private val subject:FinanceBankStatementIntakeApprovalSubject,
    private val approvals:ApprovalPersistencePort,
    private val engine:ApprovalEngineService,
    private val idempotency:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,
    private val environment:Environment,
    private val clock:Clock,
) {
    @Transactional
    fun prepare(
        actor:UUID,org:UUID,request:FinanceBankIntakePrepareRequest,
        correlation:String,
    ):FinanceBankIntakeReceipt {
        authority(actor,org,
            "hiltech.finance.bank-statement-intake.preparer-authority-key")
        val policyKey=subject.policyKey() ?: unavailable()
        val now=clock.instant()
        val policy=approvals.loadActivePolicy(org,policyKey,now)?.takeIf{
            it.organizationId==org && it.policyKey==policyKey &&
                it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: unavailable()
        val reviewer=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,now,
        ) ?: unavailable()
        if(reviewer.organizationId!=org ||
            approvals.actorMatchesAuthority(actor,reviewer,now))unavailable()
        val bank=jdbc.query(
            """SELECT currency,state FROM finance_bank_account_register
               WHERE id=? AND organization_id=?""",
            {rs,_ ->FinanceBankAccountLookup(
                rs.getString("currency"),rs.getString("state"))},
            request.bankAccountId,org,
        ).singleOrNull() ?: unavailable()
        if(bank.state!="APPROVED")unavailable()
        val id=UUID.nameUUIDFromBytes(
            "hiltech:bank-statement-intake:${request.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        // An evidence UUID, by itself, is NOT proof of an authentic statement.
        if(!subject.evidenceReady(
            org,id,request.statementEvidenceId,request.statementSha256,
        ))unavailable()
        val opening=runCatching{BigDecimal(request.openingBalance)}
            .getOrNull() ?: unavailable()
        val closing=runCatching{BigDecimal(request.closingBalance)}
            .getOrNull() ?: unavailable()
        val lines=request.lines.map {
            // A structured file must not silently become the registered
            // bank account currency when its original row says otherwise.
            if(it.currency!=null && it.currency!=bank.currency)unavailable()
            FinanceBankIntakeSourceLine(
                it.externalTransactionId.trim(),it.bookedOn,it.flow,
                bank.currency,
                runCatching{BigDecimal(it.amount)}.getOrNull() ?: unavailable(),
                it.originalLineSha256,
            )
        }
        val prepared=FinanceBankIntakeSource(
            organizationId=org,bankAccountId=request.bankAccountId,
            evidenceId=request.statementEvidenceId,
            evidenceSha256=request.statementSha256,
            currency=bank.currency,
            statementStart=request.periodStart,statementEnd=request.periodEnd,
            openingBalance=opening,closingBalance=closing,
            lines=lines,companyBankAccountApproved=true,
            finalizedEvidenceReviewed=true,
        )
        val verified=runCatching{FinanceBankIntakeRules.validate(prepared)}
            .getOrNull() ?: unavailable()
        val sourceVersion=FinanceBankStatementReviewVersion.derive(
            org,request.bankAccountId,request.statementEvidenceId,
            request.statementSha256,verified.sourceLinesSha256,
            request.periodStart,request.periodEnd,opening,closing,
            verified.lineCount,policy.versionNumber,
            reviewer.id,reviewer.version,
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,request.bankAccountId,request.statementEvidenceId,
                request.statementSha256,verified.sourceLinesSha256,
                sourceVersion,verified.lineCount).joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            request.operationId,actor,"FINANCE_BANK_INTAKE_PROPOSED",
            "FINANCE_BANK_STATEMENT_INTAKE_PROPOSAL",id,
            fingerprint,correlation,
        )){
            val locked=jdbc.query(
                """SELECT id FROM finance_bank_account_register
                   WHERE id=? AND organization_id=? AND state='APPROVED'
                   FOR UPDATE""",
                {rs,_ ->rs.getObject("id",UUID::class.java)},
                request.bankAccountId,org,
            ).singleOrNull() ?: stale()
            if(locked!=request.bankAccountId ||
                !subject.evidenceReady(
                    org,id,request.statementEvidenceId,request.statementSha256,
                ))stale()
            val created=jdbc.update(
                """
                INSERT INTO finance_bank_statement_intake_proposal(
                    id,operation_id,organization_id,account_id,
                    statement_evidence_id,statement_sha256,
                    statement_lines_sha256,date_start,date_end,
                    opening_balance,closing_balance,expected_line_count,
                    source_version,policy_key,prepared_by_user_id,created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,request.operationId,org,request.bankAccountId,
                request.statementEvidenceId,request.statementSha256,
                verified.sourceLinesSha256,request.periodStart,request.periodEnd,
                opening,closing,verified.lineCount,sourceVersion,policyKey,
                actor,clock.instant().atOffset(ZoneOffset.UTC),
            )
            check(created==1)
            lines.forEachIndexed {idx,line ->
                check(jdbc.update(
                    """
                    INSERT INTO finance_bank_statement_intake_proposal_line(
                        id,organization_id,proposal_id,line_number,
                        external_transaction_id,booked_on,flow,currency,
                        amount,original_line_sha256
                    ) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent(),
                    UUID.nameUUIDFromBytes(
                        "hiltech:bank-prepared-line:$id:${idx+1}"
                            .toByteArray(Charsets.UTF_8),
                    ),org,id,idx+1,line.externalTransactionId,
                    line.bookedOn,line.flow.name,line.currency,
                    line.amount,line.originalLineSha256,
                )==1)
            }
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_BANK_INTAKE_PROPOSED",
                targetType="FINANCE_BANK_STATEMENT_INTAKE_PROPOSAL",targetId=id,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="COMPANY_REVIEW_REQUIRED_NO_BANK_GATEWAY_TRANSFER",
            ))
            IdempotentCommandOutcome(
                "FINANCE_BANK_INTAKE_PROPOSED","""{"id":"$id"}""",
            )
        }
        return FinanceBankIntakeReceipt(
            id,org,request.bankAccountId,request.statementSha256,
            verified.sourceLinesSha256,verified.lineCount,"PROPOSED",
            replayed=outcome.replayed,
        )
    }
    fun requestReview(
        actor:UUID,org:UUID,proposal:UUID,operation:UUID,
        correlation:String,
    ):FinanceBankIntakeReceipt {
        authority(actor,org,
            "hiltech.finance.bank-statement-intake.preparer-authority-key")
        val snap=subject.current(org,proposal) ?: stale()
        if(snap.preparedBy!=actor)unavailable()
        val ref=ApprovalSubjectRef(org,FINANCE_BANK_STATEMENT_INTAKE_SUBJECT,
            proposal,snap.reviewVersion)
        val existing=engine.subjectStatus(ref,snap.policyKey)
        if(existing!=null) {
            if(existing.state!=ApprovalRequestState.PENDING)unavailable()
            return receipt(snap,"PENDING",existing.approvalRequestId,
                replayed=true)
        }
        val requested=engine.requestException(
            actorUserId=actor,operationId=operation,
            subject=ref,policyKey=snap.policyKey,
            reasonCode="FINANCE_BANK_STATEMENT_INTAKE_REVIEW",
            safeReasonSummary=null,correlationId=correlation,
            allowAuthorityBypass=false,
        )
        if(!requested.approvalRequired ||
            requested.approvalRequestId==null ||
            requested.state!=ApprovalRequestState.PENDING ||
            requested.policyKey!=snap.policyKey ||
            requested.policyVersion!=snap.policyVersion)unavailable()
        return receipt(snap,"PENDING",requested.approvalRequestId,
            replayed=requested.replayed)
    }
    @Transactional
    fun record(
        actor:UUID,org:UUID,proposal:UUID,operation:UUID,
        correlation:String,
    ):FinanceBankIntakeReceipt {
        authority(actor,org,
            "hiltech.finance.bank-statement-intake.operator-authority-key")
        historical(org,proposal)?.let {old ->
            val existing=jdbc.query(
                """SELECT operation_id,imported_by_user_id
                   FROM finance_bank_statement_intake
                   WHERE id=? AND organization_id=?""",
                {rs,_ ->rs.getObject("operation_id",UUID::class.java) to
                    rs.getObject("imported_by_user_id",UUID::class.java)},
                old.intakeId,org,
            ).singleOrNull() ?: unavailable()
            if(existing.first!=operation || existing.second!=actor)unavailable()
            return old.copy(replayed=true)
        }
        val snap=subject.current(org,proposal) ?: stale()
        if(snap.preparedBy==actor)unavailable()
        val ref=ApprovalSubjectRef(org,FINANCE_BANK_STATEMENT_INTAKE_SUBJECT,
            proposal,snap.reviewVersion)
        val approved=engine.subjectStatus(ref,snap.policyKey)?.takeIf {
            it.state==ApprovalRequestState.APPROVED &&
                it.subjectVersion==snap.reviewVersion
        } ?: unavailable()
        val id=UUID.nameUUIDFromBytes(
            "hiltech:bank-statement-record:$operation"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,proposal,snap.reviewVersion,snap.lineHash,
                approved.approvalRequestId).joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            operation,actor,"FINANCE_BANK_INTAKE_RECORDED",
            "FINANCE_BANK_STATEMENT_INTAKE",id,fingerprint,correlation,
        )){
            val locked=jdbc.query(
                """SELECT id FROM finance_bank_account_register
                   WHERE id=? AND organization_id=? AND state='APPROVED'
                   FOR UPDATE""",
                {rs,_ ->rs.getObject("id",UUID::class.java)},
                snap.accountId,org,
            ).singleOrNull() ?: stale()
            if(locked!=snap.accountId ||
                subject.current(org,proposal)!=snap)stale()
            val at=clock.instant().atOffset(ZoneOffset.UTC)
            check(jdbc.update(
                """
                INSERT INTO finance_bank_statement_intake(
                    id,operation_id,organization_id,proposal_id,account_id,
                    approval_request_id,statement_sha256,
                    statement_lines_sha256,statement_evidence_id,currency,
                    date_start,date_end,opening_balance,closing_balance,
                    expected_line_count,imported_by_user_id,imported_at,
                    correlation_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,operation,org,proposal,snap.accountId,
                approved.approvalRequestId,snap.statementHash,snap.lineHash,
                snap.statementEvidenceId,snap.currency,snap.periodStart,
                snap.periodEnd,snap.opening,snap.closing,snap.expectedCount,
                actor,at,correlation,
            )==1)
            snap.lines.forEach {line ->
                check(jdbc.update(
                    """
                    INSERT INTO finance_bank_statement_intake_line(
                        id,organization_id,intake_id,account_id,
                        external_transaction_id,booked_on,flow,currency,
                        amount,original_line_sha256
                    ) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent(),
                    UUID.nameUUIDFromBytes(
                        ("hiltech:bank-intake-line:$id:"+
                            line.externalTransactionId).toByteArray(Charsets.UTF_8),
                    ),org,id,snap.accountId,line.externalTransactionId,
                    line.bookedOn,line.flow.name,line.currency,
                    line.amount,line.originalLineSha256,
                )==1)
            }
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_BANK_INTAKE_RECORDED",
                targetType="FINANCE_BANK_STATEMENT_INTAKE",targetId=id,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="MANUALLY_APPROVED_STATEMENT_NO_MONEY_TRANSFER",
            ))
            IdempotentCommandOutcome(
                "FINANCE_BANK_INTAKE_RECORDED","""{"id":"$id"}""",
            )
        }
        return receipt(snap,"RECORDED",
            approval=approved.approvalRequestId,intake=id,
            replayed=outcome.replayed)
    }
    fun status(actor:UUID,org:UUID,proposal:UUID):FinanceBankIntakeReceipt {
        authority(actor,org,
            "hiltech.finance.bank-statement-intake.preparer-authority-key")
        historical(org,proposal)?.let{return it}
        val snap=subject.current(org,proposal) ?: stale()
        val status=engine.subjectStatus(
            ApprovalSubjectRef(org,FINANCE_BANK_STATEMENT_INTAKE_SUBJECT,
                proposal,snap.reviewVersion),snap.policyKey,
        )
        return receipt(snap,status?.state?.name ?: "PROPOSED",
            status?.approvalRequestId)
    }
    private fun historical(org:UUID,proposal:UUID):FinanceBankIntakeReceipt? =
        jdbc.query(
            """
            SELECT id,account_id,statement_sha256,statement_lines_sha256,
                expected_line_count,approval_request_id
            FROM finance_bank_statement_intake
            WHERE organization_id=? AND proposal_id=?
            """.trimIndent(),
            {rs,_ -> FinanceBankIntakeReceipt(
                proposal,org,rs.getObject("account_id",UUID::class.java),
                rs.getString("statement_sha256"),
                rs.getString("statement_lines_sha256"),
                rs.getInt("expected_line_count"),"RECORDED",
                approvalRequestId=rs.getObject("approval_request_id",UUID::class.java),
                intakeId=rs.getObject("id",UUID::class.java),
                internalBankStatementRecorded=true,
            )},org,proposal,
        ).singleOrNull()
    private fun receipt(
        s:FinanceBankStatementVerifiedProposal,state:String,
        approval:UUID?=null,intake:UUID?=null,replayed:Boolean=false,
    )=FinanceBankIntakeReceipt(
        proposalId=s.proposalId,organizationId=s.organizationId,
        bankAccountId=s.accountId,statementHash=s.statementHash,
        lineHash=s.lineHash,lineCount=s.expectedCount,state=state,
        approvalRequestId=approval,intakeId=intake,replayed=replayed,
        internalBankStatementRecorded=intake!=null,
    )
    private fun authority(actor:UUID,org:UUID,setting:String){
        if(!subject.enabled())unavailable()
        val key=environment.getProperty(setting,"").trim()
        if(!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key))unavailable()
        val now=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,now))unavailable()
        val binding=approvals.resolveCurrentAuthority(org,key,now)
            ?: unavailable()
        if(binding.organizationId!=org ||
            !approvals.actorMatchesAuthority(actor,binding,now))unavailable()
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_BANK_INTAKE_NOT_AUTHORIZED",
        "Approved bank source, company authority or finalized evidence missing.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing=throw ProductApiException(
        "FINANCE_BANK_INTAKE_STALE",
        "Bank statement source, approval or account binding changed.",
        HttpStatus.CONFLICT,
    )
}
@RestController
class FinanceBankStatementIntakeController(
    private val intake:FinanceBankStatementIntakeService,
    private val environment:Environment,
) {
    @PostMapping("/v1/finance/organizations/{orgId}/bank-statement-intakes/prepare")
    fun prepare(http:HttpServletRequest,@PathVariable orgId:UUID,
        @RequestBody body:FinanceBankIntakePrepareRequest,
    ):FinanceBankIntakeReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        // Plain manually entered rows cannot cryptographically prove that
        // their values came from the protected uploaded statement bytes.
        // Canonical CSV/byte-matched intake is the safe default.
        if(!environment.getProperty(
                "hiltech.finance.bank-statement-intake.manual-entry-enabled",
                Boolean::class.java,false,
            )) throw ProductApiException(
                "FINANCE_BANK_MANUAL_INTAKE_DISABLED",
                "Upload a canonical verified bank CSV instead of unbound manual rows.",
                HttpStatus.NOT_FOUND,
            )
        return intake.prepare(actor(http),orgId,body,ctx.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/bank-statement-intakes/{id}/request-review")
    fun review(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceBankIntakeOperation,
    ):FinanceBankIntakeReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return intake.requestReview(actor(http),orgId,id,body.operationId,ctx.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/bank-statement-intakes/{id}/record")
    fun record(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceBankIntakeOperation,
    ):FinanceBankIntakeReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return intake.record(actor(http),orgId,id,body.operationId,ctx.correlationId)
    }
    @GetMapping("/v1/finance/organizations/{orgId}/bank-statement-intakes/{id}")
    fun status(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID):FinanceBankIntakeReceipt =
        intake.status(actor(http),orgId,id)
    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Finance identity required.",HttpStatus.UNAUTHORIZED)
}
