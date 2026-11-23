package com.hiltech.server.payroll

data class CreatePayrollPeriodRequest(
    val operationId: String,
    val organizationId: String,
    val periodCode: String,
    val startDate: String,
    val endDate: String,
    val cutoffDate: String,
    val targetPaymentDate: String? = null,
)

data class ReviewPayrollExceptionRequest(
    val operationId: String,
    val note: String,
)

data class PayrollPeriodSummaryDto(
    val periodId: String,
    val organizationId: String,
    val periodCode: String,
    val startDate: String,
    val endDate: String,
    val cutoffDate: String,
    val targetPaymentDate: String?,
    val state: String,
    val version: Long,
    val snapshotAt: String?,
    val employeeCount: Int,
    val exceptionCount: Int,
)

data class PayrollExceptionReviewDto(
    val reviewId: String,
    val reviewedAt: String,
    val note: String,
)

data class PayrollInputExceptionDto(
    val exceptionId: String,
    val code: String,
    val sourceDomain: String,
    val reviews: List<PayrollExceptionReviewDto>,
    // A review is an acknowledgement, never an approval or a resolved deduction.
    val blocksCalculation: Boolean = true,
)

data class PayrollEmployeeInputDto(
    val snapshotId: String,
    val employeeId: String,
    val employeeCode: String,
    val displayName: String,
    val employeeState: String,
    val employeeVersion: Long,
    val employmentId: String,
    val employmentVersion: Long,
    val workforceAssignmentId: String?,
    val workforceAssignmentVersion: Long?,
    val sourceChanged: Boolean,
    val exceptions: List<PayrollInputExceptionDto>,
)

data class PayrollPeriodDetailDto(
    val period: PayrollPeriodSummaryDto,
    val employees: List<PayrollEmployeeInputDto>,
    val calculationAllowed: Boolean = false,
    val approvalAllowed: Boolean = false,
    val paymentAllowed: Boolean = false,
)

data class PayrollPeriodListDto(
    val items: List<PayrollPeriodSummaryDto>,
    val correlationId: String,
)

data class PayrollPeriodCommandDto(
    val period: PayrollPeriodDetailDto,
    val replayed: Boolean,
    val correlationId: String,
)

data class PayrollExceptionReviewCommandDto(
    val exceptionId: String,
    val review: PayrollExceptionReviewDto,
    val replayed: Boolean,
    val correlationId: String,
)
