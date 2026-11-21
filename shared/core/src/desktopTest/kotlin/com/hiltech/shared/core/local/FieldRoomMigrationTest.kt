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
}
