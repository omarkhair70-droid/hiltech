package com.hiltech.server.finance

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FinancePaymentPreflightServiceTest {
    private val payable = Mockito.mock(FinancePayableCandidateService::class.java)
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val now = Instant.parse("2026-11-23T11:00:00Z")

    private fun service(enabled: Boolean = false): FinancePaymentPreflightService {
        val env = StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("test",
            mapOf("hiltech.finance.payment-preflight.enabled" to enabled)))
        return FinancePaymentPreflightService(
            payable, Clock.fixed(now, ZoneOffset.UTC), env,
        )
    }

    private fun candidate(
        currency: String = "EGP",
        amount: String = "200.50",
        current: Boolean = true,
        state: String = "ACCOUNTING_REVIEW_REQUIRED",
    ) = SupplierPayableCandidateView(
        id = UUID.randomUUID(),
        handoffId = UUID.randomUUID(),
        invoiceId = UUID.randomUUID(),
        supplierOrganizationId = UUID.randomUUID(),
        invoiceNumber = "SupplierInvoice-TEST",
        amount = amount,
        currency = currency,
        sourceSha256 = "a".repeat(64),
        state = state, sourceCurrent = current,
    )

    @Test fun preflightDisabledByDefaultBeforeAnyFinanceDataAccess() {
        val denied = assertThrows<ProductApiException> {
            service().report(actor, org)
        }
        assertEquals("FINANCE_PAYMENT_PREFLIGHT_UNAVAILABLE", denied.code)
        Mockito.verifyNoInteractions(payable)
    }

    @Test fun sourceDerivedPreviewCannotExecuteOrApprove() {
        val source = candidate()
        Mockito.`when`(payable.candidates(actor, org)).thenReturn(listOf(source))
        val report = service(true).report(actor, org)
        val row = report.rows.single()
        assertEquals(source.id, row.sourcePayableCandidateId)
        assertEquals(source.amount, row.amount)
        assertEquals(source.currency, row.currency)
        assertEquals(source.sourceSha256, row.sourceSha256)
        assertFalse(row.actionablePaymentInstruction)
        assertFalse(row.paymentExecutionAvailable)
        assertTrue(row.blockingReasons.contains("ACCOUNTING_RECOGNITION_NOT_AUTHORIZED_OR_BOOKED"))
        assertTrue(row.blockingReasons.contains("EXACT_BENEFICIARY_BANK_AND_SIGNATORY_UNVERIFIED"))
        assertFalse(report.approvalEnabled)
        assertFalse(report.bankExecutionEnabled)
        assertNull(report.paymentResultsObserved)
        assertNull(report.matchedBankStatements)
        Mockito.verify(payable).candidates(actor, org)
    }

    @Test fun changedSourceAddsExplicitBlockerAndNoPayment() {
        val source = candidate(current = false)
        Mockito.`when`(payable.candidates(actor, org)).thenReturn(listOf(source))
        val row = service(true).report(actor, org).rows.single()
        assertTrue(row.blockingReasons.contains("SUPPLIER_SOURCE_CHANGED_NEEDS_NEW_MATCH"))
        assertFalse(row.actionablePaymentInstruction)
    }

    @Test fun unrecognizedStateNeverBecomesAPayable() {
        val source = candidate(state = "UNRECOGNIZED_FUTURE_STATE")
        Mockito.`when`(payable.candidates(actor, org)).thenReturn(listOf(source))
        val row = service(true).report(actor, org).rows.single()
        assertTrue(row.blockingReasons.contains("SOURCE_ACCOUNTING_STATE_UNSUPPORTED"))
        assertFalse(row.paymentExecutionAvailable)
    }

    @Test fun sourceTotalsAreUnpostedAndNeverMixCurrencies() {
        val list = listOf(
            candidate("EGP", "120.25"), candidate("EGP", "79.75", false),
            candidate("USD", "15.00"),
        )
        Mockito.`when`(payable.candidates(actor, org)).thenReturn(list)
        val pulse = service(true).pulse(actor, org)
        assertEquals("UNPOSTED_REVIEW_CANDIDATES_ONLY_NO_ACTUAL_FINANCIAL_POSITION",
            pulse.dataSemantics)
        assertEquals(3, pulse.sourceItems)
        assertEquals(2, pulse.groupsByCurrency.size)
        val egp = pulse.groupsByCurrency.first { it.currency == "EGP" }
        assertEquals("200", egp.reviewCandidateTotal)
        assertEquals(2, egp.reviewCandidateCount)
        assertEquals(1, egp.sourceChangedCount)
        assertNull(egp.postedPayables)
        assertNull(egp.approvedPayments)
        assertNull(egp.executedPayments)
        assertFalse(pulse.actualBankBalanceKnown)
        assertFalse(pulse.reconciliationAvailable)
    }
    @Test fun reachingTheSourceLimitNeverPretendsToBeTheCompanyWideTotal() {
        val page = (1..100).map { candidate(amount = "1.00") }
        Mockito.`when`(payable.candidates(actor, org)).thenReturn(page)
        val pulse = service(true).pulse(actor, org)
        assertEquals(100, pulse.sourceItems)
        assertEquals(100, pulse.sourceQueryLimit)
        assertTrue(pulse.possibleUnreturnedSources)
        assertEquals("100", pulse.groupsByCurrency.single().reviewCandidateTotal)
        assertNull(pulse.groupsByCurrency.single().postedPayables)

        val preview = service(true).report(actor, org)
        assertTrue(preview.possibleUnreturnedSources)
        assertEquals(100, preview.sourceQueryLimit)
        assertFalse(preview.paymentInstructionEnabled)
    }

}
