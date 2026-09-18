package com.hiltech.spike.shared.sync

import com.hiltech.spike.shared.local.PendingCommandDao

class OfflineCommandSyncEngine(
    private val dao: PendingCommandDao,
    private val transport: SyncTransport,
) {
    suspend fun syncOnce(nowEpochMs: Long): SyncRunSummary {
        val queued = dao.listSyncable()

        var attempted = 0
        var applied = 0
        var duplicateApplied = 0
        var retryable = 0
        var conflicts = 0
        var terminalFailures = 0

        val conflictedObjects = mutableSetOf<String>()

        for (command in queued) {
            val objectKey = command.objectType + ":" + command.objectId
            if (objectKey in conflictedObjects) {
                continue
            }

            val attemptCount = command.attemptCount + 1
            attempted += 1

            dao.updateSyncResult(
                operationId = command.operationId,
                state = SyncQueueState.SYNCING,
                attemptCount = attemptCount,
                lastAttemptAtEpochMs = nowEpochMs,
                lastResultCode = null,
                serverVersion = command.serverVersion,
            )

            when (val result = transport.send(command)) {
                is SyncSendResult.Applied -> {
                    dao.updateSyncResult(
                        operationId = command.operationId,
                        state = SyncQueueState.APPLIED,
                        attemptCount = attemptCount,
                        lastAttemptAtEpochMs = nowEpochMs,
                        lastResultCode = result.resultCode,
                        serverVersion = result.serverVersion,
                    )
                    applied += 1
                }

                is SyncSendResult.DuplicateApplied -> {
                    dao.updateSyncResult(
                        operationId = command.operationId,
                        state = SyncQueueState.APPLIED,
                        attemptCount = attemptCount,
                        lastAttemptAtEpochMs = nowEpochMs,
                        lastResultCode = result.resultCode,
                        serverVersion = result.serverVersion,
                    )
                    duplicateApplied += 1
                }

                is SyncSendResult.Retryable -> {
                    dao.updateSyncResult(
                        operationId = command.operationId,
                        state = SyncQueueState.RETRYABLE,
                        attemptCount = attemptCount,
                        lastAttemptAtEpochMs = nowEpochMs,
                        lastResultCode = result.resultCode,
                        serverVersion = command.serverVersion,
                    )
                    retryable += 1
                    break
                }

                is SyncSendResult.Conflict -> {
                    dao.updateSyncResult(
                        operationId = command.operationId,
                        state = SyncQueueState.CONFLICT,
                        attemptCount = attemptCount,
                        lastAttemptAtEpochMs = nowEpochMs,
                        lastResultCode = result.resultCode,
                        serverVersion = result.serverVersion,
                    )

                    dao.blockLaterCommandsForObject(
                        objectType = command.objectType,
                        objectId = command.objectId,
                        afterSequence = command.localSequence,
                        resultCode = "BLOCKED_AFTER_" + result.resultCode,
                        serverVersion = result.serverVersion,
                    )

                    conflictedObjects += objectKey
                    conflicts += 1
                }

                is SyncSendResult.TerminalFailure -> {
                    dao.updateSyncResult(
                        operationId = command.operationId,
                        state = SyncQueueState.FAILED_TERMINAL,
                        attemptCount = attemptCount,
                        lastAttemptAtEpochMs = nowEpochMs,
                        lastResultCode = result.resultCode,
                        serverVersion = command.serverVersion,
                    )
                    terminalFailures += 1
                    break
                }
            }
        }

        return SyncRunSummary(
            attempted = attempted,
            applied = applied,
            duplicateApplied = duplicateApplied,
            retryable = retryable,
            conflicts = conflicts,
            terminalFailures = terminalFailures,
        )
    }
}
