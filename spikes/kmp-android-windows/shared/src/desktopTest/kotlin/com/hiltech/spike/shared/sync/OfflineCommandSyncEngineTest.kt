package com.hiltech.spike.shared.sync

import com.hiltech.spike.shared.local.PendingCommandEntity
import com.hiltech.spike.shared.local.buildHiltechLocalDatabase
import com.hiltech.spike.shared.local.getDesktopDatabaseBuilder
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OfflineCommandSyncEngineTest {
    @Test
    fun offline_work_survives_restart_and_syncs_once_in_local_order() = runBlocking {
        val dbFile = freshDbFile()

        val firstDatabase = buildHiltechLocalDatabase(
            getDesktopDatabaseBuilder(dbFile.absolutePath),
        )

        val commands = workOrderCommands()
        commands.forEach { firstDatabase.pendingCommandDao().insert(it) }
        firstDatabase.close()

        val reopenedDatabase = buildHiltechLocalDatabase(
            getDesktopDatabaseBuilder(dbFile.absolutePath),
        )

        val transport = RecordingTransport()
        val engine = OfflineCommandSyncEngine(
            dao = reopenedDatabase.pendingCommandDao(),
            transport = transport,
        )

        val firstRun = engine.syncOnce(nowEpochMs = 20_000)

        assertEquals(4, firstRun.attempted)
        assertEquals(4, firstRun.applied)
        assertEquals(
            listOf("op-start", "op-evidence", "op-material", "op-complete"),
            transport.receivedOperationIds,
        )

        val persisted = reopenedDatabase.pendingCommandDao().listAll()
        assertEquals(4, persisted.size)
        assertTrue(persisted.all { it.state == SyncQueueState.APPLIED })

        val secondRun = engine.syncOnce(nowEpochMs = 30_000)
        assertEquals(0, secondRun.attempted)
        assertEquals(4, transport.receivedOperationIds.size)

        reopenedDatabase.close()
        dbFile.delete()
    }

    @Test
    fun ambiguous_network_failure_retries_by_operation_id_without_duplicate_business_action() = runBlocking {
        val dbFile = freshDbFile()
        val database = buildHiltechLocalDatabase(
            getDesktopDatabaseBuilder(dbFile.absolutePath),
        )

        database.pendingCommandDao().insert(
            command(
                operationId = "op-start",
                commandType = "StartWorkOrder",
                sequence = 1,
                baseVersion = 7,
            ),
        )

        val transport = ApplyThenLoseResponseTransport()
        val engine = OfflineCommandSyncEngine(database.pendingCommandDao(), transport)

        val firstRun = engine.syncOnce(nowEpochMs = 40_000)
        assertEquals(1, firstRun.retryable)

        val afterFirst = assertNotNull(database.pendingCommandDao().get("op-start"))
        assertEquals(SyncQueueState.RETRYABLE, afterFirst.state)
        assertEquals(1, afterFirst.attemptCount)

        val secondRun = engine.syncOnce(nowEpochMs = 50_000)
        assertEquals(1, secondRun.duplicateApplied)

        val afterSecond = assertNotNull(database.pendingCommandDao().get("op-start"))
        assertEquals(SyncQueueState.APPLIED, afterSecond.state)
        assertEquals("DUPLICATE_APPLIED", afterSecond.lastResultCode)
        assertEquals(2, afterSecond.attemptCount)

        assertEquals(2, transport.requestCount)
        assertEquals(1, transport.businessApplyCount)

        database.close()
        dbFile.delete()
    }

    @Test
    fun server_change_while_offline_surfaces_conflict_and_preserves_local_work() = runBlocking {
        val dbFile = freshDbFile()
        val database = buildHiltechLocalDatabase(
            getDesktopDatabaseBuilder(dbFile.absolutePath),
        )

        workOrderCommands().forEach { database.pendingCommandDao().insert(it) }

        val transport = StaleVersionTransport(
            currentServerVersion = 8,
        )

        val engine = OfflineCommandSyncEngine(database.pendingCommandDao(), transport)
        val result = engine.syncOnce(nowEpochMs = 60_000)

        assertEquals(1, result.attempted)
        assertEquals(1, result.conflicts)

        val all = database.pendingCommandDao().listAll()
        assertEquals(4, all.size)

        val first = all.first()
        assertEquals(SyncQueueState.CONFLICT, first.state)
        assertEquals("STALE_VERSION", first.lastResultCode)
        assertEquals(8, first.serverVersion)

        val blocked = all.drop(1)
        assertTrue(blocked.all { it.state == SyncQueueState.BLOCKED_BY_CONFLICT })
        assertTrue(blocked.all { it.serverVersion == 8L })

        val evidence = assertNotNull(database.pendingCommandDao().get("op-evidence"))
        assertEquals("""{"photo":"local://evidence-1.jpg"}""", evidence.payloadJson)

        assertEquals(listOf("op-start"), transport.receivedOperationIds)

        database.close()
        dbFile.delete()
    }

    private fun workOrderCommands(): List<PendingCommandEntity> =
        listOf(
            command(
                operationId = "op-start",
                commandType = "StartWorkOrder",
                sequence = 1,
                baseVersion = 7,
                payload = """{"workOrderId":"wo-42"}""",
            ),
            command(
                operationId = "op-evidence",
                commandType = "AttachEvidenceMetadata",
                sequence = 2,
                payload = """{"photo":"local://evidence-1.jpg"}""",
            ),
            command(
                operationId = "op-material",
                commandType = "ConsumeMaterial",
                sequence = 3,
                payload = """{"sku":"fiber-patch","qty":2}""",
            ),
            command(
                operationId = "op-complete",
                commandType = "CompleteWorkOrder",
                sequence = 4,
                payload = """{"evidenceCount":1}""",
            ),
        )

    private fun command(
        operationId: String,
        commandType: String,
        sequence: Long,
        baseVersion: Long? = null,
        payload: String = "{}",
    ) = PendingCommandEntity(
        operationId = operationId,
        commandType = commandType,
        objectType = "WorkOrder",
        objectId = "wo-42",
        baseVersion = baseVersion,
        payloadJson = payload,
        state = SyncQueueState.PENDING,
        attemptCount = 0,
        createdAtEpochMs = 10_000 + sequence,
        lastAttemptAtEpochMs = null,
        localSequence = sequence,
    )

    private fun freshDbFile(): File {
        val file = File.createTempFile("hiltech-offline-spike-", ".db")
        file.delete()
        return file
    }

    private class RecordingTransport : SyncTransport {
        val receivedOperationIds = mutableListOf<String>()
        private var serverVersion = 7L

        override suspend fun send(command: PendingCommandEntity): SyncSendResult {
            receivedOperationIds += command.operationId
            serverVersion += 1
            return SyncSendResult.Applied(serverVersion = serverVersion)
        }
    }

    private class ApplyThenLoseResponseTransport : SyncTransport {
        var requestCount = 0
        var businessApplyCount = 0
        private val appliedOperationIds = mutableSetOf<String>()

        override suspend fun send(command: PendingCommandEntity): SyncSendResult {
            requestCount += 1

            if (command.operationId in appliedOperationIds) {
                return SyncSendResult.DuplicateApplied(serverVersion = 8)
            }

            appliedOperationIds += command.operationId
            businessApplyCount += 1
            return SyncSendResult.Retryable("NETWORK_RESPONSE_LOST_AFTER_COMMIT")
        }
    }

    private class StaleVersionTransport(
        private val currentServerVersion: Long,
    ) : SyncTransport {
        val receivedOperationIds = mutableListOf<String>()

        override suspend fun send(command: PendingCommandEntity): SyncSendResult {
            receivedOperationIds += command.operationId

            return if (
                command.baseVersion != null &&
                command.baseVersion != currentServerVersion
            ) {
                SyncSendResult.Conflict(
                    serverVersion = currentServerVersion,
                    resultCode = "STALE_VERSION",
                )
            } else {
                SyncSendResult.Applied(serverVersion = currentServerVersion + 1)
            }
        }
    }
}
