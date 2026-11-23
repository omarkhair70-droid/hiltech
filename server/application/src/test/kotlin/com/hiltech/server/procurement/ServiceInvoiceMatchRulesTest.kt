package com.hiltech.server.procurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ServiceInvoiceMatchRulesTest {
    private fun facts(delivery:String?="ACCEPTED",orderCurrency:String="EGP",
                      invoiceCurrency:String="EGP",expected:String="1200.00",
                      actual:String="1200.00") = ServiceInvoiceMatchFacts(
        delivery,orderCurrency,invoiceCurrency,BigDecimal(expected),BigDecimal(actual),
    )

    @Test fun noProofMeansNoFinanceHandoff() {
        assertEquals("NO_ACCEPTANCE",ServiceInvoiceMatchRules.classify(facts(delivery=null)))
    }
    @Test fun latestReworkOrRejectedServiceCannotCountAsDelivery() {
        assertEquals("DELIVERY_REVIEW_REQUIRED",
            ServiceInvoiceMatchRules.classify(facts(delivery="NEEDS_REWORK")))
        assertEquals("DELIVERY_REVIEW_REQUIRED",
            ServiceInvoiceMatchRules.classify(facts(delivery="REJECTED")))
    }
    @Test fun currencyMustMatchApprovedOrderExactly() {
        assertEquals("CURRENCY_MISMATCH",
            ServiceInvoiceMatchRules.classify(facts(invoiceCurrency="USD")))
    }
    @Test fun supplierAmountDifferenceIsAuditedMismatchNotSilentlyRoundedOff() {
        assertEquals("AMOUNT_MISMATCH",
            ServiceInvoiceMatchRules.classify(facts(actual="1200.01")))
    }
    @Test fun genuinelyAcceptedServiceAndExactInvoiceBecomeReviewEligible() {
        assertEquals("MATCHED",ServiceInvoiceMatchRules.classify(facts()))
        assertEquals("MATCHED",ServiceInvoiceMatchRules.classify(
            facts(expected="1200.000000",actual="1200.00")))
    }
}
