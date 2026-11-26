package com.hiltech.shared.core.procurement

import kotlinx.serialization.Serializable

/** Restricted buyer-only contracts. These types do not grant read or write permissions. */
@Serializable
data class OpenSupplierRfqDto(
    val operationId: String,
    val expectedRequirementVersion: Long,
)

@Serializable
data class SourcingEligibleNeedDto(
    val requirementId: String,
    val technicalVersion: Long,
    val stockItemId: String,
    val requestedQuantity: String,
    val unitCode: String,
    val technicalState: String,
    val sourceCurrent: Boolean,
    val sourcingRoundExists: Boolean,
    val stockItemName: String? = null,
    val stockItemCode: String? = null,
)

@Serializable
data class SourcingSupplierCandidateDto(
    val supplierOrganizationId: String,
    val displayName: String,
    val relationshipRevision: Int,
    val organizationCode: String = "",
)

internal fun SourcingSupplierCandidateDto.displayLabel(): String =
    displayName + organizationCode.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()

@Serializable
data class SupplierRfqDto(
    val id: String,
    val requirementId: String,
    val capturedRequirementVersion: Long,
    val capturedQuantity: String,
    val unitCode: String,
    val sourceCurrent: Boolean,
    val quoteCount: Int,
    val purchaseAuthorized: Boolean = false,
)

@Serializable
data class SupplierRfqReceiptDto(val rfq: SupplierRfqDto, val replayed: Boolean)

@Serializable
data class CreateSupplierQuoteDocumentSlotDto(
    val operationId: String,
    val supplierOrganizationId: String,
)

@Serializable
data class SupplierQuoteDocumentSlotReceiptDto(
    val documentSlotId: String,
    val rfqId: String,
    val supplierOrganizationId: String,
    val replayed: Boolean,
)

@Serializable
data class RecordSupplierQuoteDto(
    val operationId: String,
    val supplierOrganizationId: String,
    val expectedRevisionNumber: Int,
    val supplierQuoteReference: String,
    val currency: String,
    val quotedQuantity: String,
    val quotedUnitCode: String,
    val unitPrice: String,
    val taxAmount: String,
    val deliveryDays: Int? = null,
    val validUntil: String? = null,
    val deliveryTerms: String? = null,
    val paymentTerms: String? = null,
    val warrantyTerms: String? = null,
    val sourceEvidenceRef: String,
    val sourceSha256: String,
)

@Serializable
data class SupplierQuoteReceiptDto(
    val quoteId: String,
    val rfqId: String,
    val revisionNumber: Int,
    val replayed: Boolean,
)

/** A separate technical-review right is enforced on the server. */
@Serializable
data class RecordSupplierQuoteTechnicalAssessmentDto(
    val operationId: String,
    val expectedReviewRevision: Int,
    val decision: String,
    val justification: String,
)

@Serializable
data class SupplierQuoteTechnicalAssessmentReceiptDto(
    val id: String,
    val quoteEvidenceId: String,
    val reviewRevision: Int,
    val decision: String,
    val replayed: Boolean,
)

@Serializable
data class SupplierQuoteComparisonRowDto(
    val quoteId: String,
    val supplierOrganizationId: String,
    val revisionNumber: Int,
    val quoteReference: String,
    val currency: String,
    val quantity: String,
    val unitCode: String,
    val unitPrice: String,
    val taxAmount: String,
    val total: String,
    val deliveryDays: Int? = null,
    val validUntil: String? = null,
    val evidenceRef: String,
    val evidenceSha256: String,
    val technicalCompliance: String,
    val issues: List<String>,
    val supplierName: String? = null,
    val supplierCode: String? = null,
)

@Serializable
data class SupplierQuoteComparisonDto(
    val expectedQuantity: String,
    val expectedUnitCode: String,
    val evaluatedAt: String,
    val latestBySupplier: List<SupplierQuoteComparisonRowDto>,
    val currencies: List<String>,
    val allQuotesDirectlyComparable: Boolean,
    val supplierSelected: Boolean = false,
    val purchaseAuthorized: Boolean = false,
)

@Serializable
data class SupplierRfqComparisonDto(
    val rfq: SupplierRfqDto,
    val comparison: SupplierQuoteComparisonDto,
    val allRevisionCount: Int,
    val recordedSupplierCount: Int,
)
