package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import com.hiltech.shared.core.evidence.FinalizeEvidenceRequestDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadRequestDto

data class UploadedServiceSupplierQuote(
    val source:EvidenceMetadataDto,
    val recorded:ServiceSupplierQuoteReceiptDto,
)

/**
 * A single real buyer action: establish supplier-specific private quote slot,
 * upload the actual file, finalize it with canonical Evidence, then record
 * supplier's quote with that exact evidence. Never fake uploaded bytes or
 * price. No supplier award, automatic email or payment.
 */
class ServiceSupplierQuoteUploader(
    private val sourcing:ServiceSupplierSourcingApiClient,
    private val evidence:EvidenceApiClient,
) {
    suspend fun uploadAndRecord(
        rfqId:String,
        supplierOrganizationId:String,
        installationId:String,
        supplierQuoteReference:String,
        revisionNumber:Int,
        currency:String,
        serviceAmount:String,
        taxAmount:String,
        serviceTerms:String,
        validUntil:String?,
        contentType:String,
        originalFileName:String,
        fileBytes:ByteArray,
        fileSha256:String,
        capturedAt:String,
        slotOperationId:String,
        reserveOperationId:String,
        finalizeOperationId:String,
        recordOperationId:String,
    ):UploadedServiceSupplierQuote {
        require(fileBytes.isNotEmpty())
        require(contentType in setOf("application/pdf","image/jpeg","image/png","image/webp"))
        require(Regex("^[0-9a-f]{64}$").matches(fileSha256))
        require(revisionNumber>=1)
        val slot=sourcing.documentSlot(
            rfqId,CreateServiceQuoteSlotDto(
                operationId=slotOperationId,supplierOrganizationId=supplierOrganizationId,
            ),installationId,
        )
        require(slot.supplierOrganizationId==supplierOrganizationId)
        require(slot.rfqId==rfqId)
        val reservation=evidence.reserve(
            ReserveEvidenceUploadRequestDto(
                operationId=reserveOperationId,
                targetType="SERVICE_QUOTE_DOCUMENT",targetId=slot.slotId,
                evidenceRequirementKey="SERVICE_QUOTE_BINARY",
                evidenceTypeCode="SERVICE_QUOTE",contentType=contentType,
                originalFileName=originalFileName,sizeBytes=fileBytes.size.toLong(),
                expectedSha256=fileSha256,capturedAt=capturedAt,
            ),installationId,
        )
        require(reservation.evidence.targetType=="SERVICE_QUOTE_DOCUMENT")
        require(reservation.evidence.targetId==slot.slotId)
        require(reservation.evidence.sha256==fileSha256)
        evidence.uploadBytes(reservation.upload,contentType,fileBytes)
        val ready=evidence.finalize(
            reservation.evidence.evidenceId,
            FinalizeEvidenceRequestDto(
                operationId=finalizeOperationId,
                uploadSessionId=reservation.evidence.uploadSessionId,
                expectedSha256=fileSha256,expectedSizeBytes=fileBytes.size.toLong(),
            ),installationId,
        ).evidence
        require(ready.storageState=="READY")
        require(ready.targetType=="SERVICE_QUOTE_DOCUMENT"&&ready.targetId==slot.slotId)
        require(ready.sha256==fileSha256)
        val recorded=sourcing.verifiedQuote(
            rfqId,RecordServiceQuoteDto(
                operationId=recordOperationId,slotId=slot.slotId,
                expectedRevisionNumber=revisionNumber,
                supplierQuoteReference=supplierQuoteReference,
                evidenceId=ready.evidenceId,expectedSha256=fileSha256,
                currency=currency,serviceAmount=serviceAmount,taxAmount=taxAmount,
                serviceTerms=serviceTerms,validUntil=validUntil,
            ),installationId,
        )
        require(recorded.quote.evidenceId==ready.evidenceId)
        return UploadedServiceSupplierQuote(source=ready,recorded=recorded)
    }
}
