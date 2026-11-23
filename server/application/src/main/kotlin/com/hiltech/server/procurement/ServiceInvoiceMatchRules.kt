package com.hiltech.server.procurement

import java.math.BigDecimal

data class ServiceInvoiceMatchFacts(
    val latestDeliveryOutcome:String?,
    val approvedCurrency:String,
    val invoiceCurrency:String,
    val approvedTotal:BigDecimal,
    val invoiceTotal:BigDecimal,
)

/** Two-way service match: approved order and accepted deliverable vs invoice.
 * Never treats Warehouse physical stock as proof that a service happened. */
object ServiceInvoiceMatchRules {
    fun classify(facts:ServiceInvoiceMatchFacts):String=when {
        facts.latestDeliveryOutcome==null -> "NO_ACCEPTANCE"
        facts.latestDeliveryOutcome!="ACCEPTED" -> "DELIVERY_REVIEW_REQUIRED"
        facts.approvedCurrency!=facts.invoiceCurrency -> "CURRENCY_MISMATCH"
        facts.approvedTotal.compareTo(facts.invoiceTotal)!=0 -> "AMOUNT_MISMATCH"
        else -> "MATCHED"
    }
}
