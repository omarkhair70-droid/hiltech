package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
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

data class FinancePeriodCloseOperation(val operationId: UUID)
data class FinancePeriodCloseReceipt(
    val organizationId: UUID,
    val periodId: UUID,
    val ledgerSnapshotSha256: String,
    val journalCount: Long,
    val sourceVersion: Long,
    val state: String,
    val approvalRequestId: UUID? = null,
    val periodClosureId: UUID? = null,
    val replayed: Boolean = false,
    val periodClosed: Boolean = false,
    val moneyTransferred: Boolean = false,
    val bankReconciled: Boolean = false,
)

/**
 * Closing a financial period is a permanent APPEND event.
 * No direct UPDATE to finance_gl_period or previously posted journal.
 * Actual status is from the close table, not the base period.state alone.
 */
@Service
class FinanceGlPeriodCloseService(
    private val jdbc: JdbcTemplate,
    private val source: FinanceGlPeriodCloseApprovalSubject,
    private val approvals: ApprovalPersistencePort,
    private val engine: ApprovalEngineService,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val environment: Environment,
    private val clock: Clock,
) {
    fun requestReview(
        actor: UUID, org: UUID, periodId: UUID, operation: UUID,
        correlation: String,
    ): FinancePeriodCloseReceipt {
        requireAuthority(actor,org,"hiltech.finance.gl-period-close.preparer-authority-key")
        val current = source.current(org,periodId) ?: stale()
        val policy = approvals.loadActivePolicy(
            org,current.policyKey,clock.instant(),
        ) ?: unavailable()
        val reviewer = policy.authorityKey?.let {
            approvals.resolveCurrentAuthority(org,it,clock.instant())
        } ?: unavailable()
        if (reviewer.organizationId != org ||
            approvals.actorMatchesAuthority(actor,reviewer,clock.instant())) unavailable()
        val ref = ApprovalSubjectRef(
            org,FINANCE_GL_PERIOD_CLOSE_SUBJECT,periodId,current.sourceVersion,
        )
        val existing = engine.subjectStatus(ref,current.policyKey)
        if (existing != null) {
            if (existing.state != ApprovalRequestState.PENDING ||
                existing.subjectVersion != current.sourceVersion) unavailable()
            return receipt(current,"PENDING",existing.approvalRequestId,replayed=true)
        }
        val requested = engine.requestException(
            actorUserId=actor,operationId=operation,subject=ref,
            policyKey=current.policyKey,
            reasonCode="FINANCE_GL_PERIOD_CLOSE_REVIEW",
            safeReasonSummary=null,correlationId=correlation,
            allowAuthorityBypass=false,
        )
        if (!requested.approvalRequired ||
            requested.approvalRequestId==null ||
            requested.state!=ApprovalRequestState.PENDING ||
            requested.policyKey!=current.policyKey ||
            requested.policyVersion!=current.policyVersion) unavailable()
        return receipt(current,"PENDING",requested.approvalRequestId,
            replayed=requested.replayed)
    }

    @Transactional
    fun close(
        actor: UUID,org: UUID,periodId: UUID,
        operation: UUID,correlation: String,
    ): FinancePeriodCloseReceipt {
        requireAuthority(actor,org,"hiltech.finance.gl-period-close.operator-authority-key")
        historical(org,periodId)?.let { recorded ->
            val replay = jdbc.query(
                """
                SELECT operation_id,closed_by_user_id FROM finance_gl_period_close
                WHERE organization_id=? AND period_id=?
                """.trimIndent(),
                { rs, _ ->
                    rs.getObject("operation_id",UUID::class.java) to
                        rs.getObject("closed_by_user_id",UUID::class.java)
                },org,periodId,
            ).singleOrNull() ?: unavailable()
            if (replay.first != operation || replay.second != actor) unavailable()
            return recorded.copy(replayed=true)
        }

        val snapshot = source.current(org,periodId) ?: stale()
        val ref=ApprovalSubjectRef(
            org,FINANCE_GL_PERIOD_CLOSE_SUBJECT,periodId,snapshot.sourceVersion,
        )
        val approved=engine.subjectStatus(ref,snapshot.policyKey)
            ?.takeIf {
                it.state==ApprovalRequestState.APPROVED &&
                    it.subjectVersion==snapshot.sourceVersion
            } ?: unavailable()
        val requester=jdbc.query(
            """
            SELECT requester_user_id FROM approval_request
            WHERE id=? AND organization_id=?
                AND subject_type='FINANCE_GL_PERIOD_CLOSE'
                AND subject_id=? AND subject_version=?
                AND policy_key=? AND state='APPROVED'
            """.trimIndent(),
            { rs,_ -> rs.getObject("requester_user_id",UUID::class.java) },
            approved.approvalRequestId,org,periodId,
            snapshot.sourceVersion,snapshot.policyKey,
        ).singleOrNull() ?: unavailable()
        if (requester==actor) unavailable()
        val eventId=UUID.nameUUIDFromBytes(
            "hiltech:finance-period-close:$operation".toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,periodId,snapshot.sourceVersion,
                snapshot.ledgerSnapshotSha256,snapshot.journalCount,
                approved.approvalRequestId).joinToString("|"),
        )
        val result=idempotency.execute(
            IdempotentCommandSpec(
                operation,actor,"FINANCE_GL_PERIOD_CLOSED",
                "FINANCE_GL_PERIOD_CLOSE",eventId,fingerprint,correlation,
            )
        ) {
            // Lock the same period row the AP journal posting trigger locks.
            // All in-flight GL postings commit before this snapshot is read.
            val locked=jdbc.query(
                """
                SELECT id FROM finance_gl_period WHERE organization_id=?
                    AND id=? AND state='OPEN' FOR UPDATE
                """.trimIndent(),
                { rs,_ -> rs.getObject("id",UUID::class.java) },
                org,periodId,
            ).singleOrNull() ?: stale()
            if (locked!=periodId || source.current(org,periodId)!=snapshot) stale()
            val now=clock.instant().atOffset(ZoneOffset.UTC)
            check(jdbc.update(
                """
                INSERT INTO finance_gl_period_close (
                    id,operation_id,organization_id,period_id,
                    approval_request_id,policy_key,subject_version,
                    snapshot_sha256,journal_count,closed_by_user_id,
                    closed_at,correlation_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                eventId,operation,org,periodId,
                approved.approvalRequestId,snapshot.policyKey,
                snapshot.sourceVersion,snapshot.ledgerSnapshotSha256,
                snapshot.journalCount,actor,now,correlation,
            ) == 1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_GL_PERIOD_CLOSED",
                targetType="FINANCE_GL_PERIOD_CLOSE",targetId=eventId,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="INDEPENDENT_APPROVED_APPEND_ONLY_PERIOD_CLOSE",
            ))
            IdempotentCommandOutcome(
                "FINANCE_GL_PERIOD_CLOSED","""{"id":"$eventId"}""",
            )
        }
        return receipt(snapshot,"CLOSED",approved.approvalRequestId,
            closureId=eventId,replayed=result.replayed)
    }

    fun status(actor: UUID,org: UUID,periodId: UUID): FinancePeriodCloseReceipt {
        requireAuthority(actor,org,"hiltech.finance.gl-period-close.preparer-authority-key")
        historical(org,periodId)?.let { return it }
        val current=source.current(org,periodId) ?: stale()
        val request=engine.subjectStatus(
            ApprovalSubjectRef(
                org,FINANCE_GL_PERIOD_CLOSE_SUBJECT,periodId,current.sourceVersion,
            ),current.policyKey,
        )
        return receipt(current,request?.state?.name ?: "NOT_REQUESTED",
            request?.approvalRequestId)
    }

    private fun historical(org: UUID,period: UUID): FinancePeriodCloseReceipt? {
        if (!source.enabled()) unavailable()
        return jdbc.query(
            """
            SELECT id,approval_request_id,source_version_stub,
                   journal_count,snapshot_sha256
            FROM finance_gl_period_close
            WHERE organization_id=? AND period_id=?
            """.trimIndent().replace("source_version_stub","subject_version"),
            { rs,_ ->
                FinancePeriodCloseReceipt(
                    organizationId=org,periodId=period,
                    ledgerSnapshotSha256=rs.getString("snapshot_sha256"),
                    journalCount=rs.getLong("journal_count"),
                    sourceVersion=rs.getLong("subject_version"),
                    state="CLOSED",
                    approvalRequestId=rs.getObject("approval_request_id",UUID::class.java),
                    periodClosureId=rs.getObject("id",UUID::class.java),
                    periodClosed=true,
                )
            },org,period,
        ).singleOrNull()
    }

    private fun receipt(
        snap: FinancePeriodCloseSnapshot,state:String,
        approvalId: UUID?=null,closureId:UUID?=null,replayed:Boolean=false,
    )=FinancePeriodCloseReceipt(
        organizationId=snap.organizationId,periodId=snap.periodId,
        ledgerSnapshotSha256=snap.ledgerSnapshotSha256,
        journalCount=snap.journalCount,sourceVersion=snap.sourceVersion,
        state=state,approvalRequestId=approvalId,
        periodClosureId=closureId,replayed=replayed,
        periodClosed=closureId!=null,
    )

    private fun requireAuthority(
        actor: UUID,org: UUID,keyName:String,
    ):ApprovalAuthorityBindingSnapshot {
        if (!source.enabled()) unavailable()
        val key=environment.getProperty(keyName,"").trim()
        if (!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) unavailable()
        val now=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,now)) unavailable()
        val binding=approvals.resolveCurrentAuthority(org,key,now) ?: unavailable()
        if (binding.organizationId!=org || binding.authorityKey!=key ||
            !approvals.actorMatchesAuthority(actor,binding,now)) unavailable()
        return binding
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_GL_PERIOD_CLOSE_UNAUTHORIZED",
        "Company Finance period closing is not authorized.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing=throw ProductApiException(
        "FINANCE_GL_PERIOD_CLOSE_STALE",
        "Accounting period or posted-journal snapshot has changed.",
        HttpStatus.CONFLICT,
    )
}

@RestController
class FinanceGlPeriodCloseController(private val service:FinanceGlPeriodCloseService) {
    @PostMapping("/v1/finance/organizations/{orgId}/periods/{periodId}/request-close-review")
    fun request(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable periodId:UUID,@RequestBody body:FinancePeriodCloseOperation
    ):FinancePeriodCloseReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return service.requestReview(actor(http),orgId,periodId,body.operationId,
            ctx.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/periods/{periodId}/close")
    fun close(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable periodId:UUID,@RequestBody body:FinancePeriodCloseOperation
    ):FinancePeriodCloseReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return service.close(actor(http),orgId,periodId,body.operationId,
            ctx.correlationId)
    }
    @GetMapping("/v1/finance/organizations/{orgId}/periods/{periodId}/close")
    fun status(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable periodId:UUID):FinancePeriodCloseReceipt =
        service.status(actor(http),orgId,periodId)

    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED","Finance login required.",HttpStatus.UNAUTHORIZED,
            )
}
