package com.hiltech.shared.core.field

import com.hiltech.shared.core.evidence.EvidenceBinaryUploadException
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import com.hiltech.shared.core.evidence.EvidenceUploadTargetDto
import com.hiltech.shared.core.evidence.FinalizeEvidenceResponseDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadRequestDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadResponseDto
import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.local.LocalEvidenceEntity
import com.hiltech.shared.core.local.LocalEvidenceStates
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import kotlinx.coroutines.CancellationException

interface FieldEvidenceRemote {
    suspend fun currentBundle(workOrderId: String, installationId: String): TechnicianJobBundleDto
    suspend fun reserve(request: ReserveEvidenceUploadRequestDto, installationId: String): ReserveEvidenceUploadResponseDto
    suspend fun upload(target: EvidenceUploadTargetDto, contentType: String, bytes: ByteArray)
    suspend fun finalize(evidenceId: String, uploadSessionId: String, operationId: String,
                         sha256: String, sizeBytes: Long, installationId: String): FinalizeEvidenceResponseDto
    suspend fun metadata(evidenceId: String, installationId: String): EvidenceMetadataDto
}

fun interface FieldEvidenceBytes {
    suspend fun readAndVerify(row: LocalEvidenceEntity): ByteArray
}

data class FieldEvidenceReplaySummary(
    val ready: Int = 0,
    val quarantined: Int = 0,
    val reviewRequired: Int = 0,
    val retryable: Int = 0,
    val reauthenticationRequired: Boolean = false,
)

/**
 * Server-authorized Evidence reserve -> private binary PUT -> independent finalize.
 * Does not keep signed upload URLs, and never turns PUT success into READY locally.
 */
