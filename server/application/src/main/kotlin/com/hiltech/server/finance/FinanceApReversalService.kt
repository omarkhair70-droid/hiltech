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

data class FinanceApCreateReversalRequest(
    val operationId: UUID,
    val targetPeriodId: UUID,
    val reasonReference: String,
)
data class FinanceApReversalOperation(val operationId: UUID)
data class FinanceApReversalReceipt(
    val proposalId: UUID,
    val originalJournalId: UUID,
    val targetPeriodId: UUID,
    val sourceVersion: Long,
    val state: String,
    val currency: String,
    val grossAmount: String,
    val approvalRequestId: UUID?=null,
    val reversalJournalId: UUID?=null,
    val replayed: Boolean=false,
    val financialLedgerReversed: Boolean=false,
    val bankPaymentReversed: Boolean=false,
    val moneyTransferred: Boolean=false,
)

/**
 * AP correction is NOT a refund, bank transfer or update to original journal.
 * Every command is fail-closed by default, company-scoped and source-versioned.
 */
@Service
class FinanceApReversalService(
    private val jdbc: JdbcTemplate,
    private val subject: FinanceApReversalApprovalSubject,
    private val approvals: ApprovalPersistencePort,
    private val engine: ApprovalEngineService,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val environment: Environment,
    private val clock: Clock,
) {
    @Transactional
    fun propose(
        actor:UUID,org:UUID,original:UUID,request:FinanceApCreateReversalRequest,
        correlation:String,
    ):FinanceApReversalReceipt {
        requireAuthority(actor,org,"hiltech.finance.ap-reversal.preparer-authority-key")
        val reason=request.reasonReference.trim()
        if(reason.length !in 8..180) unavailable()
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
            approvals.actorMatchesAuthority(actor,reviewer,now)) unavailable()
        val originalRows=jdbc.query(
            """
            SELECT j.currency,j.gross_amount,j.ledger_fingerprint_sha256,j.posted_at,
              l.id AS line_id,l.gl_account_id,l.side,l.amount
            FROM finance_ap_posted_journal j
            JOIN finance_ap_posted_journal_line l
              ON l.journal_id=j.id AND l.organization_id=j.organization_id
            WHERE j.organization_id=? AND j.id=? ORDER BY l.line_number
            """.trimIndent(),
            { rs,_ -> Pair(
                FinanceApOriginalHeader(
                    rs.getString("currency"),rs.getBigDecimal("gross_amount"),
                    rs.getString("ledger_fingerprint_sha256"),
                    rs.getObject("posted_at",java.time.OffsetDateTime::class.java)
                        .toLocalDate(),
                ),
                FinanceApReversalSourceLine(
                    rs.getObject("line_id",UUID::class.java),
                    rs.getObject("gl_account_id",UUID::class.java),
                    FinanceJournalSide.valueOf(rs.getString("side")),
                    rs.getBigDecimal("amount"),
                ),
            ) },org,original,
        )
        val h=originalRows.firstOrNull()?.first ?: stale()
        if(originalRows.any { it.first!=h }) stale()
        val period=jdbc.query(
            """SELECT date_start,date_end,state FROM finance_gl_period
               WHERE id=? AND organization_id=?""",
            { rs,_ -> Triple(rs.getDate("date_start").toLocalDate(),
                rs.getDate("date_end").toLocalDate(),rs.getString("state")) },
            request.targetPeriodId,org,
        ).singleOrNull() ?: unavailable()
        val today=LocalDate.ofInstant(now,ZoneOffset.UTC)
        if(period.third!="OPEN" || today.isBefore(period.first) ||
            today.isAfter(period.second)) unavailable()
        val closed=jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_gl_period_close
               WHERE organization_id=? AND period_id=?)""",
            Boolean::class.java,org,request.targetPeriodId,
        )==true
        val sourceClosed=jdbc.queryForObject(
            """SELECT EXISTS(
               SELECT 1 FROM finance_gl_period_close c
               JOIN finance_ap_posted_journal j ON j.fiscal_period_id=c.period_id
                 AND j.organization_id=c.organization_id
               WHERE j.organization_id=? AND j.id=?)""",
            Boolean::class.java,org,original,
        )==true
        val already=jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_ap_journal_reversal
               WHERE organization_id=? AND original_journal_id=?)""",
            Boolean::class.java,org,original,
        )==true
        val source=FinanceApReversalSnapshot(
            org,original,h.currency,h.amount,h.postedDate,
            originalRows.map { it.second },sourceClosed,already,
        )
        val target=FinanceApReversalTarget(
            org,request.targetPeriodId,h.currency,today,true,closed,
        )
        runCatching { FinanceApReversalRules.plan(source,target) }
            .getOrNull() ?: unavailable()
        val version=FinanceApReversalReviewVersion.derive(
            org,original,request.targetPeriodId,h.fingerprint,h.currency,
            h.amount,reason,source.sourceLines,policy.versionNumber,
            reviewer.id,reviewer.version,
        )
        val proposalId=UUID.nameUUIDFromBytes(
            "hiltech:finance-ap-reversal-proposal:${request.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,original,request.targetPeriodId,version,h.fingerprint,reason)
                .joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            request.operationId,actor,"FINANCE_AP_REVERSAL_PROPOSED",
            "FINANCE_AP_REVERSAL_PROPOSAL",proposalId,fingerprint,correlation,
        )) {
            val locked=jdbc.query(
                """SELECT id FROM finance_gl_period
                   WHERE id=? AND organization_id=? AND state='OPEN' FOR UPDATE""",
                { rs,_ -> rs.getObject("id",UUID::class.java) },
                request.targetPeriodId,org,
            ).singleOrNull() ?: stale()
            if(locked!=request.targetPeriodId ||
                jdbc.queryForObject(
                    """SELECT EXISTS(SELECT 1 FROM finance_gl_period_close
                       WHERE organization_id=? AND period_id=?)""",
                    Boolean::class.java,org,locked,
                )==true) stale()
            val at=clock.instant().atOffset(ZoneOffset.UTC)
            check(jdbc.update(
                """
                INSERT INTO finance_ap_reversal_proposal(
                  id,operation_id,organization_id,original_journal_id,
                  target_period_id,currency,gross_amount,
                  source_ledger_fingerprint_sha256,approval_subject_version,
                  approval_policy_key,reason_reference,prepared_by_user_id,created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                proposalId,request.operationId,org,original,
                request.targetPeriodId,h.currency,h.amount,h.fingerprint,
                version,key,reason,actor,at,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_AP_REVERSAL_PROPOSED",
                targetType="FINANCE_AP_REVERSAL_PROPOSAL",targetId=proposalId,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="APPEND_ONLY_CORRECTION_PROPOSED_NOT_POSTED",
            ))
            IdempotentCommandOutcome("FINANCE_AP_REVERSAL_PROPOSED",
                """{"id":"$proposalId"}""",
            )
        }
        return receipt(proposalId,original,request.targetPeriodId,version,
            "PROPOSED",h.currency,h.amount,replayed=outcome.replayed)
    }

    fun requestApproval(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceApReversalReceipt {
        requireAuthority(actor,org,"hiltech.finance.ap-reversal.preparer-authority-key")
        val snapshot=subject.current(org,id) ?: stale()
        if(snapshot.preparedBy!=actor) unavailable()
        val ref=ApprovalSubjectRef(
            org,FINANCE_AP_JOURNAL_REVERSAL_SUBJECT,id,snapshot.subjectVersion,
        )
        val existing=engine.subjectStatus(ref,snapshot.policyKey)
        if(existing!=null) {
            if(existing.state!=ApprovalRequestState.PENDING) unavailable()
            return receipt(snapshot,"PENDING",approval=existing.approvalRequestId,
                replayed=true)
        }
        val review=engine.requestException(
            actorUserId=actor,operationId=operation,subject=ref,
            policyKey=snapshot.policyKey,
            reasonCode="FINANCE_AP_JOURNAL_REVERSAL_REVIEW",
            safeReasonSummary=null,correlationId=correlation,
            allowAuthorityBypass=false,
        )
        if(!review.approvalRequired ||
            review.approvalRequestId==null ||
            review.state!=ApprovalRequestState.PENDING ||
            review.policyKey!=snapshot.policyKey ||
            review.policyVersion!=snapshot.policyVersion) unavailable()
        return receipt(snapshot,"PENDING",approval=review.approvalRequestId,
            replayed=review.replayed)
    }

    @Transactional
    fun post(
        actor:UUID,org:UUID,id:UUID,operation:UUID,correlation:String,
    ):FinanceApReversalReceipt {
        requireAuthority(actor,org,"hiltech.finance.ap-reversal.operator-authority-key")
        historic(org,id)?.let { old ->
            val prior=jdbc.query(
                """SELECT operation_id,posted_by_user_id FROM finance_ap_journal_reversal
                   WHERE id=? AND organization_id=?""",
                { rs,_ -> Pair(
                    rs.getObject("operation_id",UUID::class.java),
                    rs.getObject("posted_by_user_id",UUID::class.java),
                ) },old.reversalJournalId,org,
            ).singleOrNull() ?: unavailable()
            if(prior.first!=operation || prior.second!=actor) unavailable()
            return old.copy(replayed=true)
        }
        val snapshot=subject.current(org,id) ?: stale()
        if(snapshot.preparedBy==actor) unavailable()
        val ref=ApprovalSubjectRef(
            org,FINANCE_AP_JOURNAL_REVERSAL_SUBJECT,id,snapshot.subjectVersion,
        )
        val approved=engine.subjectStatus(ref,snapshot.policyKey)
            ?.takeIf {
                it.state==ApprovalRequestState.APPROVED &&
                    it.subjectVersion==snapshot.subjectVersion
            } ?: unavailable()
        val reversalId=UUID.nameUUIDFromBytes(
            "hiltech:finance-ap-reversal:$operation".toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,id,snapshot.sourceFingerprint,snapshot.subjectVersion,
                approved.approvalRequestId).joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            operation,actor,"FINANCE_AP_JOURNAL_REVERSED",
            "FINANCE_AP_JOURNAL_REVERSAL",reversalId,fingerprint,correlation,
        )) {
            val locked=jdbc.query(
                """SELECT id FROM finance_gl_period WHERE id=?
                   AND organization_id=? AND state='OPEN' FOR UPDATE""",
                { rs,_ -> rs.getObject("id",UUID::class.java) },
                snapshot.targetPeriodId,org,
            ).singleOrNull() ?: stale()
            if(locked!=snapshot.targetPeriodId ||
                subject.current(org,id)!=snapshot) stale()
            val now=clock.instant().atOffset(ZoneOffset.UTC)
            check(jdbc.update(
                """
                INSERT INTO finance_ap_journal_reversal(
                  id,operation_id,organization_id,original_journal_id,
                  proposal_id,target_period_id,approval_request_id,
                  approval_subject_version,approval_policy_key,
                  source_ledger_fingerprint_sha256,currency,gross_amount,
                  reason_reference,posted_by_user_id,posted_at,correlation_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                reversalId,operation,org,snapshot.originalJournalId,
                id,snapshot.targetPeriodId,approved.approvalRequestId,
                snapshot.subjectVersion,snapshot.policyKey,snapshot.sourceFingerprint,
                snapshot.currency,snapshot.grossAmount,
                snapshot.reasonReference,actor,now,correlation,
            )==1)
            // Database guards independently verify every line is the exact
            // opposing side of its immutable original and balance at commit.
            val inserted=jdbc.update(
                """
                INSERT INTO finance_ap_journal_reversal_line(
                   id,organization_id,reversal_id,original_line_id,
                   line_number,gl_account_id,side,amount
                )
                SELECT gen_random_uuid(),l.organization_id,?,l.id,
                       l.line_number,l.gl_account_id,
                       CASE WHEN l.side='DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,
                       l.amount
                FROM finance_ap_posted_journal_line l
                WHERE l.journal_id=? AND l.organization_id=?
                ORDER BY l.line_number
                """.trimIndent(),reversalId,snapshot.originalJournalId,org,
            )
            check(inserted==snapshot.lines.size)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_AP_JOURNAL_REVERSED",
                targetType="FINANCE_AP_JOURNAL_REVERSAL",targetId=reversalId,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="INDEPENDENT_APPROVED_APPEND_ONLY_REVERSAL_NOT_BANK_REFUND",
            ))
            IdempotentCommandOutcome("FINANCE_AP_JOURNAL_REVERSED",
                """{"id":"$reversalId"}""",
            )
        }
        return receipt(snapshot,"REVERSED",approval=approved.approvalRequestId,
            reversal=reversalId,replayed=outcome.replayed)
    }

    fun status(actor:UUID,org:UUID,id:UUID):FinanceApReversalReceipt {
        requireAuthority(actor,org,"hiltech.finance.ap-reversal.preparer-authority-key")
        historic(org,id)?.let { return it }
        val snapshot=subject.current(org,id) ?: stale()
        val review=engine.subjectStatus(
            ApprovalSubjectRef(org,FINANCE_AP_JOURNAL_REVERSAL_SUBJECT,
                id,snapshot.subjectVersion),snapshot.policyKey,
        )
        return receipt(snapshot,review?.state?.name ?: "PROPOSED",
            approval=review?.approvalRequestId)
    }

    private fun historic(org:UUID,id:UUID):FinanceApReversalReceipt? =
        jdbc.query(
            """
            SELECT r.id,r.original_journal_id,r.target_period_id,
                r.approval_subject_version,r.currency,r.gross_amount,
                r.approval_request_id
            FROM finance_ap_journal_reversal r
            WHERE r.organization_id=? AND r.proposal_id=?
            """.trimIndent(),
            { rs,_ -> receipt(
                id,rs.getObject("original_journal_id",UUID::class.java),
                rs.getObject("target_period_id",UUID::class.java),
                rs.getLong("approval_subject_version"),"REVERSED",
                rs.getString("currency"),rs.getBigDecimal("gross_amount"),
                approval=rs.getObject("approval_request_id",UUID::class.java),
                reversal=rs.getObject("id",UUID::class.java),
            ) },org,id,
        ).singleOrNull()

    private fun requireAuthority(actor:UUID,org:UUID,keyName:String) {
        if(!subject.enabled()) unavailable()
        val key=environment.getProperty(keyName,"").trim()
        if(!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) unavailable()
        val now=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,now)) unavailable()
        val authority=approvals.resolveCurrentAuthority(org,key,now) ?: unavailable()
        if(authority.organizationId!=org || authority.authorityKey!=key ||
            !approvals.actorMatchesAuthority(actor,authority,now)) unavailable()
    }
    private fun receipt(
        s:FinanceApReversalVerifiedSnapshot,state:String,
        approval:UUID?=null,reversal:UUID?=null,replayed:Boolean=false,
    )=receipt(s.proposalId,s.originalJournalId,s.targetPeriodId,
        s.subjectVersion,state,s.currency,s.grossAmount,
        approval,reversal,replayed)
    private fun receipt(
        id:UUID,original:UUID,period:UUID,version:Long,state:String,
        currency:String,amount:BigDecimal,approval:UUID?=null,
        reversal:UUID?=null,replayed:Boolean=false,
    )=FinanceApReversalReceipt(
        proposalId=id,originalJournalId=original,targetPeriodId=period,
        sourceVersion=version,state=state,currency=currency,
        grossAmount=amount.toPlainString(),approvalRequestId=approval,
        reversalJournalId=reversal,replayed=replayed,
        financialLedgerReversed=reversal!=null,
    )
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_AP_REVERSAL_UNAUTHORIZED",
        "Reversal policy or current company authority unavailable.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing=throw ProductApiException(
        "FINANCE_AP_REVERSAL_STALE",
        "Source journal, approved reversal or fiscal period changed.",
        HttpStatus.CONFLICT,
    )
}
private data class FinanceApOriginalHeader(
    val currency:String,val amount:BigDecimal,
    val fingerprint:String,val postedDate:LocalDate,
)

@RestController
class FinanceApReversalController(private val service:FinanceApReversalService) {
    @PostMapping("/v1/finance/organizations/{orgId}/ap-journals/{journalId}/reversal-proposals")
    fun propose(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable journalId:UUID,@RequestBody body:FinanceApCreateReversalRequest
    ):FinanceApReversalReceipt {
        val c=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,body.operationId)
        return service.propose(actor(http),orgId,journalId,body,c.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/ap-reversal-proposals/{id}/request-approval")
    fun request(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceApReversalOperation
    ):FinanceApReversalReceipt {
        val c=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,body.operationId)
        return service.requestApproval(actor(http),orgId,id,body.operationId,c.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/ap-reversal-proposals/{id}/post")
    fun post(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceApReversalOperation
    ):FinanceApReversalReceipt {
        val c=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,body.operationId)
        return service.post(actor(http),orgId,id,body.operationId,c.correlationId)
    }
    @GetMapping("/v1/finance/organizations/{orgId}/ap-reversal-proposals/{id}")
    fun status(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID):FinanceApReversalReceipt =
            service.status(actor(http),orgId,id)
    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException("UNAUTHENTICATED",
                "Finance login required.",HttpStatus.UNAUTHORIZED)
}
