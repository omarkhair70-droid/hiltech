package com.hiltech.server.payroll

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

class Payroll9b9cDomainTest {
    private val org = UUID.randomUUID()
    private val employee = UUID.randomUUID()
    private val policy = PayrollValidatedPolicy(
        organizationId = org,
        policyVersionId = UUID.randomUUID(),
        policyVersion = 1,
        currency = "EGP",
        fractionalDigits = 2, // synthetic fixture, not a HILTECH payroll default
        roundingMode = RoundingMode.HALF_EVEN,
        companyAuthorizationReference = "synthetic-finance-approval",
        statutoryPolicyReference = "synthetic-statutory-review",
        statutoryVerified = true,
        deductionLimitPolicyReference = "synthetic-deduction-policy",
        deductionLimitsVerified = true,
    )
    private fun component(
        kind: PayrollComponentKind,
        amount: String,
        sourceId: UUID = UUID.randomUUID(),
        employeeId: UUID = employee,
        currency: String = "EGP",
        status: PayrollInputApproval = PayrollInputApproval.APPROVED,
        version: Long = 1,
    ) = PayrollApprovedComponent(
        organizationId = org,
        employeeId = employeeId,
        componentCode = kind.name,
        kind = kind,
        sourceDomain = "SYNTHETIC_PAYROLL_TEST",
        sourceId = sourceId,
        sourceVersion = version,
        sourceApprovalReference = "synthetic-component-review",
        approval = status,
        currency = currency,
        amount = amount,
        approvedDeductionPlanReference =
            if (kind == PayrollComponentKind.DEDUCTION) "synthetic-plan-ref" else null,
    )

    @Test
    fun approvedSourcesProduceDeterministicTotalsAndVersionBoundFingerprint() {
        val wage = component(PayrollComponentKind.EARNING, "100.005")
        val reimbursement = component(PayrollComponentKind.REIMBURSEMENT, "12.995")
        val deduction = component(PayrollComponentKind.DEDUCTION, "10.005")
        val lines = listOf(wage, reimbursement, deduction)
        val first = Payroll9bCalculator.calculate(org, employee, policy, lines)
        val reversed = Payroll9bCalculator.calculate(org, employee, policy, lines.reversed())
        assertEquals("100.00", first.gross.toPlainString())
        assertEquals("13.00", first.reimbursements.toPlainString())
        assertEquals("10.00", first.deductions.toPlainString())
        assertEquals("103.00", first.net.toPlainString())
        assertEquals(first, reversed)
        val change = Payroll9bCalculator.calculate(
            org, employee, policy, listOf(wage.copy(sourceVersion = 2), reimbursement, deduction),
        )
        assertNotEquals(first.sourceFingerprint, change.sourceFingerprint)
    }

    @Test
    fun missingLegalPolicyUnapprovedSourcesWrongEmployeeAndUnverifiedDeductionFailClosed() {
        val earning = component(PayrollComponentKind.EARNING, "100")
        assertThrows<ProductApiException> {
            Payroll9bCalculator.calculate(org, employee, policy.copy(statutoryVerified = false), listOf(earning))
        }
        assertThrows<ProductApiException> {
            Payroll9bCalculator.calculate(org, employee, policy, emptyList())
        }
        assertThrows<ProductApiException> {
            Payroll9bCalculator.calculate(org, employee, policy, listOf(earning.copy(approval = PayrollInputApproval.PENDING)))
        }
        assertThrows<ProductApiException> {
            Payroll9bCalculator.calculate(org, employee, policy, listOf(earning.copy(employeeId = UUID.randomUUID())))
        }
        assertThrows<ProductApiException> {
            Payroll9bCalculator.calculate(org, employee, policy, listOf(earning.copy(currency = "USD")))
        }
        assertThrows<ProductApiException> {
            Payroll9bCalculator.calculate(org, employee,
                policy.copy(deductionLimitsVerified = false),
                listOf(earning, component(PayrollComponentKind.DEDUCTION, "1")))
        }
        assertThrows<ProductApiException> {
            Payroll9bCalculator.calculate(org, employee, policy,
                listOf(earning, component(PayrollComponentKind.DEDUCTION, "201")))
        }
        assertThrows<ProductApiException> {
            Payroll9bCalculator.calculate(org, employee, policy, listOf(earning.copy(amount = "not-money")))
        }
    }

    @Test
    fun duplicateComponentIsRejectedAndZeroIsNotInvented() {
        val earning = component(PayrollComponentKind.EARNING, "100")
        assertThrows<ProductApiException> {
            Payroll9bCalculator.calculate(org, employee, policy, listOf(earning, earning))
        }
        assertThrows<ProductApiException> {
            Payroll9bCalculator.calculate(org, employee, policy, listOf(earning.copy(amount = "0")))
        }
    }

