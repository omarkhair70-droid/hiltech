package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import com.hiltech.shared.core.evidence.FinalizeEvidenceRequestDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadRequestDto
import kotlinx.serialization.Serializable

@Serializable
data class SupplierTechnicalUploadIntent(
    val slotOperationId: String, val reserveOperationId: String, val finalizeOperationId: String,
    val organizationId: String, val quoteId: String, val quoteTitle: String, val title: String,
    val filename: String, val contentType: String, val sizeBytes: Long, val sha256: String, val capturedAt: String,
    val slotId: String? = null, val evidenceId: String? = null, val uploadSessionId: String? = null, val uploaded: Boolean = false,
)

fun supplierTechnicalFileValid(file: ServiceSelectedFile): Boolean = file.contentType in setOf("application/pdf", "image/png", "image/jpeg") &&
    file.bytes.size in 1..16_777_216 && technicalSha(file.sha256) && file.originalFileName.isNotBlank() && file.capturedAt.isNotBlank()

internal fun supplierTechnicalFileMatches(intent: SupplierTechnicalUploadIntent, file: ServiceSelectedFile): Boolean =
    supplierTechnicalFileValid(file) && file.sha256 == intent.sha256 && file.bytes.size.toLong() == intent.sizeBytes && file.contentType == intent.contentType

/** Reuses canonical reservation/PUT/finalize, retaining each exact operation and a successful PUT checkpoint. */
class SupplierTechnicalUploadCoordinator(private val client: SupplierTechnicalApiClient, private val evidence: EvidenceApiClient) {
    suspend fun upload(initial: SupplierTechnicalUploadIntent, file: ServiceSelectedFile?, installation: String,
        save: (SupplierTechnicalUploadIntent) -> Unit): EvidenceMetadataDto {
        var intent = initial
        if (intent.slotId == null) {
            val slot = client.createDocument(intent.organizationId, intent.quoteId, SupplierTechnicalDocumentRequest(intent.slotOperationId, intent.title), installation)
            intent = intent.copy(slotId = slot.slotId); save(intent)
        }
        if (intent.evidenceId != null) {
            val current = evidence.metadata(requireNotNull(intent.evidenceId), installation)
            verify(current, intent)
            if (current.storageState == "READY") return current
        }
        if (!intent.uploaded) {
            require(file != null && supplierTechnicalFileMatches(intent, file)) { "SUPPLIER_TECHNICAL_RESELECT_SAME_FILE" }
            val reservation = evidence.reserve(ReserveEvidenceUploadRequestDto(
                operationId = intent.reserveOperationId, targetType = "SUPPLIER_TECHNICAL_DOCUMENT", targetId = requireNotNull(intent.slotId),
                evidenceRequirementKey = "SUPPLIER_TECHNICAL_SOURCE", evidenceTypeCode = "SUPPLIER_TECHNICAL_SOURCE", contentType = intent.contentType,
                originalFileName = intent.filename, sizeBytes = intent.sizeBytes, expectedSha256 = intent.sha256, capturedAt = intent.capturedAt,
            ), installation)
            verify(reservation.evidence, intent)
            intent = intent.copy(evidenceId = reservation.evidence.evidenceId, uploadSessionId = reservation.evidence.uploadSessionId); save(intent)
            if (reservation.evidence.storageState == "READY") return reservation.evidence
            check(reservation.upload.expectedSizeBytes == intent.sizeBytes)
            evidence.uploadBytes(reservation.upload, intent.contentType, file.bytes)
            intent = intent.copy(uploaded = true); save(intent)
        }
        val finalized = evidence.finalize(requireNotNull(intent.evidenceId), FinalizeEvidenceRequestDto(
            intent.finalizeOperationId, requireNotNull(intent.uploadSessionId), intent.sha256, intent.sizeBytes), installation)
        verify(finalized.evidence, intent); check(finalized.evidence.storageState == "READY")
        return finalized.evidence
    }

    private fun verify(value: EvidenceMetadataDto, intent: SupplierTechnicalUploadIntent) {
        check(value.targetType == "SUPPLIER_TECHNICAL_DOCUMENT" && value.targetId == intent.slotId && value.workOrderId == null && value.evidencePolicyId == null)
        check(value.evidenceTypeCode == "SUPPLIER_TECHNICAL_SOURCE" && value.evidenceRequirementKey == value.evidenceTypeCode)
        check(value.contentType == intent.contentType && value.sizeBytes == intent.sizeBytes && value.sha256 == intent.sha256)
        check(value.classificationCode == "HIGHLY_RESTRICTED" && value.clientVisibilityMode == "INTERNAL_ONLY")
        if (intent.evidenceId != null) check(value.evidenceId == intent.evidenceId && value.uploadSessionId == intent.uploadSessionId)
    }
}
