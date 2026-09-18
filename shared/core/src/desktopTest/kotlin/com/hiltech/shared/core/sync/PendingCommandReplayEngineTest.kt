package com.hiltech.shared.core.sync

import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.local.PendingCommandDependencyEntity
import com.hiltech.shared.core.local.PendingCommandEntity
import com.hiltech.shared.core.local.PendingCommandStates
import com.hiltech.shared.core.local.buildHiltechLocalDatabase
import com.hiltech.shared.core.local.getDesktopDatabaseBuilder
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PendingCommandReplayEngineTest {
    @Test
    fun acceptedCommandBecomesAppliedWithoutChangingOperationIdentity() = withDatabase { db ->
        val operationId = "00000000-0000-0000-0000-000000001001"
        db.pendingCommandDao().insert(pendingCommand(operationId, sequence = 1))

        val engine = PendingCommandReplayEngine(
            database = db,
            transport = PendingCommandTransport {
                assertEquals(operationId, it.operationId)
                CommandTransportResult.Applied(
                    currentVersion = 8,
                    correlationId = "corr-applied",
                )
            },
            clock = FixedClock(1_700_000_010_000),
            backoffPolicy = zeroJitterPolicy(),
        )

        val summary = engine.replayOnce()
        val restored = db.pendingCommandDao().get(operationId)

        assertEquals(1, summary.applied)
        assertNotNull(restored)
        assertEquals(PendingCommandStates.APPLIED, restored.state)
        assertEquals(operationId, restored.operationId)
        assertEquals(8, restored.lastServerVersion)
        assertEquals("ACCEPTED", restored.lastResultCode)
        assertEquals("""{"intent":"preserve-me"}""", restored.payloadJson)
    }

    @Test
    fun retryableFailureUsesFrozenThirtySecondFirstBackoff() = withDatabase { db ->
        val operationId = "00000000-0000-0000-0000-000000001002"
        val now = 1_700_000_020_000L
        db.pendingCommandDao().insert(pendingCommand(operationId, sequence = 1))

        val engine = PendingCommandReplayEngine(
            database = db,
            transport = PendingCommandTransport {
                CommandTransportResult.Retryable(code = "SERVICE_UNAVAILABLE")
            },
            clock = FixedClock(now),
            backoffPolicy = zeroJitterPolicy(),
        )

        val summary = engine.replayOnce()
        val restored = db.pendingCommandDao().get(operationId)

        assertEquals(1, summary.retryable)
        assertNotNull(restored)
        assertEquals(PendingCommandStates.RETRYABLE, restored.state)
        assertEquals(1, restored.retryCount)
        assertEquals(now + 30_000L, restored.nextRetryAtEpochMs)
        assertEquals("SERVICE_UNAVAILABLE", restored.lastResultCode)
    }

    @Test
    fun conflictPreservesLocalIntentAndBlocksDependentCommand() = withDatabase { db ->
        val parentId = "00000000-0000-0000-0000-000000001003"
        val childId = "00000000-0000-0000-0000-000000001004"

        db.pendingCommandDao().insert(pendingCommand(parentId, sequence = 1))
        db.pendingCommandDao().insert(pendingCommand(childId, sequence = 2))
        db.pendingCommandDependencyDao().insert(
            PendingCommandDependencyEntity(
                operationId = childId,
                dependsOnOperationId = parentId,
            ),
        )

        val executed = mutableListOf<String>()
        val engine = PendingCommandReplayEngine(
            database = db,
            transport = PendingCommandTransport { command ->
                executed += command.operationId
                CommandTransportResult.Conflict(
                    conflictType = "STALE_ASSIGNMENT",
                    currentVersion = 12,
                    safeServerStateJson = """{"state":"REASSIGNED"}""",
                    allowedRecoveryActionsJson = """["REVIEW_LOCAL_WORK","DISCARD_COMMAND"]""",
                    correlationId = "corr-conflict",
                )
            },
            clock = FixedClock(1_700_000_030_000),
            backoffPolicy = zeroJitterPolicy(),
        )

        val summary = engine.replayOnce()
        val parent = db.pendingCommandDao().get(parentId)
        val child = db.pendingCommandDao().get(childId)
        val conflict = db.conflictDao().getByOperationId(parentId)

        assertEquals(listOf(parentId), executed)
        assertEquals(1, summary.conflicts)
        assertEquals(1, summary.dependencyBlocked)
        assertNotNull(parent)
        assertNotNull(child)
        assertNotNull(conflict)
        assertEquals(PendingCommandStates.CONFLICT, parent.state)
        assertEquals(PendingCommandStates.BLOCKED_BY_CONFLICT, child.state)
        assertEquals("""{"intent":"preserve-me"}""", parent.payloadJson)
        assertEquals("STALE_ASSIGNMENT", conflict.conflictType)
        assertEquals(12, conflict.currentServerVersion)
    }

    private fun zeroJitterPolicy(): FirstSliceRetryBackoffPolicy =
        FirstSliceRetryBackoffPolicy(RetryJitterSource { 0.0 })

    private class FixedClock(
        private val value: Long,
    ) : SyncClock {
        override fun nowEpochMs(): Long = value
    }

    private fun pendingCommand(
        operationId: String,
        sequence: Long,
    ): PendingCommandEntity =
        PendingCommandEntity(
            operationId = operationId,
            actorUserIdentityId = "00000000-0000-0000-0000-000000009001",
            deviceId = "android-contract-device",
            commandType = "SubmitWorkCompletion",
            targetType = "WorkOrder",
            targetId = "00000000-0000-0000-0000-000000009002",
            baseVersion = 7,
            payloadVersion = 1,
            payloadJson = """{"intent":"preserve-me"}""",
            clientOccurredAtEpochMs = 1_700_000_000_000,
            enqueuedAtEpochMs = 1_700_000_000_100,
            localSequence = sequence,
            state = PendingCommandStates.PENDING,
            retryCount = 0,
            nextRetryAtEpochMs = null,
            lastAttemptAtEpochMs = null,
            lastResultCode = null,
            lastServerVersion = null,
            lastCorrelationId = null,
            contractVersion = 1,
            policyBindingRef = "binding:v1",
            updatedAtEpochMs = 1_700_000_000_100,
        )

    private fun withDatabase(
        block: suspend (HiltechLocalDatabase) -> Unit,
    ) = runBlocking {
        val tempDir = Files.createTempDirectory("hiltech-replay-contract").toFile()
        val dbFile = tempDir.resolve("hiltech-local.db")
        val database = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(dbFile.absolutePath))

        try {
            block(database)
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }
}