    private fun expected(
        employeeId: UUID = employee,
        instructionId: UUID = UUID.randomUUID(),
        version: Long = 1,
    ): PayrollExpectedPayment = PayrollExpectedPayment(
        organizationId = org,
        runVersionId = runId,
        employeeId = employeeId,
        financeInstructionId = instructionId,
        financeInstructionVersion = version,
        amount = BigDecimal("100"),
        currency = "EGP",
    )
    private val runId = UUID.randomUUID()
    private fun result(
        instruction: PayrollExpectedPayment,
        outcome: PayrollFinanceOutcome,
        version: Long = 1,
    ) = PayrollFinanceResult(
        organizationId = org,
        runVersionId = runId,
        employeeId = instruction.employeeId,
        financeInstructionId = instruction.financeInstructionId,
        financeInstructionVersion = instruction.financeInstructionVersion,
        outcomeEventId = UUID.randomUUID(),
        outcome = outcome,
        financeSourceVersion = version,
        financeEvidenceReference = "synthetic-bank-result-reference",
        observedAt = Instant.parse("2026-11-23T09:00:00Z"),
    )

    @Test
    fun partialAndUnknownFinanceTransfersNeverMarkWholePayrollPaid() {
        val first = expected()
        val second = expected(UUID.randomUUID())
        val a = result(first, PayrollFinanceOutcome.SUCCESS)
        val partial = Payroll9cReconciler.reconcile(org, runId, listOf(first, second),
            listOf(a, result(second, PayrollFinanceOutcome.FAILED)))
        assertEquals(PayrollSettlementStatus.PARTIALLY_PAID, partial.status)
        assertEquals(1, partial.unresolvedCount)
        val unknown = Payroll9cReconciler.reconcile(org, runId, listOf(first, second), listOf(a))
        assertEquals(PayrollSettlementStatus.REQUIRES_REVIEW, unknown.status)
        assertEquals(1, unknown.unresolvedCount)
        val success = Payroll9cReconciler.reconcile(org, runId, listOf(first, second),
            listOf(a, result(second, PayrollFinanceOutcome.SUCCESS)))
        assertEquals(PayrollSettlementStatus.PAID, success.status)
        assertEquals(0, success.unresolvedCount)
    }

    @Test
    fun returnedAfterSuccessRequiresReviewAndIdempotentReplayDoesNotCountTwice() {
        val entry = expected()
        val initial = result(entry, PayrollFinanceOutcome.SUCCESS, version = 1)
        val returned = result(entry, PayrollFinanceOutcome.RETURNED, version = 2)
        val latest = Payroll9cReconciler.reconcile(
            org, runId, listOf(entry), listOf(initial, returned, initial),
        )
        assertEquals(PayrollSettlementStatus.REQUIRES_REVIEW, latest.status)
        assertEquals(PayrollFinanceOutcome.RETURNED, latest.employees.single().result)
        val settled = Payroll9cReconciler.reconcile(org, runId, listOf(entry), listOf(initial, initial))
        assertEquals(PayrollSettlementStatus.PAID, settled.status)
        val forged = initial.copy(outcome = PayrollFinanceOutcome.FAILED)
        assertThrows<ProductApiException> {
            Payroll9cReconciler.reconcile(org, runId, listOf(entry), listOf(initial, forged))
        }
        val sameVersionDifferentEvent = initial.copy(
            outcomeEventId = UUID.randomUUID(),
            outcome = PayrollFinanceOutcome.RETURNED,
        )
        assertThrows<ProductApiException> {
            Payroll9cReconciler.reconcile(
                org, runId, listOf(entry), listOf(initial, sameVersionDifferentEvent),
            )
        }
    }

    @Test
    fun wrongOrganizationEmployeeOrInstructionVersionIsRejected() {
        val entry = expected()
        assertThrows<ProductApiException> {
            Payroll9cReconciler.reconcile(org, runId, listOf(entry),
                listOf(result(entry, PayrollFinanceOutcome.SUCCESS).copy(organizationId = UUID.randomUUID())))
        }
        assertThrows<ProductApiException> {
            Payroll9cReconciler.reconcile(org, runId, listOf(entry),
                listOf(result(entry, PayrollFinanceOutcome.SUCCESS).copy(employeeId = UUID.randomUUID())))
        }
        assertThrows<ProductApiException> {
            Payroll9cReconciler.reconcile(org, runId, listOf(entry),
                listOf(result(entry, PayrollFinanceOutcome.SUCCESS).copy(financeInstructionVersion = 2)))
        }
        assertThrows<ProductApiException> {
            Payroll9cReconciler.reconcile(org, runId, listOf(entry),
                listOf(result(entry, PayrollFinanceOutcome.SUCCESS).copy(financeEvidenceReference = "")))
        }
        assertTrue(Payroll9cReconciler.reconcile(org, runId, listOf(entry), emptyList())
            .employees.all { it.result == PayrollFinanceOutcome.UNKNOWN })
    }
}
