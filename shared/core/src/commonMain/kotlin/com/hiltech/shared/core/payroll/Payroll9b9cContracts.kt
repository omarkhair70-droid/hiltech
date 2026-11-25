package com.hiltech.shared.core.payroll

import kotlinx.serialization.Serializable

@Serializable
data class CreatePayrollDraftRequestDto(val operationId: String)

@Serializable
data class PayrollRunLineDto(
    val employeeId: String,
    val employeeCode: String,
    val displayName: String,
    val snapshotId: String,
    val currency: String,
    val gross: String,
    val deductions: String,
    val reimbursements: String,
    val net: String,
    val sourceFingerprint: String,
)

@Serializable
data class PayrollRunDto(
    val runVersionId: String,
    val organizationId: String,
    val payrollPeriodId: String,
    val generation: Int,
    val state: String,
    val currency: String,
    val gross: String,
    val deductions: String,
    val reimbursements: String,
    val net: String,
    val sourceFingerprint: String,
    val lines: List<PayrollRunLineDto>,
    val approvalAllowed: Boolean = false,
    val paymentAllowed: Boolean = false,
)

@Serializable
data class PayrollRunDraftCommandDto(
    val run: PayrollRunDto,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class PayrollRunSummaryDto(
    val runVersionId: String,
    val generation: Int,
    val state: String,
    val currency: String,
    val net: String,
    val employeeCount: Int,
    val sourceFingerprint: String,
    val releaseState: String = "NOT_RELEASED",
)

@Serializable
data class PayrollRunHistoryDto(
    val payrollPeriodId: String,
    val items: List<PayrollRunSummaryDto>,
)

@Serializable
data class PayrollEmployeeFinanceStatusDto(
    val employeeId: String,
    val outcome: String,
)

@Serializable
data class PayrollRunFinanceStatusDto(
    val runVersionId: String,
    val status: String,
    val financeConnected: Boolean,
    val publicationApproved: Boolean,
    val employeeCount: Int,
    val settledCount: Int,
    val unresolvedCount: Int,
    val employeeOutcomes: List<PayrollEmployeeFinanceStatusDto>,
)

@Serializable
data class PayrollApprovalRequestDto(
    val operationId: String,
    val reasonCode: String,
)

@Serializable
data class PayrollApprovalResultDto(
    val approvalRequired: Boolean,
    val approvalRequestId: String?,
    val approvalState: String?,
    val policyVersion: Int,
    val replayed: Boolean,
)

@Serializable
data class PayrollReleaseRequestDto(val operationId: String)

@Serializable
data class PayrollReleaseResultDto(
    val releaseId: String,
    val runVersionId: String,
    val status: String,
    val policyKey: String,
    val policyVersion: Int,
    val decisionPath: String,
    val releasedAt: String,
    val replayed: Boolean,
)

@Serializable
data class PayrollFinanceHandoffRequestDto(val operationId: String)

@Serializable
data class PayrollFinanceHandoffResultDto(
    val financeReviewBatchId: String,
    val runVersionId: String,
    val state: String,
    val manifestSha256: String,
    val currency: String,
    val payableTotal: String,
    val employeeCount: Int,
    val transferCandidates: Int,
    val bankExecutionAvailable: Boolean = false,
    val replayed: Boolean,
)

@Serializable
data class PayrollFinalPayReadinessDto(
    val offboardingCaseId: String,
    val employeeId: String,
    val employeeState: String,
    val offboardingState: String,
    val lastWorkingDate: String,
    val payrollClearanceState: String?,
    val financeClearanceState: String?,
    val hrClearanceState: String?,
    val status: String,
    val blockers: List<String>,
    val releasedRunVersionId: String?,
    val currency: String?,
    val frozenNet: String?,
    val paymentStatus: String?,
)

@Serializable
data class PrivatePayslipDto(
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

@Serializable
data class OwnPayslipsDto(
    val items: List<PrivatePayslipDto>,
    val correlationId: String,
)
