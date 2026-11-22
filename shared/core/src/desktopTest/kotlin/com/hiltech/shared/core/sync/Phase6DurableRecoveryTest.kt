package com.hiltech.shared.core.sync

import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.local.PendingCommandEntity
import com.hiltech.shared.core.local.PendingCommandStates
import com.hiltech.shared.core.local.buildHiltechLocalDatabase
import com.hiltech.shared.core.local.getDesktopDatabaseBuilder
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 6 S06 synthetic recovery proof. The remote is deliberately simulated:
 * these tests prove durable replay/security contracts, NOT a live HILTECH field
 * technician session or a production authentication provider.
 */
class Phase6DurableRecoveryTest {
    private val actor = "11111111-1111-4111-8111-111111111111"
    private val otherActor = "22222222-2222-4222-8222-222222222222"
    private val device = "android-recovery"
    private val otherDevice = "android-other"
    private val work = "33333333-3333-4333-8333-333333333333"
    private val baseNow = 1_700_000_000_000L

    @Test
    fun responseLostAfterServerApplyCanRecoverOriginalOperationAfterProcessRestart() = runBlocking {
        val folder = Files.createTempDirectory("hiltech-s06-replay-restart").toFile()
        val file = folder.resolve("field.db").absolutePath
        val op = "44444444-4444-4444-8444-444444444444"
        var remoteCalls = 0
        try {
            val firstDb = open(file)
            try {
                firstDb.pendingCommandDao().insert(pending(op, 1))
                val first = engine(
                    db = firstDb,
                    now = baseNow,
                    remote = PendingCommandTransport { request ->
                        remoteCalls++
                        assertEquals(op, request.operationId)
                        assertEquals(11L, request.baseVersion)
                        // The simulated server has applied the command, but
                        // the response is lost before the client ACK.
                        throw IOException("response lost after server commit")
                    },
                ).replayOnce()
                assertEquals(1, first.retryable)
                val row = assertNotNull(firstDb.pendingCommandDao().get(op))
                assertEquals(PendingCommandStates.RETRYABLE, row.state)
                assertEquals("NETWORK_EXCEPTION", row.lastResultCode)
                assertEquals(baseNow + 30_000L, row.nextRetryAtEpochMs)
                assertEquals(1, remoteCalls)
            } finally {
                firstDb.close()
            }

            // Simulates native process death, DB reopen, network recovery.
            val recovered = open(file)
            try {
                val restored = assertNotNull(recovered.pendingCommandDao().get(op))
                assertEquals("""{"assignmentVersion":3,"bindingRevision":2}""", restored.payloadJson)
                val outcome = engine(
                    db = recovered,
                    now = baseNow + 30_001L,
                    remote = PendingCommandTransport { request ->
                        remoteCalls++
                        assertEquals(op, request.operationId)
                        assertEquals(restored.payloadJson, request.payloadJson)
                        CommandTransportResult.Applied(
                            currentVersion = 12,
                            correlationId = "corr-duplicate-recovered",
                            duplicateReplay = true,
                        )
                    },
                ).replayOnce()
                assertEquals(1, outcome.applied)
                val row = assertNotNull(recovered.pendingCommandDao().get(op))
                assertEquals(PendingCommandStates.APPLIED, row.state)
                assertEquals("DUPLICATE_REPLAY", row.lastResultCode)
                assertEquals(12L, row.lastServerVersion)
                assertEquals("corr-duplicate-recovered", row.lastCorrelationId)
                assertNull(row.nextRetryAtEpochMs)
                assertEquals(2, remoteCalls)
            } finally {
                recovered.close()
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun rateLimitThenServerOutagePreserveFrozenCommandWithBackoff() = runBlocking {
        withDb("hiltech-s06-throttle") { db ->
            val op = "55555555-5555-4555-8555-555555555555"
            db.pendingCommandDao().insert(pending(op, 1))
            var currentTime = baseNow
            val invocations = mutableListOf<String>()
            val transport = PendingCommandTransport { command ->
                invocations += command.operationId
                when (invocations.size) {
                    1 -> CommandTransportResult.Retryable(
                        code = "RATE_LIMITED",
                        correlationId = "corr-429",
                        retryAfterMs = 90_000L,
                    )
                    2 -> CommandTransportResult.Retryable(
                        code = "SERVER_503",
                        correlationId = "corr-503",
                    )
                    else -> CommandTransportResult.Applied(
                        currentVersion = 12,
                        correlationId = "corr-final",
                    )
                }
            }
            val engine = engine(
                db = db,
                nowProvider = { currentTime },
                remote = transport,
            )
            assertEquals(1, engine.replayOnce().retryable)
            assertEquals(baseNow + 90_000L,
                db.pendingCommandDao().get(op)?.nextRetryAtEpochMs)
            currentTime += 89_999L
            assertEquals(0, engine.replayOnce().attempted)
            assertEquals(1, invocations.size)
            currentTime += 1L
            assertEquals(1, engine.replayOnce().retryable)
            assertEquals(baseNow + 150_000L,
                db.pendingCommandDao().get(op)?.nextRetryAtEpochMs)
            currentTime += 60_000L
            assertEquals(1, engine.replayOnce().applied)
            val row = assertNotNull(db.pendingCommandDao().get(op))
            assertEquals(PendingCommandStates.APPLIED, row.state)
            assertEquals("corr-final", row.lastCorrelationId)
            assertEquals(3, invocations.size)
            assertTrue(invocations.all { it == op })
        }
    }

    @Test
    fun reauthenticationReleaseIsIdentityAndInstallationScopedAndRevocationBecomesReview() = runBlocking {
        val folder = Files.createTempDirectory("hiltech-s06-revoked").toFile()
        val file = folder.resolve("field.db").absolutePath
        val ownOp = "66666666-6666-4666-8666-666666666666"
        val foreignOp = "77777777-7777-4777-8777-777777777777"
        val otherDeviceOp = "88888888-8888-4888-8888-888888888888"
        try {
            val db = open(file)
            try {
                db.pendingCommandDao().insert(pending(ownOp, 1))
                db.pendingCommandDao().insert(pending(foreignOp, 2).copy(
                    actorUserIdentityId = otherActor,
                ))
                db.pendingCommandDao().insert(pending(otherDeviceOp, 3).copy(
                    deviceId = otherDevice,
                ))
                val seen = mutableListOf<String>()
                val first = engine(
                    db = db,
                    now = baseNow,
                    remote = PendingCommandTransport { intent ->
                        seen += intent.operationId
                        CommandTransportResult.Retryable(
                            "REAUTH_REQUIRED",
                            retryAfterMs = 900_000L,
                        )
                    },
                ).replayOnce()
                assertEquals(listOf(ownOp), seen)
                assertEquals(1, first.retryable)
                assertEquals(1, db.pendingCommandDao().pausedForReauthCount(actor, device))
                assertEquals(0, db.pendingCommandDao().releaseReauthenticatedCommands(
                    otherActor, device, baseNow + 100L,
                ))
                assertEquals(PendingCommandStates.RETRYABLE,
                    db.pendingCommandDao().get(ownOp)?.state)
                assertEquals(1, db.pendingCommandDao().releaseReauthenticatedCommands(
                    actor, device, baseNow + 200L,
                ))

                // Current authority now rejects the user's assignment.
                val denied = engine(
                    db = db,
                    now = baseNow + 300L,
                    remote = PendingCommandTransport { intent ->
                        seen += intent.operationId
                        CommandTransportResult.Conflict(
                            conflictType = "COMPLETION_ASSIGNMENT_CHANGED",
                            currentVersion = 14,
                            safeServerStateJson = """{"state":"REASSIGNED"}""",
                            allowedRecoveryActionsJson = """["REFRESH_JOB_BUNDLE","DISCARD_COMMAND"]""",
                            correlationId = "corr-revoked",
                        )
                    },
                ).replayOnce()
                assertEquals(1, denied.conflicts)
                assertEquals(listOf(ownOp, ownOp), seen)
                assertEquals(PendingCommandStates.CONFLICT,
                    db.pendingCommandDao().get(ownOp)?.state)
                assertEquals(PendingCommandStates.PENDING,
                    db.pendingCommandDao().get(foreignOp)?.state)
                assertEquals(PendingCommandStates.PENDING,
                    db.pendingCommandDao().get(otherDeviceOp)?.state)
            } finally {
                db.close()
            }
            val reopened = open(file)
            try {
                val conflict = assertNotNull(reopened.conflictDao().getByOperationId(ownOp))
                assertEquals("COMPLETION_ASSIGNMENT_CHANGED", conflict.conflictType)
                assertEquals("""{"state":"REASSIGNED"}""", conflict.safeServerStateJson)
                assertEquals(14L, conflict.currentServerVersion)
                val result = engine(
                    db = reopened,
                    now = baseNow + 1_000_000L,
                    remote = PendingCommandTransport {
                        error("Conflict must not be silently resent.")
                    },
                ).replayOnce()
                assertEquals(0, result.attempted)
                assertEquals(PendingCommandStates.CONFLICT,
                    reopened.pendingCommandDao().get(ownOp)?.state)
            } finally {
                reopened.close()
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun sourceDriftPreservesConflictEvidenceUntilExplicitResolution() = runBlocking {
        withDb("hiltech-s06-stale-drawing") { db ->
            val op = "99999999-9999-4999-8999-999999999999"
            db.pendingCommandDao().insert(pending(op, 1))
            val result = engine(
                db = db,
                now = baseNow,
                remote = PendingCommandTransport {
                    CommandTransportResult.Conflict(
                        conflictType = "DRAWING_REVISION_STALE",
                        currentVersion = 16,
                        safeServerStateJson =
                            """{"currentDocumentRevision":"REV-B","policyRevision":5}""",
                        allowedRecoveryActionsJson =
                            """["REFRESH_JOB_BUNDLE","REVIEW_LOCAL_WORK"]""",
                        correlationId = "corr-drawing-conflict",
                    )
                },
            ).replayOnce()
            assertEquals(1, result.conflicts)
            val persisted = assertNotNull(db.conflictDao().getByOperationId(op))
            assertEquals("DRAWING_REVISION_STALE", persisted.conflictType)
            assertEquals(16L, persisted.currentServerVersion)
            assertEquals(
                """["REFRESH_JOB_BUNDLE","REVIEW_LOCAL_WORK"]""",
                persisted.allowedRecoveryActionsJson,
            )
            assertEquals(PendingCommandStates.CONFLICT,
                db.pendingCommandDao().get(op)?.state)
        }
    }

    private fun pending(operation: String, sequence: Long) =
        PendingCommandEntity(
            operationId = operation,
            actorUserIdentityId = actor,
            deviceId = device,
            commandType = "SubmitWorkCompletion",
            targetType = "WorkOrder",
            targetId = work,
            baseVersion = 11,
            payloadVersion = 1,
            payloadJson = """{"assignmentVersion":3,"bindingRevision":2}""",
            clientOccurredAtEpochMs = baseNow,
            enqueuedAtEpochMs = baseNow + 100,
            localSequence = sequence,
            state = PendingCommandStates.PENDING,
            retryCount = 0,
            nextRetryAtEpochMs = null,
            lastAttemptAtEpochMs = null,
            lastResultCode = null,
            lastServerVersion = null,
            lastCorrelationId = null,
            contractVersion = 1,
            policyBindingRef = "binding:2",
            updatedAtEpochMs = baseNow + 100,
        )

    private fun open(path: String): HiltechLocalDatabase =
        buildHiltechLocalDatabase(getDesktopDatabaseBuilder(path))

    private fun engine(
        db: HiltechLocalDatabase,
        now: Long,
        remote: PendingCommandTransport,
    ) = engine(db, { now }, remote)

    private fun engine(
        db: HiltechLocalDatabase,
        nowProvider: () -> Long,
        remote: PendingCommandTransport,
    ) = PendingCommandReplayEngine(
        database = db,
        transport = remote,
        clock = SyncClock { nowProvider() },
        backoffPolicy = FirstSliceRetryBackoffPolicy(RetryJitterSource { 0.0 }),
        replayScope = CommandReplayScope(actor, device),
        includeCommandTypes = setOf("SubmitWorkCompletion"),
    )

    private suspend fun <T> withDb(
        prefix: String,
        block: suspend (HiltechLocalDatabase) -> T,
    ): T {
        val folder = Files.createTempDirectory(prefix).toFile()
        val db = open(folder.resolve("field.db").absolutePath)
        return try {
            block(db)
        } finally {
            db.close()
            folder.deleteRecursively()
        }
    }
}
