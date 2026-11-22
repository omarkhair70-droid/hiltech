package com.hiltech.server.payroll

import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Configured payroll arithmetic. No company-specific value or Egypt statutory
 * tax, insurance or deduction percentage is compiled into this engine.
 *
 * The approved component source adapter and signed policy registry, not an
 * HTTP request body, must supply every input of a real calculation.
 */
enum class PayrollComponentKind {
    EARNING, DEDUCTION, REIMBURSEMENT,
}

enum class PayrollInputApproval {
    APPROVED, PENDING, REJECTED, SUPERSEDED,
}

enum class PayrollInputCoverageDomain {
    COMPENSATION, APPROVED_TIME, FINANCE,
}

/**
 * An approved ZERO is as important as an approved monetary component.
 * Each owning HR/attendance/Finance source must positively attest that it
 * checked the exact employee and period, even when it produces no rows.
 */
data class PayrollInputCoverageReceipt(
    val organizationId: UUID,
    val payrollPeriodId: UUID,
    val employeeId: UUID,
    val domain: PayrollInputCoverageDomain,
    val sourceId: UUID,
    val sourceVersion: Long,
    val approval: PayrollInputApproval,
    val sourceApprovalReference: String,
    val complete: Boolean,
)

/**
 * Required for former/offboarding employees: source-controlled and reviewed
 * People case, not an assumed continuation of normal monthly salary.
 */
data class PayrollVerifiedSeparationEligibility(
    val organizationId: UUID,
    val payrollPeriodId: UUID,
    val employeeId: UUID,
    val offboardingCaseId: UUID,
    val offboardingCaseVersion: Long,
    val lastWorkingDate: java.time.LocalDate,
    val approvedFinalPayScopeRef: String,
    val approvedFinalPayScopeVersion: Long,
    val approval: PayrollInputApproval,
)

data class PayrollValidatedPolicy(
    val organizationId: UUID,
    val policyVersionId: UUID,
    val policyVersion: Long,
    val currency: String,
    val fractionalDigits: Int,
    val roundingMode: RoundingMode,
    val companyAuthorizationReference: String,
    val statutoryPolicyReference: String,
    val statutoryVerified: Boolean,
    val deductionLimitPolicyReference: String?,
    val deductionLimitsVerified: Boolean,
)

data class PayrollApprovedComponent(
    val organizationId: UUID,
    val employeeId: UUID,
    val componentCode: String,
    val kind: PayrollComponentKind,
    val sourceDomain: String,
    val sourceId: UUID,
    val sourceVersion: Long,
    val sourceApprovalReference: String,
    val approval: PayrollInputApproval,
    val currency: String,
    val amount: String,
    val approvedDeductionPlanReference: String? = null,
)

data class PayrollComponentCalculation(
    val code: String,
    val kind: PayrollComponentKind,
    val amount: BigDecimal,
    val sourceDomain: String,
    val sourceId: UUID,
    val sourceVersion: Long,
)

data class PayrollEmployeeCalculation(
    val organizationId: UUID,
    val payrollPeriodId: UUID,
    val employeeId: UUID,
    val policyVersionId: UUID,
    val policyVersion: Long,
    val currency: String,
    val gross: BigDecimal,
    val deductions: BigDecimal,
    val reimbursements: BigDecimal,
    val net: BigDecimal,
    val sourceFingerprint: String,
    val components: List<PayrollComponentCalculation>,
)

