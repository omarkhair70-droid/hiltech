package com.hiltech.shared.core.procurement

import kotlinx.serialization.Serializable

/** Deliberately omits prices, currencies, payment terms and priced document references. */
@Serializable
data class SupplierTechnicalQuoteDto(
    val quoteId: String, val organizationId: String, val rfqId: String, val supplierId: String,
    val supplierName: String, val supplierCode: String, val quoteReference: String, val quoteRevision: Int,
    val itemId: String, val itemName: String, val itemCode: String, val requirementId: String,
    val needVersion: Long, val requestedQuantity: String, val requestedUnit: String, val offeredQuantity: String, val offeredUnit: String,
    val sourceType: String, val needDescription: String, val sourceLabel: String, val sourceCurrent: Boolean, val latestQuote: Boolean,
)
@Serializable
data class SupplierTechnicalPacketFields(
    val manufacturer: String = "", val model: String = "", val offeredSpecification: String = "", val deviations: String = "",
    val supplierSource: String = "", val sourceReference: String = "", val sourceDate: String? = null,
    val evidenceIds: List<String> = emptyList(), val nonFinancialConfirmed: Boolean = false, val correctionReason: String = "",
)
@Serializable
data class SupplierTechnicalPublishRequest(val operationId: String, val expectedLatestRevision: Int, val details: SupplierTechnicalPacketFields)
@Serializable
data class SupplierTechnicalDocumentRequest(val operationId: String, val title: String)
@Serializable
data class SupplierTechnicalDocumentSlotDto(val operationId: String, val organizationId: String, val quoteId: String, val slotId: String, val title: String, val replayed: Boolean)
@Serializable
data class SupplierTechnicalEvidenceDto(val evidenceId: String, val title: String, val contentType: String, val sizeBytes: Long, val sha256: String)
@Serializable
data class SupplierTechnicalPacketDto(
    val packetId: String, val organizationId: String, val quoteId: String, val packetRevision: Int,
    val needSnapshot: SupplierTechnicalQuoteDto, val details: SupplierTechnicalPacketFields, val sourceHash: String,
    val preparedByName: String, val preparedAt: String, val documents: List<SupplierTechnicalEvidenceDto>, val displayedEvidenceIds: List<String>,
    val sourceCurrent: Boolean,
)
@Serializable
data class SupplierTechnicalAssessmentHistoryDto(
    val assessmentId: String, val reviewRevision: Int, val decision: String, val justification: String,
    val reviewerName: String, val reviewedAt: String, val technicalPacketId: String?, val packetRevision: Int?, val packetReviewed: Boolean,
)
@Serializable
data class SupplierTechnicalDetailDto(
    val quote: SupplierTechnicalQuoteDto, val canPrepare: Boolean, val canReview: Boolean, val packets: List<SupplierTechnicalPacketDto>,
    val availableDocuments: List<SupplierTechnicalEvidenceDto>, val history: List<SupplierTechnicalAssessmentHistoryDto>,
    val latestAssessmentRevision: Int, val effectiveDecision: String,
)
@Serializable
data class SupplierTechnicalQueueItemDto(val quote: SupplierTechnicalQuoteDto, val packetRevision: Int, val effectiveDecision: String)
@Serializable
data class SupplierTechnicalQueueDto(val organizationId: String, val canPrepare: Boolean, val canReview: Boolean, val items: List<SupplierTechnicalQueueItemDto>, val nextOffset: Int?)
@Serializable
data class SupplierTechnicalPacketReceiptDto(val operationId: String, val organizationId: String, val quoteId: String, val packetId: String, val packetRevision: Int, val result: String, val replayed: Boolean)
@Serializable
data class SupplierTechnicalEvidencePageDto(
    val organizationId: String, val quoteId: String, val packetId: String?, val evidenceId: String, val title: String,
    val originalContentType: String, val originalSha256: String, val pageNumber: Int, val pageCount: Int, val imageBase64: String, val viewReceiptId: String?,
)
@Serializable
data class SupplierTechnicalDisplayDto(val organizationId: String, val quoteId: String, val packetId: String, val evidenceId: String, val viewReceiptId: String, val confirmed: Boolean)
