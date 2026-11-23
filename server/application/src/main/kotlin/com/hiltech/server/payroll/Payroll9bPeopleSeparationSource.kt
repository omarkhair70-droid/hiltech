package com.hiltech.server.payroll

import com.hiltech.server.people.OffboardingCaseState
import com.hiltech.server.people.EmployeeState
import com.hiltech.server.people.OffboardingPersistencePort
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

data class PayrollApprovedFinalPayScope(
    val organizationId: UUID,
    val payrollPeriodId: UUID,
    val employeeId: UUID,
    val offboardingCaseId: UUID,
    val offboardingCaseVersion: Long,
    val approval: PayrollInputApproval,
    val legalAndCompanyScopeReference: String,
    val scopeSourceVersion: Long,
)

/**
 * Owning HR/accountant source authorizes what FINAL pay includes; this port
 * is not a way to inject a client-supplied salary or a made-up severance rate.
 */
interface PayrollApprovedFinalPayScopeSourcePort {
    fun approvedFor(
        organizationId: UUID,
        payrollPeriodId: UUID,
        employeeId: UUID,
        offboardingCaseId: UUID,
        offboardingCaseVersion: Long,
    ): PayrollApprovedFinalPayScope?
}

/**
 * Uses the existing People offboarding case and currently approved external
 * final-pay scope. No new offboarding table, second employee state or
 * local statutory-pay formula is introduced.
 */
@Component
@ConditionalOnProperty(
    prefix = "hiltech.payroll",
    name = ["separation-source-enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class PayrollPeopleSeparationSource(
    private val cases: OffboardingPersistencePort,
    private val scopeProvider: ObjectProvider<PayrollApprovedFinalPayScopeSourcePort>,
    private val jdbc: JdbcTemplate,
) : PayrollVerifiedSeparationEligibilitySourcePort {
    override fun verifiedFor(
        organizationId: UUID,
        payrollPeriodId: UUID,
        employeeId: UUID,
    ): PayrollVerifiedSeparationEligibility? {
        val currentEmployee = cases.employee(employeeId) ?: return null
        // A rehired ACTIVE employee may retain a historical offboarding case.
        // That old case must not poison a normal active payroll run.
        if (currentEmployee.organizationId != organizationId ||
            currentEmployee.state !in setOf(EmployeeState.OFFBOARDING, EmployeeState.FORMER)
        ) return null
        val case = cases.latestForEmployee(employeeId) ?: return null
        if (case.organizationId != organizationId ||
            case.employeeId != employeeId ||
            case.state !in setOf(OffboardingCaseState.OPEN, OffboardingCaseState.COMPLETED)
        ) return null
        val periodEnd = jdbc.query(
            """
            SELECT end_date FROM payroll_period
            WHERE id = ? AND organization_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getObject("end_date", LocalDate::class.java) },
            payrollPeriodId, organizationId,
        ).singleOrNull() ?: return null
        if (case.lastWorkingDate.isAfter(periodEnd)) return null
        val approved = scopeProvider.ifAvailable?.approvedFor(
            organizationId, payrollPeriodId, employeeId, case.caseId, case.version,
        ) ?: return null
        if (approved.organizationId != organizationId ||
            approved.payrollPeriodId != payrollPeriodId ||
            approved.employeeId != employeeId ||
            approved.offboardingCaseId != case.caseId ||
            approved.offboardingCaseVersion != case.version ||
            approved.approval != PayrollInputApproval.APPROVED ||
            approved.legalAndCompanyScopeReference.isBlank() ||
            approved.scopeSourceVersion < 1
        ) return null
        return PayrollVerifiedSeparationEligibility(
            organizationId = organizationId,
            payrollPeriodId = payrollPeriodId,
            employeeId = employeeId,
            offboardingCaseId = case.caseId,
            offboardingCaseVersion = case.version,
            lastWorkingDate = case.lastWorkingDate,
            approvedFinalPayScopeRef = approved.legalAndCompanyScopeReference,
            approvedFinalPayScopeVersion = approved.scopeSourceVersion,
            approval = PayrollInputApproval.APPROVED,
        )
    }
}
