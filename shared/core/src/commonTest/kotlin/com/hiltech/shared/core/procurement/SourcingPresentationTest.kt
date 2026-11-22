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

    @Test fun confirmedRfqReceiptDisablesDuplicateDraftBeforeRefresh() {
        val source = listOf(need(), need().copy(requirementId = "other-need"))
        val afterReceipt = needsAfterConfirmedRfq(source, "req-qa")
        assertFalse(canOfferNewRfq(afterReceipt.first(), busy = false))
        assertTrue(canOfferNewRfq(afterReceipt.last(), busy = false))
        assertFalse(source.first().sourcingRoundExists)
    }

    @Test fun largeAuthorizedNeedListRendersOnlyRequestedPage() {
        val first = buyerListWindow(total = 1_000, requestedPage = 0, pageSize = 20)
        assertEquals(0, first.start)
        assertEquals(20, first.endExclusive)
        assertEquals(50, first.pageCount)
        assertFalse(first.hasPrevious)
        assertTrue(first.hasNext)
        val last = buyerListWindow(total = 1_000, requestedPage = 999, pageSize = 20)
        assertEquals(980, last.start)
        assertEquals(1000, last.endExclusive)
        assertFalse(last.hasNext)
    }

    @Test fun paginationClampsAfterSourceOrSearchReducesResults() {
        val empty = buyerListWindow(0, 15, 20)
        assertEquals(0, empty.visibleCount)
        assertEquals(0, empty.pageIndex)
        assertEquals(1, empty.pageCount)
        val small = buyerListWindow(3, 200, 20)
        assertEquals(0, small.start)
        assertEquals(3, small.visibleCount)
        val negativePage = buyerListWindow(41, -4, 20)
        assertEquals(0, negativePage.pageIndex)
        assertEquals(3, negativePage.pageCount)
    }

    @Test fun confirmedQuoteIsSelectedOnlyWhenNewRevisionIsInFreshSource() {
        val recorded = SupplierQuoteReceiptDto(
            quoteId = "quote-qa", rfqId = "rfq-qa",
            revisionNumber = 3, replayed = false,
        )
        assertTrue(isRecordedQuoteVisibleInFreshComparison(recorded, comparison()))
        assertFalse(isRecordedQuoteVisibleInFreshComparison(
            recorded, comparison(current = false)))
        assertFalse(isRecordedQuoteVisibleInFreshComparison(
            recorded.copy(revisionNumber = 4), comparison()))
        assertFalse(isRecordedQuoteVisibleInFreshComparison(
            recorded.copy(rfqId = "some-other-rfq"), comparison()))
        assertFalse(isRecordedQuoteVisibleInFreshComparison(
            recorded.copy(quoteId = "not-in-source"), comparison()))
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
