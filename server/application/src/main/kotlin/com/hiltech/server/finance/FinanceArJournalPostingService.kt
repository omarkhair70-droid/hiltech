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
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class FinanceArAllocationRequest(
    val glAccountId:UUID,val side:FinanceJournalSide,val amount:String,
)
data class FinanceArProposeJournalRequest(
    val operationId:UUID,val fiscalPeriodId:UUID,
    val accountingTreatmentReference:String,val taxDispositionReference:String,
    val taxEvidenceId:UUID?=null,
    val lines:List<FinanceArAllocationRequest>,
)
data class FinanceArJournalOperation(val operationId:UUID)
data class FinanceArJournalReceipt(
    val proposalId:UUID,val issuedInvoiceId:UUID,val organizationId:UUID,
    val sourceVersion:Long,val ledgerFingerprintSha256:String,
    val amount:String,val currency:String,val state:String,
    val approvalRequestId:UUID?=null,val postedJournalId:UUID?=null,
    val replayed:Boolean=false,val receivableRecognized:Boolean=false,
    val cashCollected:Boolean=false,val bankReconciled:Boolean=false,
)

/** Source-based AR recognition, not an issuance, sales lead or bank receipt. */
@Service
class FinanceArJournalPostingService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc:JdbcTemplate,
    private val subject:FinanceArJournalPostingApprovalSubject,
    private val approvals:ApprovalPersistencePort,
    private val engine:ApprovalEngineService,
    private val idempotency:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,
    private val environment:Environment,
    private val clock:Clock,
    private val operationalAuthority: OperationalAuthorityPort? = null,
) {
    @Transactional
    fun propose(
        actor:UUID,org:UUID,invoice:UUID,request:FinanceArProposeJournalRequest,
        correlation:String,
    ):FinanceArJournalReceipt {
        authority(actor,org,"hiltech.finance.receivables.ar-posting.preparer-authority-key")
        if(request.lines.size !in 2..32) unavailable()
        val now=clock.instant()
        val key=subject.policyKey() ?: unavailable()
        val policy=approvals.loadActivePolicy(org,key,now)?.takeIf {
            it.organizationId==org && it.policyKey==key &&
                it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: unavailable()
        val reviewer=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,now,
        ) ?: unavailable()
        if(reviewer.organizationId!=org ||
            approvals.actorMatchesAuthority(actor,reviewer,now)) unavailable()
        val row=jdbc.query(
            """SELECT id,source_fingerprint_sha256,gross_amount,currency,
                invoice_approval_id FROM finance_ar_issued_invoice
               WHERE id=? AND organization_id=?""",
            { rs,_ -> FinanceArSourceInvoice(
                rs.getObject("id",UUID::class.java),
                rs.getString("source_fingerprint_sha256"),
                rs.getBigDecimal("gross_amount"),
                rs.getString("currency"),
                rs.getObject("invoice_approval_id",UUID::class.java),
            ) },invoice,org,
        ).singleOrNull() ?: stale()
        val period=jdbc.query(
            """SELECT state,date_start,date_end FROM finance_gl_period
               WHERE id=? AND organization_id=?""",
            { rs,_ ->Triple(rs.getString("state"),
                rs.getDate("date_start").toLocalDate(),
                rs.getDate("date_end").toLocalDate()) },
            request.fiscalPeriodId,org,
        ).singleOrNull() ?: unavailable()
        val today=LocalDate.ofInstant(now,ZoneOffset.UTC)
        if(period.first!="OPEN" || today.isBefore(period.second) ||
            today.isAfter(period.third) || periodClosed(org,request.fiscalPeriodId))
            unavailable()
        val allocation=request.lines.map {l->
            val account=jdbc.query(
                """SELECT account_kind FROM finance_gl_account
                   WHERE id=? AND organization_id=? AND state='APPROVED'
                     AND (currency IS NULL OR currency=?)""",
                {rs,_ ->FinanceArAccountKind.valueOf(rs.getString("account_kind")) },
                l.glAccountId,org,row.currency,
            ).singleOrNull() ?: unavailable()
            FinanceArJournalAllocation(
                l.glAccountId,account,l.side,
                runCatching { BigDecimal(l.amount) }.getOrNull() ?: unavailable(),
            )
        }
        // No tax recovery/revenue split is synthesized from an award.
        // The canonical Tax Evidence target is currently unintegrated:
        // preserve this default-OFF gate instead of accepting arbitrary UUID.
        if(request.taxEvidenceId!=null &&
            !environment.getProperty(
                "hiltech.finance.receivables.ar-posting.tax-evidence-ready",
                Boolean::class.java,false,
            )) unavailable()
        val plan=FinanceArJournalPlan(
            org,invoice,row.hash,request.fiscalPeriodId,row.amount,row.currency,
            request.accountingTreatmentReference,request.taxDispositionReference,
            request.taxEvidenceId,allocation,
        )
        val checked=runCatching { FinanceArJournalRules.validate(plan) }
            .getOrNull() ?: unavailable()
        val version=FinanceArPostingReviewVersion.derive(
            checked,policy.versionNumber,reviewer.id,reviewer.version,
            row.issuanceApprovalId,
        )
        val proposal=UUID.nameUUIDFromBytes(
            "hiltech:finance-ar-journal:${request.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,invoice,checked.fingerprintSha256,version,key)
                .joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            request.operationId,actor,"FINANCE_AR_JOURNAL_PROPOSED",
            "FINANCE_AR_POSTING_PROPOSAL",proposal,fingerprint,correlation,
        )) {
            // Same period lock as AP, AR and period-closing trigger.
            val locked=jdbc.query(
                """SELECT id FROM finance_gl_period
                  WHERE id=? AND organization_id=? AND state='OPEN' FOR UPDATE""",
                {rs,_ ->rs.getObject("id",UUID::class.java)},
                request.fiscalPeriodId,org,
            ).singleOrNull() ?: stale()
            if(locked!=request.fiscalPeriodId ||
               periodClosed(org,locked)) stale()
            val at=clock.instant().atOffset(ZoneOffset.UTC)
            check(jdbc.update(
                """INSERT INTO finance_ar_posting_proposal(
                  id,operation_id,organization_id,issued_invoice_id,
                  original_issuance_approval_id,source_fingerprint_sha256,
                  ledger_fingerprint_sha256,ledger_subject_version,
                  fiscal_period_id,currency,gross_amount,
                  accounting_treatment_reference,tax_disposition_reference,
                  tax_evidence_id,policy_key,state,created_by_user_id,created_at,
                  correlation_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?)""",
                proposal,request.operationId,org,invoice,
                row.issuanceApprovalId,row.hash,checked.fingerprintSha256,
                version,request.fiscalPeriodId,row.currency,row.amount,
                request.accountingTreatmentReference.trim(),
                request.taxDispositionReference.trim(),request.taxEvidenceId,
                key,actor,at,correlation,
            )==1)
            allocation.forEachIndexed {index,l->
                check(jdbc.update(
                    """INSERT INTO finance_ar_posting_proposal_line(
                      id,organization_id,proposal_id,line_number,
                      gl_account_id,side,amount
                    ) VALUES(?,?,?,?,?,?,?)""",
                    UUID.nameUUIDFromBytes(
                        "hiltech:finance-ar-line:$proposal:${index+1}"
                            .toByteArray(Charsets.UTF_8),
                    ),org,proposal,index+1,l.accountId,l.side.name,l.amount,
                )==1)
            }
            check(jdbc.update(
                """UPDATE finance_ar_posting_proposal SET state='SEALED',
                     sealed_at=? WHERE id=? AND organization_id=? AND state='DRAFT'""",
                at,proposal,org,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_AR_JOURNAL_PROPOSED",
                targetType="FINANCE_AR_POSTING_PROPOSAL",targetId=proposal,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="REVIEWED_AR_ALLOCATION_NOT_RECOGNIZED_YET",
            ))
            IdempotentCommandOutcome(
                "FINANCE_AR_JOURNAL_PROPOSED","""{"id":"$proposal"}""",
            )
        }
        return receipt(proposal,invoice,org,version,checked.fingerprintSha256,
            row.amount,row.currency,"SEALED_AWAITING_APPROVAL",
            replayed=outcome.replayed)
    }
    fun requestApproval(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceArJournalReceipt {
        authority(actor,org,"hiltech.finance.receivables.ar-posting.preparer-authority-key")
        val snap=subject.current(org,id) ?: stale()
        if(snap.preparedBy!=actor) unavailable()
        val ref=ApprovalSubjectRef(org,FINANCE_AR_JOURNAL_POSTING_SUBJECT,
            id,snap.subjectVersion)
        val previous=engine.subjectStatus(ref,snap.policyKey)
        if(previous!=null) {
            if(previous.state!=ApprovalRequestState.PENDING) unavailable()
            return receipt(snap,"PENDING",approval=previous.approvalRequestId,
                replayed=true)
        }
        val review=engine.requestException(
            actorUserId=actor,operationId=operation,subject=ref,
            policyKey=snap.policyKey,reasonCode="FINANCE_AR_JOURNAL_POSTING_REVIEW",
            safeReasonSummary=null,correlationId=correlation,
            allowAuthorityBypass=false,
        )
        if(!review.approvalRequired || review.approvalRequestId==null ||
            review.state!=ApprovalRequestState.PENDING ||
            review.policyKey!=snap.policyKey ||
            review.policyVersion!=snap.policyVersion) unavailable()
        return receipt(snap,"PENDING",approval=review.approvalRequestId,
            replayed=review.replayed)
    }
    @Transactional
    fun post(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceArJournalReceipt {
        authority(actor,org,"hiltech.finance.receivables.ar-posting.operator-authority-key")
        historic(org,id)?.let { old->
            val recorded=jdbc.query(
                """SELECT operation_id,posted_by_user_id FROM finance_ar_posted_journal
                   WHERE organization_id=? AND id=?""",
                {rs,_ ->rs.getObject("operation_id",UUID::class.java) to
                    rs.getObject("posted_by_user_id",UUID::class.java)},
                org,old.postedJournalId,
            ).singleOrNull() ?: unavailable()
            if(recorded.first!=operation || recorded.second!=actor) unavailable()
            return old.copy(replayed=true)
        }
        val snap=subject.current(org,id) ?: stale()
        if(snap.preparedBy==actor) unavailable()
        val ref=ApprovalSubjectRef(org,FINANCE_AR_JOURNAL_POSTING_SUBJECT,
            id,snap.subjectVersion)
        val approved=engine.subjectStatus(ref,snap.policyKey)?.takeIf {
            it.state==ApprovalRequestState.APPROVED &&
                it.subjectVersion==snap.subjectVersion
        } ?: unavailable()
        val journal=UUID.nameUUIDFromBytes(
            "hiltech:finance-ar-posted:$operation".toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,id,snap.ledgerHash,snap.subjectVersion,
                approved.approvalRequestId).joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            operation,actor,"FINANCE_AR_JOURNAL_POSTED",
            "FINANCE_AR_POSTED_JOURNAL",journal,fingerprint,correlation,
        )) {
            val period=jdbc.query(
                """SELECT id FROM finance_gl_period
                   WHERE id=? AND organization_id=? AND state='OPEN' FOR UPDATE""",
                {rs,_ ->rs.getObject("id",UUID::class.java)},
                snap.periodId,org,
            ).singleOrNull() ?: stale()
            if(period!=snap.periodId || subject.current(org,id)!=snap) stale()
            val at=clock.instant().atOffset(ZoneOffset.UTC)
            check(jdbc.update(
                """INSERT INTO finance_ar_posted_journal(
                  id,operation_id,organization_id,proposal_id,issued_invoice_id,
                  posting_approval_id,fiscal_period_id,ledger_fingerprint_sha256,
                  source_fingerprint_sha256,currency,gross_amount,
                  posted_by_user_id,posted_at,correlation_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                journal,operation,org,id,snap.issuedInvoiceId,
                approved.approvalRequestId,snap.periodId,
                snap.ledgerHash,snap.sourceHash,snap.currency,
                snap.amount,actor,at,correlation,
            )==1)
            val n=jdbc.update(
                """INSERT INTO finance_ar_posted_journal_line(
                   id,organization_id,journal_id,proposal_line_id,
                   line_number,gl_account_id,side,amount)
                SELECT gen_random_uuid(),organization_id,?,id,
                       line_number,gl_account_id,side,amount
                FROM finance_ar_posting_proposal_line
                WHERE proposal_id=? AND organization_id=?
                ORDER BY line_number""",journal,id,org,
            )
            check(n==snap.lines.size)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_AR_JOURNAL_POSTED",
                targetType="FINANCE_AR_POSTED_JOURNAL",targetId=journal,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="APPROVED_BALANCED_AR_RECOGNITION_NO_COLLECTION",
            ))
            IdempotentCommandOutcome(
                "FINANCE_AR_JOURNAL_POSTED","""{"id":"$journal"}""",
            )
        }
        return receipt(snap,"POSTED_ACCOUNTS_RECEIVABLE",
            approval=approved.approvalRequestId,journal=journal,
            replayed=outcome.replayed)
    }
    /** Discover only currently approved, unrecorded operations for the existing operator authority. */
    fun operatorQueue(actor: UUID, org: UUID): List<FinanceArJournalReceipt> {
        authority(actor,org,"hiltech.finance.receivables.ar-posting.operator-authority-key")
        val ids = jdbc.query(
            """SELECT p.id FROM finance_ar_posting_proposal p
               WHERE p.organization_id=?
               AND EXISTS (SELECT 1 FROM approval_request a
                 WHERE a.organization_id=p.organization_id AND a.subject_id=p.id
                 AND a.subject_type=? AND a.state='APPROVED'
                 AND a.requester_user_id<>?)
               AND NOT EXISTS (SELECT 1 FROM finance_ar_posted_journal r
                 WHERE r.organization_id=p.organization_id AND r.proposal_id=p.id)
               ORDER BY p.id LIMIT 100""",
            { rs,_ -> rs.getObject("id",UUID::class.java) },org,FINANCE_AR_JOURNAL_POSTING_SUBJECT,actor,
        )
        return ids.mapNotNull { id ->
            val snapshot = subject.current(org,id) ?: return@mapNotNull null
            if (snapshot.preparedBy == actor) return@mapNotNull null
            val current = status(actor,org,id)
            current.takeIf { it.state=="APPROVED" && it.approvalRequestId!=null && it.postedJournalId==null }
        }
    }

    fun status(actor:UUID,org:UUID,id:UUID):FinanceArJournalReceipt {
        val operator = try {
            authority(actor,org,"hiltech.finance.receivables.ar-posting.operator-authority-key")
            true
        } catch (_: ProductApiException) { false }
        if (!operator) authority(actor,org,"hiltech.finance.receivables.ar-posting.preparer-authority-key")
        historic(org,id)?.let { return it }
        val snap=subject.current(org,id) ?: stale()
        val review=engine.subjectStatus(
            ApprovalSubjectRef(org,FINANCE_AR_JOURNAL_POSTING_SUBJECT,
                id,snap.subjectVersion),snap.policyKey,
        )
        return receipt(snap,review?.state?.name ?: "SEALED_AWAITING_APPROVAL",
            approval=review?.approvalRequestId)
    }
    private fun historic(org:UUID,id:UUID):FinanceArJournalReceipt?=
        jdbc.query(
            """SELECT j.id,j.issued_invoice_id,j.ledger_fingerprint_sha256,
                j.currency,j.gross_amount,j.posting_approval_id,
                p.ledger_subject_version
               FROM finance_ar_posted_journal j
               JOIN finance_ar_posting_proposal p ON p.id=j.proposal_id
               WHERE j.organization_id=? AND j.proposal_id=?""",
            {rs,_ ->receipt(
                id,rs.getObject("issued_invoice_id",UUID::class.java),org,
                rs.getLong("ledger_subject_version"),
                rs.getString("ledger_fingerprint_sha256"),
                rs.getBigDecimal("gross_amount"),rs.getString("currency"),
                "POSTED_ACCOUNTS_RECEIVABLE",
                approval=rs.getObject("posting_approval_id",UUID::class.java),
                journal=rs.getObject("id",UUID::class.java),
            )},org,id,
        ).singleOrNull()
    private fun receipt(s:FinanceArPostingVerifiedSource,state:String,
                        approval:UUID?=null,journal:UUID?=null,replayed:Boolean=false)=
        receipt(s.proposalId,s.issuedInvoiceId,s.organizationId,
            s.subjectVersion,s.ledgerHash,s.amount,s.currency,
            state,approval,journal,replayed)
    private fun receipt(proposal:UUID,invoice:UUID,org:UUID,version:Long,
                        hash:String,amount:BigDecimal,currency:String,
                        state:String,approval:UUID?=null,
                        journal:UUID?=null,replayed:Boolean=false)=
        FinanceArJournalReceipt(proposal,invoice,org,version,hash,
            amount.toPlainString(),currency,state,
            approvalRequestId=approval,postedJournalId=journal,replayed=replayed,
            receivableRecognized=journal!=null)
    private fun periodClosed(org:UUID,period:UUID):Boolean =
        jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_gl_period_close
               WHERE organization_id=? AND period_id=?)""",
            Boolean::class.java,org,period,
        )==true
    private fun authority(actor: UUID, org: UUID, keyName: String) {
        if (!subject.enabled()) unavailable()
        currentOperationalAuthority(operationalAuthority, approvals, actor, org, keyName,
            environment.getProperty(keyName), clock.instant()) ?: unavailable()
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_AR_POSTING_NOT_AUTHORIZED",
        "Company receivable ledger allocation policy or authority unavailable.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing=throw ProductApiException(
        "FINANCE_AR_POSTING_SOURCE_STALE",
        "Invoice, approval or fiscal source changed.",HttpStatus.CONFLICT,
    )
}
private data class FinanceArSourceInvoice(
    val id:UUID,val hash:String,val amount:BigDecimal,val currency:String,
    val issuanceApprovalId:UUID,
)

@RestController
class FinanceArJournalPostingController(private val finance:FinanceArJournalPostingService) {
    @PostMapping("/v1/finance/organizations/{orgId}/issued-invoices/{invoiceId}/ar-journal-proposals")
    fun propose(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable invoiceId:UUID,@RequestBody body:FinanceArProposeJournalRequest
    ):FinanceArJournalReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return finance.propose(actor(http),orgId,invoiceId,body,ctx.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/ar-journal-proposals/{id}/request-approval")
    fun request(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceArJournalOperation
    ):FinanceArJournalReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return finance.requestApproval(actor(http),orgId,id,body.operationId,ctx.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/ar-journal-proposals/{id}/post")
    fun post(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceArJournalOperation
    ):FinanceArJournalReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return finance.post(actor(http),orgId,id,body.operationId,ctx.correlationId)
    }
    @GetMapping("/v1/finance/organizations/{orgId}/ar-journal-proposals/operator-queue")
    fun operatorQueue(http:HttpServletRequest,@PathVariable orgId:UUID):List<FinanceArJournalReceipt> =
        finance.operatorQueue(actor(http),orgId)
    @GetMapping("/v1/finance/organizations/{orgId}/ar-journal-proposals/{id}")
    fun status(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID):FinanceArJournalReceipt =
        finance.status(actor(http),orgId,id)
    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Finance login required.",HttpStatus.UNAUTHORIZED)
}
