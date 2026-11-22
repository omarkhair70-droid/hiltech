package com.hiltech.server.payroll

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Contract boundary between HR/payroll compensation, approved workforce time
 * and Finance advance/deduction/reimbursement sources. Source owners keep
 * their own records; Payroll consumes proof + approved lines, never balances.
 */
data class PayrollAttestedSourceBundle(
    val receipt: PayrollInputCoverageReceipt,
    val components: List<PayrollApprovedComponent>,
)

interface PayrollValidatedStatutoryPolicySourcePort {
    fun approvedPolicy(organizationId: UUID, payrollPeriodId: UUID): PayrollValidatedPolicy?
}

interface PayrollApprovedCompensationSourcePort {
    fun approvedCompensation(
        organizationId: UUID,
        payrollPeriodId: UUID,
        employeeId: UUID,
    ): PayrollAttestedSourceBundle?
}

interface PayrollApprovedAttendanceSourcePort {
    fun approvedTimeAndAllowances(
        organizationId: UUID,
        payrollPeriodId: UUID,
        employeeId: UUID,
    ): PayrollAttestedSourceBundle?
}

interface PayrollApprovedFinanceInputSourcePort {
    fun approvedAdvancesDeductionsAndReimbursements(
        organizationId: UUID,
        payrollPeriodId: UUID,
        employeeId: UUID,
    ): PayrollAttestedSourceBundle?
}

/**
 * OFF until separate lane implementations and approved policy exist.
 * Each source bundle is one canonical owner read, including an explicit
 * signed NO-INPUT attestation where the true amount is zero.
 */
@Component
@ConditionalOnProperty(
    prefix = "hiltech.payroll",
    name = ["source-assembly-enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class PayrollVerifiedInputAssembler(
    private val accounting: ObjectProvider<PayrollValidatedStatutoryPolicySourcePort>,
    private val compensation: ObjectProvider<PayrollApprovedCompensationSourcePort>,
    private val attendance: ObjectProvider<PayrollApprovedAttendanceSourcePort>,
    private val finance: ObjectProvider<PayrollApprovedFinanceInputSourcePort>,
) : PayrollApprovedInputSourcePort {
    override fun policyFor(
        organizationId: UUID,
        payrollPeriodId: UUID,
    ): PayrollValidatedPolicy? {
        if (compensation.ifAvailable == null || attendance.ifAvailable == null ||
            finance.ifAvailable == null
        ) return null
        val policy = accounting.ifAvailable?.approvedPolicy(organizationId, payrollPeriodId)
            ?: return null
        if (policy.organizationId != organizationId ||
            policy.companyAuthorizationReference.isBlank() ||
            policy.statutoryPolicyReference.isBlank() ||
            !policy.statutoryVerified ||
            policy.policyVersion < 1
        ) return null
        return policy
    }

    override fun verifiedFor(
        organizationId: UUID,
        payrollPeriodId: UUID,
        employeeId: UUID,
    ): PayrollVerifiedEmployeeInputs {
        val hr = compensation.ifAvailable?.approvedCompensation(
            organizationId, payrollPeriodId, employeeId,
        ) ?: Payroll9aPolicy.fail("PAYROLL_COMPENSATION_SOURCE_MISSING", HttpStatus.CONFLICT)
        val time = attendance.ifAvailable?.approvedTimeAndAllowances(
            organizationId, payrollPeriodId, employeeId,
        ) ?: Payroll9aPolicy.fail("PAYROLL_ATTENDANCE_SOURCE_MISSING", HttpStatus.CONFLICT)
        val money = finance.ifAvailable?.approvedAdvancesDeductionsAndReimbursements(
            organizationId, payrollPeriodId, employeeId,
        ) ?: Payroll9aPolicy.fail("PAYROLL_FINANCE_SOURCE_MISSING", HttpStatus.CONFLICT)

        val expected = listOf(
            PayrollInputCoverageDomain.COMPENSATION to hr,
            PayrollInputCoverageDomain.APPROVED_TIME to time,
            PayrollInputCoverageDomain.FINANCE to money,
        )
        expected.forEach { (domain, owner) ->
            val receipt = owner.receipt
            if (receipt.domain != domain ||
                receipt.organizationId != organizationId ||
                receipt.payrollPeriodId != payrollPeriodId ||
                receipt.employeeId != employeeId ||
                receipt.approval != PayrollInputApproval.APPROVED ||
                !receipt.complete || receipt.sourceVersion < 1 ||
                receipt.sourceApprovalReference.isBlank()
            ) Payroll9aPolicy.fail("PAYROLL_SOURCE_ATTESTATION_INVALID", HttpStatus.CONFLICT)
            owner.components.forEach { component ->
                if (component.organizationId != organizationId ||
                    component.employeeId != employeeId ||
                    component.approval != PayrollInputApproval.APPROVED
                ) Payroll9aPolicy.fail("PAYROLL_COMPONENT_SOURCE_MISMATCH", HttpStatus.CONFLICT)
                if (domain == PayrollInputCoverageDomain.FINANCE &&
                    component.kind == PayrollComponentKind.EARNING
                ) Payroll9aPolicy.fail("PAYROLL_FINANCE_EARNING_OWNERSHIP", HttpStatus.CONFLICT)
                if (domain != PayrollInputCoverageDomain.FINANCE &&
                    component.kind == PayrollComponentKind.DEDUCTION
                ) Payroll9aPolicy.fail("PAYROLL_DEDUCTION_REQUIRES_FINANCE", HttpStatus.CONFLICT)
                if (domain != PayrollInputCoverageDomain.FINANCE &&
                    component.kind == PayrollComponentKind.REIMBURSEMENT
                ) Payroll9aPolicy.fail("PAYROLL_REIMBURSEMENT_REQUIRES_FINANCE", HttpStatus.CONFLICT)
            }
        }
        return PayrollVerifiedEmployeeInputs(
            coverageReceipts = expected.map { it.second.receipt },
            components = expected.flatMap { it.second.components },
        )
    }
}
