package com.hiltech.shared.core.procurement

/**
 * Pure issuer presentation rules. Server queue membership and server receipts
 * remain the authoritative source. These helpers never grant permissions.
 */
fun issuerQueueAfterConfirmedReceipt(
    queue: List<PurchaseOrderIssuerQueueItemDto>,
    receipt: PurchaseOrderIssueReceiptDto,
): List<PurchaseOrderIssuerQueueItemDto> =
    queue.filterNot { it.candidateId == receipt.candidateId }

fun issuerReceiptMatchesSource(
    receipt: PurchaseOrderIssueReceiptDto,
    candidate: PurchaseOrderIssuerQueueItemDto,
): Boolean =
    receipt.candidateId == candidate.candidateId &&
        receipt.approvalRequestId == candidate.approvalRequestId

fun issuerSelectedCandidateAfterRefresh(
    selectedId: String?,
    queue: List<PurchaseOrderIssuerQueueItemDto>,
): String? = selectedId?.takeIf { id -> queue.any { it.candidateId == id } }

fun issuerVisibleQueue(
    queue: List<PurchaseOrderIssuerQueueItemDto>,
    search: String,
): List<PurchaseOrderIssuerQueueItemDto> {
    val phrase = search.trim()
    if (phrase.isEmpty()) return queue
    return queue.filter { row ->
        row.supplierName.contains(phrase, ignoreCase = true) ||
            row.candidateId.contains(phrase, ignoreCase = true) ||
            row.currency.contains(phrase, ignoreCase = true)
    }
}

fun issuerDestinationIsAuthorized(
    selectedId: String?,
    destinations: List<PurchaseOrderDeliveryDestinationDto>,
): Boolean = selectedId != null &&
    destinations.any { destination -> destination.storageLocationId == selectedId }

fun issuerShouldClearRestrictedQueue(status: Int?): Boolean = status == 401 || status == 403

/** This contract is checked by the actual canonical server Issue service. */
const val CANONICAL_ISSUER_CANDIDATE_VERSION: Long = 1L

/** Retains BOTH idempotency operation ID and its immutable target on uncertain outcomes. */
data class IssuerPendingOperation(
    val operationId: String,
    val destinationId: String,
)

fun issuerPendingOperation(
    existing: IssuerPendingOperation?,
    destinationId: String,
    newOperationId: () -> String,
): IssuerPendingOperation {
    require(destinationId.isNotBlank()) { "A verified destination is required." }
    require(existing == null || existing.destinationId == destinationId) {
        "An uncertain issue operation cannot change its delivery destination."
    }
    return existing ?: IssuerPendingOperation(newOperationId(), destinationId)
}

/** Reconciliation uses the canonical issued-PO register, never a guessed result. */
fun findConfirmedIssuerOrder(
    issued: List<PurchaseOrderLifecycleSummaryDto>,
    candidateId: String,
): PurchaseOrderLifecycleSummaryDto? = issued.firstOrNull { it.candidateId == candidateId }
