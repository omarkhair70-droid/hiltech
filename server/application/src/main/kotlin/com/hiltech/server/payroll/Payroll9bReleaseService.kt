package com.hiltech.server.payroll

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalEvaluationResult
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * This decision never creates a payment instruction. It binds a currently
 * approved company ApprovalEngine policy to one exact run version.
 */
data class PayrollRunPolicyDecision(
    val path: String,
    val policyKey: String,
    val policyVersion: Int,
    val approvalRequestId: UUID?,
)

interface PayrollRunReleaseDecisionPort {
    fun resolve(
        actorId: UUID,
        subject: ApprovalSubjectRef,
        policyKey: String,
    ): PayrollRunPolicyDecision?
}

@Component
class SharedApprovalPayrollRunReleaseDecision(
    private val engine: ApprovalEngineService,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) : PayrollRunReleaseDecisionPort {
    override fun resolve(
        actorId: UUID,
        subject: ApprovalSubjectRef,
        policyKey: String,
    ): PayrollRunPolicyDecision? {
        val policy = jdbc.query(
            """
            SELECT version_number, approval_required, step_mode
            FROM approval_policy_version
            WHERE organization_id = ? AND policy_key = ?
              AND lifecycle_state = 'ACTIVE'
              AND effective_from <= ?
              AND (effective_to IS NULL OR effective_to > ?)
            """.trimIndent(),
            { rs, _ ->
                Triple(
                    rs.getInt("version_number"),
                    rs.getBoolean("approval_required"),
                    rs.getString("step_mode"),
                )
            },
            subject.organizationId, policyKey,
            clock.instant().atOffset(ZoneOffset.UTC),
            clock.instant().atOffset(ZoneOffset.UTC),
        ).singleOrNull() ?: return null
        val evaluated = engine.evaluate(actorId, subject.organizationId, policyKey)
        val evaluatedVersion = when (evaluated) {
            is ApprovalEvaluationResult.NoApprovalRequired -> evaluated.policyVersion
            is ApprovalEvaluationResult.ApprovalRequired -> evaluated.policyVersion
        }
        if (evaluatedVersion != policy.first) return null
        // Only a genuinely NO_APPROVAL_REQUIRED company policy may take the
        // routine route. ApprovalEngine's "actor matches authority" shortcut
        // must NOT silently bypass a configured SINGLE exception approval.
        if (!policy.second && policy.third == "NO_APPROVAL_REQUIRED") {
            return PayrollRunPolicyDecision(
                "ROUTINE_AUTHORIZED", policyKey, policy.first, null,
            )
        }
        if (!policy.second || policy.third != "SINGLE") return null
        val state = engine.subjectStatus(subject, policyKey) ?: return null
        if (state.state != ApprovalRequestState.APPROVED ||
            state.subjectVersion != subject.subjectVersion
        ) return null
        val matchesCurrentPolicy = jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM approval_request ar
                WHERE ar.id = ? AND ar.organization_id = ?
                  AND ar.subject_type = ? AND ar.subject_id = ?
                  AND ar.subject_version = ? AND ar.policy_key = ?
                  AND ar.policy_version = ? AND ar.state = 'APPROVED'
                  AND EXISTS (SELECT 1 FROM approval_decision d JOIN payroll_run_version r ON r.id = ar.subject_id
                      WHERE d.approval_request_id = ar.id AND d.subject_version = ar.subject_version AND d.decision = 'APPROVE'
                        AND r.organization_id = ar.organization_id AND d.actor_user_id <> ar.requester_user_id
                        AND d.actor_user_id <> r.created_by_user_id)
            )
            """.trimIndent(),
            Boolean::class.java,
            state.approvalRequestId, subject.organizationId, subject.subjectType,
            subject.subjectId, subject.subjectVersion, policyKey, policy.first,
        ) == true
        if (!matchesCurrentPolicy) return null
        return PayrollRunPolicyDecision(
            "APPROVED_EXCEPTION", policyKey, policy.first, state.approvalRequestId,
        )
    }
}

data class PayrollRunReleaseDto(
    val releaseId: String,
    val runVersionId: String,
    val status: String,
    val policyKey: String,
    val policyVersion: Int,
    val decisionPath: String,
    val releasedAt: String,
    val replayed: Boolean,
)

private data class PayrollReleaseStored(
    val id: UUID,
    val operationId: UUID,
    val org: UUID,
    val runId: UUID,
    val fingerprint: String,
    val policyKey: String,
    val policyVersion: Int,
    val path: String,
    val approvalRequestId: UUID?,
    val actorId: UUID,
    val releasedAt: OffsetDateTime,
)

/**
 * The last *approval* step before a future Finance payment-preparation lane.
 * Does NOT mark an employee paid and does NOT create a bank/payment object.
 */
@Component
class Payroll9bRunReleaseService(
    private val jdbc: JdbcTemplate,
    private val drafts: Payroll9bDraftService,
    private val authority: Payroll9aAuthorityPort,
    private val guard: PayrollRunApprovalVersionSource,
    private val decision: PayrollRunReleaseDecisionPort,
    private val corrections: Payroll9cCorrectionService,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.payroll.run-approval-policy-key:PAYROLL_RUN_REVIEW}") policyKey: String,
) {
    private val policyKey = payrollRunPolicyKey(policyKey)

    @Transactional
    fun release(
        actorId: UUID,
        runId: UUID,
        operationId: UUID,
        correlationId: String,
    ): PayrollRunReleaseDto {
        val run = drafts.read(actorId, runId)
        val org = UUID.fromString(run.organizationId)
        if (!authority.canReview(actorId, org)) {
            Payroll9aPolicy.fail("PAYROLL_RELEASE_FORBIDDEN", HttpStatus.FORBIDDEN)
        }
        if (!Regex("^[A-Z][A-Z0-9_]{2,95}$").matches(policyKey)) {
            Payroll9aPolicy.fail("PAYROLL_RELEASE_POLICY_UNCONFIGURED", HttpStatus.CONFLICT)
        }
        // Serializes release with creation of later run generations.
        jdbc.queryForObject(
            "SELECT id FROM payroll_period WHERE id = ? AND organization_id = ? FOR UPDATE",
            UUID::class.java, UUID.fromString(run.payrollPeriodId), org,
        ) ?: Payroll9aPolicy.fail("PAYROLL_PERIOD_NOT_FOUND", HttpStatus.NOT_FOUND)
        // A retry of an already committed command remains a historical
        // replay even if a later correction generation now exists.
        val existing = findByOperation(operationId)
        if (existing != null) {
            if (existing.org != org || existing.runId != runId ||
                existing.actorId != actorId || existing.fingerprint != run.sourceFingerprint ||
                existing.policyKey != policyKey
            ) Payroll9aPolicy.fail("PAYROLL_RELEASE_IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT)
            return toDto(existing, replayed = true)
        }
        val subject = ApprovalSubjectRef(org, "PAYROLL_RUN_VERSION", runId, 1)
        if (!guard.isCurrent(subject)) {
            Payroll9aPolicy.fail("PAYROLL_RELEASE_SOURCE_STALE", HttpStatus.CONFLICT)
        }
        val policy = decision.resolve(actorId, subject, policyKey)
            ?: Payroll9aPolicy.fail("PAYROLL_RELEASE_APPROVAL_REQUIRED", HttpStatus.CONFLICT)
        if (policy.policyKey != policyKey ||
            policy.policyVersion < 1 ||
            (policy.path != "ROUTINE_AUTHORIZED" && policy.path != "APPROVED_EXCEPTION") ||
            (policy.path == "APPROVED_EXCEPTION" && policy.approvalRequestId == null) ||
            (policy.path == "ROUTINE_AUTHORIZED" && policy.approvalRequestId != null)
        ) Payroll9aPolicy.fail("PAYROLL_RELEASE_INVALID_DECISION", HttpStatus.CONFLICT)
        // A previous released payroll needs signed HR+Finance correction
        // clearance before another release. This and the release INSERT share
        // one transaction: failures roll back the correction ledger too.
        corrections.recordIfCorrecting(actorId, run, operationId, correlationId)
        val id = UUID.nameUUIDFromBytes(
            ("hiltech:payroll-release:" + operationId).toByteArray(StandardCharsets.UTF_8),
        )
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        try {
            jdbc.update(
                """
                INSERT INTO payroll_run_release (
                    id, operation_id, organization_id, run_version_id,
                    run_source_fingerprint, policy_key, policy_version,
                    decision_path, approval_request_id, released_by_user_id, released_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, operationId, org, runId, run.sourceFingerprint,
                policyKey, policy.policyVersion, policy.path,
                policy.approvalRequestId, actorId, now,
            )
        } catch (_: DataIntegrityViolationException) {
            Payroll9aPolicy.fail("PAYROLL_RUN_ALREADY_RELEASED", HttpStatus.CONFLICT)
        }
        audit.append(
            AuditEventRecord(
                actorUserId = actorId,
                action = "PAYROLL_RUN_RELEASED_FOR_FINANCE_REVIEW",
                targetType = "PAYROLL_RUN_VERSION",
                targetId = runId,
                occurredAt = clock.instant(),
                correlationId = correlationId,
            ),
        )
        return PayrollRunReleaseDto(
            id.toString(), runId.toString(),
            "RELEASED_FOR_FINANCE_REVIEW",
            policyKey, policy.policyVersion,
            policy.path, now.toInstant().toString(), replayed = false,
        )
    }

    private fun toDto(row: PayrollReleaseStored, replayed: Boolean) =
        PayrollRunReleaseDto(
            releaseId = row.id.toString(),
            runVersionId = row.runId.toString(),
            status = "RELEASED_FOR_FINANCE_REVIEW",
            policyKey = row.policyKey,
            policyVersion = row.policyVersion,
            decisionPath = row.path,
            releasedAt = row.releasedAt.toInstant().toString(),
            replayed = replayed,
        )

    private fun findByOperation(id: UUID): PayrollReleaseStored? =
        jdbc.query(
            """
            SELECT id, operation_id, organization_id, run_version_id,
                   run_source_fingerprint, policy_key, policy_version,
                   decision_path, approval_request_id, released_by_user_id, released_at
            FROM payroll_run_release WHERE operation_id = ?
            """.trimIndent(),
            { rs, _ ->
                PayrollReleaseStored(
                    id = rs.getObject("id", UUID::class.java),
                    operationId = rs.getObject("operation_id", UUID::class.java),
                    org = rs.getObject("organization_id", UUID::class.java),
                    runId = rs.getObject("run_version_id", UUID::class.java),
                    fingerprint = rs.getString("run_source_fingerprint"),
                    policyKey = rs.getString("policy_key"),
                    policyVersion = rs.getInt("policy_version"),
                    path = rs.getString("decision_path"),
                    approvalRequestId = rs.getObject("approval_request_id", UUID::class.java),
                    actorId = rs.getObject("released_by_user_id", UUID::class.java),
                    releasedAt = rs.getObject("released_at", OffsetDateTime::class.java),
                )
            }, id,
        ).singleOrNull()
}
