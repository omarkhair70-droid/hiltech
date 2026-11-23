package com.hiltech.server.finance

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Customer award -> proposed invoice is NOT invoice issuance or AR recognition.
 * Billing milestone, legal tax treatment, due date and amount must be supplied
 * by independently verified company evidence, never inferred from P10 quote.
 */
data class FinanceArInvoiceCandidateInput(
    val organizationId: UUID,
    val customerOrganizationId: UUID,
    val opportunityId: UUID,
    val commercialSourceSha256: String,
    val contractId: UUID,
    val clientPurchaseOrderId: UUID,
    val commercialCurrency: String,
    val awardedAmount: BigDecimal,
    val requestedInvoiceAmount: BigDecimal,
    val billingEvidenceId: UUID?,
    val legalTaxReviewReference: String,
    val contractualBillableReference: String,
    val invoiceIssueDate: LocalDate?,
    val invoiceDueDate: LocalDate?,
)
data class FinanceArInvoiceCandidateValidation(
    val organizationId: UUID,
    val opportunityId: UUID,
    val currency: String,
    val amount: BigDecimal,
    val commercialSourceSha256: String,
    val invoiceIssued: Boolean=false,
    val receivablePosted: Boolean=false,
    val customerPaid: Boolean=false,
)
object FinanceArInvoiceCandidateRules {
    fun validate(input:FinanceArInvoiceCandidateInput):FinanceArInvoiceCandidateValidation {
        require(input.organizationId!=input.customerOrganizationId) { "CROSS_PARTY_BILLING" }
        require(input.commercialSourceSha256.matches(Regex("^[a-f0-9]{64}$"))) {
            "UNVERIFIED_AWARD_FINGERPRINT"
        }
        require(input.commercialCurrency.matches(Regex("^[A-Z]{3}$"))) {
            "INVALID_BILLING_CURRENCY"
        }
        require(input.awardedAmount>BigDecimal.ZERO &&
            input.requestedInvoiceAmount>BigDecimal.ZERO &&
            input.requestedInvoiceAmount<=input.awardedAmount &&
            input.requestedInvoiceAmount.scale()<=6) { "INVALID_INVOICE_AMOUNT" }
        require(input.billingEvidenceId!=null) { "MISSING_BILLABLE_MILESTONE_EVIDENCE" }
        require(input.legalTaxReviewReference.trim().length in 8..180) {
            "MISSING_LEGAL_TAX_REVIEW"
        }
        require(input.contractualBillableReference.trim().length in 8..180) {
            "MISSING_CONTRACTUAL_BILLING_REFERENCE"
        }
        require(input.invoiceIssueDate!=null && input.invoiceDueDate!=null &&
            !input.invoiceDueDate.isBefore(input.invoiceIssueDate)) {
            "UNAPPROVED_INVOICE_DATES"
        }
        return FinanceArInvoiceCandidateValidation(
            input.organizationId,input.opportunityId,input.commercialCurrency,
            input.requestedInvoiceAmount,input.commercialSourceSha256,
        )
    }
}
