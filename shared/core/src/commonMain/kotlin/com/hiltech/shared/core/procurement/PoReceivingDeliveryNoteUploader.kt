package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import com.hiltech.shared.core.evidence.FinalizeEvidenceRequestDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadRequestDto

/** Restricted delivery note for exactly one actual canonical PO receiving. */
data class PoDeliveryNoteFile(
    val contentType: String,
    val originalFileName: String,
    val bytes: ByteArray,
    val sha256: String,
    val capturedAt: String,
)
data class PoDeliveryNoteUploadRequest(
    val receivingRecordId: String,
    val reserveOperationId: String,
    val finalizeOperationId: String,
    val file: PoDeliveryNoteFile,
)
data class VerifiedPoDeliveryNote(
    val receivingRecordId: String,
    val evidenceId: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Uses the existing signed-upload, server-side security scan and READY gate. */
class PoReceivingDeliveryNoteUploader(private val evidence: EvidenceApiClient) {
    suspend fun upload(
        request: PoDeliveryNoteUploadRequest,
        installationId: String,
    ): VerifiedPoDeliveryNote {
        val f = request.file
        require(f.bytes.isNotEmpty() && f.bytes.size <= 16 * 1024 * 1024)
        require(f.contentType in setOf(
            "application/pdf", "image/jpeg", "image/png", "image/webp",
        ))
        require(Regex("^[0-9a-f]{64}$").matches(f.sha256))
        require(request.reserveOperationId != request.finalizeOperationId)
        val reserved = evidence.reserve(
            ReserveEvidenceUploadRequestDto(
                operationId = request.reserveOperationId,
                targetType = "PO_RECEIVING_DELIVERY_NOTE",
                targetId = request.receivingRecordId,
                evidenceRequirementKey = "PO_DELIVERY_NOTE_BINARY",
                evidenceTypeCode = "PO_DELIVERY_NOTE",
                contentType = f.contentType,
                originalFileName = f.originalFileName,
                sizeBytes = f.bytes.size.toLong(),
                expectedSha256 = f.sha256,
                capturedAt = f.capturedAt,
            ), installationId,
        )
        verify(reserved.evidence, request.receivingRecordId, f)
        check(reserved.evidence.storageState == "RESERVED") {
            "The delivery note reservation cannot be uploaded in its current state."
        }
        evidence.uploadBytes(reserved.upload, f.contentType, f.bytes)
        val finalized = evidence.finalize(
            reserved.evidence.evidenceId,
            FinalizeEvidenceRequestDto(
                operationId = request.finalizeOperationId,
                uploadSessionId = reserved.evidence.uploadSessionId,
                expectedSha256 = f.sha256,
                expectedSizeBytes = f.bytes.size.toLong(),
            ), installationId,
        )
        verify(finalized.evidence, request.receivingRecordId, f)
        check(finalized.evidence.storageState == "READY") {
            "Delivery note is not verified READY; Warehouse receiving cannot close."
        }
        return VerifiedPoDeliveryNote(
            request.receivingRecordId, finalized.evidence.evidenceId,
            finalized.evidence.sha256, finalized.evidence.sizeBytes,
        )
    }

    private fun verify(meta: EvidenceMetadataDto, recordId: String, file: PoDeliveryNoteFile) {
        require(meta.targetType == "PO_RECEIVING_DELIVERY_NOTE" &&
            meta.targetId == recordId &&
            meta.evidenceRequirementKey == "PO_DELIVERY_NOTE_BINARY" &&
            meta.evidenceTypeCode == "PO_DELIVERY_NOTE" &&
            meta.classificationCode == "HIGHLY_RESTRICTED" &&
            meta.clientVisibilityMode == "INTERNAL_ONLY" &&
            meta.sha256 == file.sha256 &&
            meta.sizeBytes == file.bytes.size.toLong()
        ) { "Delivery note is not bound to the authorized PO receiving." }
    }
}
