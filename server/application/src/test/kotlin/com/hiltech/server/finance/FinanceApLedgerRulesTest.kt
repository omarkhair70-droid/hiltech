package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FinanceApLedgerRulesTest {
    private val org = UUID.randomUUID()
    private val payable = UUID.randomUUID()
    private val expense = UUID.randomUUID()
    private val vat = UUID.randomUUID()
    private val source = UUID.randomUUID()
    private val period = UUID.randomUUID()
    private val evidence = UUID.randomUUID()
    private fun line(
        id: UUID, kind: FinanceApAccountKind, side: FinanceJournalSide,
        amount: String,
    ) = FinanceApLedgerLine(id, kind, side, BigDecimal(amount))

    private fun plan(
        amount: String = "100.000000",
        tax: String = "TAX_REVIEWED_NO_RECOVERABLE_TAX",
        taxEvidence: UUID? = null,
        lines: List<FinanceApLedgerLine> = listOf(
            line(expense, FinanceApAccountKind.EXPENSE, FinanceJournalSide.DEBIT, amount),
            line(payable, FinanceApAccountKind.SUPPLIER_PAYABLE, FinanceJournalSide.CREDIT, amount),
        ),
    ) = FinanceApLedgerPlan(
        organizationId = org,
        candidateId = source,
        sourceSha256 = "a".repeat(64),
        sourceVersion = 102030L,
        fiscalPeriodId = period,
        grossAmount = BigDecimal(amount),
        currency = "EGP",
        accountingTreatmentReference = "COMPANY-REVIEWED-GL-001",
        accountingTaxDisposition = tax,
        taxEvidenceId = taxEvidence,
        lines = lines,
    )

    @Test fun postedSupplierInvoiceMustBeExactBalancedWithoutInferredTax() {
        val result = FinanceApLedgerRules.validate(plan())
        assertEquals("100.000000", result.grossAmount)
        assertEquals("100.000000", result.debitTotal)
        assertEquals("100.000000", result.creditTotal)
        assertEquals(2, result.lineCount)
        assertEquals(64, result.fingerprintSha256.length)
        assertEquals(result.fingerprintSha256, FinanceApLedgerRules.validate(
            plan("100.0", lines = listOf(
                line(expense, FinanceApAccountKind.EXPENSE, FinanceJournalSide.DEBIT, "100"),
                line(payable, FinanceApAccountKind.SUPPLIER_PAYABLE,
                    FinanceJournalSide.CREDIT, "100"),
            )),
        ).fingerprintSha256)
    }

    @Test fun validReviewedTaxAllocationRequiresEvidenceAndExplicitSplit() {
        val result = FinanceApLedgerRules.validate(plan(
            amount = "116.000000",
            tax = "TAX_REVIEWED_RECOVERABLE_WITH_EVIDENCE",
            taxEvidence = evidence,
            lines = listOf(
                line(expense, FinanceApAccountKind.EXPENSE,
                    FinanceJournalSide.DEBIT, "100.000000"),
                line(vat, FinanceApAccountKind.TAX_RECOVERABLE,
                    FinanceJournalSide.DEBIT, "16.000000"),
                line(payable, FinanceApAccountKind.SUPPLIER_PAYABLE,
                    FinanceJournalSide.CREDIT, "116.000000"),
            ),
        ))
        assertEquals("116.000000", result.debitTotal)
        assertEquals("116.000000", result.creditTotal)
        assertEquals(3, result.lineCount)
    }

    @Test fun invoiceGrossNeverBecomesArtificialNetOfTax() {
        assertThrows(IllegalArgumentException::class.java) {
            FinanceApLedgerRules.validate(plan(amount = "116", lines = listOf(
                line(expense, FinanceApAccountKind.EXPENSE, FinanceJournalSide.DEBIT, "100"),
                line(payable, FinanceApAccountKind.SUPPLIER_PAYABLE, FinanceJournalSide.CREDIT, "116"),
            )))
        }
    }

    @Test fun cannotPretendRecoverableTaxWithoutSpecificDocumentAndTreatment() {
        val withTax = listOf(
            line(expense, FinanceApAccountKind.EXPENSE, FinanceJournalSide.DEBIT, "90"),
            line(vat, FinanceApAccountKind.TAX_RECOVERABLE, FinanceJournalSide.DEBIT, "10"),
            line(payable, FinanceApAccountKind.SUPPLIER_PAYABLE,
                FinanceJournalSide.CREDIT, "100"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            FinanceApLedgerRules.validate(plan(lines = withTax))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FinanceApLedgerRules.validate(plan(
                tax = "TAX_REVIEWED_RECOVERABLE_WITH_EVIDENCE",
                lines = withTax,
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FinanceApLedgerRules.validate(plan(taxEvidence = evidence))
        }
    }

    @Test fun cannotInferPostingOnZeroOrFractionalExcessOrNegative() {
        for (gross in listOf("0", "-25", "100.0000001")) {
            assertThrows(IllegalArgumentException::class.java) {
                FinanceApLedgerRules.validate(plan(amount = gross))
            }
        }
    }

    @Test fun duplicateAccountsAndAdditionalCreditsAreNotAllowed() {
        assertThrows(IllegalArgumentException::class.java) {
            FinanceApLedgerRules.validate(plan(lines = listOf(
                line(expense, FinanceApAccountKind.EXPENSE,
                    FinanceJournalSide.DEBIT, "40"),
                line(expense, FinanceApAccountKind.EXPENSE,
                    FinanceJournalSide.DEBIT, "60"),
                line(payable, FinanceApAccountKind.SUPPLIER_PAYABLE,
                    FinanceJournalSide.CREDIT, "100"),
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FinanceApLedgerRules.validate(plan(lines = listOf(
                line(expense, FinanceApAccountKind.EXPENSE,
                    FinanceJournalSide.DEBIT, "100"),
                line(payable, FinanceApAccountKind.SUPPLIER_PAYABLE,
                    FinanceJournalSide.CREDIT, "50"),
                line(vat, FinanceApAccountKind.OTHER_ASSET,
                    FinanceJournalSide.CREDIT, "50"),
            )))
        }
    }

    @Test fun sourceVersionAccountAndPolicyEvidenceAffectDigest() {
        val baseline = FinanceApLedgerRules.validate(plan()).fingerprintSha256
        assertNotEquals(baseline, FinanceApLedgerRules.validate(
            plan().copy(sourceVersion = 102031L),
        ).fingerprintSha256)
        assertNotEquals(baseline, FinanceApLedgerRules.validate(
            plan().copy(accountingTreatmentReference = "COMPANY-REVIEWED-GL-002"),
        ).fingerprintSha256)
        assertNotEquals(baseline, FinanceApLedgerRules.validate(
            plan().copy(fiscalPeriodId = UUID.randomUUID()),
        ).fingerprintSha256)
        assertNotEquals(baseline, FinanceApLedgerRules.validate(
            plan().copy(lines = listOf(
                line(UUID.randomUUID(), FinanceApAccountKind.EXPENSE,
                    FinanceJournalSide.DEBIT, "100"),
                line(payable, FinanceApAccountKind.SUPPLIER_PAYABLE,
                    FinanceJournalSide.CREDIT, "100"),
            )),
        ).fingerprintSha256)
    }
}
