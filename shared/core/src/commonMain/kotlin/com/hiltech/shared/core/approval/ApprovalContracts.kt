package com.hiltech.shared.core.approval

import kotlinx.serialization.Serializable

@Serializable
data class ApprovalRequestDto(
    val approvalRequestId: String,
    val subjectType: String,
    val subjectId: String,
    val subjectVersion: Long,
    val policyKey: String,
    val policyVersion: Int,
    val state: String,
    val reasonCode: String,
    val safeReasonSummary: String? = null,
    val createdAt: String,
    val authorityKey: String,
)

@Serializable
data class AssignedApprovalPageDto(
    val items: List<ApprovalRequestDto>,
    val nextCursor: String? = null,
    val asOf: String,
    val correlationId: String,
)

@Serializable
data class ApprovalDecisionRequestDto(
    val operationId: String,
    val decision: String,
    val comment: String? = null,
)

@Serializable
data class ApprovalDecisionResponseDto(
    val approvalRequestId: String,
    val state: String,
    val replayed: Boolean,
    val correlationId: String,
)
