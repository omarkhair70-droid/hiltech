package com.hiltech.server.procurement

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Buyer-only quotation evidence; no quote is public to vendors or project requesters.
 * Comparisons never infer supplier selection, money approval, technical compliance
 * or an FX conversion. Stored revisions remain immutable.
 */
data class SupplierQuoteEvidence(
    val id: UUID,
    val rfqId: UUID,
    val supplierOrganizationId: UUID,
    val supplierQuoteReference: String,
    val revisionNumber: Int,
    val currency: String,
    val quantity: BigDecimal,
    val unitCode: String,
    val unitPrice: BigDecimal,
    val taxAmount: BigDecimal,
    val deliveryDays: Int?,
    val validUntil: LocalDate?,
    val deliveryTerms: String?,
    val paymentTerms: String?,
    val warrantyTerms: String?,
    val sourceEvidenceRef: String,
    val sourceSha256: String,
    val technicalCompliance: String = "NOT_ASSESSED",
)

data class SupplierQuoteComparisonRow(
    val quoteId: UUID,
    val supplierOrganizationId: UUID,
    val revisionNumber: Int,
    val quoteReference: String,
    val currency: String,
    val quantity: String,
    val unitCode: String,
    val unitPrice: String,
    val taxAmount: String,
    val total: String,
    val deliveryDays: Int?,
    val validUntil: LocalDate?,
    val evidenceRef: String,
    val evidenceSha256: String,
    val technicalCompliance: String,
    val issues: List<String>,
)

data class SupplierQuoteComparison(
    val expectedQuantity: String,
    val expectedUnitCode: String,
    val evaluatedAt: LocalDate,
    val latestBySupplier: List<SupplierQuoteComparisonRow>,
    val currencies: List<String>,
    val allQuotesDirectlyComparable: Boolean,
    val supplierSelected: Boolean = false,
    val purchaseAuthorized: Boolean = false,
)

/** Pure deterministic comparison: latest revision per supplier, never by smallest price. */
object SupplierQuoteComparator {
    fun compare(
        expectedQuantity: BigDecimal,
        expectedUnitCode: String,
        quotes: List<SupplierQuoteEvidence>,
        date: LocalDate,
    ): SupplierQuoteComparison {
        require(expectedQuantity.signum() > 0)
        require(expectedUnitCode.isNotBlank())
        val current = quotes.groupBy { it.supplierOrganizationId }
            .values.map { versions ->
                versions.maxWith(compareBy<SupplierQuoteEvidence> { it.revisionNumber }
                    .thenBy { it.id.toString() })
            }
            .sortedWith(compareBy<SupplierQuoteEvidence> { it.supplierOrganizationId.toString() })
        val currencies = current.map { it.currency }.distinct().sorted()
        val rows = current.map { quote ->
            val issues = buildList {
                if (!Regex("[A-Z]{3}").matches(quote.currency)) {
                    add("INVALID_CURRENCY")
                }
                if (currencies.size > 1) add("CURRENCY_DIFFERS_NO_FX_CONVERSION")
                if (quote.quantity.compareTo(expectedQuantity) != 0) add("QUANTITY_DIFFERS")
                if (quote.unitCode != expectedUnitCode) add("UNIT_DIFFERS")
                if (quote.validUntil == null) add("VALIDITY_UNCONFIRMED")
                else if (quote.validUntil.isBefore(date)) add("QUOTE_EXPIRED")
                if (quote.technicalCompliance != "COMPLIANT") add("TECHNICAL_COMPLIANCE_UNCONFIRMED")
            }
            SupplierQuoteComparisonRow(
                quoteId = quote.id,
                supplierOrganizationId = quote.supplierOrganizationId,
                revisionNumber = quote.revisionNumber,
                quoteReference = quote.supplierQuoteReference,
                currency = quote.currency,
                quantity = quote.quantity.toPlainString(),
                unitCode = quote.unitCode,
                unitPrice = quote.unitPrice.toPlainString(),
                taxAmount = quote.taxAmount.toPlainString(),
                total = quote.unitPrice.multiply(quote.quantity).add(quote.taxAmount).toPlainString(),
                deliveryDays = quote.deliveryDays,
                validUntil = quote.validUntil,
                evidenceRef = quote.sourceEvidenceRef,
                evidenceSha256 = quote.sourceSha256,
                technicalCompliance = quote.technicalCompliance,
                issues = issues,
            )
        }
        return SupplierQuoteComparison(
            expectedQuantity.toPlainString(), expectedUnitCode, date, rows, currencies,
            allQuotesDirectlyComparable = rows.isNotEmpty() && rows.all { it.issues.isEmpty() },
        )
    }
}
