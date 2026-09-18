package com.hiltech.shared.core.sync

import androidx.room3.withWriteTransaction
import com.hiltech.shared.core.local.ConflictRecordEntity
import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.local.PendingCommandEntity
import com.hiltech.shared.core.local.PendingCommandStates
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

sealed interface CommandTransportResult {
    data class Applied(
        val currentVersion: Long?,
        val correlationId: String?,
        val duplicateReplay: Boolean = false,
    ) : CommandTransportResult

    data class Retryable(
        val code: String,
        val correlationId: String? = null,
        val currentVersion: Long? = null,
        val retryAfterMs: Long? = null,
    ) : CommandTransportResult

    data class Conflict(
        val conflictType: String,
        val currentVersion: Long?,
        val safeServerStateJson: String,
        val allowedRecoveryActionsJson: String,
        val correlationId: String? = null,
    ) : CommandTransportResult

    data class Terminal(
        val code: String,
        val correlationId: String? = null,
        val currentVersion: Long? = null,
    ) : CommandTransportResult
}

fun interface PendingCommandTransport {
    suspend fun execute(command: PendingCommandEntity): CommandTransportResult
}

fun interface SyncClock {
    fun nowEpochMs(): Long
}

fun interface RetryJitterSource {
    fun fraction(): Double
}

object SystemSyncClock : SyncClock {
    override fun nowEpochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}

object RandomRetryJitterSource : RetryJitterSource {
    override fun fraction(): Double = Random.nextDouble(from = -0.20, until = 0.20)
}

class FirstSliceRetryBackoffPolicy(
    private val jitterSource: RetryJitterSource = RandomRetryJitterSource,
) {
    private val baseDelaysMs = longArrayOf(
        30_000L,
        60_000L,
        120_000L,
        240_000L,
        480_000L,
        900_000L,
    )

    fun delayMs(nextRetryCount: Int): Long {
        require(nextRetryCount >= 1) { "nextRetryCount must be >= 1" }

        val index = (nextRetryCount - 1).coerceAtMost(baseDelaysMs.lastIndex)
        val fraction = jitterSource.fraction().coerceIn(-0.20, 0.20)
        return (baseDelaysMs[index] * (1.0 + fraction)).toLong().coerceAtLeast(1L)
    }
}

data class ReplaySummary(
    val considered: Int,
    val attempted: Int,
    val applied: Int,
    val retryable: Int,
    val conflicts: Int,
    val terminal: Int,
    val dependencyBlocked: Int,
)

class PendingCommandReplayEngine(
    private val database: HiltechLocalDatabase,
    private val transport: PendingCommandTransport,
    private val clock: SyncClock = SystemSyncClock,
    private val backoffPolicy: FirstSliceRetryBackoffPolicy = FirstSliceRetryBackoffPolicy(),
) {
    suspend fun replayOnce(): ReplaySummary {
        val now = clock.nowEpochMs()
        val candidates = database.pendingCommandDao().listSyncable(now)

        var attempted = 0
        var applied = 0
        var retryable = 0
        var conflicts = 0
        var terminal = 0
        var dependencyBlocked = 0

        for (command in candidates) {
            if (!dependenciesApplied(command.operationId)) {
                dependencyBlocked += 1
                continue
            }

            attempted += 1

            val result = try {
                transport.execute(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                CommandTransportResult.Retryable(code = "NETWORK_EXCEPTION")
            }

            val appliedAt = clock.nowEpochMs()

            database.withWriteTransaction {
                when (result) {
                    is CommandTransportResult.Applied -> {
                        database.pendingCommandDao().updateSyncState(
                            operationId = command.operationId,
                            state = PendingCommandStates.APPLIED,
                            retryCount = command.retryCount,
                            nextRetryAtEpochMs = null,
                            lastAttemptAtEpochMs = appliedAt,
                            lastResultCode = if (result.duplicateReplay) "DUPLICATE_REPLAY" else "ACCEPTED",
                            lastServerVersion = result.currentVersion,
                            lastCorrelationId = result.correlationId,
                            updatedAtEpochMs = appliedAt,
                        )
                        applied += 1
                    }

                    is CommandTransportResult.Retryable -> {
                        val nextRetryCount = command.retryCount + 1
                        val retryDelay = result.retryAfterMs
                            ?: backoffPolicy.delayMs(nextRetryCount)

                        database.pendingCommandDao().updateSyncState(
                            operationId = command.operationId,
                            state = PendingCommandStates.RETRYABLE,
                            retryCount = nextRetryCount,
                            nextRetryAtEpochMs = appliedAt + retryDelay,
                            lastAttemptAtEpochMs = appliedAt,
                            lastResultCode = result.code,
                            lastServerVersion = result.currentVersion,
                            lastCorrelationId = result.correlationId,
                            updatedAtEpochMs = appliedAt,
                        )
                        retryable += 1
                    }

                    is CommandTransportResult.Conflict -> {
                        database.pendingCommandDao().updateSyncState(
                            operationId = command.operationId,
                            state = PendingCommandStates.CONFLICT,
                            retryCount = command.retryCount,
                            nextRetryAtEpochMs = null,
                            lastAttemptAtEpochMs = appliedAt,
                            lastResultCode = "VERSION_CONFLICT",
                            lastServerVersion = result.currentVersion,
                            lastCorrelationId = result.correlationId,
                            updatedAtEpochMs = appliedAt,
                        )

                        database.conflictDao().upsert(
                            ConflictRecordEntity(
                                id = command.operationId,
                                operationId = command.operationId,
                                commandType = command.commandType,
                                targetType = command.targetType,
                                targetId = command.targetId,
                                conflictType = result.conflictType,
                                baseVersion = command.baseVersion,
                                currentServerVersion = result.currentVersion,
                                safeServerStateJson = result.safeServerStateJson,
                                allowedRecoveryActionsJson = result.allowedRecoveryActionsJson,
                                detectedAtEpochMs = appliedAt,
                                resolvedAtEpochMs = null,
                                resolutionType = null,
                                resolutionOperationId = null,
                            ),
                        )

                        database.pendingCommandDependencyDao().blockDirectDependents(
                            operationId = command.operationId,
                            resultCode = "BLOCKED_BY_CONFLICT",
                            updatedAtEpochMs = appliedAt,
                        )
                        conflicts += 1
                    }

                    is CommandTransportResult.Terminal -> {
                        database.pendingCommandDao().updateSyncState(
                            operationId = command.operationId,
                            state = PendingCommandStates.FAILED_TERMINAL,
                            retryCount = command.retryCount,
                            nextRetryAtEpochMs = null,
                            lastAttemptAtEpochMs = appliedAt,
                            lastResultCode = result.code,
                            lastServerVersion = result.currentVersion,
                            lastCorrelationId = result.correlationId,
                            updatedAtEpochMs = appliedAt,
                        )
                        terminal += 1
                    }
                }
            }
        }

        return ReplaySummary(
            considered = candidates.size,
            attempted = attempted,
            applied = applied,
            retryable = retryable,
            conflicts = conflicts,
            terminal = terminal,
            dependencyBlocked = dependencyBlocked,
        )
    }

    private suspend fun dependenciesApplied(operationId: String): Boolean {
        val dependencyIds = database.pendingCommandDependencyDao().listDependencies(operationId)
        return dependencyIds.all { dependencyId ->
            database.pendingCommandDao().get(dependencyId)?.state == PendingCommandStates.APPLIED
        }
    }
}
