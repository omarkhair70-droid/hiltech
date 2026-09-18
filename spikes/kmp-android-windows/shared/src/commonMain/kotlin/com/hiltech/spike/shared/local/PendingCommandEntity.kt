package com.hiltech.spike.shared.local

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "pending_command")
data class PendingCommandEntity(
    @PrimaryKey
    val operationId: String,
    val commandType: String,
    val objectType: String,
    val objectId: String,
    val baseVersion: Long?,
    val payloadJson: String,
    val state: String,
    val attemptCount: Int,
    val createdAtEpochMs: Long,
    val lastAttemptAtEpochMs: Long?,
    val localSequence: Long = createdAtEpochMs,
    val lastResultCode: String? = null,
    val serverVersion: Long? = null,
)
