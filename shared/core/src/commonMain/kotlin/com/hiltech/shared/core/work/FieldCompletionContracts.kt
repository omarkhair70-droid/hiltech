package com.hiltech.shared.core.work

import kotlinx.serialization.Serializable

@Serializable
data class FieldCompletionSubmissionDto(
    val operationId: String,
    val assignmentId: String,
    val assignmentVersion: Long,
    val baseVersion: Long,
    val bindingRevision: Int,
    val clientOccurredAt: String,
)

@Serializable
data class FieldCompletionSubmissionReceiptDto(
    val workOrderId: String,
    val lifecycleState: String,
    val version: Long,
    val submittedReviewVersion: Long,
    val duplicateReplay: Boolean,
    val correlationId: String,
)
