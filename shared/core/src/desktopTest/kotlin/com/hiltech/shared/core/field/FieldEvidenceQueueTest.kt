package com.hiltech.shared.core.field

import com.hiltech.shared.core.local.LocalEvidenceStates
import com.hiltech.shared.core.local.buildHiltechLocalDatabase
import com.hiltech.shared.core.local.getDesktopDatabaseBuilder
import com.hiltech.shared.core.work.FieldAssignmentDto
import com.hiltech.shared.core.work.FieldConfigRefDto
import com.hiltech.shared.core.work.FieldEvidenceRequirementDto
import com.hiltech.shared.core.work.FieldExecutionCapabilitiesDto
import com.hiltech.shared.core.work.FieldProjectDto
import com.hiltech.shared.core.work.FieldSiteDto
import com.hiltech.shared.core.work.FieldWorkBindingDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldEvidenceQueueTest {
    private val actor = "22222222-2222-4222-8222-222222222222"
    private val device = "44444444-4444-4444-8444-444444444444"
    private val workId = "11111111-1111-4111-8111-111111111111"
    private val evidenceId = "55555555-5555-4555-8555-555555555555"
    private val sha = "a".repeat(64)

    @Test fun capturedEvidenceRetainsExactSourceBindingAndSurvivesProcessRestart() = runBlocking {
        val directory = Files.createTempDirectory("field-evidence-v3").toFile()
        val databaseFile = directory.resolve("local.db")
        try {
            val db = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(databaseFile.absolutePath))
            try {
                val row = FieldEvidenceQueue(db).capture(
                    actorId = actor, installationId = device, bundle = bundle(),
                    requirementKey = "PHOTO", contentType = "image/jpeg", sizeBytes = 60,
                    sha256 = sha, localFileRef = "$evidenceId.bin",
                    evidenceId = evidenceId, capturedAtEpochMs = 1_700_000_000_000L,
                    verifiedOnlineThisSession = true,
                )
                assertEquals(11L, row.workOrderVersion)
                assertEquals(3L, row.assignmentVersion)
                assertEquals(2, row.bindingRevision)
                assertEquals(LocalEvidenceStates.LOCAL_READY, row.localState)
                assertEquals(null, row.serverEvidenceId)
            } finally {
                db.close()
            }
            val reopened = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(databaseFile.absolutePath))
            try {
                val rows = FieldEvidenceQueue(reopened).list(actor, device, workId)
                assertEquals(1, rows.size)
                assertEquals(evidenceId, rows.single().evidenceId)
                assertEquals(1, reopened.evidenceQueueDao().pendingForActor(actor, device).size)
                assertTrue(reopened.evidenceQueueDao().pendingForActor("another-actor", device).isEmpty())
                assertTrue(reopened.evidenceQueueDao().pendingForActor(actor, "other-device").isEmpty())
                val changed = reopened.evidenceQueueDao().casUploadState(
                    evidenceId, actor, device,
                    LocalEvidenceStates.LOCAL_READY, LocalEvidenceStates.RESERVED,
                    "66666666-6666-4666-8666-666666666666", "session-1",
                    null, null, 1_700_000_100_000L,
                )
                assertEquals(1, changed)
                assertEquals(0, reopened.evidenceQueueDao().casUploadState(
                    evidenceId, "other-actor", device,
                    LocalEvidenceStates.RESERVED, LocalEvidenceStates.READY,
                    null, null, null, null, 1_700_000_100_100L,
                ))
                assertEquals("66666666-6666-4666-8666-666666666666",
                    assertNotNull(reopened.evidenceQueueDao().get(evidenceId)).serverEvidenceId)
            } finally {
                reopened.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun sourcePolicyMimeLimitStaleAndDuplicateCaptureAreRejected() = runBlocking {
        val directory = Files.createTempDirectory("field-evidence-policy").toFile()
        val db = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(directory.resolve("db").absolutePath))
        try {
            val queue = FieldEvidenceQueue(db)
            val base = bundle()
            assertFailsWith<IllegalArgumentException> {
                queue.capture(actor, device, base, "PHOTO", "image/jpeg", 50, sha,
                    "$evidenceId.bin", evidenceId, 1, verifiedOnlineThisSession = false)
            }
            assertFailsWith<IllegalArgumentException> {
                queue.capture(actor, device, base, "PHOTO", "application/pdf", 50, sha,
                    "$evidenceId.bin", evidenceId, 1, verifiedOnlineThisSession = true)
            }
            assertFailsWith<IllegalArgumentException> {
                queue.capture(actor, device, base, "PHOTO", "image/jpeg", 16L*1024*1024+1, sha,
                    "$evidenceId.bin", evidenceId, 1, verifiedOnlineThisSession = true)
            }
            assertFailsWith<IllegalArgumentException> {
                queue.capture(actor, device, base, "PHOTO", "image/jpeg", 50, sha,
                    "../private.bin", evidenceId, 1, verifiedOnlineThisSession = true)
            }
            queue.capture(actor, device, base, "PHOTO", "image/jpeg", 50, sha,
                "$evidenceId.bin", evidenceId, 1, verifiedOnlineThisSession = true)
            assertFailsWith<IllegalArgumentException> {
                queue.capture(actor, device, base, "PHOTO", "image/jpeg", 50, sha,
                    "66666666-6666-4666-8666-666666666666.bin",
                    "66666666-6666-4666-8666-666666666666", 2,
                    verifiedOnlineThisSession = true)
            }
            assertFailsWith<IllegalArgumentException> {
                FieldEvidenceCapturePolicy.bind(
                    base.copy(executionCapabilities = base.executionCapabilities.copy(evidenceCaptureOffline = false)),
                    actor, device, "PHOTO", "image/jpeg", 50, true, 0)
            }
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    private fun bundle(): TechnicianJobBundleDto {
        val ref = FieldConfigRefDto("88888888-8888-4888-8888-888888888888", "CONF", "Config", 1)
        return TechnicianJobBundleDto(
            workOrderId = workId, workOrderCode = "WO-11", title = "Fiber installation",
            lifecycleState = "IN_PROGRESS", readinessState = "READY", workOrderVersion = 11,
            project = FieldProjectDto("99999999-9999-4999-8999-999999999999", "PRJ", "Project", "ACTIVE", 1),
            site = FieldSiteDto("66666666-6666-4666-8666-666666666666", "SITE", "Site",
                "77777777-7777-4777-8777-777777777777", "PS", "ACTIVE"),
            assignment = FieldAssignmentDto("33333333-3333-4333-8333-333333333333",
                "USER", actor, "Tech", "2026-11-21T01:00:00Z", version = 3),
            binding = FieldWorkBindingDto(
                bindingRevision = 2, workType = ref, assignmentPolicy = ref,
                readinessPolicy = ref, evidencePolicy = ref, reviewPolicy = ref),
            evidenceRequirements = listOf(
                FieldEvidenceRequirementDto(
                    requirementKey = "PHOTO", evidenceTypeCode = "PHOTO",
                    stage = "DURING_EXECUTION", minCount = 0, maxCount = 1,
                    offlineCaptureAllowedByPolicy = true,
                    allowedContentTypes = listOf("image/jpeg"),
                ),
            ),
            executionCapabilities = FieldExecutionCapabilitiesDto(
                startOffline = false, blockOffline = true, resumeOffline = false,
                evidenceCaptureOffline = true, submitOffline = false,
                authoritativeOfflineQueue = false),
            asOf = "2026-11-21T06:00:00Z",
        )
    }
}
