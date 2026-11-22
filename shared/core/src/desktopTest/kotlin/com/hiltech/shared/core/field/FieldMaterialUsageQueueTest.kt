package com.hiltech.shared.core.field

import com.hiltech.shared.core.local.PendingCommandStates
import com.hiltech.shared.core.local.buildHiltechLocalDatabase
import com.hiltech.shared.core.local.getDesktopDatabaseBuilder
import com.hiltech.shared.core.work.FieldAssignmentDto
import com.hiltech.shared.core.work.FieldCompletionGateDto
import com.hiltech.shared.core.work.FieldConfigRefDto
import com.hiltech.shared.core.work.FieldExecutionCapabilitiesDto
import com.hiltech.shared.core.work.FieldPersonalIssuedStockLineDto
import com.hiltech.shared.core.work.FieldPersonalIssuedStockSnapshotDto
import com.hiltech.shared.core.work.FieldProjectDto
import com.hiltech.shared.core.work.FieldSiteDto
import com.hiltech.shared.core.work.FieldWorkBindingDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import java.nio.file.Files

class FieldMaterialUsageQueueTest {
    private val workId = "11111111-1111-4111-8111-111111111111"
    private val actorId = "22222222-2222-4222-8222-222222222222"
    private val assignmentId = "33333333-3333-4333-8333-333333333333"
    private val installationId = "44444444-4444-4444-8444-444444444444"
    private val requirementId = "55555555-5555-4555-8555-555555555555"
    private val itemId = "66666666-6666-4666-8666-666666666666"

    @Test
    fun `material intent survives process reopen and never mutates stock locally`() = runBlocking {
        val folder = Files.createTempDirectory("hiltech-material-usage").toFile()
        val path = folder.resolve("field.db").absolutePath
        val operationId = "77777777-7777-4777-8777-777777777777"
        try {
            val first = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(path))
            try {
                val queue = FieldMaterialUsageQueue(first)
                val created = queue.queue(
                    actorId, installationId, bundle(), stock(), stock().items.single(),
                    "2.500000", operationId, 1_700_000_000_000L,
                    verifiedOnlineThisSession = true,
                )
                assertEquals(PendingCommandStates.PENDING, created.state)
                assertEquals("2.5", created.quantity)
                val stored = requireNotNull(first.pendingCommandDao().get(operationId))
                assertEquals(11, stored.baseVersion)
                val payload = Json.parseToJsonElement(stored.payloadJson).jsonObject
                assertEquals("3", payload.getValue("assignmentVersion").toString())
                assertEquals("9", payload.getValue("issuedVersion").toString())
                assertEquals("Cable", payload.getValue("_localStockItemName").jsonPrimitive.content)
            } finally {
                first.close()
            }

            val reopened = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(path))
            try {
                val queue = FieldMaterialUsageQueue(reopened)
                val intents = queue.list(actorId, installationId, workId)
                assertEquals(1, intents.size)
                assertEquals(operationId, intents.single().operationId)
                assertEquals("10.000000", stock().items.single().outstandingQuantity)
                assertFalse(intents.single().state == PendingCommandStates.APPLIED)
            } finally {
                reopened.close()
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `stale session or locally overcommitted usage cannot create another intent`() = runBlocking {
        val folder = Files.createTempDirectory("hiltech-material-policy").toFile()
        val db = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(folder.resolve("field.db").absolutePath))
        try {
            val queue = FieldMaterialUsageQueue(db)
            assertFailsWith<IllegalArgumentException> {
                queue.queue(
                    actorId, installationId, bundle(), stock(), stock().items.single(),
                    "1", "77777777-7777-4777-8777-777777777777",
                    1_700_000_000_000L, verifiedOnlineThisSession = false,
                )
            }
            queue.queue(
                actorId, installationId, bundle(), stock(), stock().items.single(),
                "7", "88888888-8888-4888-8888-888888888888",
                1_700_000_000_001L, verifiedOnlineThisSession = true,
            )
            assertFailsWith<IllegalArgumentException> {
                queue.queue(
                    actorId, installationId, bundle(), stock(), stock().items.single(),
                    "4", "99999999-9999-4999-8999-999999999999",
                    1_700_000_000_002L, verifiedOnlineThisSession = true,
                )
            }
        } finally {
            db.close()
            folder.deleteRecursively()
        }
    }

