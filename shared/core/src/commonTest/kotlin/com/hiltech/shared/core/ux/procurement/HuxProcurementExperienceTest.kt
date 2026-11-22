package com.hiltech.shared.core.ux.procurement

import com.hiltech.shared.core.procurement.PurchaseRequirementDraftDto
import com.hiltech.shared.core.procurement.PurchaseRequirementReviewPreviewDto
import com.hiltech.shared.core.procurement.SupplierQuoteComparisonDto
import com.hiltech.shared.core.procurement.SupplierQuoteComparisonRowDto
import com.hiltech.shared.core.procurement.SupplierRfqComparisonDto
import com.hiltech.shared.core.procurement.SupplierRfqDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HuxProcurementExperienceTest {
    private fun buyer(
        sourceCurrent: Boolean = true,
        comparable: Boolean = true,
        currencies: List<String> = listOf("EGP"),
        purchase: Boolean = false,
    ) = HuxBuyerStudy(
        sourcing = SupplierRfqComparisonDto(
            rfq = SupplierRfqDto("rfq-qa", "req-qa", 3, "10", "PCS", sourceCurrent, 1, purchase),
            comparison = SupplierQuoteComparisonDto(
                expectedQuantity = "10", expectedUnitCode = "PCS",
                evaluatedAt = "2026-11-22T10:00:00Z",
                latestBySupplier = listOf(
                    SupplierQuoteComparisonRowDto(
                        quoteId = "quote-qa", supplierOrganizationId = "sup-qa",
                        revisionNumber = 2, quoteReference = "QA-REF",
                        currency = "EGP", quantity = "10", unitCode = "PCS",
                        unitPrice = "100.00", taxAmount = "0.00",
                        total = "1000.00", evidenceRef = "evidence-qa",
                        evidenceSha256 = "sha-qa", technicalCompliance = "COMPLIANT",
                        issues = emptyList(),
                    ),
                ),
                currencies = currencies,
                allQuotesDirectlyComparable = comparable,
                purchaseAuthorized = purchase,
            ),
            allRevisionCount = 2, recordedSupplierCount = 1,
        ),
        supplierNames = mapOf("sup-qa" to "Synthetic supplier"),
        workLabel = "WO-QA-01", itemLabel = "QA item",
        scopeLabel = "QA organization", online = true,
    )

    private fun field(
        current: Boolean = true,
        refresh: Boolean = false,
        stockVisible: Boolean = true,
        canSubmit: Boolean = true,
        online: Boolean = true,
    ): HuxFieldStudy {
        val req = PurchaseRequirementDraftDto(
            id = "req-qa", workOrderId = "work-qa", workOrderVersionAtCreation = 1,
            stockItemId = "stock-qa", stockItemVersionAtCreation = 1,
            requestedQuantity = "4", unitCode = "PCS", justification = "QA need",
            state = "DRAFT", assessmentCode = if(stockVisible) "INSUFFICIENT_STOCK" else "WAREHOUSE_REVIEW_REQUIRED",
            observedFreeQuantity = if(stockVisible) "2" else null,
            assessedAt = "2026-11-22T10:00:00Z", createdBy = "actor-qa",
            createdAt = "2026-11-22T09:00:00Z",
            sourceCurrent = current, assessmentRequiresRefresh = refresh,
            canSubmit = canSubmit,
        )
        val preview = PurchaseRequirementReviewPreviewDto(
            requirementId = "req-qa", state = "DRAFT", currentWorkOrderVersion = 1,
            sourceCurrent = current, assessmentCode = req.assessmentCode,
            visibleFreeQuantity = if(stockVisible) "2" else null,
            assessedAt = "2026-11-22T10:00:00Z",
        )
        return HuxFieldStudy(req, preview, "WO-QA-01", "Synthetic stock item", online)
    }

    @Test fun currenciesCannotBeRankedWhenNotComparable() {
        assertFalse(buyer(comparable = false).canCompareTotals())
        assertFalse(buyer(currencies = listOf("EGP", "USD")).canCompareTotals())
        assertFalse(buyer(sourceCurrent = false).canCompareTotals())
        assertTrue(buyer().canCompareTotals())
    }

    @Test fun quoteSelectionNeverGrantsPurchaseAuthority() {
        assertFalse(buyer().isPurchaseAuthorizedHint())
        assertTrue(buyer(purchase = true).isPurchaseAuthorizedHint())
    }

    @Test fun hiddenOrStaleStockNeverDisplaysAsZero() {
        assertFalse(field(stockVisible = false).canShowFreeStock())
        assertFalse(field(current = false).canShowFreeStock())
        assertFalse(field(refresh = true).canShowFreeStock())
        assertFalse(field(online = false).canShowFreeStock())
        assertTrue(field().canShowFreeStock())
    }

    @Test fun serverHintIsNotAnAutomaticSubmitAction() {
        assertFalse(field().canOfferSubmit(wired = false))
        assertFalse(field(canSubmit = false).canOfferSubmit(wired = true))
        assertFalse(field(online = false).canOfferSubmit(wired = true))
        assertFalse(field(current = false).canOfferSubmit(wired = true))
        assertTrue(field().canOfferSubmit(wired = true))
    }
}
