package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FinanceArJournalRulesTest {
    private val ar=UUID.randomUUID()
    private val revenue=UUID.randomUUID()
    private val tax=UUID.randomUUID()
    private val plan=FinanceArJournalPlan(
        organizationId=UUID.randomUUID(),issuedInvoiceId=UUID.randomUUID(),
        issuedInvoiceFingerprint="e".repeat(64),fiscalPeriodId=UUID.randomUUID(),
        grossAmount=BigDecimal("116.000000"),currency="EGP",
        accountingTreatmentReference="APPROVED-COMPANY-GL-2026",
        taxDispositionReference="TAX_REVIEWED_WITH_EVIDENCE",
        taxEvidenceId=UUID.randomUUID(),
        lines=listOf(
            FinanceArJournalAllocation(ar,FinanceArAccountKind.CUSTOMER_RECEIVABLE,
                FinanceJournalSide.DEBIT,BigDecimal("116.000000")),
            FinanceArJournalAllocation(revenue,FinanceArAccountKind.SALES_REVENUE,
                FinanceJournalSide.CREDIT,BigDecimal("100.000000")),
            FinanceArJournalAllocation(tax,FinanceArAccountKind.TAX_PAYABLE,
                FinanceJournalSide.CREDIT,BigDecimal("16.000000")),
        ),
    )
    private fun rejects(p:FinanceArJournalPlan) {
        assertThrows(IllegalArgumentException::class.java) {
            FinanceArJournalRules.validate(p)
        }
    }
    @Test fun balancedRecognizedARRequiresExplicitRevenueAndTaxSplit() {
        val v=FinanceArJournalRules.validate(plan)
        assertEquals("116.000000",v.grossAmount)
        assertEquals(v.fingerprintSha256,
            FinanceArJournalRules.validate(plan).fingerprintSha256)
    }
    @Test fun neverInferTaxOrReverseReceivableOrDoubleCountSource() {
        rejects(plan.copy(taxEvidenceId=null))
        rejects(plan.copy(lines=plan.lines.take(2)))
        rejects(plan.copy(grossAmount=BigDecimal("115")))
        rejects(plan.copy(lines=plan.lines +
            plan.lines.first()))
        rejects(plan.copy(lines=plan.lines.map {
            if(it.accountKind==FinanceArAccountKind.CUSTOMER_RECEIVABLE)
                it.copy(side=FinanceJournalSide.CREDIT) else it
        }))
        rejects(plan.copy(lines=plan.lines.map {
            if(it.accountKind==FinanceArAccountKind.TAX_PAYABLE)
                it.copy(amount=BigDecimal.ZERO) else it
        }))
        rejects(plan.copy(issuedInvoiceFingerprint=""))
    }
    @Test fun anyApprovedAllocationChangeInvalidatesExactReview() {
        val v=FinanceArJournalRules.validate(plan)
        val edited=plan.copy(
            accountingTreatmentReference="APPROVED-COMPANY-GL-2027")
        assertNotEquals(v.fingerprintSha256,
            FinanceArJournalRules.validate(edited).fingerprintSha256)
    }
}
