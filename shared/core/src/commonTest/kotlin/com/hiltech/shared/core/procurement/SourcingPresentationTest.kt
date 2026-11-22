package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.procurementSourceTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourcingPresentationTest {
    private fun need(current: Boolean = true, rfqExists: Boolean = false) =
        SourcingEligibleNeedDto(
            requirementId = "req-qa",
            technicalVersion = 2,
            stockItemId = "stock-qa",
            requestedQuantity = "12",
            unitCode = "PCS",
            technicalState = "TECHNICALLY_READY",
            sourceCurrent = current,
            sourcingRoundExists = rfqExists,
        )

    private fun quote(
        technical: String = "COMPLIANT",
        issues: List<String> = emptyList(),
        currency: String = "EGP",
    ) = SupplierQuoteComparisonRowDto(
        quoteId = "quote-qa",
        supplierOrganizationId = "supplier-qa",
        revisionNumber = 3,
        quoteReference = "QA-03",
        currency = currency,
        quantity = "12", unitCode = "PCS",
        unitPrice = "25", taxAmount = "12",
        total = "312",
        evidenceRef = "source-qa",
        evidenceSha256 = "sha-qa",
        technicalCompliance = technical,
        issues = issues,
    )

    private fun comparison(
        current: Boolean = true,
        comparable: Boolean = true,
        currencies: List<String> = listOf("EGP"),
    ): SupplierRfqComparisonDto {
        val rfq = SupplierRfqDto(
            id = "rfq-qa", requirementId = "req-qa",
            capturedRequirementVersion = 2,
            capturedQuantity = "12", unitCode = "PCS",
            sourceCurrent = current, quoteCount = 1,
            purchaseAuthorized = false,
        )
        return SupplierRfqComparisonDto(
            rfq = rfq,
            comparison = SupplierQuoteComparisonDto(
                expectedQuantity = "12", expectedUnitCode = "PCS",
                evaluatedAt = "2026-11-22T15:00:00Z",
                latestBySupplier = listOf(quote()), currencies = currencies,
                allQuotesDirectlyComparable = comparable,
                supplierSelected = false, purchaseAuthorized = false,
            ),
            allRevisionCount = 3, recordedSupplierCount = 1,
        )
    }

    @Test fun onlyCurrentUnopenedAuthorizedNeedOffersRfq() {
        assertTrue(canOfferNewRfq(need(), false))
        assertFalse(canOfferNewRfq(need(current = false), false))
        assertFalse(canOfferNewRfq(need(rfqExists = true), false))
        assertFalse(canOfferNewRfq(need(), true))
    }

    @Test fun candidatePreflightRequiresCurrentComparisonAndApi() {
        val data = comparison()
        assertFalse(canDisplayCandidatePreflight(data, "quote-qa", false))
        assertFalse(canDisplayCandidatePreflight(data, null, true))
        assertFalse(canDisplayCandidatePreflight(data, "other-quote", true))
        assertFalse(canDisplayCandidatePreflight(comparison(current = false), "quote-qa", true))
        assertTrue(canDisplayCandidatePreflight(data, "quote-qa", true))
    }

    @Test fun technicalStatusAndSourceIssuesStaySeparate() {
        assertEquals("Technically compliant", technicalQuoteText(quote()))
        assertEquals("Compliant · source issue",
            technicalQuoteText(quote(issues = listOf("Terms under review"))))
        assertEquals(HiltechStateTone.ATTENTION,
            technicalQuoteTone(quote(issues = listOf("Terms under review"))))
        assertEquals("Technical review required",
            technicalQuoteText(quote(technical = "REVIEW_REQUIRED")))
        assertEquals("Technical status requires verification",
            technicalQuoteText(quote(technical = "NEW_FROM_SERVER")))
    }

    @Test fun technicalCopyNeverTurnsUnknownStateIntoApproval() {
        assertEquals("Technically ready", sourceTechnicalReviewCopy("TECHNICALLY_READY"))
        assertEquals("Revision requested", sourceTechnicalReviewCopy("REVISION_REQUESTED"))
        assertEquals("Technical source requires inspection",
            sourceTechnicalReviewCopy("UNRECOGNIZED_FROM_SERVER"))
    }

    @Test fun noAutomaticMixedCurrencyRanking() {
        assertTrue(comparisonTotalsAreComparable(comparison()))
        assertFalse(comparisonTotalsAreComparable(
            comparison(currencies = listOf("EGP", "USD"))))
        assertFalse(comparisonTotalsAreComparable(
            comparison(comparable = false)))
        assertFalse(comparisonTotalsAreComparable(
            comparison(current = false)))
    }

    @Test fun authorizationLossDiscardsRestrictedBuyerSnapshot() {
        assertTrue(shouldDiscardBuyerSnapshot(401))
        assertTrue(shouldDiscardBuyerSnapshot(403))
        assertFalse(shouldDiscardBuyerSnapshot(409))
        assertFalse(shouldDiscardBuyerSnapshot(null))
    }

    @Test fun sourceMeaningAndSupplierLabelsAreFailClosed() {
        assertEquals(HiltechStateTone.BLOCKED, procurementSourceTone(false))
        assertEquals(HiltechStateTone.ATTENTION, procurementSourceTone(true, true))
        assertEquals(HiltechStateTone.CURRENT, procurementSourceTone(true))
        assertEquals(
            "Supplier name unavailable — inspect source ID",
            supplierNameFor("restricted", emptyList()))
        assertEquals("req-qa", sourceReferencePreview("req-qa"))
        assertTrue(isolateSourceId("PO-127").startsWith("\u2066"))
        assertTrue(isolateSourceId("PO-127").endsWith("\u2069"))
    }
}
