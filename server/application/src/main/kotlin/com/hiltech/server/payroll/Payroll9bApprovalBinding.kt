package com.hiltech.server.payroll

import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import com.hiltech.server.approval.ApprovalEngineService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * ApprovalEngine can bind to a payroll run only when its exact underlying
 * employee/contract/assignment and component input source versions remain
 * current. A newer run for the same period supersedes the prior candidate.
 */
@Component
class PayrollRunApprovalVersionSource(
    private val jdbc: JdbcTemplate,
    private val providers: ObjectProvider<PayrollApprovedInputSourcePort>,
) : ApprovalSubjectVersionSource {
    override fun supports(subjectType: String): Boolean =
        subjectType == "PAYROLL_RUN_VERSION"

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean =
        runCatching { validate(subject) }.getOrDefault(false)

    fun isCurrentForPublication(subject: ApprovalSubjectRef): Boolean =
        runCatching { historicalReleaseSource(subject) }.getOrDefault(false)

    /** Frozen, released truth is independent of today's employment or draft input changes. */
    private fun historicalReleaseSource(subject: ApprovalSubjectRef): Boolean {
        if (subject.subjectVersion != 1L) return false
        val source = jdbc.query(
            """
            SELECT rv.organization_id,rv.payroll_period_id,rv.policy_version_id,rv.source_fingerprint
            FROM payroll_run_version rv JOIN payroll_run_release released
              ON released.run_version_id = rv.id AND released.organization_id = rv.organization_id
             AND released.run_source_fingerprint = rv.source_fingerprint
            WHERE rv.id = ? AND rv.organization_id = ? AND rv.state = 'DRAFT'
              AND NOT EXISTS (SELECT 1 FROM payroll_run_version newer
                  JOIN payroll_run_release completed ON completed.run_version_id = newer.id
                  WHERE newer.payroll_period_id = rv.payroll_period_id
                    AND newer.organization_id = rv.organization_id AND newer.generation > rv.generation)
              AND EXISTS (SELECT 1 FROM payroll_run_employee_line line WHERE line.run_version_id = rv.id)
              AND (SELECT count(*) FROM payroll_run_employee_line line WHERE line.run_version_id = rv.id) =
                  (SELECT count(*) FROM payroll_employee_input_snapshot snap WHERE snap.payroll_period_id = rv.payroll_period_id)
              AND rv.gross = (SELECT sum(line.gross) FROM payroll_run_employee_line line WHERE line.run_version_id = rv.id)
              AND rv.deductions = (SELECT sum(line.deductions) FROM payroll_run_employee_line line WHERE line.run_version_id = rv.id)
              AND rv.reimbursements = (SELECT sum(line.reimbursements) FROM payroll_run_employee_line line WHERE line.run_version_id = rv.id)
              AND rv.net = (SELECT sum(line.net) FROM payroll_run_employee_line line WHERE line.run_version_id = rv.id)
            """.trimIndent(),
            { rs, _ -> listOf(rs.getObject(1, UUID::class.java).toString(), rs.getObject(2, UUID::class.java).toString(),
                rs.getObject(3, UUID::class.java).toString(), rs.getString(4)) },
            subject.subjectId, subject.organizationId,
        ).singleOrNull() ?: return false
        val fingerprints = jdbc.query("SELECT source_fingerprint FROM payroll_run_employee_line WHERE run_version_id = ?",
            { rs, _ -> rs.getString(1) }, subject.subjectId)
        return PayrollRunSourceFingerprint.of(UUID.fromString(source[0]), UUID.fromString(source[1]),
            UUID.fromString(source[2]), fingerprints) == source[3]
    }

    private fun validate(subject: ApprovalSubjectRef): Boolean {
        if (subject.subjectVersion != 1L) return false
        val run = jdbc.query(
            """
            SELECT rv.organization_id, rv.payroll_period_id, rv.policy_version_id,
                   rv.policy_version, rv.source_fingerprint, rv.generation, rv.state,
                   (SELECT MAX(generation) FROM payroll_run_version
                    WHERE payroll_period_id = rv.payroll_period_id) AS newest
            FROM payroll_run_version rv WHERE rv.id = ?
            """.trimIndent(),
            { rs, _ ->
                RunSourceRow(
                    organizationId = rs.getObject("organization_id", UUID::class.java),
                    periodId = rs.getObject("payroll_period_id", UUID::class.java),
                    policyId = rs.getObject("policy_version_id", UUID::class.java),
                    policyVersion = rs.getLong("policy_version"),
                    fingerprint = rs.getString("source_fingerprint"),
                    generation = rs.getInt("generation"),
                    newest = rs.getInt("newest"),
                    state = rs.getString("state"),
                )
            }, subject.subjectId,
        ).singleOrNull() ?: return false
        if (run.organizationId != subject.organizationId ||
            run.state != "DRAFT" ||
            run.generation != run.newest
        ) return false
        val provider = providers.ifAvailable ?: return false
        val policy = provider.policyFor(run.organizationId, run.periodId) ?: return false
        if (policy.organizationId != run.organizationId ||
            policy.policyVersionId != run.policyId ||
            policy.policyVersion != run.policyVersion
        ) return false

        val lines = jdbc.query(
            """
            SELECT l.employee_id, l.source_fingerprint, s.employee_state,
                   s.employee_version, e.version AS current_employee_version,
                   s.employment_version, em.version AS current_employment_version,
                   s.workforce_assignment_id, s.workforce_assignment_version,
                   current_wa.id AS current_assignment_id,
                   current_wa.version AS current_assignment_version
            FROM payroll_run_employee_line l
            JOIN payroll_employee_input_snapshot s ON s.id = l.employee_snapshot_id
            JOIN employee e ON e.id = s.employee_id AND e.organization_id = s.organization_id
            LEFT JOIN employment em ON em.id = s.employment_id
            LEFT JOIN LATERAL (
                SELECT a.id, a.version
                FROM workforce_assignment a
                JOIN payroll_period pp ON pp.id = s.payroll_period_id
                WHERE a.employee_id = s.employee_id
                  AND a.organization_id = s.organization_id
                  AND a.effective_from < ((pp.cutoff_date + 1)::timestamp AT TIME ZONE 'UTC')
                  AND (a.effective_to IS NULL OR
                       a.effective_to >= (pp.start_date::timestamp AT TIME ZONE 'UTC'))
                ORDER BY a.effective_from DESC, a.id DESC
                LIMIT 1
            ) current_wa ON true
            WHERE l.run_version_id = ?
            ORDER BY l.employee_id
            """.trimIndent(),
            { rs, _ ->
                RunLineSourceRow(
                    employeeId = rs.getObject("employee_id", UUID::class.java),
                    fingerprint = rs.getString("source_fingerprint"),
                    employeeState = rs.getString("employee_state"),
                    employeeVersion = rs.getLong("employee_version"),
                    currentEmployeeVersion = rs.getLong("current_employee_version"),
                    employmentVersion = rs.getLong("employment_version"),
                    currentEmploymentVersion = rs.getObject("current_employment_version", java.lang.Long::class.java)?.toLong(),
                    assignmentId = rs.getObject("workforce_assignment_id", UUID::class.java),
                    assignmentVersion = rs.getObject("workforce_assignment_version", java.lang.Long::class.java)?.toLong(),
                    currentAssignmentId = rs.getObject("current_assignment_id", UUID::class.java),
                    currentAssignmentVersion = rs.getObject("current_assignment_version", java.lang.Long::class.java)?.toLong(),
                )
            }, subject.subjectId,
        )
        if (lines.isEmpty()) return false
        val expectedPopulation = jdbc.queryForObject(
            "SELECT count(*) FROM payroll_employee_input_snapshot WHERE payroll_period_id = ?",
            Int::class.java, run.periodId,
        )
        if (expectedPopulation != lines.size) return false
        val currentFingerprints = lines.map { line ->
            if (Payroll9aPolicy.sourceChanged(
                line.employeeVersion, line.employmentVersion,
                line.assignmentId, line.assignmentVersion,
                line.currentEmployeeVersion, line.currentEmploymentVersion,
                line.currentAssignmentId, line.currentAssignmentVersion,
            )) return false
            val sourceBundle = provider.verifiedFor(
                run.organizationId, run.periodId, line.employeeId,
            )
            val current = Payroll9bCalculator.calculate(
                run.organizationId, run.periodId, line.employeeId, policy,
                sourceBundle.components, sourceBundle.coverageReceipts,
                line.employeeState, sourceBundle.separation,
            )
            if (current.sourceFingerprint != line.fingerprint) return false
            current.sourceFingerprint
        }
        return PayrollRunSourceFingerprint.of(
            run.organizationId, run.periodId, policy.policyVersionId, currentFingerprints,
        ) == run.fingerprint
    }

    private data class RunSourceRow(
        val organizationId: UUID,
        val periodId: UUID,
        val policyId: UUID,
        val policyVersion: Long,
        val fingerprint: String,
        val generation: Int,
        val newest: Int,
        val state: String,
    )

    private data class RunLineSourceRow(
        val employeeId: UUID,
        val fingerprint: String,
        val employeeState: String,
        val employeeVersion: Long,
        val currentEmployeeVersion: Long,
        val employmentVersion: Long,
        val currentEmploymentVersion: Long?,
        val assignmentId: UUID?,
        val assignmentVersion: Long?,
        val currentAssignmentId: UUID?,
        val currentAssignmentVersion: Long?,
    )
}

