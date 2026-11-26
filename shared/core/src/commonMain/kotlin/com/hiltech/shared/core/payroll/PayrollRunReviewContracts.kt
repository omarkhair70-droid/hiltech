package com.hiltech.shared.core.payroll

import kotlinx.serialization.Serializable

@Serializable
data class PayrollRunReviewDto(
    val runVersionId: String,
    val approvalRequestId: String?,
    val approvalState: String,
    val policyVersion: Int?,
    val sourceCurrent: Boolean,
    val canRequestReview: Boolean,
    val canDecide: Boolean,
    val canRelease: Boolean,
)

@Serializable
data class PayrollRunDecisionRequestDto(
    val operationId: String,
    val approvalRequestId: String,
    val decision: String,
    val comment: String? = null,
)

@Serializable
data class PayrollRunDecisionResultDto(
    val runVersionId: String,
    val approvalRequestId: String,
    val approvalState: String,
    val replayed: Boolean,
)
