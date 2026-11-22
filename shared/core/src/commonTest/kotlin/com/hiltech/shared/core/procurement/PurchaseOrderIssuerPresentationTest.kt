package com.hiltech.shared.core.procurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PurchaseOrderIssuerPresentationTest {
    private fun candidate(
        id: String,
        supplier: String,
        approval: String = "approved-1",
    ) = PurchaseOrderIssuerQueueItemDto(
        candidateId = id,
        supplierOrganizationId = "supplier-" + id,
        supplierName = supplier,
        stockItemId = "stock-" + id,
        quantity = "12",
        unitCode = "PCS",
        currency = "EGP",
        total = "360.00",
        approvalRequestId = approval,
        approvalPolicyVersion = 4,
    )

    private fun receipt(
        id: String = "candidate-1",
        approval: String = "approved-1",
    ) = PurchaseOrderIssueReceiptDto(
        purchaseOrderId = "po-1", code = "PO-2026-001",
        serial = 1L, state = "ISSUED", supplierDispatchState = "NOT_DISPATCHED",
        candidateId = id, approvalRequestId = approval, total = "360.00",
        currency = "EGP", replayed = false,
        stockReceived = false, supplierNotified = false, paymentAuthorized = false,
    )

    private fun destination(id: String) = PurchaseOrderDeliveryDestinationDto(
        storageLocationId = id, code = "WH-1", name = "Warehouse", kind = "WAREHOUSE",
    )

    @Test fun issuerQueueSearchStaysInsideAuthorizedServerResponse() {
        val queue = listOf(candidate("one", "Alpha Co"), candidate("two", "Beta Group"))
        assertEquals(queue, issuerVisibleQueue(queue, "   "))
        assertEquals(listOf("two"), issuerVisibleQueue(queue, "betA").map { it.candidateId })
        assertEquals(listOf("one"), issuerVisibleQueue(queue, "one").map { it.candidateId })
        assertTrue(issuerVisibleQueue(queue, "unknown supplier").isEmpty())
    }

    @Test fun confirmedReceiptRemovesOnlyItsCandidate() {
        val queue = listOf(candidate("candidate-1", "Alpha"), candidate("candidate-2", "Beta"))
        val remaining = issuerQueueAfterConfirmedReceipt(queue, receipt())
        assertEquals(listOf("candidate-2"), remaining.map { it.candidateId })
        assertEquals(2, queue.size)
    }

    @Test fun mismatchedApprovalOrCandidateNeverClaimsMatchingSource() {
        val item = candidate("candidate-1", "Alpha")
        assertTrue(issuerReceiptMatchesSource(receipt(), item))
        assertFalse(issuerReceiptMatchesSource(receipt(approval = "another"), item))
        assertFalse(issuerReceiptMatchesSource(receipt(id = "other"), item))
    }

    @Test fun scopeRefreshRemovesSelectionIfNotReturnedByServer() {
        val queue = listOf(candidate("candidate-2", "Beta"))
        assertEquals("candidate-2", issuerSelectedCandidateAfterRefresh("candidate-2", queue))
        assertNull(issuerSelectedCandidateAfterRefresh("candidate-1", queue))
        assertNull(issuerSelectedCandidateAfterRefresh(null, queue))
    }

    @Test fun onlyServerReturnedDestinationCanBeChosen() {
        val available = listOf(destination("warehouse-1"))
        assertTrue(issuerDestinationIsAuthorized("warehouse-1", available))
        assertFalse(issuerDestinationIsAuthorized("warehouse-2", available))
        assertFalse(issuerDestinationIsAuthorized(null, available))
        assertFalse(issuerDestinationIsAuthorized("warehouse-1", emptyList()))
    }

    @Test fun uncertainIssueReusesOperationAndCannotChangeDestination() {
        var allocations = 0
        val first = issuerPendingOperation(null, "warehouse-1") {
            allocations++
            "same-operation-id"
        }
        val retry = issuerPendingOperation(first, "warehouse-1") {
            allocations++
            "must-not-be-generated"
        }
        assertEquals(first, retry)
        assertEquals(1, allocations)
        assertFailsWith<IllegalArgumentException> {
            issuerPendingOperation(first, "warehouse-2") { "new-id" }
        }
    }

    @Test fun canonicalIssuedOrderRegisterResolvesOnlyExactCandidate() {
        val issued = listOf(
            PurchaseOrderLifecycleSummaryDto(
                id = "po-1", code = "PO-0001", candidateId = "candidate-1",
                effectiveState = "ISSUED", version = 1,
                supplierDispatchState = "NOT_DISPATCHED",
                total = "360.00", currency = "EGP",
            ),
            PurchaseOrderLifecycleSummaryDto(
                id = "po-2", code = "PO-0002", candidateId = "candidate-2",
                effectiveState = "ISSUED", version = 1,
                supplierDispatchState = "NOT_DISPATCHED",
                total = "500.00", currency = "EGP",
            ),
        )
        assertEquals("PO-0002", findConfirmedIssuerOrder(issued, "candidate-2")?.code)
        assertNull(findConfirmedIssuerOrder(issued, "some-other-candidate"))
        assertNull(findConfirmedIssuerOrder(emptyList(), "candidate-1"))
    }

    @Test fun deniedIdentityIsNotTreatedLikeOrdinaryTransientFailure() {
        assertTrue(issuerShouldClearRestrictedQueue(401))
        assertTrue(issuerShouldClearRestrictedQueue(403))
        assertFalse(issuerShouldClearRestrictedQueue(409))
        assertFalse(issuerShouldClearRestrictedQueue(500))
        assertFalse(issuerShouldClearRestrictedQueue(null))
        assertEquals(1L, CANONICAL_ISSUER_CANDIDATE_VERSION)
    }
}