class FieldEvidenceUploadCoordinator(
    private val db: HiltechLocalDatabase,
    private val remote: FieldEvidenceRemote,
    private val bytes: FieldEvidenceBytes,
    private val nowEpochMs: () -> Long,
    private val formatEpochUtc: (Long) -> String,
) {
    suspend fun replayOnce(actorId: String, installationId: String): FieldEvidenceReplaySummary {
        require(actorId.isNotBlank() && installationId.isNotBlank())
        val records = db.evidenceQueueDao().pendingForActor(actorId, installationId)
        var summary = FieldEvidenceReplaySummary()
        for (record in records) {
            try {
                val result = replay(record, actorId, installationId)
                summary = when (result) {
                    ReplayResult.READY -> summary.copy(ready = summary.ready + 1)
                    ReplayResult.QUARANTINED -> summary.copy(quarantined = summary.quarantined + 1)
                    ReplayResult.REVIEW -> summary.copy(reviewRequired = summary.reviewRequired + 1)
                    ReplayResult.RETRY -> summary.copy(retryable = summary.retryable + 1)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val code = failureCode(failure)
                val requiresAuth = failure is HiltechApiException &&
                    (failure.httpStatus == 401 || failure.code == "REAUTH_REQUIRED")
                val retry = failure is HiltechApiException &&
                    (failure.retryable || failure.code == "NETWORK_UNAVAILABLE") ||
                    failure is EvidenceBinaryUploadException && failure.retryable
                val resultState = when {
                    requiresAuth || retry -> LocalEvidenceStates.RETRYABLE
                    else -> LocalEvidenceStates.REJECTED
                }
                val current = db.evidenceQueueDao().get(record.evidenceId)
                if (current != null && current.capturedByUserId == actorId &&
                    current.sourceDeviceId == installationId) {
                    move(current, actorId, installationId, resultState, error = code)
                }
                summary = summary.copy(
                    reviewRequired = summary.reviewRequired + if (!requiresAuth && !retry) 1 else 0,
                    retryable = summary.retryable + if (retry) 1 else 0,
                    reauthenticationRequired = summary.reauthenticationRequired || requiresAuth,
                )
                if (requiresAuth) return summary
            }
        }
        return summary
    }

    private suspend fun replay(
        row: LocalEvidenceEntity, actorId: String, installationId: String,
    ): ReplayResult {
        if (row.assignmentId.isBlank() || row.assignmentVersion < 1 ||
            row.workOrderVersion < 1 || row.bindingRevision < 1 ||
            row.evidencePolicyId.isNullOrBlank() || row.evidencePolicyRevision == null ||
            row.requirementKey.isNullOrBlank() || row.targetType != "WORK_ORDER" ||
            row.targetId != row.workOrderId) {
            move(row, actorId, installationId, LocalEvidenceStates.REJECTED, error = "CAPTURE_CONTEXT_MISSING")
            return ReplayResult.REVIEW
        }

        // Metadata-only follow-up is authorized by the server Evidence endpoint.
        // Once finalized, later WorkOrder edits must not erase a verified READY result.
        if (row.finalizedServerEvidenceId != null) {
            return verifyMetadata(row, actorId, installationId, row.finalizedServerEvidenceId)
        }

        // Source recheck is mandatory before any NEW reservation, upload or finalize.
        val bundle = remote.currentBundle(row.workOrderId, installationId)
        val versionAllowed = bundle.workOrderVersion == row.workOrderVersion ||
            (bundle.workOrderVersion == row.workOrderVersion + 1 &&
                db.pendingCommandDao().provenFieldVersionAdvancement(
                    actorId, installationId, row.workOrderId,
                    row.workOrderVersion, bundle.workOrderVersion,
                ))
        val activeBinding = bundle.binding
        if (bundle.workOrderId != row.workOrderId ||
            !versionAllowed ||
            bundle.assignment.assignmentId != row.assignmentId ||
            bundle.assignment.version != row.assignmentVersion ||
            activeBinding == null ||
            activeBinding.bindingRevision != row.bindingRevision ||
            activeBinding.evidencePolicy.id != row.evidencePolicyId ||
            activeBinding.evidencePolicy.revision != row.evidencePolicyRevision ||
            !bundle.executionCapabilities.evidenceCaptureOffline ||
            bundle.evidenceRequirements.none {
                it.requirementKey == row.requirementKey &&
                    it.evidenceTypeCode == row.evidenceTypeCode &&
                    it.offlineCaptureAllowedByPolicy &&
                    row.contentType in it.allowedContentTypes
            }) {
            move(row, actorId, installationId, LocalEvidenceStates.REJECTED, error = "CAPTURE_SOURCE_CHANGED")
            return ReplayResult.REVIEW
        }

        val buffer = bytes.readAndVerify(row)
        if (buffer.size.toLong() != row.sizeBytes) {
            move(row, actorId, installationId, LocalEvidenceStates.REJECTED, error = "LOCAL_SIZE_CHANGED")
            return ReplayResult.REVIEW
        }

        var current = row
        if (current.localState != LocalEvidenceStates.UPLOADED_UNVERIFIED) {
            val reserve = remote.reserve(
                ReserveEvidenceUploadRequestDto(
                    operationId = row.evidenceId, // stable idempotent reservation
                    targetType = "WORK_ORDER",
                    targetId = row.workOrderId,
                    workOrderId = row.workOrderId,
                    evidenceRequirementKey = requireNotNull(row.requirementKey),
                    evidenceTypeCode = row.evidenceTypeCode,
                    contentType = row.contentType,
                    originalFileName = null, // do not leak a selected private filename
                    sizeBytes = row.sizeBytes,
                    expectedSha256 = row.sha256,
                    capturedAt = formatEpochUtc(row.capturedAtEpochMs),
                    clientOccurredAt = formatEpochUtc(row.capturedAtEpochMs),
                ),
                installationId,
            )
            val authoritative = reserve.evidence
            if (authoritative.targetType != "WORK_ORDER" ||
                authoritative.targetId != row.workOrderId ||
                authoritative.evidenceRequirementKey != row.requirementKey ||
                authoritative.evidenceTypeCode != row.evidenceTypeCode ||
                authoritative.contentType != row.contentType ||
                authoritative.sizeBytes != row.sizeBytes ||
                authoritative.sha256 != row.sha256 ||
                (current.serverEvidenceId != null && current.serverEvidenceId != authoritative.evidenceId)) {
                move(current, actorId, installationId, LocalEvidenceStates.REJECTED, error = "RESERVATION_RESULT_MISMATCH")
                return ReplayResult.REVIEW
            }
            current = move(
                current, actorId, installationId, LocalEvidenceStates.RESERVED,
                serverId = authoritative.evidenceId, sessionId = authoritative.uploadSessionId,
            ) ?: return ReplayResult.REVIEW
            current = move(
                current, actorId, installationId, LocalEvidenceStates.UPLOADING,
                serverId = authoritative.evidenceId, sessionId = authoritative.uploadSessionId,
            ) ?: return ReplayResult.REVIEW
            remote.upload(reserve.upload, row.contentType, buffer)
            current = move(
                current, actorId, installationId, LocalEvidenceStates.UPLOADED_UNVERIFIED,
                serverId = authoritative.evidenceId, sessionId = authoritative.uploadSessionId,
            ) ?: return ReplayResult.REVIEW
        }

        val serverId = current.serverEvidenceId ?: return ReplayResult.REVIEW
        val sessionId = current.uploadSessionId ?: return ReplayResult.REVIEW
        val finalized = remote.finalize(
            serverId, sessionId, finalizeOperationId(row.evidenceId),
            row.sha256, row.sizeBytes, installationId,
        )
        if (finalized.evidence.evidenceId != serverId ||
            finalized.evidence.sha256 != row.sha256 ||
            finalized.evidence.sizeBytes != row.sizeBytes) {
            move(current, actorId, installationId, LocalEvidenceStates.REJECTED, error = "FINALIZE_RESULT_MISMATCH")
            return ReplayResult.REVIEW
        }
        val state = when (finalized.evidence.storageState) {
            "READY" -> LocalEvidenceStates.READY
            "REJECTED" -> LocalEvidenceStates.REJECTED
            else -> LocalEvidenceStates.UPLOADED_UNVERIFIED
        }
        move(current, actorId, installationId, state,
            serverId = serverId, sessionId = sessionId, finalizedId = serverId,
            error = if (state == LocalEvidenceStates.UPLOADED_UNVERIFIED) finalized.evidence.storageState else null)
        return when (state) {
            LocalEvidenceStates.READY -> ReplayResult.READY
            LocalEvidenceStates.REJECTED -> ReplayResult.REVIEW
            else -> ReplayResult.QUARANTINED
        }
    }

    private suspend fun verifyMetadata(
        row: LocalEvidenceEntity, actorId: String, installationId: String, serverId: String,
    ): ReplayResult {
        val metadata = remote.metadata(serverId, installationId)
        if (metadata.evidenceId != serverId || metadata.sha256 != row.sha256 ||
            metadata.sizeBytes != row.sizeBytes) {
            move(row, actorId, installationId, LocalEvidenceStates.REJECTED, error = "METADATA_MISMATCH")
            return ReplayResult.REVIEW
        }
        return when (metadata.storageState) {
            "READY" -> {
                move(row, actorId, installationId, LocalEvidenceStates.READY,
                    finalizedId = serverId, error = null)
                ReplayResult.READY
            }
            "REJECTED" -> {
                move(row, actorId, installationId, LocalEvidenceStates.REJECTED,
                    finalizedId = serverId, error = "EVIDENCE_REJECTED")
                ReplayResult.REVIEW
            }
            else -> ReplayResult.QUARANTINED // no automatic re-upload, no READY claim
        }
    }

    private suspend fun move(
        row: LocalEvidenceEntity,
        actorId: String,
        installationId: String,
        state: String,
        serverId: String? = row.serverEvidenceId,
        sessionId: String? = row.uploadSessionId,
        finalizedId: String? = row.finalizedServerEvidenceId,
        error: String? = null,
    ): LocalEvidenceEntity? {
        val changed = db.evidenceQueueDao().casUploadState(
            evidenceId = row.evidenceId,
            actorId = actorId,
            installationId = installationId,
            expectedState = row.localState,
            newState = state,
            serverEvidenceId = serverId,
            uploadSessionId = sessionId,
            finalizedServerId = finalizedId,
            errorCode = error,
            atEpochMs = nowEpochMs(),
        )
        return if (changed == 1) db.evidenceQueueDao().get(row.evidenceId) else null
    }

    private fun finalizeOperationId(original: String): String {
        val first = original.first().digitToInt(16)
        return (first xor 8).toString(16) + original.drop(1)
    }

    private fun failureCode(error: Throwable): String = when (error) {
        is HiltechApiException -> error.code
        is EvidenceBinaryUploadException -> "BINARY_UPLOAD_FAILED"
        else -> "EVIDENCE_LOCAL_OR_POLICY_ERROR"
    }

    private enum class ReplayResult { READY, QUARANTINED, REVIEW, RETRY }
}
