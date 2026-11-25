package com.hiltech.server.payroll

import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal data class StoredPayrollSource(
    val organizationId: UUID,
    val operationId: UUID,
    val requestFingerprint: String,
    val record: PayrollSourceRecordDto,
)

@Component
class PayrollSourceRepository(private val jdbc: JdbcTemplate, private val clock: Clock) {
    internal fun find(id: UUID): StoredPayrollSource? = read("s.id = ?", id).singleOrNull()
    internal fun byOperation(operationId: UUID): StoredPayrollSource? = read("s.operation_id = ?", operationId).singleOrNull()

    internal fun latest(organizationId: UUID, periodId: UUID, domain: String, employeeId: UUID?): StoredPayrollSource? =
        read("s.organization_id = ? AND s.payroll_period_id = ? AND s.source_domain = ? AND s.employee_id IS NOT DISTINCT FROM ?::uuid",
            organizationId, periodId, domain, employeeId).maxByOrNull { it.record.revision }

    internal fun current(organizationId: UUID, periodId: UUID): List<StoredPayrollSource> =
        read("s.organization_id = ? AND s.payroll_period_id = ?", organizationId, periodId)
            .groupBy { it.record.domain to it.record.employeeId }
            .values.map { rows -> rows.maxBy { it.record.revision } }
            .sortedWith(compareBy({ it.record.domain }, { it.record.employeeId.orEmpty() }))

    internal fun approved(organizationId: UUID, periodId: UUID, domain: String, employeeId: UUID?): StoredPayrollSource? =
        latest(organizationId, periodId, domain, employeeId)?.takeIf { it.record.approvalState == "APPROVED" }

    fun reviewPolicyReady(organizationId: UUID): Boolean = jdbc.queryForObject(
        """
        SELECT EXISTS (
            SELECT 1 FROM approval_policy_version p
            JOIN approval_authority_binding b ON b.organization_id = p.organization_id AND b.authority_key = p.authority_key
            WHERE p.organization_id = ? AND p.policy_key = ? AND p.lifecycle_state = 'ACTIVE'
              AND p.approval_required = true AND p.step_mode = 'SINGLE'
              AND p.authority_key = 'PAYROLL_SOURCE_REVIEWER' AND p.reason_required = true
              AND p.effective_from <= ? AND (p.effective_to IS NULL OR p.effective_to > ?)
              AND b.active = true AND b.effective_from <= ? AND (b.effective_to IS NULL OR b.effective_to > ?)
        )
        """.trimIndent(), Boolean::class.java, organizationId, PayrollSourcePolicy.REVIEW_POLICY,
        now(), now(), now(), now(),
    ) == true

    private fun now() = clock.instant().atOffset(ZoneOffset.UTC)

    fun runReviewPolicyReady(organizationId: UUID, policyKey: String): Boolean = jdbc.queryForObject(
        """
        SELECT EXISTS (SELECT 1 FROM approval_policy_version p
          LEFT JOIN approval_authority_binding b ON b.organization_id = p.organization_id AND b.authority_key = p.authority_key
            AND b.active = true AND b.effective_from <= ? AND (b.effective_to IS NULL OR b.effective_to > ?)
          WHERE p.organization_id = ? AND p.policy_key = ? AND p.lifecycle_state = 'ACTIVE'
            AND p.effective_from <= ? AND (p.effective_to IS NULL OR p.effective_to > ?)
            AND ((p.approval_required = false AND p.step_mode = 'NO_APPROVAL_REQUIRED')
                 OR (p.approval_required = true AND p.step_mode = 'SINGLE' AND b.id IS NOT NULL)))
        """.trimIndent(), Boolean::class.java, now(), now(), organizationId, policyKey, now(), now(),
    ) == true

