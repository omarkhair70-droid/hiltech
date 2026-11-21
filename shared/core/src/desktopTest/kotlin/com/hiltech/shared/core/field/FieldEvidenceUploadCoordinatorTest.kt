package com.hiltech.shared.core.field

import com.hiltech.shared.core.evidence.*
import com.hiltech.shared.core.local.*
import com.hiltech.shared.core.work.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldEvidenceUploadCoordinatorTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val work = "22222222-2222-4222-8222-222222222222"
    private val actor = "33333333-3333-4333-8333-333333333333"
    private val device = "44444444-4444-4444-8444-444444444444"
    private val remoteId = "55555555-5555-4555-8555-555555555555"
    private val session = "66666666-6666-4666-8666-666666666666"
    private val hash = "a".repeat(64)
    private val buffer = "field-photo".encodeToByteArray()

    @Test fun reserveUploadFinalizeReadyWithSameSourceAndNoPublicUrlSaved() = withDb { db ->
        val remote = FakeRemote()
        db.evidenceQueueDao().insert(row())
        val result = engine(db, remote).replayOnce(actor, device)
        assertEquals(1, result.ready)
        assertEquals(1, remote.reserves)
        assertEquals(1, remote.uploads)
        assertEquals(1, remote.finalizes)
        val captured = assertNotNull(db.evidenceQueueDao().get(id))
        assertEquals(LocalEvidenceStates.READY, captured.localState)
        assertEquals(remoteId, captured.serverEvidenceId)
        assertEquals(remoteId, captured.finalizedServerEvidenceId)
        assertEquals(null, captured.remoteObjectRef) // never persist signed URLs/object keys
        assertEquals(id, remote.reserveOperationIds.single())
    }

    @Test fun quarantinedAfterFinalizeMustNotBecomeReadyWithoutServerMetadata() = withDb { db ->
        val remote = FakeRemote().apply { finalizeState = "QUARANTINED"; metadataState = "QUARANTINED" }
        db.evidenceQueueDao().insert(row())
        assertEquals(1, engine(db, remote).replayOnce(actor, device).quarantined)
        assertEquals(LocalEvidenceStates.UPLOADED_UNVERIFIED, db.evidenceQueueDao().get(id)?.localState)
        assertEquals(1, engine(db, remote).replayOnce(actor, device).quarantined)
        assertEquals(1, remote.reserves)
        assertEquals(1, remote.uploads)
        remote.metadataState = "READY"
        assertEquals(1, engine(db, remote).replayOnce(actor, device).ready)
        assertEquals(LocalEvidenceStates.READY, db.evidenceQueueDao().get(id)?.localState)
        assertEquals(1, remote.finalizes)
    }

    @Test fun networkRetryReusesReservationIdAndPreservesBytesAndOldActorRows() = withDb { db ->
        val remote = FakeRemote().apply { retryUploadOnce = true }
        db.evidenceQueueDao().insert(row())
        db.evidenceQueueDao().insert(row().copy(
            evidenceId = "77777777-7777-4777-8777-777777777777",
            localFileRef = "77777777-7777-4777-8777-777777777777.bin",
            capturedByUserId = "88888888-8888-4888-8888-888888888888",
        ))
        assertEquals(1, engine(db, remote).replayOnce(actor, device).retryable)
        assertEquals(LocalEvidenceStates.RETRYABLE, db.evidenceQueueDao().get(id)?.localState)
        assertEquals(1, engine(db, remote).replayOnce(actor, device).ready)
        assertEquals(listOf(id, id), remote.reserveOperationIds)
        assertEquals(2, remote.uploads)
        assertEquals(LocalEvidenceStates.LOCAL_READY,
            db.evidenceQueueDao().get("77777777-7777-4777-8777-777777777777")?.localState)
    }

    @Test fun actorVersionConflictAndDamagedBytesNeverUploadOrDeleteLocalEvidence() = withDb { db ->
        db.evidenceQueueDao().insert(row())
        val changed = FakeRemote().apply {
            bundleState = bundle().copy(assignment = bundle().assignment.copy(version = 9))
        }
        assertEquals(1, engine(db, changed).replayOnce(actor, device).reviewRequired)
        assertEquals(0, changed.reserves)
        assertEquals("CAPTURE_SOURCE_CHANGED", db.evidenceQueueDao().get(id)?.lastUploadErrorCode)
    }

    @Test fun localReadIntegrityFailureBlocksRemote() = withDb { db ->
        db.evidenceQueueDao().insert(row())
        val remote = FakeRemote()
        val failed = FieldEvidenceUploadCoordinator(db, remote,
            bytes = FieldEvidenceBytes { error("LOCAL_BYTES_SHA_MISMATCH") },
            nowEpochMs = { 1_700_000_000_001L },
            formatEpochUtc = { "2026-11-21T06:00:00Z" },
        ).replayOnce(actor, device)
        assertEquals(1, failed.reviewRequired)
        assertEquals(0, remote.reserves)
        assertEquals(LocalEvidenceStates.REJECTED, db.evidenceQueueDao().get(id)?.localState)
        assertTrue(db.evidenceQueueDao().get(id)?.localFileRef == "$id.bin")
    }

    private fun engine(db: HiltechLocalDatabase, remote: FakeRemote) =
        FieldEvidenceUploadCoordinator(
            db, remote,
            bytes = FieldEvidenceBytes { buffer },
            nowEpochMs = { 1_700_000_000_001L },
            formatEpochUtc = { "2026-11-21T06:00:00Z" },
        )

    private fun row() = LocalEvidenceEntity(
        evidenceId = id, workOrderId = work, targetType = "WORK_ORDER", targetId = work,
        requirementKey = "PHOTO", evidencePolicyId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        evidencePolicyRevision = 1, evidenceTypeCode = "PHOTO",
        localFileRef = "$id.bin", sha256 = hash, contentType = "image/jpeg",
        sizeBytes = buffer.size.toLong(), capturedAtEpochMs = 1_700_000_000_000L,
        capturedByUserId = actor, sourceDeviceId = device,
        localState = LocalEvidenceStates.LOCAL_READY, uploadSessionId = null,
        remoteObjectRef = null, finalizedServerEvidenceId = null,
        uploadAttemptCount = 0, lastUploadErrorCode = null, updatedAtEpochMs = 1_700_000_000_000L,
        assignmentId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        assignmentVersion = 2, workOrderVersion = 7, bindingRevision = 3,
    )

    private fun bundle(): TechnicianJobBundleDto {
        val policy = FieldConfigRefDto("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "PHOTO", "Photo", 1)
        return TechnicianJobBundleDto(
            workOrderId = work, workOrderCode = "WO-7", title = "Field install",
            lifecycleState = "IN_PROGRESS", readinessState = "READY", workOrderVersion = 7,
            project = FieldProjectDto("cccccccc-cccc-4ccc-8ccc-cccccccccccc", "P", "Project", "ACTIVE", 1),
            site = FieldSiteDto("dddddddd-dddd-4ddd-8ddd-dddddddddddd", "S", "Site",
                "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", "PS", "ACTIVE"),
            assignment = FieldAssignmentDto("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "USER", actor, "Worker", "2026-11-21T06:00:00Z", version = 2),
            binding = FieldWorkBindingDto(3, policy, policy, policy, policy, policy),
            evidenceRequirements = listOf(FieldEvidenceRequirementDto(
                "PHOTO", "PHOTO", "DURING_EXECUTION", 0, 3, true, listOf("image/jpeg"),
            )),
            executionCapabilities = FieldExecutionCapabilitiesDto(
                false, true, false, true, false, false,
            ),
            asOf = "2026-11-21T06:00:00Z",
        )
    }

    private inner class FakeRemote : FieldEvidenceRemote {
        var bundleState = bundle()
        var finalizeState = "READY"
        var metadataState = "READY"
        var retryUploadOnce = false
        var reserves = 0
        var uploads = 0
        var finalizes = 0
        val reserveOperationIds = mutableListOf<String>()

        private fun metadata(state: String) = EvidenceMetadataDto(
            evidenceId = remoteId, uploadSessionId = session,
            targetType = "WORK_ORDER", targetId = work, workOrderId = work,
            evidenceRequirementKey = "PHOTO",
            evidencePolicyId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            evidencePolicyRevision = 1,
            evidenceTypeCode = "PHOTO", contentType = "image/jpeg",
            sizeBytes = buffer.size.toLong(), sha256 = hash,
            storageState = state, classificationCode = "INTERNAL",
            clientVisibilityMode = "INTERNAL", evidenceVersion = 1,
        )

        override suspend fun currentBundle(workOrderId: String, installationId: String) = bundleState
        override suspend fun reserve(request: ReserveEvidenceUploadRequestDto, installationId: String): ReserveEvidenceUploadResponseDto {
            reserves++
            reserveOperationIds.add(request.operationId)
            return ReserveEvidenceUploadResponseDto(
                evidence = metadata("RESERVED"),
                upload = EvidenceUploadTargetDto("https://storage.invalid/opaque", emptyMap(), "2026-11-21T09:00:00Z", buffer.size.toLong()),
                correlationId = "evidence-test", replayed = reserves > 1,
            )
        }
        override suspend fun upload(target: EvidenceUploadTargetDto, contentType: String, bytes: ByteArray) {
            uploads++
            assertEquals(buffer.toList(), bytes.toList())
            if (retryUploadOnce) {
                retryUploadOnce = false
                throw EvidenceBinaryUploadException(503, true, "Temporary S3 outage")
            }
        }
        override suspend fun finalize(
            evidenceId: String, uploadSessionId: String, operationId: String,
            sha256: String, sizeBytes: Long, installationId: String,
        ): FinalizeEvidenceResponseDto {
            finalizes++
            assertTrue(operationId != id)
            return FinalizeEvidenceResponseDto(metadata(finalizeState), "finalize-corr", false)
        }
        override suspend fun metadata(evidenceId: String, installationId: String) = metadata(metadataState)
    }

    private fun withDb(block: suspend (HiltechLocalDatabase) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("field-evidence-replay").toFile()
        val db = buildHiltechLocalDatabase(getDesktopDatabaseBuilder(directory.resolve("local.db").absolutePath))
        try { block(db) } finally { db.close(); directory.deleteRecursively() }
    }
}
