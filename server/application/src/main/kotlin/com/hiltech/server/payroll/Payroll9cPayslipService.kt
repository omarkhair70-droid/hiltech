package com.hiltech.server.payroll

import com.hiltech.server.people.PeoplePersistencePort
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Publication is an exact historical approval/release fact, not a writable
 * Boolean or a dependency on later employment edits and bank availability.
 * Without an adapter the employee sees NO payroll data.
 */
interface PayrollRunPublicationPort {
    fun canPublish(
        organizationId: UUID,
        runVersionId: UUID,
        exactSourceFingerprint: String,
    ): Boolean
}

data class PrivatePayrollPayslipDto(
    val runVersionId: String,
    val employeeId: String,
    val periodStart: String,
    val periodEnd: String,
    val generation: Int,
    val currency: String,
    val gross: String,
    val deductions: String,
    val reimbursements: String,
    val net: String,
    val paymentStatus: String,
    val paymentAvailability: String = "RECORDED",
)

data class OwnPayrollPayslipsDto(
    val items: List<PrivatePayrollPayslipDto>,
    val correlationId: String,
)

private data class OwnPayrollLine(
    val runId: UUID,
    val employeeId: UUID,
    val start: LocalDate,
    val end: LocalDate,
    val generation: Int,
    val currency: String,
    val gross: BigDecimal,
    val deductions: BigDecimal,
    val reimbursements: BigDecimal,
    val net: BigDecimal,
    val fingerprint: String,
)

/** Strict self-only read. No administrative list and no bank account metadata. */
@Component
class Payroll9cPayslipService(
    private val jdbc: JdbcTemplate,
    private val people: PeoplePersistencePort,
    private val roleAuthority: RoleTeamSourceAuthorityPort,
    private val publication: ObjectProvider<PayrollRunPublicationPort>,
    private val finance: ObjectProvider<PayrollFinancePaymentResultsPort>,
    private val clock: Clock,
) {
    fun own(
        actorIdentity: UUID,
        organizationId: UUID,
        correlationId: String,
    ): OwnPayrollPayslipsDto {
        // Authoritative People identity/person/employee mapping; never accept
        // employeeId from an API parameter or a general People directory.
        val at = clock.instant()
        if (!roleAuthority.isOrganizationMemberCurrent(actorIdentity, organizationId, at)) {
            Payroll9aPolicy.fail("PAYROLL_SELF_ACCESS_DENIED", HttpStatus.FORBIDDEN)
        }
        val employee = people.loadOwnEmployee(actorIdentity, organizationId, at)
            ?: Payroll9aPolicy.fail("PAYROLL_SELF_ACCESS_DENIED", HttpStatus.FORBIDDEN)
        val publisher = publication.ifAvailable ?: return OwnPayrollPayslipsDto(emptyList(), correlationId)
        val financePort = finance.ifAvailable
        val rows = jdbc.query(
            """
            SELECT rv.id AS run_id, l.employee_id, p.start_date, p.end_date,
                   rv.generation, l.currency, l.gross, l.deductions,
                   l.reimbursements, l.net, rv.source_fingerprint
            FROM payroll_run_employee_line l
            JOIN payroll_run_version rv ON rv.id = l.run_version_id
            JOIN payroll_period p ON p.id = rv.payroll_period_id
            JOIN payroll_run_release published
              ON published.run_version_id = rv.id
             AND published.organization_id = rv.organization_id
            WHERE l.employee_id = ? AND rv.organization_id = ?
              AND rv.generation = (
                  SELECT MAX(latest.generation)
                  FROM payroll_run_version latest
                  JOIN payroll_run_release completed
                    ON completed.run_version_id = latest.id
                  WHERE latest.payroll_period_id = rv.payroll_period_id
              )
            ORDER BY p.end_date DESC, rv.generation DESC
            LIMIT 24
            """.trimIndent(),
            { rs, _ ->
                OwnPayrollLine(
                    runId = rs.getObject("run_id", UUID::class.java),
                    employeeId = rs.getObject("employee_id", UUID::class.java),
                    start = rs.getObject("start_date", LocalDate::class.java),
                    end = rs.getObject("end_date", LocalDate::class.java),
                    generation = rs.getInt("generation"),
                    currency = rs.getString("currency"),
                    gross = rs.getBigDecimal("gross"),
                    deductions = rs.getBigDecimal("deductions"),
                    reimbursements = rs.getBigDecimal("reimbursements"),
                    net = rs.getBigDecimal("net"),
                    fingerprint = rs.getString("source_fingerprint"),
                )
            }, employee.employeeId, organizationId,
        )
        val approved = rows.mapNotNull { row ->
            if (!publisher.canPublish(organizationId, row.runId, row.fingerprint)) {
                return@mapNotNull null
            }
            val expected = financePort?.expectedPayments(organizationId, row.runId).orEmpty()
            val events = financePort?.paymentEvents(organizationId, row.runId).orEmpty()
            if (expected.isEmpty() && events.isNotEmpty()) {
                Payroll9aPolicy.fail("PAYROLL_FINANCE_UNEXPECTED_EVENT", HttpStatus.CONFLICT)
            }
            // Missing payment preparation does not erase an authorized,
            // published payslip. A non-empty batch must still match every
            // frozen employee exactly before any result is trusted.
            if (expected.isNotEmpty()) Payroll9cExpectedBatchGuard.validateAgainstDatabase(
                jdbc, organizationId, row.runId, expected,
            )
            val outcome = if (row.net.signum() == 0) {
                // No transfer was required for this employee; do not assert
                // SUCCESS or invent a zero-value Finance instruction.
                if (expected.isNotEmpty()) {
                    Payroll9cReconciler.reconcile(
                        organizationId, row.runId, expected, events,
                    )
                }
                "NOT_APPLICABLE"
            } else if (financePort == null) {
                "PAYMENT_STATUS_UNAVAILABLE"
            } else if (expected.isEmpty()) {
                "PAYMENT_NOT_RECORDED"
            } else {
                val reconciliation = Payroll9cReconciler.reconcile(
                    organizationId, row.runId, expected, events,
                )
                val result = reconciliation.employees.singleOrNull {
                    it.employeeId == employee.employeeId
                } ?: return@mapNotNull null
                if (events.none { it.employeeId == employee.employeeId }) "PAYMENT_NOT_RECORDED" else result.result.name
            }
            PrivatePayrollPayslipDto(
                runVersionId = row.runId.toString(),
                employeeId = row.employeeId.toString(),
                periodStart = row.start.toString(),
                periodEnd = row.end.toString(),
                generation = row.generation,
                currency = row.currency,
                gross = row.gross.toPlainString(),
                deductions = row.deductions.toPlainString(),
                reimbursements = row.reimbursements.toPlainString(),
                net = row.net.toPlainString(),
                paymentStatus = outcome,
                paymentAvailability = when (outcome) {
                    "PAYMENT_STATUS_UNAVAILABLE" -> "UNAVAILABLE"
                    "PAYMENT_NOT_RECORDED" -> "NOT_RECORDED"
                    "NOT_APPLICABLE" -> "NOT_APPLICABLE"
                    else -> "RECORDED"
                },
            )
        }
        return OwnPayrollPayslipsDto(approved, correlationId)
    }
}
