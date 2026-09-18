package com.hiltech.shared.core.network

import kotlinx.serialization.Serializable

@Serializable
data class TargetRef(
    val type: String,
    val id: String,
)

@Serializable
data class ErrorEnvelope(
    val code: String,
    val message: String,
    val messageKey: String? = null,
    val correlationId: String,
    val target: TargetRef? = null,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class CommandMetadata(
    val operationId: String,
    val baseVersion: Long? = null,
    val clientOccurredAt: String,
)

@Serializable
data class CursorPage<T>(
    val items: List<T>,
    val nextCursor: String? = null,
    val asOf: String,
)
