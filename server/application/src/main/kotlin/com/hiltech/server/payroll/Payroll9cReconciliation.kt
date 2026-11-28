package com.hiltech.server.payroll

import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Finance owns every actual transfer, payment result and balance.
 * This read-only projection can neither initiate nor retry a payment.
 */
enum class PayrollFinanceOutcome { PENDING, SUCCESS, FAILED, RETURNED, UNKNOWN }

enum class PayrollSettlementStatus { NOT_PAID, PARTIALLY_PAID, PAID, REQUIRES_REVIEW }

data class PayrollExpectedPayment(
    val organizationId: UUID,
    val runVersionId: UUID,
    val employeeId: UUID,
    val financeInstructionId: UUID,
    val financeInstructionVersion: Long,
    val amount: BigDecimal,
    val currency: String,
)

data class PayrollFinanceResult(
    val organizationId: UUID,
    val runVersionId: UUID,
    val employeeId: UUID,
    val financeInstructionId: UUID,
    val financeInstructionVersion: Long,
    val outcomeEventId: UUID,
    val outcome: PayrollFinanceOutcome,
    val financeSourceVersion: Long,
    val financeEvidenceReference: String,
    val observedAt: Instant,
)

data class PayrollEmployeeSettlement(
    val employeeId: UUID,
    val instructionId: UUID,
    val result: PayrollFinanceOutcome,
    val evidenceRef: String?,
)

data class PayrollRunSettlement(
    val status: PayrollSettlementStatus,
    val employees: List<PayrollEmployeeSettlement>,
    val settledCount: Int,
    val unresolvedCount: Int,
)

object Payroll9cReconciler {
    fun reconcile(
        organizationId: UUID,
        runVersionId: UUID,
        expected: List<PayrollExpectedPayment>,
        observed: List<PayrollFinanceResult>,
    ): PayrollRunSettlement {
        if (expected.isEmpty()) {
            Payroll9aPolicy.fail("PAYROLL_PAYMENT_BATCH_MISSING", HttpStatus.CONFLICT)
        }
        val expectedByInstruction = expected.associateBy { it.financeInstructionId }
        if (expectedByInstruction.size != expected.size ||
            expected.map { it.employeeId }.toSet().size != expected.size
        ) {
            Payroll9aPolicy.fail("PAYROLL_PAYMENT_DUPLICATE_INSTRUCTION", HttpStatus.CONFLICT)
        }
        expected.forEach {
            if (it.organizationId != organizationId || it.runVersionId != runVersionId ||
                it.financeInstructionVersion < 1 || it.amount < BigDecimal.ZERO ||
                !Regex("^[A-Z]{3}$").matches(it.currency)
            ) Payroll9aPolicy.fail("PAYROLL_PAYMENT_SCOPE_MISMATCH", HttpStatus.CONFLICT)
        }
        val observedByEvent = mutableMapOf<UUID, PayrollFinanceResult>()
        for (event in observed) {
            val approvedInstruction = expectedByInstruction[event.financeInstructionId]
                ?: Payroll9aPolicy.fail("PAYROLL_FOREIGN_PAYMENT_RESULT", HttpStatus.FORBIDDEN)
            if (event.organizationId != organizationId ||
                event.runVersionId != runVersionId ||
                event.employeeId != approvedInstruction.employeeId ||
                event.financeInstructionVersion != approvedInstruction.financeInstructionVersion ||
                event.financeEvidenceReference.isBlank() || event.financeSourceVersion < 1
            ) Payroll9aPolicy.fail("PAYROLL_PAYMENT_SOURCE_MISMATCH", HttpStatus.CONFLICT)
            val previous = observedByEvent.putIfAbsent(event.outcomeEventId, event)
            if (previous != null && previous != event) {
                Payroll9aPolicy.fail("PAYROLL_PAYMENT_EVENT_REPLAY_CONFLICT", HttpStatus.CONFLICT)
            }
        }
        // Different external events must never disagree about the same
        // authoritative Finance source version for one instruction.
        val byVersion = observedByEvent.values.groupBy {
            it.financeInstructionId to it.financeSourceVersion
        }
        if (byVersion.values.any { versions ->
            versions.map { it.outcome }.distinct().size > 1 ||
                versions.map { it.financeEvidenceReference }.distinct().size > 1
        }) {
            Payroll9aPolicy.fail("PAYROLL_PAYMENT_VERSION_CONFLICT", HttpStatus.CONFLICT)
        }
        val employees = expected.sortedBy { it.employeeId.toString() }.map { instruction ->
            val latest = observedByEvent.values
                .filter { it.financeInstructionId == instruction.financeInstructionId }
                .maxWithOrNull(compareBy({ it.financeSourceVersion }, { it.observedAt }))
            PayrollEmployeeSettlement(
                employeeId = instruction.employeeId,
                instructionId = instruction.financeInstructionId,
                result = latest?.outcome ?: PayrollFinanceOutcome.UNKNOWN,
                evidenceRef = latest?.financeEvidenceReference,
            )
        }
        val success = employees.count { it.result == PayrollFinanceOutcome.SUCCESS }
        val unresolved = employees.size - success
        val status = when {
            unresolved == 0 -> PayrollSettlementStatus.PAID
            employees.any {
                it.result == PayrollFinanceOutcome.UNKNOWN ||
                    it.result == PayrollFinanceOutcome.RETURNED
            } -> PayrollSettlementStatus.REQUIRES_REVIEW
            success > 0 -> PayrollSettlementStatus.PARTIALLY_PAID
            else -> PayrollSettlementStatus.NOT_PAID
        }
        return PayrollRunSettlement(
            status = status,
            employees = employees,
            settledCount = success,
            unresolvedCount = unresolved,
        )
    }
}

/** Finance provides immutable, source-versioned events, never a mutable PAID flag. */
data class PayrollDifferentialPaymentBasis(val available:Boolean,val lines:List<PayrollFrozenPaymentLine>,
    val recoveryEmployees:Set<UUID> = emptySet())

interface PayrollFinancePaymentResultsPort {
    // Null means ordinary payroll. Unavailable differential evidence never falls back to a full transfer.
    fun differentialPaymentBasis(organizationId:UUID,runVersionId:UUID):PayrollDifferentialPaymentBasis? = null
    fun expectedPayments(
        organizationId: UUID,
        runVersionId: UUID,
    ): List<PayrollExpectedPayment>
    fun paymentEvents(
        organizationId: UUID,
        runVersionId: UUID,
    ): List<PayrollFinanceResult>
}

/**
 * A sensitive employee self-pay view must resolve current identity through
 * People and must never broaden access via the People directory.
 */
data class PrivatePayslipAccess(
    val organizationId: UUID,
    val employeeId: UUID,
    val authorizedIdentityId: UUID,
    val runVersionId: UUID,
    val paymentStatus: PayrollFinanceOutcome,
)
