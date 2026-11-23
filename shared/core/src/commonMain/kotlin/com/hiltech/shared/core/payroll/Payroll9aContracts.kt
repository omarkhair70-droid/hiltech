package com.hiltech.shared.core.payroll

import kotlinx.serialization.Serializable

@Serializable
data class CreatePayrollPeriodRequestDto(
    val operationId: String,
    val organizationId: String,
    val periodCode: String,
    val startDate: String,
    val endDate: String,
    val cutoffDate: String,
    val targetPaymentDate: String? = null,
)

@Serializable
data class ReviewPayrollExceptionRequestDto(
    val operationId: String,
    val note: String,
)

@Serializable
data class PayrollPeriodSummaryDto(
    val periodId: String,
    val organizationId: String,
    val periodCode: String,
    val startDate: String,
    val endDate: String,
    val cutoffDate: String,
    val targetPaymentDate: String? = null,
    val state: String,
    val version: Long,
    val snapshotAt: String? = null,
    val employeeCount: Int = 0,
    val exceptionCount: Int = 0,
)

@Serializable
data class PayrollExceptionReviewDto(
    val reviewId: String,
    val reviewedAt: String,
    val note: String,
)

@Serializable
data class PayrollInputExceptionDto(
    val exceptionId: String,
    val code: String,
    val sourceDomain: String,
    val reviews: List<PayrollExceptionReviewDto> = emptyList(),
    val blocksCalculation: Boolean = true,
)

@Serializable
data class PayrollEmployeeInputDto(
    val snapshotId: String,
    val employeeId: String,
    val employeeCode: String,
    val displayName: String,
    val employeeState: String,
    val employeeVersion: Long,
    val employmentId: String,
    val employmentVersion: Long,
    val workforceAssignmentId: String? = null,
    val workforceAssignmentVersion: Long? = null,
    val sourceChanged: Boolean,
    val exceptions: List<PayrollInputExceptionDto> = emptyList(),
)

@Serializable
data class PayrollPeriodDetailDto(
    val period: PayrollPeriodSummaryDto,
    val employees: List<PayrollEmployeeInputDto> = emptyList(),
    val calculationAllowed: Boolean = false,
    val approvalAllowed: Boolean = false,
    val paymentAllowed: Boolean = false,
)

@Serializable
data class PayrollPeriodListDto(
    val items: List<PayrollPeriodSummaryDto>,
    val correlationId: String,
)

@Serializable
data class PayrollPeriodCommandDto(
    val period: PayrollPeriodDetailDto,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class PayrollExceptionReviewCommandDto(
    val exceptionId: String,
    val review: PayrollExceptionReviewDto,
    val replayed: Boolean,
    val correlationId: String,
)
