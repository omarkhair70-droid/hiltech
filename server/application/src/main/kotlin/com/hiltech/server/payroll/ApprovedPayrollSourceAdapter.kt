package com.hiltech.server.payroll

import com.hiltech.server.approval.ApprovalSubjectRef
import org.springframework.stereotype.Component
import java.math.RoundingMode
import java.util.UUID

interface PayrollSourceCoverageReadPort {
    fun approvedDomains(organizationId: UUID, periodId: UUID, employeeId: UUID): Set<String>
}

/**
 * Production bridge for company-recorded, independently reviewed inputs.
 * Records preserve the exact employee/period/source/revision; absence never
 * becomes a zero receipt. Existing native operational feeds can coexist by
 * preparing these records with their own references, without changing the calculator.
 */
@Component
class ApprovedPayrollSourceAdapter(
    private val records: PayrollSourceRepository,
    private val guard: PayrollSourceApprovalVersionSource,
) : PayrollSourceCoverageReadPort, PayrollValidatedStatutoryPolicySourcePort, PayrollApprovedCompensationSourcePort,
    PayrollApprovedAttendanceSourcePort, PayrollApprovedFinanceInputSourcePort {

    override fun approvedDomains(organizationId: UUID, periodId: UUID, employeeId: UUID): Set<String> = buildSet {
        if (approvedPolicy(organizationId, periodId) != null && approvedCompensation(organizationId, periodId, employeeId) != null) add("COMPENSATION")
        if (approvedTimeAndAllowances(organizationId, periodId, employeeId) != null) add("APPROVED_TIME")
        if (approvedAdvancesDeductionsAndReimbursements(organizationId, periodId, employeeId) != null) add("FINANCE")
    }

    override fun approvedPolicy(organizationId: UUID, payrollPeriodId: UUID): PayrollValidatedPolicy? {
        val source = current(organizationId, payrollPeriodId, "POLICY", null) ?: return null
        val row = source.record
        return PayrollValidatedPolicy(organizationId, UUID.fromString(row.recordId), row.revision, row.currency,
            requireNotNull(row.fractionalDigits), RoundingMode.valueOf(requireNotNull(row.roundingMode)),
            requireNotNull(row.companyAuthorizationReference), requireNotNull(row.statutoryPolicyReference),
            statutoryVerified = true, deductionLimitPolicyReference = row.deductionPolicyReference,
            deductionLimitsVerified = row.deductionPolicyReference != null)
    }

    override fun approvedCompensation(organizationId: UUID, payrollPeriodId: UUID, employeeId: UUID): PayrollAttestedSourceBundle? =
        bundle(organizationId, payrollPeriodId, employeeId, PayrollInputCoverageDomain.COMPENSATION)

    override fun approvedTimeAndAllowances(organizationId: UUID, payrollPeriodId: UUID, employeeId: UUID): PayrollAttestedSourceBundle? =
        bundle(organizationId, payrollPeriodId, employeeId, PayrollInputCoverageDomain.APPROVED_TIME)

    override fun approvedAdvancesDeductionsAndReimbursements(organizationId: UUID, payrollPeriodId: UUID, employeeId: UUID): PayrollAttestedSourceBundle? =
        bundle(organizationId, payrollPeriodId, employeeId, PayrollInputCoverageDomain.FINANCE)

    private fun bundle(organizationId: UUID, periodId: UUID, employeeId: UUID, domain: PayrollInputCoverageDomain): PayrollAttestedSourceBundle? {
        val source = current(organizationId, periodId, domain.name, employeeId) ?: return null
        val row = source.record
        val sourceId = UUID.fromString(row.recordId)
        val approval = "APPROVAL:${requireNotNull(row.approvalRequestId)}"
        return PayrollAttestedSourceBundle(
            receipt = PayrollInputCoverageReceipt(organizationId, periodId, employeeId, domain, sourceId,
                row.revision, PayrollInputApproval.APPROVED, approval, complete = true),
            components = row.components.map { line ->
                PayrollApprovedComponent(organizationId, employeeId, line.code, PayrollComponentKind.valueOf(line.kind),
                    domain.name, sourceId, row.revision, approval, PayrollInputApproval.APPROVED, row.currency,
                    line.amount, line.deductionPlanReference)
            },
        )
    }

    private fun current(organizationId: UUID, periodId: UUID, domain: String, employeeId: UUID?): StoredPayrollSource? =
        records.approved(organizationId, periodId, domain, employeeId)?.takeIf {
            guard.isCurrent(ApprovalSubjectRef(organizationId, PayrollSourcePolicy.SUBJECT_TYPE, UUID.fromString(it.record.recordId), 1))
        }
}
