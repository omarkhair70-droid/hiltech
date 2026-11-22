package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import com.hiltech.shared.core.evidence.FinalizeEvidenceRequestDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadRequestDto

/**
 * Online-only restricted quotation document pipeline shared by Android/Windows.
 *
 * The host supplies selected file bytes and a SHA-256 digest. The private
 * storage finalize step verifies the real bytes independently. Nothing is
 * queued, broadcast to suppliers, attached to an invoice or awarded here.
 */
data class SupplierQuoteDocumentUploadInput(
    val rfqId: String,
    val supplierOrganizationId: String,
    val slotOperationId: String,
    val reserveOperationId: String,
    val finalizeOperationId: String,
    val contentType: String,
    val originalFileName: String?,
    val sha256: String,
    val bytes: ByteArray,
    val capturedAt: String,
)

data class VerifiedSupplierQuoteDocument(
    val documentSlotId: String,
    val evidenceId: String,
    val evidenceReference: String,
    val sha256: String,
    val sizeBytes: Long,
)

class SupplierQuoteDocumentNotReady(val storageState: String) :
    IllegalStateException("Quotation evidence has not reached READY; quote submission is prohibited.")

class SupplierQuoteDocumentUploader(
    private val sourcing: SupplierSourcingApiClient,
    private val evidence: EvidenceApiClient,
) {
    suspend fun upload(
        input: SupplierQuoteDocumentUploadInput,
        installationId: String,
    ): VerifiedSupplierQuoteDocument {
        require(input.bytes.isNotEmpty() && input.bytes.size <= 16 * 1024 * 1024) {
            "Quotation files must be between 1 byte and 16 MiB."
        }
        require(input.contentType in setOf(
            "application/pdf", "image/jpeg", "image/png", "image/webp",
        )) { "Only supported PDF or image quotation documents can be uploaded." }
        require(Regex("^[0-9a-f]{64}$").matches(input.sha256)) {
            "A valid lowercase SHA-256 must be computed from the selected file."
        }
        require(setOf(
            input.slotOperationId,
            input.reserveOperationId,
            input.finalizeOperationId,
        ).size == 3) { "Each stage requires a separate idempotency operation." }

        val slot = sourcing.createQuoteDocumentSlot(
            input.rfqId,
            CreateSupplierQuoteDocumentSlotDto(
                input.slotOperationId, input.supplierOrganizationId,
            ),
            installationId,
        )
        require(slot.rfqId == input.rfqId &&
            slot.supplierOrganizationId == input.supplierOrganizationId
        ) { "Quotation document slot was not bound to the requested RFQ and supplier." }

        val reserved = evidence.reserve(
            ReserveEvidenceUploadRequestDto(
                operationId = input.reserveOperationId,
                targetType = "SUPPLIER_QUOTE_DOCUMENT",
                targetId = slot.documentSlotId,
                evidenceRequirementKey = "SUPPLIER_QUOTE_BINARY",
                evidenceTypeCode = "SUPPLIER_QUOTE",
                contentType = input.contentType,
                originalFileName = input.originalFileName,
                sizeBytes = input.bytes.size.toLong(),
                expectedSha256 = input.sha256,
                capturedAt = input.capturedAt,
            ),
            installationId,
        )
        verifyScope(reserved.evidence, slot.documentSlotId, input.sha256, input.bytes.size.toLong())
        if (reserved.evidence.storageState != "RESERVED") {
            throw SupplierQuoteDocumentNotReady(reserved.evidence.storageState)
        }
        evidence.uploadBytes(reserved.upload, input.contentType, input.bytes)
        val finalized = evidence.finalize(
            reserved.evidence.evidenceId,
            FinalizeEvidenceRequestDto(
                operationId = input.finalizeOperationId,
                uploadSessionId = reserved.evidence.uploadSessionId,
                expectedSha256 = input.sha256,
                expectedSizeBytes = input.bytes.size.toLong(),
            ),
            installationId,
        )
        verifyScope(finalized.evidence, slot.documentSlotId, input.sha256, input.bytes.size.toLong())
        if (finalized.evidence.storageState != "READY") {
            throw SupplierQuoteDocumentNotReady(finalized.evidence.storageState)
        }
        return VerifiedSupplierQuoteDocument(
            documentSlotId = slot.documentSlotId,
            evidenceId = finalized.evidence.evidenceId,
            evidenceReference = "evidence:" + finalized.evidence.evidenceId,
            sha256 = finalized.evidence.sha256,
            sizeBytes = finalized.evidence.sizeBytes,
        )
    }

    private fun verifyScope(
        metadata: EvidenceMetadataDto,
        slotId: String,
        sha256: String,
        sizeBytes: Long,
    ) {
        require(metadata.targetType == "SUPPLIER_QUOTE_DOCUMENT" &&
            metadata.targetId == slotId &&
            metadata.evidenceRequirementKey == "SUPPLIER_QUOTE_BINARY" &&
            metadata.evidenceTypeCode == "SUPPLIER_QUOTE" &&
            metadata.classificationCode == "HIGHLY_RESTRICTED" &&
            metadata.clientVisibilityMode == "INTERNAL_ONLY" &&
            metadata.sha256 == sha256 &&
            metadata.sizeBytes == sizeBytes
        ) { "The canonical Evidence response does not match this private quotation." }
    }
}
