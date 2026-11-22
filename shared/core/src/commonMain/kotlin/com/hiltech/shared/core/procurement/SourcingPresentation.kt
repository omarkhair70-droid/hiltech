package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.design.HiltechStateTone

/**
 * Pure cross-platform presentation contract for the LIVE buyer screen.
 * Always use the server's sourceCurrent and permission checks; this only
 * determines whether an already-authorized button can be shown.
 */
fun canOfferNewRfq(need: SourcingEligibleNeedDto, busy: Boolean): Boolean =
    !busy && need.sourceCurrent && !need.sourcingRoundExists

fun canDisplayCandidatePreflight(
    comparison: SupplierRfqComparisonDto?,
    quoteId: String?,
    hasCandidateApi: Boolean,
): Boolean =
    hasCandidateApi && comparison != null && comparison.rfq.sourceCurrent &&
        quoteId != null &&
        comparison.comparison.latestBySupplier.any { it.quoteId == quoteId }

fun technicalQuoteTone(quote: SupplierQuoteComparisonRowDto): HiltechStateTone =
    when {
        quote.technicalCompliance != "COMPLIANT" -> HiltechStateTone.ATTENTION
        quote.issues.isNotEmpty() -> HiltechStateTone.ATTENTION
        else -> HiltechStateTone.CURRENT
    }

fun technicalQuoteText(quote: SupplierQuoteComparisonRowDto): String = when {
    quote.technicalCompliance == "COMPLIANT" && quote.issues.isEmpty() -> "Technically compliant"
    quote.technicalCompliance == "COMPLIANT" -> "Compliant · source issue"
    quote.technicalCompliance == "NON_COMPLIANT" -> "Not technically compliant"
    quote.technicalCompliance == "REVIEW_REQUIRED" -> "Technical review required"
    else -> "Technical status requires verification"
}

fun supplierNameFor(
    supplierOrganizationId: String,
    suppliers: List<SourcingSupplierCandidateDto>,
): String = suppliers.firstOrNull { it.supplierOrganizationId == supplierOrganizationId }
    ?.displayName ?: "Supplier name unavailable — inspect source ID"

/**
 * Do not rank prices if even one currency differs or if the source does
 * not declare direct comparability. Quote contents remain viewable as evidence.
 */
fun comparisonTotalsAreComparable(result: SupplierRfqComparisonDto): Boolean =
    result.rfq.sourceCurrent && result.comparison.allQuotesDirectlyComparable &&
        result.comparison.currencies.size == 1

/** Keep the full source identifier inspectable; shortened refs are never identity. */
fun sourceReferencePreview(id: String): String = id.take(8) + if (id.length > 8) "…" else ""

fun isolateSourceId(raw: String): String = "\u2066$raw\u2069"

/** Operator-safe language; raw backend strings belong in the source inspector. */
fun sourceTechnicalReviewCopy(value: String): String = when (value) {
    "TECHNICALLY_READY", "TECHNICALLY_APPROVED" -> "Technically ready"
    "REVIEW_REQUESTED", "REVISION_REQUESTED" -> "Revision requested"
    "SUBMITTED", "PENDING_TECHNICAL_REVIEW" -> "Awaiting technical review"
    "REJECTED" -> "Technically rejected"
    "DRAFT" -> "Draft need"
    else -> "Technical source requires inspection"
}

/** A changed login/role must never keep cached restricted supplier/quote data. */
fun shouldDiscardBuyerSnapshot(httpStatus: Int?): Boolean =
    httpStatus == 401 || httpStatus == 403

/** Bounded visible window: no eager rendering of hundreds of operational cards. */
data class BuyerListWindow(
    val start: Int,
    val endExclusive: Int,
    val pageIndex: Int,
    val pageCount: Int,
    val totalCount: Int,
) {
    val hasPrevious: Boolean get() = pageIndex > 0
    val hasNext: Boolean get() = pageIndex + 1 < pageCount
    val visibleCount: Int get() = endExclusive - start
}
fun buyerListWindow(total: Int, requestedPage: Int, pageSize: Int): BuyerListWindow {
    require(total >= 0) { "total must not be negative" }
    require(pageSize in 1..100) { "pageSize must be between 1 and 100" }
    val count = if (total == 0) 1 else ((total - 1) / pageSize) + 1
    val index = requestedPage.coerceIn(0, count - 1)
    val start = index * pageSize
    val end = minOf(total, start + pageSize)
    return BuyerListWindow(start, end, index, count, total)
}

fun isRecordedQuoteVisibleInFreshComparison(
    receipt: SupplierQuoteReceiptDto,
    comparison: SupplierRfqComparisonDto,
): Boolean =
    receipt.rfqId == comparison.rfq.id &&
        comparison.rfq.sourceCurrent &&
        comparison.comparison.latestBySupplier.any { row ->
            row.quoteId == receipt.quoteId && row.revisionNumber >= receipt.revisionNumber
        }

/** A confirmed RFQ receipt must disable a second draft even if list refresh fails. */
fun needsAfterConfirmedRfq(
    authorizedNeeds: List<SourcingEligibleNeedDto>,
    requirementId: String,
): List<SourcingEligibleNeedDto> = authorizedNeeds.map { need ->
    if (need.requirementId == requirementId) need.copy(sourcingRoundExists = true) else need
}
