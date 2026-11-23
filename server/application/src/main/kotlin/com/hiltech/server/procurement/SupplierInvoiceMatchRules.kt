package com.hiltech.server.procurement

import java.math.BigDecimal

/**
 * Source-bound review classification for S05. Invalid supplier invoices must
 * be retained as evidence and reviewed, never silently discarded or paid.
 */
internal data class SupplierInvoiceMatchFacts(
    val orderedQuantity: BigDecimal,
    val acceptedQuantity: BigDecimal,
    val invoicedQuantity: BigDecimal,
    val poCurrency: String,
    val invoiceCurrency: String,
    val priceVariance: BigDecimal,
    val taxVariance: BigDecimal,
    val unresolvedPhysicalDiscrepancy: Boolean,
)

internal object SupplierInvoiceMatchRules {
    fun classify(facts: SupplierInvoiceMatchFacts): String = with(facts) {
        when {
            unresolvedPhysicalDiscrepancy -> "REVIEW_REQUIRED"
            invoiceCurrency != poCurrency -> "CURRENCY_MISMATCH"
            priceVariance.compareTo(BigDecimal.ZERO) != 0 -> "PRICE_MISMATCH"
            taxVariance.compareTo(BigDecimal.ZERO) != 0 -> "TAX_MISMATCH"
            acceptedQuantity.signum() == 0 -> "MISSING_RECEIPT"
            orderedQuantity.signum() <= 0 ||
                acceptedQuantity.compareTo(orderedQuantity) > 0 ||
                invoicedQuantity.compareTo(orderedQuantity) > 0 ||
                acceptedQuantity.compareTo(invoicedQuantity) != 0 -> "QUANTITY_MISMATCH"
            acceptedQuantity.compareTo(orderedQuantity) < 0 -> "PARTIAL_RECEIPT"
            else -> "MATCHED"
        }
    }
}