/** Shared deterministic run-version digest; never calculate on a mutable source. */
object PayrollRunSourceFingerprint {
    fun of(
        organizationId: UUID,
        periodId: UUID,
        policyId: UUID,
        employeeFingerprints: List<String>,
    ): String {
        val canonical = (
            listOf(organizationId.toString(), periodId.toString(), policyId.toString()) +
                employeeFingerprints.sorted()
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/**
 * A private payslip is exposed only for an exact immutable company release.
 * Policy/authority were checked when that release was recorded. Later employee
 * changes, reviewer rotation and unapproved correction drafts do not erase it;
 * a completed replacement release supersedes the displayed version.
 */
@Component
class ApprovalBackedPayrollPublication(
    private val guard: PayrollRunApprovalVersionSource,
    @Suppress("UNUSED_PARAMETER") decisions: PayrollRunReleaseDecisionPort,
    private val jdbc: JdbcTemplate,
    @Suppress("UNUSED_PARAMETER") @Value("\${hiltech.payroll.run-approval-policy-key:}") policyKey: String,
) : PayrollRunPublicationPort {
    override fun canPublish(
        organizationId: UUID,
        runVersionId: UUID,
        exactSourceFingerprint: String,
    ): Boolean = runCatching {
        if (!Regex("^[0-9a-f]{64}$").matches(exactSourceFingerprint)) return@runCatching false
        val approvedRelease = jdbc.queryForObject(
            """
            SELECT EXISTS (SELECT 1
            FROM payroll_run_release r
            JOIN payroll_run_version rv
              ON rv.id = r.run_version_id AND rv.organization_id = r.organization_id
             AND rv.source_fingerprint = r.run_source_fingerprint
            JOIN approval_policy_version p ON p.organization_id = r.organization_id
              AND p.policy_key = r.policy_key AND p.version_number = r.policy_version
              AND p.lifecycle_state IN ('ACTIVE','RETIRED')
              AND p.effective_from <= r.released_at AND (p.effective_to IS NULL OR p.effective_to > r.released_at)
            WHERE r.organization_id = ? AND r.run_version_id = ?
              AND rv.source_fingerprint = ?
              AND ((r.decision_path = 'ROUTINE_AUTHORIZED' AND r.approval_request_id IS NULL
                    AND p.approval_required = false AND p.step_mode = 'NO_APPROVAL_REQUIRED')
                   OR (r.decision_path = 'APPROVED_EXCEPTION' AND p.approval_required = true AND p.step_mode = 'SINGLE'
                       AND EXISTS (SELECT 1 FROM approval_request a
                           WHERE a.id = r.approval_request_id AND a.organization_id = r.organization_id
                             AND a.subject_type = 'PAYROLL_RUN_VERSION' AND a.subject_id = r.run_version_id
                             AND a.subject_version = 1 AND a.policy_key = r.policy_key AND a.policy_version = r.policy_version
                             AND a.state = 'APPROVED' AND a.completed_at <= r.released_at
                             AND EXISTS (SELECT 1 FROM approval_decision d WHERE d.approval_request_id = a.id
                                 AND d.subject_version = 1 AND d.decision = 'APPROVE'
                                 AND d.actor_user_id <> a.requester_user_id AND d.actor_user_id <> rv.created_by_user_id))))
            )
            """.trimIndent(),
            Boolean::class.java, organizationId, runVersionId, exactSourceFingerprint,
        ) == true
        if (!approvedRelease) return@runCatching false
        val subject = ApprovalSubjectRef(
            organizationId = organizationId,
            subjectType = "PAYROLL_RUN_VERSION",
            subjectId = runVersionId,
            subjectVersion = 1,
        )
        guard.isCurrentForPublication(subject)
    }.getOrDefault(false)
}
