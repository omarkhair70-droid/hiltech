package com.hiltech.shared.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class TargetRef(
    val type: String,
    val id: String,
)

@Serializable
data class ConflictPayload(
    val conflictType: String,
    val targetType: String? = null,
    val targetId: String? = null,
    val attemptedBaseVersion: Long? = null,
    val currentVersion: Long? = null,
    val safeCurrentState: JsonObject? = null,
    val localWorkSafe: Boolean? = null,
    val allowedRecoveryActions: List<String> = emptyList(),
)

@Serializable
data class ErrorEnvelope(
    val code: String,
    val message: String,
    val correlationId: String,
    val retryable: Boolean = false,
    val details: JsonObject? = null,
    val currentVersion: Long? = null,
    val conflict: ConflictPayload? = null,
    val messageKey: String? = null,
    val target: TargetRef? = null,
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
