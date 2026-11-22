package com.hiltech.shared.core.local

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FieldRoomMigrationTest {
    @Test
    fun roomOneToTwoKeepsPendingCommandsWhileAddingScopedReadCache() = runBlocking {
        val temp = Files.createTempDirectory("hiltech-room1-room2").toFile()
        val path = temp.resolve("hiltech-local.db")
        val source = listOf(
            File("schemas/com.hiltech.shared.core.local.HiltechLocalDatabase/1.json"),
            File("shared/core/schemas/com.hiltech.shared.core.local.HiltechLocalDatabase/1.json"),
        ).firstOrNull { it.isFile }
            ?: error("Committed version-one Room schema required for migration fixture.")
        try {
            val db = Json.parseToJsonElement(source.readText()).jsonObject
                .getValue("database").jsonObject
            val connection = BundledSQLiteDriver().open(path.absolutePath)
            try {
                db.getValue("entities").jsonArray.forEach { entity ->
                    val table = entity.jsonObject
                    val tableName = table.getValue("tableName").jsonPrimitive.content
                    val placeholder = "\$" + "{TABLE_NAME}"
                    connection.execSQL(
                        table.getValue("createSql").jsonPrimitive.content.replace(placeholder, tableName),
                    )
                    table["indices"]?.jsonArray?.forEach { index ->
                        connection.execSQL(
                            index.jsonObject.getValue("createSql").jsonPrimitive.content
                                .replace(placeholder, tableName),
                        )
                    }
                }
                db.getValue("setupQueries").jsonArray.forEach { query ->
                    connection.execSQL(query.jsonPrimitive.content)
                }
                connection.execSQL(
                    """INSERT INTO pending_command (
                      operation_id, actor_user_identity_id, device_id, command_type,
                      target_type, target_id, payload_version, payload_json,
                      client_occurred_at_epoch_ms, enqueued_at_epoch_ms, local_sequence,
                      state, retry_count, contract_version, updated_at_epoch_ms
                    ) VALUES (
                      '00000000-0000-0000-0000-000000000191',
                      '00000000-0000-0000-0000-000000000192',
                      'device-v1', 'SubmitWorkCompletion', 'WorkOrder',
                      '00000000-0000-0000-0000-000000000193', 1, '{}',
                      1700000000000, 1700000000001, 1, 'PENDING', 0, 1, 1700000000001
                    )""".trimIndent(),
                )
                connection.execSQL("PRAGMA user_version = 1")
            } finally {
                connection.close()
            }

            val upgraded = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(path.absolutePath))
            try {
                val preserved = assertNotNull(upgraded.pendingCommandDao().get(
                    "00000000-0000-0000-0000-000000000191",
                ))
                assertEquals("SubmitWorkCompletion", preserved.commandType)
                assertEquals(PendingCommandStates.PENDING, preserved.state)
                assertEquals(1, upgraded.pendingCommandDao().unresolvedCount())
                upgraded.fieldOfflineCacheDao().upsertToday(
                    FieldTodayCacheEntity("actor-one", "[]", 1700000000100),
                )
                assertEquals(
                    "[]",
                    assertNotNull(upgraded.fieldOfflineCacheDao().today("actor-one")).payloadJson,
                )
                assertEquals(null, upgraded.fieldOfflineCacheDao().today("actor-two"))
            } finally {
                upgraded.close()
            }
        } finally {
            temp.deleteRecursively()
        }
    }
    @Test
    fun roomTwoToThreePreservesLocalEvidenceAndPendingCommandsWithoutInventingSourceAuthority() = runBlocking {
        val temp = Files.createTempDirectory("hiltech-room2-room3").toFile()
        val path = temp.resolve("hiltech-local.db")
        val source = listOf(
            File("schemas/com.hiltech.shared.core.local.HiltechLocalDatabase/2.json"),
            File("shared/core/schemas/com.hiltech.shared.core.local.HiltechLocalDatabase/2.json"),
        ).firstOrNull { it.isFile }
            ?: error("Committed v2 Room schema required for evidence migration proof.")
        try {
            val db = Json.parseToJsonElement(source.readText()).jsonObject
                .getValue("database").jsonObject
            val connection = BundledSQLiteDriver().open(path.absolutePath)
            try {
                db.getValue("entities").jsonArray.forEach { entity ->
                    val table = entity.jsonObject
                    val tableName = table.getValue("tableName").jsonPrimitive.content
                    val placeholder = "$" + "{TABLE_NAME}"
                    connection.execSQL(
                        table.getValue("createSql").jsonPrimitive.content.replace(placeholder, tableName),
                    )
                    table["indices"]?.jsonArray?.forEach { index ->
                        connection.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content
                            .replace(placeholder, tableName))
                    }
                }
                db.getValue("setupQueries").jsonArray.forEach { query ->
                    connection.execSQL(query.jsonPrimitive.content)
                }
                connection.execSQL(
                    """INSERT INTO pending_command(
                      operation_id,actor_user_identity_id,device_id,command_type,
                      target_type,target_id,payload_version,payload_json,
                      client_occurred_at_epoch_ms,enqueued_at_epoch_ms,local_sequence,
                      state,retry_count,contract_version,updated_at_epoch_ms
                    ) VALUES (
                      '11111111-1111-4111-8111-111111111111',
                      '22222222-2222-4222-8222-222222222222',
                      'device-v2','StartWork','WorkOrder',
                      '33333333-3333-4333-8333-333333333333',
                      1,'{}',1700000000000,1700000000000,1,'PENDING',0,1,1700000000000
                    )""",
                )
                connection.execSQL(
                    """INSERT INTO local_evidence(
                      evidence_id,work_order_id,target_type,target_id,requirement_key,
                      evidence_policy_id,evidence_policy_revision,evidence_type_code,
                      local_file_ref,sha256,content_type,size_bytes,captured_at_epoch_ms,
                      captured_by_user_id,source_device_id,local_state,
                      upload_attempt_count,updated_at_epoch_ms
                    ) VALUES(
                      '44444444-4444-4444-8444-444444444444',
                      '33333333-3333-4333-8333-333333333333','WORK_ORDER',
                      '33333333-3333-4333-8333-333333333333','PHOTO',
                      NULL,NULL,'PHOTO','44444444-4444-4444-8444-444444444444.bin',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'image/jpeg',1024,1700000000000,
                      '22222222-2222-4222-8222-222222222222','device-v2',
                      'LOCAL_READY',0,1700000000000
                    )""",
                )
                connection.execSQL("PRAGMA user_version = 2")
            } finally {
                connection.close()
            }
            val upgraded = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(path.absolutePath))
            try {
                val command = assertNotNull(upgraded.pendingCommandDao().get(
                    "11111111-1111-4111-8111-111111111111"))
                assertEquals(PendingCommandStates.PENDING, command.state)
                val evidence = assertNotNull(upgraded.evidenceQueueDao().get(
                    "44444444-4444-4444-8444-444444444444"))
                assertEquals(LocalEvidenceStates.LOCAL_READY, evidence.localState)
                assertEquals("", evidence.assignmentId)
                assertEquals(0L, evidence.assignmentVersion)
                assertEquals(0L, evidence.workOrderVersion)
                assertEquals(0, evidence.bindingRevision)
                assertEquals(null, evidence.serverEvidenceId)
                assertEquals(1024L, evidence.sizeBytes)
                // Old unbound data survives, but cannot be replayed as source-authorized v3 Evidence.
            } finally {
                upgraded.close()
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun roomThreeToFourPreservesCompletionAndEvidenceAndAddsEngineeringIntentTable() = runBlocking {
        val temp = Files.createTempDirectory("hiltech-room3-room4").toFile()
        val path = temp.resolve("hiltech-local.db")
        val source = listOf(
            File("schemas/com.hiltech.shared.core.local.HiltechLocalDatabase/3.json"),
            File("shared/core/schemas/com.hiltech.shared.core.local.HiltechLocalDatabase/3.json"),
        ).firstOrNull { it.isFile }
            ?: error("Committed Room v3 schema is required for forward migration proof.")
        val operationId = "11111111-1111-4111-8111-111111111111"
        val evidenceId = "22222222-2222-4222-8222-222222222222"
        try {
            val legacy = Json.parseToJsonElement(source.readText()).jsonObject
                .getValue("database").jsonObject
            val connection = BundledSQLiteDriver().open(path.absolutePath)
            try {
                legacy.getValue("entities").jsonArray.forEach { entity ->
                    val table = entity.jsonObject
                    val name = table.getValue("tableName").jsonPrimitive.content
                    val placeholder = "$" + "{TABLE_NAME}"
                    connection.execSQL(
                        table.getValue("createSql").jsonPrimitive.content
                            .replace(placeholder, name),
                    )
                    table["indices"]?.jsonArray?.forEach { index ->
                        connection.execSQL(
                            index.jsonObject.getValue("createSql").jsonPrimitive.content
                                .replace(placeholder, name),
                        )
                    }
                }
                legacy.getValue("setupQueries").jsonArray.forEach { query ->
                    connection.execSQL(query.jsonPrimitive.content)
                }
                connection.execSQL(
                    """INSERT INTO pending_command (
                      operation_id,actor_user_identity_id,device_id,command_type,
                      target_type,target_id,base_version,payload_version,payload_json,
                      client_occurred_at_epoch_ms,enqueued_at_epoch_ms,local_sequence,
                      state,retry_count,contract_version,updated_at_epoch_ms
                    ) VALUES (
                      '$operationId','33333333-3333-4333-8333-333333333333',
                      'android-old','SubmitWorkCompletion','WorkOrder',
                      '44444444-4444-4444-8444-444444444444',7,1,
                      '{"source":"v3","policy":"freeze"}',
                      1700000000000,1700000000100,1,'PENDING',0,1,1700000000100
                    )""".trimIndent(),
                )
                connection.execSQL(
                    """INSERT INTO local_evidence (
                      evidence_id,work_order_id,target_type,target_id,requirement_key,
                      evidence_policy_id,evidence_policy_revision,evidence_type_code,
                      local_file_ref,sha256,content_type,size_bytes,captured_at_epoch_ms,
                      captured_by_user_id,source_device_id,local_state,
                      upload_attempt_count,updated_at_epoch_ms,assignment_id,
                      assignment_version,work_order_version,binding_revision
                    ) VALUES (
                      '$evidenceId','44444444-4444-4444-8444-444444444444',
                      'WORK_ORDER','44444444-4444-4444-8444-444444444444',
                      'TECH_REPORT','55555555-5555-4555-8555-555555555555',
                      4,'ENGINEERING_TEST','internal/report.enc',
                      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                      'application/pdf',1024,1700000000000,
                      '33333333-3333-4333-8333-333333333333',
                      'android-old','LOCAL_READY',0,1700000000100,
                      '66666666-6666-4666-8666-666666666666',3,7,2
                    )""".trimIndent(),
                )
                connection.execSQL("PRAGMA user_version = 3")
            } finally {
                connection.close()
            }

            val migrated = buildHiltechLocalDatabase(
                getDesktopDatabaseBuilder(path.absolutePath),
            )
            try {
                val command = assertNotNull(migrated.pendingCommandDao().get(operationId))
                assertEquals(PendingCommandStates.PENDING, command.state)
                assertEquals("SubmitWorkCompletion", command.commandType)
                assertEquals(7, command.baseVersion)
                assertEquals("""{"source":"v3","policy":"freeze"}""", command.payloadJson)
                val evidence = assertNotNull(migrated.evidenceQueueDao().get(evidenceId))
                assertEquals(LocalEvidenceStates.LOCAL_READY, evidence.localState)
                assertEquals(3L, evidence.assignmentVersion)
                assertEquals(7L, evidence.workOrderVersion)
                assertEquals(2, evidence.bindingRevision)
                assertEquals(4, OfflineSchemaContract.ROOM_SCHEMA_VERSION)
                // The new Room v4 table exists, without fabricating any engineering attempt.
                assertEquals(null, migrated.engineeringTestIntentDao().get(operationId))
            } finally {
                migrated.close()
            }

            // Process death after auto-migration must not lose original pending intent.
            val reopened = buildHiltechLocalDatabase(
                getDesktopDatabaseBuilder(path.absolutePath),
            )
            try {
                assertEquals(
                    operationId,
                    reopened.pendingCommandDao().get(operationId)?.operationId,
                )
                assertEquals(
                    evidenceId,
                    reopened.evidenceQueueDao().get(evidenceId)?.evidenceId,
                )
            } finally {
                reopened.close()
            }
        } finally {
            temp.deleteRecursively()
        }
    }
}
