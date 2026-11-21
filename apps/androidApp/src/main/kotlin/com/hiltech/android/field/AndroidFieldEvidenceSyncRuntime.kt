package com.hiltech.android.field

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import com.hiltech.shared.core.evidence.EvidenceUploadTargetDto
import com.hiltech.shared.core.evidence.FinalizeEvidenceRequestDto
import com.hiltech.shared.core.evidence.FinalizeEvidenceResponseDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadRequestDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadResponseDto
import com.hiltech.shared.core.field.FieldEvidenceBytes
import com.hiltech.shared.core.field.FieldEvidenceRemote
import com.hiltech.shared.core.field.FieldEvidenceReplaySummary
import com.hiltech.shared.core.field.FieldEvidenceUploadCoordinator
import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.work.FieldAssignedWorkApiClient
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun interface FieldEvidenceOnlineIdentity {
    suspend fun currentVerifiedActorId(): String?
}

sealed interface AndroidEvidenceSyncOutcome {
    data class Completed(val summary: FieldEvidenceReplaySummary) : AndroidEvidenceSyncOutcome
    data object Retry : AndroidEvidenceSyncOutcome
    data object ReauthenticationRequired : AndroidEvidenceSyncOutcome
}

class AndroidFieldEvidenceSyncRuntime(
    private val db: HiltechLocalDatabase,
    private val files: AndroidFieldEvidenceFiles,
    private val assignedWork: FieldAssignedWorkApiClient,
    private val evidence: EvidenceApiClient,
    private val onlineIdentity: FieldEvidenceOnlineIdentity,
    private val installationId: String,
) {
    suspend fun runOnce(): AndroidEvidenceSyncOutcome {
        val actor = try {
            onlineIdentity.currentVerifiedActorId()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return if (failure is HiltechApiException && failure.httpStatus in setOf(401, 403, 404)) {
                AndroidEvidenceSyncOutcome.ReauthenticationRequired
            } else AndroidEvidenceSyncOutcome.Retry
        }?.takeIf { it.isNotBlank() }
            ?: return AndroidEvidenceSyncOutcome.ReauthenticationRequired

        val remote = object : FieldEvidenceRemote {
            override suspend fun currentBundle(workOrderId: String, installationId: String): TechnicianJobBundleDto =
                assignedWork.jobBundle(workOrderId, installationId)
            override suspend fun reserve(request: ReserveEvidenceUploadRequestDto, installationId: String): ReserveEvidenceUploadResponseDto =
                evidence.reserve(request, installationId)
            override suspend fun upload(target: EvidenceUploadTargetDto, contentType: String, bytes: ByteArray) =
                evidence.uploadBytes(target, contentType, bytes)
            override suspend fun finalize(
                evidenceId: String, uploadSessionId: String, operationId: String,
                sha256: String, sizeBytes: Long, installationId: String,
            ): FinalizeEvidenceResponseDto = evidence.finalize(
                evidenceId, FinalizeEvidenceRequestDto(
                    operationId = operationId,
                    uploadSessionId = uploadSessionId,
                    expectedSha256 = sha256,
                    expectedSizeBytes = sizeBytes,
                ), installationId,
            )
            override suspend fun metadata(evidenceId: String, installationId: String): EvidenceMetadataDto =
                evidence.metadata(evidenceId, installationId)
        }
        val summary = FieldEvidenceUploadCoordinator(
            db = db, remote = remote,
            bytes = FieldEvidenceBytes { files.readAndVerify(it) },
            nowEpochMs = System::currentTimeMillis,
            formatEpochUtc = { epoch ->
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(epoch))
            },
        ).replayOnce(actor, installationId)
        return when {
            summary.reauthenticationRequired -> AndroidEvidenceSyncOutcome.ReauthenticationRequired
            summary.retryable > 0 -> AndroidEvidenceSyncOutcome.Retry
            else -> AndroidEvidenceSyncOutcome.Completed(summary)
        }
    }
}
