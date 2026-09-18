package com.hiltech.spike.shared.sync

import com.hiltech.spike.shared.local.PendingCommandEntity

object SyncQueueState {
    const val PENDING = "PENDING"
    const val RETRYABLE = "RETRYABLE"
    const val SYNCING = "SYNCING"
    const val APPLIED = "APPLIED"
    const val CONFLICT = "CONFLICT"
    const val BLOCKED_BY_CONFLICT = "BLOCKED_BY_CONFLICT"
    const val FAILED_TERMINAL = "FAILED_TERMINAL"
}

sealed interface SyncSendResult {
    data class Applied(
        val serverVersion: Long?,
        val resultCode: String = "APPLIED",
    ) : SyncSendResult

    data class DuplicateApplied(
        val serverVersion: Long?,
        val resultCode: String = "DUPLICATE_APPLIED",
    ) : SyncSendResult

    data class Retryable(
        val resultCode: String,
    ) : SyncSendResult

    data class Conflict(
        val serverVersion: Long?,
        val resultCode: String,
    ) : SyncSendResult

    data class TerminalFailure(
        val resultCode: String,
    ) : SyncSendResult
}

fun interface SyncTransport {
    suspend fun send(command: PendingCommandEntity): SyncSendResult
}

data class SyncRunSummary(
    val attempted: Int,
    val applied: Int,
    val duplicateApplied: Int,
    val retryable: Int,
    val conflicts: Int,
    val terminalFailures: Int,
)
