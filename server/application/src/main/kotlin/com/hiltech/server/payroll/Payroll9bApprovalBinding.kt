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
        runCatching { validate(subject, forPublication = false) }.getOrDefault(false)

    fun isCurrentForPublication(subject: ApprovalSubjectRef): Boolean =
        runCatching { validate(subject, forPublication = true) }.getOrDefault(false)

    private fun validate(subject: ApprovalSubjectRef, forPublication: Boolean): Boolean {
        if (subject.subjectVersion != 1L) return false
        val run = jdbc.query(
            """
            SELECT rv.organization_id, rv.payroll_period_id, rv.policy_version_id,
                   rv.policy_version, rv.source_fingerprint, rv.generation, rv.state,
                   (SELECT MAX(generation) FROM payroll_run_version
                    WHERE payroll_period_id = rv.payroll_period_id) AS newest,
                   (SELECT MAX(released.generation)
                    FROM payroll_run_version released
                    JOIN payroll_run_release approval ON
                        approval.run_version_id = released.id
                    WHERE released.payroll_period_id = rv.payroll_period_id)
                       AS newest_published
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
                    newestPublished = rs.getObject("newest_published", Integer::class.java)?.toInt(),
                    state = rs.getString("state"),
                )
            }, subject.subjectId,
        ).singleOrNull() ?: return false
        if (run.organizationId != subject.organizationId ||
            run.state != "DRAFT" ||
            (forPublication && run.generation != run.newestPublished) ||
            (!forPublication && run.generation != run.newest)
        ) return false
        val provider = providers.ifAvailable ?: return false
        val policy = provider.policyFor(run.organizationId, run.periodId) ?: return false
        if (policy.organizationId != run.organizationId ||
            policy.policyVersionId != run.policyId ||
            policy.policyVersion != run.policyVersion
        ) return false

        val lines = jdbc.query(
            """
            SELECT l.employee_id, l.source_fingerprint,
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
        val newestPublished: Int?,
        val state: String,
    )

    private data class RunLineSourceRow(
        val employeeId: UUID,
        val fingerprint: String,
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
 * A private payslip is exposed only if the exact run has a durable, current
 * company release record. ApprovalEngine approval alone is insufficient:
 * an explicit Finance-review release was not necessarily signed.
 */
@Component
class ApprovalBackedPayrollPublication(
    private val guard: PayrollRunApprovalVersionSource,
    private val decisions: PayrollRunReleaseDecisionPort,
    private val jdbc: JdbcTemplate,
    @Value("\${hiltech.payroll.run-approval-policy-key:}") private val policyKey: String,
) : PayrollRunPublicationPort {
    override fun canPublish(
        organizationId: UUID,
        runVersionId: UUID,
        exactSourceFingerprint: String,
    ): Boolean = runCatching {
        if (!Regex("^[A-Z][A-Z0-9_]{2,95}$").matches(policyKey)) return@runCatching false
        val record = jdbc.query(
            """
            SELECT r.run_source_fingerprint, r.policy_version,
                   r.decision_path, r.approval_request_id, r.released_by_user_id
            FROM payroll_run_release r
            JOIN payroll_run_version rv
              ON rv.id = r.run_version_id AND rv.organization_id = r.organization_id
            WHERE r.organization_id = ? AND r.run_version_id = ?
              AND r.policy_key = ? AND rv.source_fingerprint = ?
            """.trimIndent(),
            { rs, _ ->
                PayrollReleaseReadModel(
                    fingerprint = rs.getString("run_source_fingerprint"),
                    policyVersion = rs.getInt("policy_version"),
                    path = rs.getString("decision_path"),
                    approvalId = rs.getObject("approval_request_id", UUID::class.java),
                    actorId = rs.getObject("released_by_user_id", UUID::class.java),
                )
            },
            organizationId, runVersionId, policyKey, exactSourceFingerprint,
        ).singleOrNull() ?: return@runCatching false
        if (record.fingerprint != exactSourceFingerprint) return@runCatching false
        val subject = ApprovalSubjectRef(
            organizationId = organizationId,
            subjectType = "PAYROLL_RUN_VERSION",
            subjectId = runVersionId,
            subjectVersion = 1,
        )
        if (!guard.isCurrentForPublication(subject)) return@runCatching false
        val current = decisions.resolve(record.actorId, subject, policyKey) ?: return@runCatching false
        current.policyVersion == record.policyVersion &&
            current.path == record.path &&
            current.approvalRequestId == record.approvalId
    }.getOrDefault(false)

    private data class PayrollReleaseReadModel(
        val fingerprint: String,
        val policyVersion: Int,
        val path: String,
        val approvalId: UUID?,
        val actorId: UUID,
    )
}
