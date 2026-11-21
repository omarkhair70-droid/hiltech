package com.hiltech.shared.core.field

import androidx.room3.withWriteTransaction
import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.local.LocalEvidenceEntity
import com.hiltech.shared.core.local.LocalEvidenceStates
import com.hiltech.shared.core.work.TechnicianJobBundleDto

/** Metadata written only AFTER bytes are safely fsynced in a private, opaque local file. */
class FieldEvidenceQueue(private val db: HiltechLocalDatabase) {
    suspend fun list(actorId: String, installationId: String, workOrderId: String): List<LocalEvidenceEntity> =
        db.evidenceQueueDao().forWorkOrder(actorId, installationId, workOrderId)

    suspend fun capture(
        actorId: String,
        installationId: String,
        bundle: TechnicianJobBundleDto,
        requirementKey: String,
        contentType: String,
        sizeBytes: Long,
        sha256: String,
        localFileRef: String,
        evidenceId: String,
        capturedAtEpochMs: Long,
        verifiedOnlineThisSession: Boolean,
    ): LocalEvidenceEntity {
        require(UUID_REGEX.matches(evidenceId))
        require(SHA256_REGEX.matches(sha256))
        require(localFileRef == "$evidenceId.bin") {
            "Only an opaque, app-private file reference can be persisted."
        }
        return db.withWriteTransaction {
            require(db.evidenceQueueDao().get(evidenceId) == null) { "Evidence ID is already used." }
            val local = db.evidenceQueueDao().forWorkOrder(actorId, installationId, bundle.workOrderId)
            val existing = FieldEvidenceCapturePolicy.unreflectedLocalCount(
                bundle, requirementKey, local,
            )
            val bound = FieldEvidenceCapturePolicy.bind(
                bundle = bundle,
                actorId = actorId,
                installationId = installationId,
                requirementKey = requirementKey,
                contentType = contentType,
                sizeBytes = sizeBytes,
                verifiedOnlineThisSession = verifiedOnlineThisSession,
                currentLocalCaptures = existing,
            )
            val row = LocalEvidenceEntity(
                evidenceId = evidenceId,
                workOrderId = bound.workOrderId,
                targetType = "WORK_ORDER",
                targetId = bound.workOrderId,
                requirementKey = requirementKey,
                evidencePolicyId = bound.evidencePolicyId,
                evidencePolicyRevision = bound.evidencePolicyRevision,
                evidenceTypeCode = bound.requirement.evidenceTypeCode,
                localFileRef = localFileRef,
                sha256 = sha256,
                contentType = contentType,
                sizeBytes = sizeBytes,
                capturedAtEpochMs = capturedAtEpochMs,
                capturedByUserId = actorId,
                sourceDeviceId = installationId,
                localState = LocalEvidenceStates.LOCAL_READY,
                uploadSessionId = null,
                remoteObjectRef = null,
                finalizedServerEvidenceId = null,
                uploadAttemptCount = 0,
                lastUploadErrorCode = null,
                updatedAtEpochMs = capturedAtEpochMs,
                assignmentId = bound.assignmentId,
                assignmentVersion = bound.assignmentVersion,
                workOrderVersion = bound.workOrderVersion,
                bindingRevision = bound.bindingRevision,
                serverEvidenceId = null,
            )
            db.evidenceQueueDao().insert(row)
            row
        }
    }

    private companion object {
        val SHA256_REGEX = Regex("[a-f0-9]{64}")
        val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    }
}
