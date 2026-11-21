package com.hiltech.shared.core.field

import com.hiltech.shared.core.local.PendingCommandStates
import com.hiltech.shared.core.local.buildHiltechLocalDatabase
import com.hiltech.shared.core.local.getDesktopDatabaseBuilder
import com.hiltech.shared.core.work.FieldAssignmentDto
import com.hiltech.shared.core.work.FieldBlockerDto
import com.hiltech.shared.core.work.FieldConfigRefDto
import com.hiltech.shared.core.work.FieldExecutionCapabilitiesDto
import com.hiltech.shared.core.work.FieldProjectDto
import com.hiltech.shared.core.work.FieldSiteDto
import com.hiltech.shared.core.work.FieldWorkBindingDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldExecutionQueueTest {
    private val workId = "11111111-1111-4111-8111-111111111111"
    private val actorId = "22222222-2222-4222-8222-222222222222"
    private val assignmentId = "33333333-3333-4333-8333-333333333333"
    private val installationId = "44444444-4444-4444-8444-444444444444"

    @Test fun pendingStartSurvivesProcessReopenAndPreservesSourceVersions() = runBlocking {
        val folder = Files.createTempDirectory("hiltech-field-queue").toFile()
        val path = folder.resolve("field.db").absolutePath
        try {
            val first = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(path))
            val op = "55555555-5555-4555-8555-555555555555"
            try {
                val queue = FieldExecutionQueue(first)
                val pending = queue.queue(
                    actorId, installationId, bundle(), FieldExecutionIntent.START,
                    op, 1_700_000_000_000L, verifiedOnlineThisSession = true,
                )
                assertEquals(PendingCommandStates.PENDING, pending.state)
                val entity = assertNotNull(first.pendingCommandDao().get(op))
                assertEquals(11L, entity.baseVersion)
                assertEquals(assignmentId, Json.parseToJsonElement(entity.payloadJson)
                    .jsonObject.getValue("assignmentId").jsonPrimitive.content)
                assertEquals("3", Json.parseToJsonElement(entity.payloadJson)
                    .jsonObject.getValue("assignmentVersion").jsonPrimitive.content)
            } finally {
                first.close()
            }
            val reopened = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(path))
            try {
                val queue = FieldExecutionQueue(reopened)
                assertEquals(op, queue.outstanding(actorId, workId)?.operationId)
                assertNull(queue.outstanding("99999999-9999-4999-8999-999999999999", workId))
                assertFailsWith<IllegalArgumentException> {
                    queue.queue(actorId, installationId, bundle(), FieldExecutionIntent.START,
                        "66666666-6666-4666-8666-666666666666", 1_700_000_000_005L,
                        verifiedOnlineThisSession = true)
                }
            } finally {
                reopened.close()
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun staleBundleCannotCreateNewExecutionAuthorityAndBlockNeedsReason() = runBlocking {
        val folder = Files.createTempDirectory("hiltech-field-queue-policy").toFile()
        val db = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(folder.resolve("field.db").absolutePath))
        try {
            val queue = FieldExecutionQueue(db)
            assertFailsWith<IllegalArgumentException> {
                queue.queue(actorId, installationId, bundle(), FieldExecutionIntent.START,
                    "77777777-7777-4777-8777-777777777777", 1_700_000_000_000L,
                    verifiedOnlineThisSession = false)
            }
            assertFailsWith<IllegalArgumentException> {
                queue.queue(actorId, installationId, bundle(lifecycle = "IN_PROGRESS"), FieldExecutionIntent.BLOCK,
                    "77777777-7777-4777-8777-777777777777", 1_700_000_000_000L,
                    verifiedOnlineThisSession = true, explanation = " ")
            }
            val block = queue.queue(
                actorId, installationId, bundle(lifecycle = "IN_PROGRESS"),
                FieldExecutionIntent.BLOCK,
                "88888888-8888-4888-8888-888888888888", 1_700_000_000_010L,
                verifiedOnlineThisSession = true, explanation = "Cable route obstructed",
            )
            assertTrue(block.operationId.isNotBlank())
            val entity = assertNotNull(db.pendingCommandDao().get(block.operationId))
            assertEquals("Cable route obstructed", Json.parseToJsonElement(entity.payloadJson)
                .jsonObject.getValue("reason").jsonPrimitive.content)
        } finally {
            db.close()
            folder.deleteRecursively()
        }
    }

    private fun bundle(lifecycle: String = "ASSIGNED"): TechnicianJobBundleDto {
        val ref = FieldConfigRefDto("11111111-1111-4111-8111-111111111111", "CONFIG", "Config", 1)
        return TechnicianJobBundleDto(
            workOrderId = workId,
            workOrderCode = "WO-11",
            title = "Fiber installation",
            lifecycleState = lifecycle,
            readinessState = "READY",
            workOrderVersion = 11,
            project = FieldProjectDto("55555555-5555-4555-8555-555555555555", "PRJ", "Project", "ACTIVE", 1),
            site = FieldSiteDto("66666666-6666-4666-8666-666666666666", "SITE", "Site",
                "77777777-7777-4777-8777-777777777777", "PS", "ACTIVE"),
            assignment = FieldAssignmentDto(assignmentId, "USER", actorId, "Tech",
                "2026-11-21T01:00:00Z", version = 3),
            binding = FieldWorkBindingDto(
                bindingRevision = 2, workType = ref, assignmentPolicy = ref,
                readinessPolicy = ref, evidencePolicy = ref, reviewPolicy = ref,
            ),
            executionCapabilities = FieldExecutionCapabilitiesDto(
                startOffline = true, blockOffline = true, resumeOffline = true,
                evidenceCaptureOffline = false, submitOffline = false,
                authoritativeOfflineQueue = false,
            ),
            asOf = "2026-11-21T06:00:00Z",
            blockers = if (lifecycle == "BLOCKED") listOf(
                FieldBlockerDto("99999999-9999-4999-8999-999999999999",
                    blockerTypeCode = "FIELD_EXECUTION", description = "Work halted",
                    state = "OPEN", version = 1),
            ) else emptyList(),
        )
    }
}
