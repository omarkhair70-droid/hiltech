package com.hiltech.server.finance

import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority

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
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

data class FinanceCashVoidPrepareRequest(
    val operationId:UUID,
    val reasonReference:String,
)
data class FinanceCashVoidOperation(val operationId:UUID)
data class FinanceCashVoidReceipt(
    val proposalId:UUID,val organizationId:UUID,
    val originalAllocationId:UUID,val sourceKind:String,
    val sourceJournalId:UUID,val bankStatementLineId:UUID,
    val amount:String,val currency:String,val state:String,
    val approvalRequestId:UUID?=null,
    val correctionRecordId:UUID?=null,val replayed:Boolean=false,
    val originalRecordPreserved:Boolean=true,
    val originalMatchStillEffective:Boolean=true,
    val bankRefundInitiated:Boolean=false,
    val generalLedgerReversed:Boolean=false,
)

/** A bank match is a reversible association, not a physical bank movement.
 *  Every correction needs a separately recorded, independently approved event.
 */
@Service
class FinanceCashVoidService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc:JdbcTemplate,
    private val subject:FinanceCashVoidApprovalSubject,
    private val approvals:ApprovalPersistencePort,
    private val engine:ApprovalEngineService,
    private val idempotency:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,
    private val environment:Environment,
    private val clock:Clock,
    private val operationalAuthority: OperationalAuthorityPort? = null,
) {
    @Transactional
    fun prepare(
        actor:UUID,org:UUID,originalId:UUID,
        req:FinanceCashVoidPrepareRequest,correlation:String,
    ):FinanceCashVoidReceipt {
        authority(actor,org,"hiltech.finance.cash-allocation-void.preparer-authority-key")
        val original=subject.original(org,originalId) ?: stale()
        if(actor in setOf(original.originalPreparedBy,original.originalRecordedBy))
            unavailable()
        val reason=req.reasonReference.trim()
        if(reason.length !in 8..180)unavailable()
        val key=subject.policyKey() ?: unavailable()
        val now=clock.instant()
        val policy=approvals.loadActivePolicy(org,key,now)?.takeIf{
            it.organizationId==org && it.policyKey==key &&
                it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: unavailable()
        val reviewer=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,now,
        ) ?: unavailable()
        if(reviewer.organizationId!=org ||
            approvals.actorMatchesAuthority(actor,reviewer,now))unavailable()
        val version=FinanceCashVoidReviewVersion.derive(
            org,originalId,original.kind,original.journalId,original.bankLineId,
            original.sourceHash,original.bankHash,original.amount,
            original.currency,reason,policy.versionNumber,
            reviewer.id,reviewer.version,
        )
        val id=UUID.nameUUIDFromBytes(
            "hiltech:finance-cash-match-correction:${req.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,originalId,original.kind,original.journalId,
                original.bankLineId,original.amount,original.currency,
                original.sourceHash,original.bankHash,version,reason)
                .joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            req.operationId,actor,"FINANCE_CASH_MATCH_CORRECTION_PROPOSED",
            "FINANCE_CASH_ALLOCATION_VOID_PROPOSAL",id,
            fingerprint,correlation,
        )){
            lockSource(org,original)
            if(subject.original(org,originalId)!=original)stale()
            check(jdbc.update(
                """
                INSERT INTO finance_cash_allocation_void_proposal(
                   id,operation_id,organization_id,original_record_id,
                   source_kind,source_journal_id,bank_statement_line_id,
                   currency,amount,source_ledger_sha256,bank_statement_sha256,
                   approval_subject_version,policy_key,reason_reference,
                   prepared_by_user_id,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,req.operationId,org,originalId,
                original.kind,original.journalId,original.bankLineId,
                original.currency,original.amount,original.sourceHash,
                original.bankHash,version,key,reason,actor,
                clock.instant().atOffset(ZoneOffset.UTC),
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_CASH_MATCH_CORRECTION_PROPOSED",
                targetType="FINANCE_CASH_ALLOCATION_VOID_PROPOSAL",
                targetId=id,occurredAt=clock.instant(),correlationId=correlation,
                reason="INDEPENDENT_REVIEW_REQUIRED_NO_BANK_REFUND",
            ))
            IdempotentCommandOutcome(
                "FINANCE_CASH_MATCH_CORRECTION_PROPOSED","""{"id":"$id"}""",
            )
        }
        return FinanceCashVoidReceipt(
            id,org,originalId,original.kind,original.journalId,
            original.bankLineId,original.amount.toPlainString(),
            original.currency,"PROPOSED",replayed=outcome.replayed,
        )
    }
    fun requestApproval(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceCashVoidReceipt {
        authority(actor,org,"hiltech.finance.cash-allocation-void.preparer-authority-key")
        val snap=subject.current(org,id) ?: stale()
        if(snap.preparedBy!=actor)unavailable()
        val ref=ApprovalSubjectRef(
            org,FINANCE_CASH_ALLOCATION_VOID_SUBJECT,id,snap.reviewVersion,
        )
        val old=engine.subjectStatus(ref,snap.policyKey)
        if(old!=null) {
            if(old.state!=ApprovalRequestState.PENDING)unavailable()
            return receipt(snap,"PENDING",old.approvalRequestId,replayed=true)
        }
        val requested=engine.requestException(
            actorUserId=actor,operationId=operation,subject=ref,
            policyKey=snap.policyKey,
            reasonCode="FINANCE_CASH_ALLOCATION_CORRECTION_REVIEW",
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
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceCashVoidReceipt {
        authority(actor,org,"hiltech.finance.cash-allocation-void.operator-authority-key")
        historic(org,id)?.let {old->
            val stored=jdbc.query(
                """SELECT operation_id,recorded_by_user_id
                   FROM finance_cash_allocation_void_record
                   WHERE id=? AND organization_id=?""",
                {rs,_ ->rs.getObject("operation_id",UUID::class.java) to
                    rs.getObject("recorded_by_user_id",UUID::class.java)},
                old.correctionRecordId,org,
            ).singleOrNull() ?: unavailable()
            if(stored.first!=operation || stored.second!=actor)unavailable()
            return old.copy(replayed=true)
        }
        val snap=subject.current(org,id) ?: stale()
        if(actor in setOf(
            snap.preparedBy,snap.originalPreparedBy,snap.originalRecordedBy))
            unavailable()
        val ref=ApprovalSubjectRef(
            org,FINANCE_CASH_ALLOCATION_VOID_SUBJECT,id,snap.reviewVersion,
        )
        val approved=engine.subjectStatus(ref,snap.policyKey)?.takeIf {
            it.state==ApprovalRequestState.APPROVED &&
                it.subjectVersion==snap.reviewVersion
        } ?: unavailable()
        val recordId=UUID.nameUUIDFromBytes(
            "hiltech:finance-cash-match-corrected:$operation"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,id,snap.originalId,snap.reviewVersion,
                approved.approvalRequestId,snap.sourceHash,snap.bankHash)
                .joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            operation,actor,"FINANCE_CASH_MATCH_CORRECTED",
            "FINANCE_CASH_ALLOCATION_VOID_RECORD",
            recordId,fingerprint,correlation,
        )){
            lockSource(org,FinanceCashVoidSource(
                snap.originalId,snap.kind,snap.journalId,snap.bankLineId,
                snap.amount,snap.currency,snap.originalPreparedBy,
                snap.originalRecordedBy,snap.sourceHash,snap.bankHash,
                UUID(0,0),true,
            ))
            if(subject.current(org,id)!=snap)stale()
            check(jdbc.update(
                """
                INSERT INTO finance_cash_allocation_void_record(
                  id,operation_id,organization_id,proposal_id,
                  original_record_id,source_kind,source_journal_id,
                  bank_statement_line_id,amount,currency,
                  approval_request_id,recorded_by_user_id,
                  recorded_at,correlation_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                recordId,operation,org,id,snap.originalId,snap.kind,
                snap.journalId,snap.bankLineId,snap.amount,snap.currency,
                approved.approvalRequestId,actor,
                clock.instant().atOffset(ZoneOffset.UTC),correlation,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_CASH_MATCH_CORRECTED",
                targetType="FINANCE_CASH_ALLOCATION_VOID_RECORD",
                targetId=recordId,occurredAt=clock.instant(),
                correlationId=correlation,
                reason="APPROVED_ASSOCIATION_CORRECTION_NO_BANK_REFUND_NO_GL_REVERSAL",
            ))
            IdempotentCommandOutcome(
                "FINANCE_CASH_MATCH_CORRECTED","""{"id":"$recordId"}""",
            )
        }
        return receipt(snap,"CORRECTED",approved.approvalRequestId,
            recordId,outcome.replayed)
    }
    /** Only the original Finance operator role can discover record-ready work. */
    fun operatorQueue(actor:UUID,org:UUID):List<FinanceCashVoidReceipt> {
        authority(actor,org,"hiltech.finance.cash-allocation-void.operator-authority-key")
        val ids=jdbc.query(
            """SELECT p.id FROM finance_cash_allocation_void_proposal p
               WHERE p.organization_id=?
               AND NOT EXISTS (SELECT 1 FROM finance_cash_allocation_void_record r
                   WHERE r.organization_id=p.organization_id AND r.proposal_id=p.id)
               ORDER BY p.created_at DESC,p.id LIMIT 100""",
            {rs,_->rs.getObject("id",UUID::class.java)},org,
        )
        return ids.mapNotNull { id ->
            val snap=subject.current(org,id) ?: return@mapNotNull null
            if (!(actor !in setOf(snap.preparedBy,snap.originalPreparedBy,snap.originalRecordedBy))) return@mapNotNull null
            val current=status(actor,org,id)
            current.takeIf { it.state=="APPROVED" && it.approvalRequestId!=null }
        }
    }
    fun status(actor:UUID,org:UUID,id:UUID):FinanceCashVoidReceipt {
        val operator=try {
            authority(actor,org,"hiltech.finance.cash-allocation-void.operator-authority-key")
            true
        } catch (_:ProductApiException) { false }
        if(!operator) authority(actor,org,"hiltech.finance.cash-allocation-void.preparer-authority-key")
        historic(org,id)?.let{return it}
        val snap=subject.current(org,id) ?: stale()
        val state=engine.subjectStatus(
            ApprovalSubjectRef(org,FINANCE_CASH_ALLOCATION_VOID_SUBJECT,
                id,snap.reviewVersion),snap.policyKey,
        )
        return receipt(snap,state?.state?.name ?:"PROPOSED",
            state?.approvalRequestId)
    }
    private fun historic(org:UUID,id:UUID):FinanceCashVoidReceipt?=
        jdbc.query(
            """
            SELECT r.id,r.original_record_id,r.source_kind,
              r.source_journal_id,r.bank_statement_line_id,
              r.amount,r.currency,r.approval_request_id
            FROM finance_cash_allocation_void_record r
            WHERE r.organization_id=? AND r.proposal_id=?
            """.trimIndent(),
            {rs,_ ->FinanceCashVoidReceipt(
                id,org,rs.getObject("original_record_id",UUID::class.java),
                rs.getString("source_kind"),
                rs.getObject("source_journal_id",UUID::class.java),
                rs.getObject("bank_statement_line_id",UUID::class.java),
                rs.getBigDecimal("amount").toPlainString(),
                rs.getString("currency"),"CORRECTED",
                approvalRequestId=rs.getObject("approval_request_id",UUID::class.java),
                correctionRecordId=rs.getObject("id",UUID::class.java),
                originalMatchStillEffective=false,
            )},org,id,
        ).singleOrNull()
    private fun receipt(
        s:FinanceCashVoidVerified,state:String,
        approval:UUID?=null,recordId:UUID?=null,replayed:Boolean=false,
    )=FinanceCashVoidReceipt(
        s.id,s.org,s.originalId,s.kind,s.journalId,s.bankLineId,
        s.amount.toPlainString(),s.currency,state,
        approvalRequestId=approval,correctionRecordId=recordId,
        replayed=replayed,originalMatchStillEffective=recordId==null,
    )
    private fun lockSource(org:UUID,s:FinanceCashVoidSource){
        val table=if(s.kind=="AP")"finance_ap_posted_journal"
            else "finance_ar_posted_journal"
        if(jdbc.query(
            """SELECT id FROM $table WHERE id=? AND organization_id=? FOR UPDATE""",
            {rs,_ ->rs.getObject("id",UUID::class.java)},
            s.journalId,org,
        ).singleOrNull()!=s.journalId)stale()
        if(jdbc.query(
            """SELECT id FROM finance_bank_statement_intake_line
               WHERE id=? AND organization_id=? FOR UPDATE""",
            {rs,_ ->rs.getObject("id",UUID::class.java)},
            s.bankLineId,org,
        ).singleOrNull()!=s.bankLineId)stale()
    }
    private fun authority(actor: UUID, org: UUID, keySetting: String) {
        if (!subject.enabled()) unavailable()
        currentOperationalAuthority(operationalAuthority, approvals, actor, org, keySetting,
            environment.getProperty(keySetting), clock.instant()) ?: unavailable()
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_CASH_CORRECTION_NOT_AUTHORIZED",
        "Independent approved accounting-match correction required.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing=throw ProductApiException(
        "FINANCE_CASH_CORRECTION_STALE",
        "Original allocation was corrected, source changed, or fiscal period closed.",
        HttpStatus.CONFLICT,
    )
}
@RestController
class FinanceCashVoidController(private val corrections:FinanceCashVoidService) {
    @PostMapping("/v1/finance/organizations/{orgId}/cash-allocations/{originalId}/corrections/prepare")
    fun prepare(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable originalId:UUID,
        @RequestBody body:FinanceCashVoidPrepareRequest,
    ):FinanceCashVoidReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return corrections.prepare(
            actor(http),orgId,originalId,body,ctx.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/cash-allocation-corrections/{id}/request-review")
    fun request(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceCashVoidOperation,
    ):FinanceCashVoidReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return corrections.requestApproval(
            actor(http),orgId,id,body.operationId,ctx.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/cash-allocation-corrections/{id}/record")
    fun record(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceCashVoidOperation,
    ):FinanceCashVoidReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return corrections.record(
            actor(http),orgId,id,body.operationId,ctx.correlationId)
    }
    @GetMapping("/v1/finance/organizations/{orgId}/cash-allocation-corrections/operator-queue")
    fun operatorQueue(http:HttpServletRequest,@PathVariable orgId:UUID):List<FinanceCashVoidReceipt> =
        corrections.operatorQueue(actor(http),orgId)
    @GetMapping("/v1/finance/organizations/{orgId}/cash-allocation-corrections/{id}")
    fun status(
        http:HttpServletRequest,@PathVariable orgId:UUID,@PathVariable id:UUID,
    ):FinanceCashVoidReceipt = corrections.status(actor(http),orgId,id)
    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Finance identity required.",HttpStatus.UNAUTHORIZED)
}
