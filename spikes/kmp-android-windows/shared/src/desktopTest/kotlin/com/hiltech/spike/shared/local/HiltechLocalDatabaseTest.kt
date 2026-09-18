package com.hiltech.spike.shared.local

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class HiltechLocalDatabaseTest {
    @Test
    fun pending_command_survives_real_sqlite_roundtrip() = runBlocking {
        val file = File.createTempFile("hiltech-room-spike-", ".db")
        file.delete()

        val database = buildHiltechLocalDatabase(
            getDesktopDatabaseBuilder(file.absolutePath),
        )

        try {
            val command = PendingCommandEntity(
                operationId = "op-001",
                commandType = "CompleteWorkOrder",
                objectType = "WorkOrder",
                objectId = "wo-42",
                baseVersion = 7,
                payloadJson = """{"evidenceCount":3}""",
                state = "PENDING",
                attemptCount = 0,
                createdAtEpochMs = 1_789_690_000_000,
                lastAttemptAtEpochMs = null,
            )

            database.pendingCommandDao().insert(command)

            assertEquals(1, database.pendingCommandDao().count())

            val pending = database.pendingCommandDao().listByState("PENDING")
            assertEquals(listOf(command), pending)

            database.pendingCommandDao().updateAttempt(
                operationId = "op-001",
                state = "SYNCING",
                attemptCount = 1,
                lastAttemptAtEpochMs = 1_789_690_010_000,
            )

            val syncing = database.pendingCommandDao().listByState("SYNCING")
            assertEquals(1, syncing.size)
            assertEquals(1, syncing.single().attemptCount)
        } finally {
            database.close()
            file.delete()
        }
    }
}