    @Test
    fun `material and completion must not overtake each other`() = runBlocking {
        val readyBundle = bundle().let { original ->
            original.copy(
                executionCapabilities = original.executionCapabilities.copy(submitOffline = true),
                completionGate = FieldCompletionGateDto(
                    available = true,
                    satisfied = true,
                    reasonCodes = emptyList(),
                ),
            )
        }
        val dir1 = Files.createTempDirectory("hiltech-material-before-submit").toFile()
        val db1 = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(dir1.resolve("field.db").absolutePath))
        try {
            FieldMaterialUsageQueue(db1).queue(
                actorId, installationId, readyBundle, stock(), stock().items.single(),
                "1", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                1_700_000_000_000L, verifiedOnlineThisSession = true,
            )
            assertFailsWith<IllegalArgumentException> {
                FieldCompletionQueue(db1).queue(
                    actorId, installationId, readyBundle,
                    "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                    1_700_000_000_001L, verifiedOnlineThisSession = true,
                )
            }
        } finally {
            db1.close()
            dir1.deleteRecursively()
        }

        val dir2 = Files.createTempDirectory("hiltech-submit-before-material").toFile()
        val db2 = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(dir2.resolve("field.db").absolutePath))
        try {
            FieldCompletionQueue(db2).queue(
                actorId, installationId, readyBundle,
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                1_700_000_000_002L, verifiedOnlineThisSession = true,
            )
            assertFailsWith<IllegalArgumentException> {
                FieldMaterialUsageQueue(db2).queue(
                    actorId, installationId, readyBundle, stock(), stock().items.single(),
                    "1", "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    1_700_000_000_003L, verifiedOnlineThisSession = true,
                )
            }
        } finally {
            db2.close()
            dir2.deleteRecursively()
        }
    }

    private fun stock() = FieldPersonalIssuedStockSnapshotDto(
        workOrderId = workId,
        workOrderVersion = 11,
        status = "SOURCE_CURRENT",
        asOf = "2026-11-22T00:00:00Z",
        items = listOf(
            FieldPersonalIssuedStockLineDto(
                requirementId = requirementId,
                stockItemId = itemId,
                stockItemCode = "CBL-01",
                stockItemName = "Cable",
                unitCode = "M",
                outstandingQuantity = "10.000000",
                sourceVersion = 9,
                sourceUpdatedAt = "2026-11-22T00:00:00Z",
            ),
        ),
    )

    private fun bundle(): TechnicianJobBundleDto {
        val ref = FieldConfigRefDto(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "CONFIG",
            "Config",
            1,
        )
        return TechnicianJobBundleDto(
            workOrderId = workId,
            workOrderCode = "WO-11",
            title = "Fiber installation",
            lifecycleState = "IN_PROGRESS",
            readinessState = "READY",
            workOrderVersion = 11,
            project = FieldProjectDto(
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "PRJ",
                "Project",
                "ACTIVE",
                1,
            ),
            site = FieldSiteDto(
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "SITE",
                "Site",
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                "PS",
                "ACTIVE",
            ),
            assignment = FieldAssignmentDto(
                assignmentId,
                "USER",
                actorId,
                "Tech",
                "2026-11-21T01:00:00Z",
                version = 3,
            ),
            binding = FieldWorkBindingDto(
                bindingRevision = 2,
                workType = ref,
                assignmentPolicy = ref,
                readinessPolicy = ref,
                evidencePolicy = ref,
                reviewPolicy = ref,
            ),
            executionCapabilities = FieldExecutionCapabilitiesDto(
                startOffline = true,
                blockOffline = true,
                resumeOffline = true,
                evidenceCaptureOffline = true,
                submitOffline = false,
                authoritativeOfflineQueue = false,
            ),
            asOf = "2026-11-22T00:00:00Z",
        )
    }
}
