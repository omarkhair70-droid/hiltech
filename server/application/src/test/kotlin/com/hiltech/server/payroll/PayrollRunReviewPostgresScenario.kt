package com.hiltech.server.payroll

import com.hiltech.server.approval.*
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** Called with a legitimately calculated run from the recorded-source contract. */
object PayrollRunReviewPostgresScenario {
    fun verify(jdbc: JdbcTemplate, tx: TransactionTemplate, org: UUID, preparer: UUID, reviewer: UUID,
        runId: UUID, drafts: Payroll9bDraftService, source: ObjectProvider<PayrollApprovedInputSourcePort>,
        authority: Payroll9aAuthorityPort, clock: Clock) {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        val guard = PayrollRunApprovalVersionSource(jdbc, source)
        val persistence = JdbcApprovalPersistence(jdbc)
        val authorization = object : ApprovalAuthorizationPort {
            override fun canDecide(actorUserId: UUID, approvalRequestId: UUID,
                assignmentPrincipalType: ApprovalPrincipalType, assignmentPrincipalId: UUID) =
                actorUserId == reviewer && assignmentPrincipalType == ApprovalPrincipalType.USER && assignmentPrincipalId == reviewer
        }
        val engine = ApprovalEngineService(persistence,
            JdbcIdempotentCommandExecutor(jdbc, DataSourceTransactionManager(jdbc.dataSource!!), clock,
                HiltechTelemetryRuntime(OpenTelemetry.noop()) {}), AuthorizationProjectionIntentWriter {}, authorization,
            FailClosedApprovalSubjectVersionGuard(listOf(guard)), AuditEventWriter.NOOP, ApplicationEventPublisher {}, clock)
        val policy = PayrollRunPolicyConfiguration("")
        jdbc.update("""INSERT INTO approval_authority_binding(id,organization_id,authority_key,principal_type,principal_user_id,
            effective_from,active,created_by_user_id,created_at) VALUES(?,?,'PAYROLL_RUN_REVIEWER','USER',?,?,true,?,?)""",
            UUID.randomUUID(), org, reviewer, at.minusDays(1), preparer, at)
        jdbc.update("""INSERT INTO approval_policy_version(id,organization_id,policy_key,version_number,lifecycle_state,
            approval_required,step_mode,authority_key,reason_required,effective_from,created_by_user_id,created_at)
            VALUES(?,?,?,1,'ACTIVE',true,'SINGLE','PAYROLL_RUN_REVIEWER',true,?,?,?)""",
            UUID.randomUUID(), org, policy.key, at.minusDays(1), preparer, at)
        jdbc.update("""INSERT INTO payroll_authority_binding(id,organization_id,identity_id,capability,valid_from,active,source_approval_ref,created_at)
            VALUES(?,?,?,'REVIEW',?,true,'SYNTHETIC_APPROVED_RUN_REVIEW',?)""", UUID.randomUUID(), org, reviewer, at.minusDays(1), at)
        val decision = SharedApprovalPayrollRunReleaseDecision(engine, jdbc, clock)
        val service = PayrollRunReviewService(jdbc, drafts, authority, guard, engine,
            ApprovalReadService(persistence, authorization, ApprovalCursorCodec("synthetic-run-review-cursor-key-at-least-32"), clock),
            persistence, decision, policy, clock)
        assertTrue(service.read(preparer, runId).canRequestReview)
        assertFalse(service.read(reviewer, runId).canDecide)
        val subject = ApprovalSubjectRef(org, "PAYROLL_RUN_VERSION", runId, 1)
        val request = engine.requestException(preparer, UUID.randomUUID(), subject, policy.key,
            "PAYROLL_RUN_REVIEW", null, "synthetic-run-review", allowAuthorityBypass = false)
        val requestId = requireNotNull(request.approvalRequestId)
        assertFalse(service.read(preparer, runId).canDecide)
        assertTrue(service.read(reviewer, runId).canDecide)
        assertFalse(service.read(reviewer, runId).canRelease)
        val command = PayrollRunDecisionRequestDto(UUID.randomUUID().toString(), requestId.toString(), "APPROVE", "Synthetic reviewed inputs and totals")
        assertThrows<ProductApiException> { tx.execute { service.decide(preparer, runId, command, "synthetic-self-review") } }
        assertThrows<ProductApiException> { tx.execute { service.decide(reviewer, runId, command.copy(approvalRequestId = UUID.randomUUID().toString()), "synthetic-cross-request") } }
        assertThrows<ProductApiException> { tx.execute { service.decide(reviewer, runId,
            command.copy(decision = "REQUEST_CHANGE", comment = null), "synthetic-missing-reason") } }
        assertEquals("PENDING", service.read(reviewer, runId).approvalState)
        val approved = tx.execute { service.decide(reviewer, runId, command, "synthetic-run-approve") }!!
        assertEquals("APPROVED", approved.approvalState)
        assertFalse(approved.replayed)
        assertTrue(tx.execute { service.decide(reviewer, runId, command, "synthetic-run-replay") }!!.replayed)
        assertTrue(service.read(reviewer, runId).canRelease)
        assertFalse(service.read(reviewer, runId).canDecide)
        assertThrows<ProductApiException> { tx.execute { service.decide(reviewer, runId,
            command.copy(decision = "REJECT", comment = "Synthetic changed decision"), "synthetic-changed-retry") } }
        val releases = Payroll9bRunReleaseService(jdbc, drafts, authority, guard, decision,
            Payroll9cCorrectionService(jdbc, StaticListableBeanFactory().getBeanProvider(PayrollVerifiedCorrectionSourcePort::class.java),
                AuditEventWriter.NOOP, clock), AuditEventWriter.NOOP, clock, policy.key)
        val released = tx.execute { releases.release(reviewer, runId, UUID.randomUUID(), "synthetic-independent-release") }!!
        assertEquals("APPROVED_EXCEPTION", released.decisionPath)
        assertEquals("RELEASED", service.read(reviewer, runId).approvalState)
        assertFalse(service.read(reviewer, runId).canRelease)
        assertTrue(ApprovalBackedPayrollPublication(guard, decision, jdbc, policy.key)
            .canPublish(org, runId, drafts.read(preparer, runId).sourceFingerprint))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM approval_decision WHERE approval_request_id=?", Long::class.java, requestId))
    }
}
