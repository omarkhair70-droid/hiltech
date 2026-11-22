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
)

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