    private fun read(where: String, vararg arguments: Any?): List<StoredPayrollSource> {
        val rows = jdbc.query(
        """
        SELECT s.*, a.id AS approval_id, a.state AS approval_state,
               CASE WHEN a.state = 'APPROVED' AND p.id IS NOT NULL
                   AND a.completed_at IS NOT NULL AND p.effective_from <= a.completed_at
                   AND (p.effective_to IS NULL OR p.effective_to > a.completed_at) AND
                   EXISTS (SELECT 1 FROM approval_decision d WHERE d.approval_request_id = a.id
                           AND d.decision = 'APPROVE' AND d.subject_version = 1
                           AND d.actor_user_id <> s.prepared_by_user_id)
                   THEN true ELSE false END AS approved_current,
               CASE WHEN p.lifecycle_state = 'ACTIVE' AND p.effective_from <= ?
                   AND (p.effective_to IS NULL OR p.effective_to > ?) THEN true ELSE false END AS policy_current
        FROM payroll_source_record s
        LEFT JOIN approval_request a ON a.organization_id = s.organization_id
          AND a.subject_type = 'PAYROLL_SOURCE_RECORD' AND a.subject_id = s.id
          AND a.subject_version = 1 AND a.policy_key = 'PAYROLL_SOURCE_REVIEW'
          AND a.requester_user_id = s.prepared_by_user_id
        LEFT JOIN approval_policy_version p ON p.organization_id = a.organization_id
          AND p.policy_key = a.policy_key AND p.version_number = a.policy_version
          AND p.lifecycle_state IN ('ACTIVE','RETIRED') AND p.approval_required = true AND p.step_mode = 'SINGLE'
          AND p.authority_key = 'PAYROLL_SOURCE_REVIEWER' AND p.reason_required = true
        WHERE $where
        ORDER BY s.revision DESC
        """.trimIndent(),
        { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            val state = rs.getString("approval_state")
            StoredPayrollSource(
                organizationId = rs.getObject("organization_id", UUID::class.java),
                operationId = rs.getObject("operation_id", UUID::class.java),
                requestFingerprint = rs.getString("request_fingerprint"),
                record = PayrollSourceRecordDto(
                    recordId = id.toString(), periodId = rs.getObject("payroll_period_id", UUID::class.java).toString(),
                    domain = rs.getString("source_domain"), employeeId = rs.getObject("employee_id", UUID::class.java)?.toString(),
                    revision = rs.getLong("revision"), replacesRecordId = rs.getObject("replaces_record_id", UUID::class.java)?.toString(),
                    sourceReference = rs.getString("source_reference"), currency = rs.getString("currency").trim(),
                    noInputs = rs.getBoolean("no_inputs"), components = emptyList(),
                    fractionalDigits = rs.getObject("fractional_digits", Integer::class.java)?.toInt(),
                    roundingMode = rs.getString("rounding_mode"),
                    companyAuthorizationReference = rs.getString("company_authorization_reference"),
                    statutoryPolicyReference = rs.getString("statutory_policy_reference"),
                    deductionPolicyReference = rs.getString("deduction_policy_reference"),
                    preparedByIdentityId = rs.getObject("prepared_by_user_id", UUID::class.java).toString(),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant().toString(),
                    approvalState = when {
                        rs.getBoolean("approved_current") -> "APPROVED"
                        state == "APPROVED" -> "POLICY_REVIEW_REQUIRED"
                        state == "PENDING" && !rs.getBoolean("policy_current") -> "POLICY_REVIEW_REQUIRED"
                        else -> state ?: "DRAFT"
                    },
                    approvalRequestId = rs.getObject("approval_id", UUID::class.java)?.toString(),
                ),
            )
        }, now(), now(), *arguments,
        )
        if (rows.isEmpty()) return rows
        val components = jdbc.query(
            """
            SELECT c.source_record_id,c.component_code,c.component_kind,c.amount,c.deduction_plan_reference,c.description
            FROM payroll_source_component c JOIN payroll_source_record s ON s.id = c.source_record_id
            WHERE $where ORDER BY c.source_record_id,c.component_code
            """.trimIndent(),
            { rs, _ -> rs.getObject(1, UUID::class.java).toString() to PayrollSourceComponentDto(
                rs.getString(2), rs.getString(3), rs.getBigDecimal(4).stripTrailingZeros().toPlainString(), rs.getString(5), rs.getString(6)) },
            *arguments,
        ).groupBy({ it.first }, { it.second })
        return rows.map { it.copy(record = it.record.copy(components = components[it.record.recordId].orEmpty())) }
    }
}

/** Same version guard is used for inbox decisions and for source consumption. */
@Component
class PayrollSourceApprovalVersionSource(private val jdbc: JdbcTemplate) : ApprovalSubjectVersionSource {
    override fun supports(subjectType: String): Boolean = subjectType == PayrollSourcePolicy.SUBJECT_TYPE

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean = subject.subjectVersion == 1L && jdbc.queryForObject(
        """
        SELECT EXISTS (
            SELECT 1 FROM payroll_source_record s
            JOIN payroll_period p ON p.id = s.payroll_period_id AND p.organization_id = s.organization_id
            WHERE s.id = ? AND s.organization_id = ? AND p.state = 'INPUTS_SNAPSHOTTED'
              AND NOT EXISTS (SELECT 1 FROM payroll_source_record newer
                  WHERE newer.organization_id = s.organization_id AND newer.payroll_period_id = s.payroll_period_id
                    AND newer.source_domain = s.source_domain AND newer.employee_id IS NOT DISTINCT FROM s.employee_id
                    AND newer.revision > s.revision)
              AND (s.employee_id IS NULL OR EXISTS (
                  SELECT 1 FROM payroll_employee_input_snapshot snap
                  JOIN employee e ON e.id = snap.employee_id AND e.organization_id = snap.organization_id
                  JOIN employment em ON em.id = snap.employment_id AND em.employee_id = e.id
                  LEFT JOIN workforce_assignment wa ON wa.id = snap.workforce_assignment_id
                  WHERE snap.payroll_period_id = s.payroll_period_id AND snap.employee_id = s.employee_id
                    AND snap.employee_version = e.version AND snap.employment_version = em.version
                    AND (snap.workforce_assignment_id IS NULL OR wa.version = snap.workforce_assignment_version)
              ))
        )
        """.trimIndent(), Boolean::class.java, subject.subjectId, subject.organizationId,
    ) == true
}
