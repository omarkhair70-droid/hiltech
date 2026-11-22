package com.hiltech.server.procurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SupplierInvoiceMatchRulesTest {
    private fun f(
        ordered: String = "10",
        accepted: String = "10",
        invoiced: String = "10",
        currency: String = "EGP",
        poCurrency: String = "EGP",
        priceVariance: String = "0",
        taxVariance: String = "0",
        physicalDiscrepancy: Boolean = false,
    ) = SupplierInvoiceMatchFacts(
        ordered.toBigDecimal(), accepted.toBigDecimal(), invoiced.toBigDecimal(),
        poCurrency, currency, priceVariance.toBigDecimal(), taxVariance.toBigDecimal(),
        physicalDiscrepancy,
    )

    @Test fun fullyReceivedAndExactlyInvoicedCanMatch() {
        assertEquals("MATCHED", SupplierInvoiceMatchRules.classify(f()))
        assertEquals("MATCHED", SupplierInvoiceMatchRules.classify(f(
            ordered = "10.000000", accepted = "10.00", invoiced = "10.0",
        )))
    }

    @Test fun overbilledInvoiceIsRetainedForExceptionReviewNotPayable() {
        assertEquals("QUANTITY_MISMATCH", SupplierInvoiceMatchRules.classify(f(invoiced = "12")))
        assertEquals("QUANTITY_MISMATCH", SupplierInvoiceMatchRules.classify(f(
            ordered = "10", accepted = "12", invoiced = "12",
        )))
    }

    @Test fun underbilledInvoiceIsStillNotAutomaticallyMatched() {
        assertEquals("QUANTITY_MISMATCH", SupplierInvoiceMatchRules.classify(f(invoiced = "8")))
    }

    @Test fun fullyInvoicedPartialDeliveryRemainsPartialReview() {
        assertEquals("PARTIAL_RECEIPT", SupplierInvoiceMatchRules.classify(f(
            ordered = "10", accepted = "4", invoiced = "4",
        )))
    }

    @Test fun noPhysicalReceiptCannotBeApproved() {
        assertEquals("MISSING_RECEIPT", SupplierInvoiceMatchRules.classify(f(
            accepted = "0", invoiced = "10",
        )))
    }

    @Test fun discrepancyAlwaysRequiresFinanceAndWarehouseReview() {
        assertEquals("REVIEW_REQUIRED", SupplierInvoiceMatchRules.classify(f(
            physicalDiscrepancy = true,
        )))
    }

    @Test fun currencyPriceAndTaxMismatchesCannotBeApproved() {
        assertEquals("CURRENCY_MISMATCH", SupplierInvoiceMatchRules.classify(f(
            currency = "USD",
        )))
        assertEquals("PRICE_MISMATCH", SupplierInvoiceMatchRules.classify(f(
            priceVariance = "-1.5",
        )))
        assertEquals("TAX_MISMATCH", SupplierInvoiceMatchRules.classify(f(
            taxVariance = "0.01",
        )))
    }
}