/** Fail closed on any untrusted or incomplete component/policy. */
object Payroll9bCalculator {
    fun calculate(
        organizationId: UUID,
        payrollPeriodId: UUID,
        employeeId: UUID,
        policy: PayrollValidatedPolicy,
        components: List<PayrollApprovedComponent>,
        coverageReceipts: List<PayrollInputCoverageReceipt>,
        employeeState: String = "ACTIVE",
        separation: PayrollVerifiedSeparationEligibility? = null,
    ): PayrollEmployeeCalculation {
        if (employeeState !in setOf("ACTIVE", "OFFBOARDING", "FORMER")) {
            Payroll9aPolicy.fail("PAYROLL_EMPLOYEE_NOT_ELIGIBLE", HttpStatus.CONFLICT)
        }
        if (employeeState != "ACTIVE") {
            if (separation == null ||
                separation.organizationId != organizationId ||
                separation.payrollPeriodId != payrollPeriodId ||
                separation.employeeId != employeeId ||
                separation.offboardingCaseVersion < 1 ||
                separation.approvedFinalPayScopeVersion < 1 ||
                separation.approval != PayrollInputApproval.APPROVED ||
                separation.approvedFinalPayScopeRef.isBlank() ||
                separation.approvedFinalPayScopeRef.length > 240
            ) {
                Payroll9aPolicy.fail("PAYROLL_FINAL_ELIGIBILITY_NOT_VERIFIED", HttpStatus.CONFLICT)
            }
        } else if (separation != null) {
            Payroll9aPolicy.fail("PAYROLL_SEPARATION_SCOPE_MISMATCH", HttpStatus.CONFLICT)
        }
        if (coverageReceipts.size != PayrollInputCoverageDomain.entries.size ||
            coverageReceipts.map { it.domain }.toSet() !=
                PayrollInputCoverageDomain.entries.toSet()
        ) {
            Payroll9aPolicy.fail("PAYROLL_SOURCE_COVERAGE_INCOMPLETE", HttpStatus.CONFLICT)
        }
        coverageReceipts.forEach { receipt ->
            if (receipt.organizationId != organizationId ||
                receipt.payrollPeriodId != payrollPeriodId ||
                receipt.employeeId != employeeId ||
                receipt.sourceVersion < 1 || !receipt.complete ||
                receipt.approval != PayrollInputApproval.APPROVED ||
                receipt.sourceApprovalReference.isBlank() ||
                receipt.sourceApprovalReference.length > 240
            ) {
                Payroll9aPolicy.fail("PAYROLL_SOURCE_COVERAGE_NOT_VERIFIED", HttpStatus.CONFLICT)
            }
        }
        if (policy.organizationId != organizationId || policy.policyVersion < 1 ||
            !Regex("^[A-Z]{3}$").matches(policy.currency) ||
            policy.fractionalDigits !in 0..6 ||
            policy.companyAuthorizationReference.isBlank() ||
            policy.statutoryPolicyReference.isBlank() ||
            !policy.statutoryVerified
        ) {
            Payroll9aPolicy.fail("PAYROLL_POLICY_NOT_VERIFIED", HttpStatus.CONFLICT)
        }
        if (components.isEmpty()) {
            Payroll9aPolicy.fail("PAYROLL_APPROVED_INPUTS_MISSING", HttpStatus.CONFLICT)
        }
        val keys = mutableSetOf<String>()
        val calculated = components.map { input ->
            if (input.organizationId != organizationId || input.employeeId != employeeId) {
                Payroll9aPolicy.fail("PAYROLL_INPUT_SCOPE_MISMATCH", HttpStatus.FORBIDDEN)
            }
            if (input.approval != PayrollInputApproval.APPROVED ||
                input.sourceApprovalReference.isBlank() || input.sourceVersion < 1 ||
                !Regex("^[A-Z][A-Z0-9_-]{0,79}$").matches(input.sourceDomain) ||
                !Regex("^[A-Z][A-Z0-9_-]{0,79}$").matches(input.componentCode) ||
                input.sourceApprovalReference.length > 240 ||
                input.currency != policy.currency
            ) {
                Payroll9aPolicy.fail("PAYROLL_APPROVED_INPUT_REQUIRED", HttpStatus.CONFLICT)
            }
            val key = input.sourceDomain + "|" + input.sourceId + "|" + input.componentCode
            if (!keys.add(key)) {
                Payroll9aPolicy.fail("PAYROLL_DUPLICATE_COMPONENT", HttpStatus.CONFLICT)
            }
            if (input.kind == PayrollComponentKind.DEDUCTION &&
                (!policy.deductionLimitsVerified ||
                    policy.deductionLimitPolicyReference.isNullOrBlank() ||
                    input.approvedDeductionPlanReference.isNullOrBlank())
            ) {
                Payroll9aPolicy.fail("PAYROLL_DEDUCTION_POLICY_REQUIRED", HttpStatus.CONFLICT)
            }
            val original = try {
                BigDecimal(input.amount)
            } catch (_: NumberFormatException) {
                Payroll9aPolicy.fail("PAYROLL_INVALID_AMOUNT", HttpStatus.BAD_REQUEST)
            }
            if (original <= BigDecimal.ZERO || original.precision() > 18) {
                Payroll9aPolicy.fail("PAYROLL_INVALID_AMOUNT", HttpStatus.BAD_REQUEST)
            }
            val amount = try {
                original.setScale(policy.fractionalDigits, policy.roundingMode)
            } catch (_: ArithmeticException) {
                Payroll9aPolicy.fail("PAYROLL_UNSUPPORTED_PRECISION", HttpStatus.CONFLICT)
            }
            // PostgreSQL numeric(20,6): no overflow and no positive amount
            // quietly rounded down to zero.
            if (amount <= BigDecimal.ZERO || amount.precision() - amount.scale() > 14) {
                Payroll9aPolicy.fail("PAYROLL_AMOUNT_OUT_OF_RANGE", HttpStatus.CONFLICT)
            }
            PayrollComponentCalculation(
                code = input.componentCode,
                kind = input.kind,
                amount = amount,
                sourceDomain = input.sourceDomain,
                sourceId = input.sourceId,
                sourceVersion = input.sourceVersion,
            )
        }.sortedWith(
            compareBy({ it.kind.name }, { it.sourceDomain }, { it.sourceId.toString() }, { it.code }),
        )
        val zero = BigDecimal.ZERO.setScale(policy.fractionalDigits)
        val gross = calculated.filter { it.kind == PayrollComponentKind.EARNING }
            .fold(zero) { total, line -> total.add(line.amount) }
        val deductions = calculated.filter { it.kind == PayrollComponentKind.DEDUCTION }
            .fold(zero) { total, line -> total.add(line.amount) }
        val reimbursements = calculated.filter { it.kind == PayrollComponentKind.REIMBURSEMENT }
            .fold(zero) { total, line -> total.add(line.amount) }
        val net = gross.add(reimbursements).subtract(deductions)
        if (listOf(gross, deductions, reimbursements, net).any {
            it.precision() - it.scale() > 14
        }) {
            Payroll9aPolicy.fail("PAYROLL_TOTAL_OUT_OF_RANGE", HttpStatus.CONFLICT)
        }
        if (gross <= zero || net < zero) {
            Payroll9aPolicy.fail("PAYROLL_INVALID_NET", HttpStatus.CONFLICT)
        }
        // All source versions and original approved amounts are bound to this
        // fingerprint; never reuse an approval after an input changes.
        val inputsByKey = components.sortedWith(
            compareBy({ it.kind.name }, { it.sourceDomain }, { it.sourceId.toString() }, { it.componentCode }),
        )
        val canonical = buildString {
            append(organizationId).append('|').append(payrollPeriodId)
            append('|').append(employeeId).append('|').append(employeeState)
            separation?.let {
                append("\nSEPARATION|").append(it.offboardingCaseId)
                append('|').append(it.offboardingCaseVersion)
                append('|').append(it.lastWorkingDate)
                append('|').append(it.approvedFinalPayScopeRef)
                append('|').append(it.approvedFinalPayScopeVersion)
                append('|').append(it.approval)
            }
            coverageReceipts.sortedBy { it.domain.name }.forEach {
                append("\nCOVERAGE|").append(it.domain)
                append('|').append(it.sourceId).append('|').append(it.sourceVersion)
                append('|').append(it.approval).append('|').append(it.sourceApprovalReference)
                append('|').append(it.complete)
            }
            append('|').append(policy.policyVersionId).append('|').append(policy.policyVersion)
            append('|').append(policy.currency).append('|').append(policy.fractionalDigits)
            append('|').append(policy.roundingMode.name)
            append('|').append(policy.companyAuthorizationReference)
            append('|').append(policy.statutoryPolicyReference)
            append('|').append(policy.deductionLimitPolicyReference ?: "")
            inputsByKey.forEach {
                append('\n').append(it.kind).append('|').append(it.sourceDomain)
                append('|').append(it.sourceId).append('|').append(it.sourceVersion)
                append('|').append(it.componentCode).append('|').append(it.amount)
                append('|').append(it.sourceApprovalReference)
                append('|').append(it.approvedDeductionPlanReference ?: "")
            }
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return PayrollEmployeeCalculation(
            organizationId = organizationId,
            payrollPeriodId = payrollPeriodId,
            employeeId = employeeId,
            policyVersionId = policy.policyVersionId,
            policyVersion = policy.policyVersion,
            currency = policy.currency,
            gross = gross,
            deductions = deductions,
            reimbursements = reimbursements,
            net = net,
            sourceFingerprint = fingerprint,
            components = calculated,
        )
    }
}

/**
 * Finance/attendance/HR own the source events and balances. No SQL table reads
 * another lane's provisional schema and no caller-supplied approved=true is trusted.
 */
data class PayrollVerifiedEmployeeInputs(
    val coverageReceipts: List<PayrollInputCoverageReceipt>,
    val components: List<PayrollApprovedComponent>,
    val separation: PayrollVerifiedSeparationEligibility? = null,
)

interface PayrollApprovedInputSourcePort {
    fun policyFor(organizationId: UUID, payrollPeriodId: UUID): PayrollValidatedPolicy?
    fun verifiedFor(
        organizationId: UUID,
        payrollPeriodId: UUID,
        employeeId: UUID,
    ): PayrollVerifiedEmployeeInputs
}
