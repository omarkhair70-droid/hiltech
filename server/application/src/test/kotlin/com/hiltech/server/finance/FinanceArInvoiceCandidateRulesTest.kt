package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class FinanceArInvoiceCandidateRulesTest {
    private val org=UUID.randomUUID()
    private val input=FinanceArInvoiceCandidateInput(
        organizationId=org,customerOrganizationId=UUID.randomUUID(),
        opportunityId=UUID.randomUUID(),commercialSourceSha256="a".repeat(64),
        contractId=UUID.randomUUID(),clientPurchaseOrderId=UUID.randomUUID(),
        commercialCurrency="EGP",awardedAmount=BigDecimal("10000.000000"),
        requestedInvoiceAmount=BigDecimal("2500.000000"),
        billingEvidenceId=UUID.randomUUID(),
        legalTaxReviewReference="LEGAL-TAX-REVIEW-2026",
        contractualBillableReference="SIGNED-MILESTONE-2026",
        invoiceIssueDate=LocalDate.of(2026,11,23),
        invoiceDueDate=LocalDate.of(2026,12,23),
    )
    private fun rejects(v:FinanceArInvoiceCandidateInput) {
        assertThrows(IllegalArgumentException::class.java) {
            FinanceArInvoiceCandidateRules.validate(v)
        }
    }
    @Test fun validEvidenceBoundPartialCandidateIsNotIssuedInvoice() {
        val result=FinanceArInvoiceCandidateRules.validate(input)
        assertEquals(BigDecimal("2500.000000"),result.amount)
        assertFalse(result.invoiceIssued)
        assertFalse(result.receivablePosted)
        assertFalse(result.customerPaid)
    }
    @Test fun noGuessingMissingTaxMilestoneDatesOrAmount() {
        rejects(input.copy(billingEvidenceId=null))
        rejects(input.copy(legalTaxReviewReference=""))
        rejects(input.copy(contractualBillableReference=""))
        rejects(input.copy(invoiceIssueDate=null))
        rejects(input.copy(invoiceDueDate=input.invoiceIssueDate!!.minusDays(1)))
        rejects(input.copy(requestedInvoiceAmount=BigDecimal("10001")))
        rejects(input.copy(requestedInvoiceAmount=BigDecimal.ZERO))
    }
    @Test fun exactCommercialOriginAndTenantMustBePreserved() {
        rejects(input.copy(customerOrganizationId=org))
        rejects(input.copy(commercialSourceSha256=""))
        rejects(input.copy(commercialCurrency="USD!"))
    }
}
