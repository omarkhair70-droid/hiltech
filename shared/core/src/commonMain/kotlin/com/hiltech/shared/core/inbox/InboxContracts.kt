package com.hiltech.shared.core.inbox

import kotlinx.serialization.Serializable

@Serializable
data class InboxItemDto(
    val inboxItemId: String,
    val sourceType: String,
    val sourceId: String,
    val sourceVersion: Long,
    val actionKey: String,
    val attentionClass: String,
    val state: String,
    val read: Boolean,
    val actionable: Boolean,
    val safeTitleCode: String,
    val safeSummary: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val resolvedAt: String? = null,
)

@Serializable
data class InboxPageDto(
    val items: List<InboxItemDto>,
    val nextCursor: String? = null,
    val asOf: String,
    val correlationId: String,
)

@Serializable
data class InboxReadStateRequestDto(
    val operationId: String,
)

@Serializable
data class InboxReadStateResponseDto(
    val inboxItemId: String,
    val read: Boolean,
    val replayed: Boolean,
    val correlationId: String,
)
