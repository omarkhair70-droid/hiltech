package com.hiltech.shared.core.procurement

/**
 * A supplier search is an RFQ-bound snapshot, not an authoritative grant or a
 * directory that can be reused after changing input, RFQ, or account context.
 */
fun serviceSupplierResultsBelongToCurrentQuery(
    input: String,
    submittedQuery: String?,
    activeRfqId: String?,
    resultRfqId: String?,
): Boolean =
    !submittedQuery.isNullOrBlank() &&
        !activeRfqId.isNullOrBlank() &&
        activeRfqId == resultRfqId &&
        input.trim() == submittedQuery

/**
 * An async response may update visible supplier results only if no newer
 * input edit, search, or source switch superseded that request.
 */
fun serviceSupplierSearchResponseIsCurrent(
    currentGeneration: Long,
    responseGeneration: Long,
    input: String,
    submittedQuery: String,
    activeRfqId: String?,
    responseRfqId: String,
): Boolean =
    currentGeneration == responseGeneration &&
        serviceSupplierResultsBelongToCurrentQuery(
            input = input,
            submittedQuery = submittedQuery,
            activeRfqId = activeRfqId,
            resultRfqId = responseRfqId,
        )
