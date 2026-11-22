package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import com.hiltech.shared.core.evidence.FinalizeEvidenceRequestDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadRequestDto

/** Trusted private PDF/image upload before a Finance invoice may be captured. */
data class SupplierInvoiceDocumentUploadInput(
    val purchaseOrderId: String,
    val slotOperationId: String,
    val reserveOperationId: String,
    val finalizeOperationId: String,
    val contentType: String,
    val originalFileName: String?,
    val sha256: String,
    val bytes: ByteArray,
    val capturedAt: String,
)

data class VerifiedSupplierInvoiceDocument(
    val documentSlotId: String,
    val purchaseOrderId: String,
    val evidenceId: String,
    val sha256: String,
    val sizeBytes: Long,
)

class SupplierInvoiceDocumentNotReady(val storageState: String) :
    IllegalStateException("Invoice evidence is not READY: invoice capture prohibited.")

class SupplierInvoiceDocumentUploader(
    private val invoices: SupplierInvoiceFinanceApiClient,
    private val evidence: EvidenceApiClient,
) {
    suspend fun upload(
        input: SupplierInvoiceDocumentUploadInput,
        installationId: String,
    ): VerifiedSupplierInvoiceDocument {
        require(input.bytes.isNotEmpty() && input.bytes.size <= 16 * 1024 * 1024) {
            "Invoice PDF/images must be 1 byte to 16 MiB."
        }
        require(input.contentType in setOf(
            "application/pdf", "image/jpeg", "image/png", "image/webp",
        )) { "Only supported PDF/image invoices can be uploaded." }
        require(Regex("^[0-9a-f]{64}$").matches(input.sha256)) {
            "SHA-256 must be computed from the selected bytes."
        }
        require(setOf(input.slotOperationId, input.reserveOperationId,
            input.finalizeOperationId).size == 3
        ) { "Reserve/upload/finalize require distinct idempotency operations." }
        val slot = invoices.createSlot(
            input.purchaseOrderId, input.slotOperationId, installationId,
        )
        require(slot.purchaseOrderId == input.purchaseOrderId) {
            "Invoice document slot does not match approved PO."
        }
        val reserved = evidence.reserve(
            ReserveEvidenceUploadRequestDto(
                operationId = input.reserveOperationId,
                targetType = "SUPPLIER_INVOICE_DOCUMENT",
                targetId = slot.slotId,
                evidenceRequirementKey = "SUPPLIER_INVOICE_BINARY",
                evidenceTypeCode = "SUPPLIER_INVOICE",
                contentType = input.contentType,
                originalFileName = input.originalFileName,
                sizeBytes = input.bytes.size.toLong(),
                expectedSha256 = input.sha256,
                capturedAt = input.capturedAt,
            ), installationId,
        )
        verifyScope(reserved.evidence, slot.slotId, input.sha256, input.bytes.size.toLong())
        if (reserved.evidence.storageState != "RESERVED")
            throw SupplierInvoiceDocumentNotReady(reserved.evidence.storageState)
        evidence.uploadBytes(reserved.upload, input.contentType, input.bytes)
        val finalized = evidence.finalize(
            reserved.evidence.evidenceId,
            FinalizeEvidenceRequestDto(
                operationId = input.finalizeOperationId,
                uploadSessionId = reserved.evidence.uploadSessionId,
                expectedSha256 = input.sha256,
                expectedSizeBytes = input.bytes.size.toLong(),
            ), installationId,
        )
        verifyScope(finalized.evidence, slot.slotId, input.sha256, input.bytes.size.toLong())
        if (finalized.evidence.storageState != "READY")
            throw SupplierInvoiceDocumentNotReady(finalized.evidence.storageState)
        return VerifiedSupplierInvoiceDocument(
            slot.slotId, input.purchaseOrderId, finalized.evidence.evidenceId,
            finalized.evidence.sha256, finalized.evidence.sizeBytes,
        )
    }

    private fun verifyScope(e: EvidenceMetadataDto, slotId: String, sha: String, size: Long) {
        require(e.targetType == "SUPPLIER_INVOICE_DOCUMENT" &&
            e.targetId == slotId &&
            e.evidenceRequirementKey == "SUPPLIER_INVOICE_BINARY" &&
            e.evidenceTypeCode == "SUPPLIER_INVOICE" &&
            e.classificationCode == "HIGHLY_RESTRICTED" &&
            e.clientVisibilityMode == "INTERNAL_ONLY" &&
            e.sha256 == sha && e.sizeBytes == size
        ) { "Private invoice upload is not bound to canonical Evidence target." }
    }
}
