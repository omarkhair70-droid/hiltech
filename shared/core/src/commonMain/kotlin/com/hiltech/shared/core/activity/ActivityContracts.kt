package com.hiltech.shared.core.activity

import kotlinx.serialization.Serializable

@Serializable
data class ActivityItemDto(
    val activityId: String,
    val activityType: String,
    val contextType: String,
    val contextId: String,
    val sourceType: String,
    val sourceId: String,
    val occurredAt: String,
    val safeSummary: Map<String, String>,
    val classificationCode: String,
    val correlationId: String? = null,
)

@Serializable
data class WorkOrderActivityPageDto(
    val items: List<ActivityItemDto>,
    val nextCursor: String? = null,
    val asOf: String,
    val correlationId: String,
)
