package com.hiltech.shared.core.local

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HiltechLocalDatabaseTest {
    @Test
    fun pendingIntentAndDependencySurviveDatabaseRestart() = runBlocking {
        val tempDir = Files.createTempDirectory("hiltech-room-contract").toFile()
        val dbFile = tempDir.resolve("hiltech-local.db")
        val firstOperation = "00000000-0000-0000-0000-000000000101"
        val secondOperation = "00000000-0000-0000-0000-000000000102"

        try {
            val firstDatabase = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(dbFile.absolutePath))
            try {
                firstDatabase.pendingCommandDao().insert(
                    pendingCommand(
                        operationId = firstOperation,
                        commandType = "FinalizeEvidence",
                        localSequence = 1,
                    ),
                )
                firstDatabase.pendingCommandDao().insert(
                    pendingCommand(
                        operationId = secondOperation,
                        commandType = "SubmitWorkCompletion",
                        localSequence = 2,
                    ),
                )
                firstDatabase.pendingCommandDependencyDao().insert(
                    PendingCommandDependencyEntity(
                        operationId = secondOperation,
                        dependsOnOperationId = firstOperation,
                    ),
                )
            } finally {
                firstDatabase.close()
            }

            val reopenedDatabase = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(dbFile.absolutePath))
            try {
                val restored = reopenedDatabase.pendingCommandDao().get(secondOperation)
                assertNotNull(restored)
                assertEquals(PendingCommandStates.PENDING, restored.state)
                assertEquals(2, restored.localSequence)
                assertEquals(
                    listOf(firstOperation),
                    reopenedDatabase.pendingCommandDependencyDao().listDependencies(secondOperation),
                )
                assertEquals(2, reopenedDatabase.pendingCommandDao().unresolvedCount())
            } finally {
                reopenedDatabase.close()
            }

            assertEquals(1, OfflineSchemaContract.ROOM_SCHEMA_VERSION)
            assertEquals(setOf(1), OfflineSchemaContract.supportedPendingPayloadVersions)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun pendingCommand(
        operationId: String,
        commandType: String,
        localSequence: Long,
    ): PendingCommandEntity =
        PendingCommandEntity(
            operationId = operationId,
            actorUserIdentityId = "00000000-0000-0000-0000-000000000201",
            deviceId = "android-contract-device",
            commandType = commandType,
            targetType = "WorkOrder",
            targetId = "00000000-0000-0000-0000-000000000301",
            baseVersion = 7,
            payloadVersion = OfflineSchemaContract.CURRENT_PENDING_PAYLOAD_VERSION,
            payloadJson = """{"contract":"preserved"}""",
            clientOccurredAtEpochMs = 1_700_000_000_000,
            enqueuedAtEpochMs = 1_700_000_000_100,
            localSequence = localSequence,
            state = PendingCommandStates.PENDING,
            retryCount = 0,
            nextRetryAtEpochMs = null,
            lastAttemptAtEpochMs = null,
            lastResultCode = null,
            lastServerVersion = null,
            lastCorrelationId = null,
            contractVersion = OfflineSchemaContract.CURRENT_COMMAND_CONTRACT_VERSION,
            policyBindingRef = "binding:v1",
            updatedAtEpochMs = 1_700_000_000_100,
        )
}
