package com.hiltech.server.payroll

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.UUID

/** Authoritative frozen employee line used only for Finance matching. */
data class PayrollFrozenPaymentLine(
    val employeeId: UUID,
    val net: BigDecimal,
    val currency: String,
)

/**
 * One Finance instruction must match exactly one frozen employee line, for
 * every employee in the approved payroll run. This verification applies to
 * admin status, employee self-payslips, and offboarding final-pay reads.
 * It is read-only: nothing here initiates or retries a payment.
 */
object Payroll9cExpectedBatchGuard {
    fun validate(
        organizationId: UUID,
        runVersionId: UUID,
        frozenLines: List<PayrollFrozenPaymentLine>,
        expected: List<PayrollExpectedPayment>,
    ) {
        // Zero-net employees must remain in the frozen payroll population,
        // but must never generate a fictitious payment instruction.
        val transferLines = frozenLines.filter { it.net.signum() > 0 }
        val currencies = frozenLines.map { it.currency }.toSet()
        if (frozenLines.isEmpty() || expected.size != transferLines.size ||
            currencies.size != 1 || !Regex("^[A-Z]{3}$").matches(frozenLines.first().currency) ||
            frozenLines.any { it.net.signum() < 0 } ||
            frozenLines.map { it.employeeId }.toSet().size != frozenLines.size ||
            expected.map { it.employeeId }.toSet().size != expected.size ||
            expected.map { it.financeInstructionId }.toSet().size != expected.size
        ) {
            Payroll9aPolicy.fail("PAYROLL_FINANCE_BATCH_INCOMPLETE", HttpStatus.CONFLICT)
        }
        val frozenByEmployee = transferLines.associateBy { it.employeeId }
        if (expected.map { it.employeeId }.toSet() != frozenByEmployee.keys) {
            Payroll9aPolicy.fail("PAYROLL_FINANCE_BATCH_INCOMPLETE", HttpStatus.CONFLICT)
        }
        expected.forEach { instruction ->
            val line = frozenByEmployee[instruction.employeeId]
                ?: Payroll9aPolicy.fail("PAYROLL_FINANCE_BATCH_INCOMPLETE", HttpStatus.CONFLICT)
            if (instruction.organizationId != organizationId ||
                instruction.runVersionId != runVersionId ||
                instruction.financeInstructionVersion < 1 ||
                line.net < BigDecimal.ZERO ||
                instruction.amount.compareTo(line.net) != 0 ||
                instruction.currency != line.currency ||
                !Regex("^[A-Z]{3}$").matches(line.currency)
            ) {
                Payroll9aPolicy.fail("PAYROLL_FINANCE_NET_MISMATCH", HttpStatus.CONFLICT)
            }
        }
    }

    fun validateAgainstDatabase(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        runVersionId: UUID,
        expected: List<PayrollExpectedPayment>,
    ) {
        // No other employee's amounts are exposed through any API response.
        val frozen = jdbc.query(
            """
            SELECT employee_id, amount AS net, currency
            FROM finance_payroll_instruction_entitlement
            WHERE run_version_id = ? AND organization_id = ?
            """.trimIndent(),
            { rs, _ ->
                PayrollFrozenPaymentLine(
                    employeeId = rs.getObject("employee_id", UUID::class.java),
                    net = rs.getBigDecimal("net"),
                    currency = rs.getString("currency"),
                )
            },
            runVersionId, organizationId,
        )
        validate(organizationId, runVersionId, frozen, expected)
    }
}
