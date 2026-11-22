package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.work.WorkOrderSnapshot
import com.hiltech.server.work.WorkPersistencePort
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PurchaseOrderDeliveryAmendmentSubjectTest {
    private val at = Instant.parse("2026-11-22T17:00:00Z")
    private val org = UUID.randomUUID()
    private val poId = UUID.randomUUID()
    private val proposalId = UUID.randomUUID()
    private val originalLocation = UUID.randomUUID()
    private val destination = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val siteId = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val po = PurchaseOrderIssuedRecord(
        poId, UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(),
        "PROCUREMENT_STANDARD", 5, "PROCUREMENT_PO_ISSUER",
        UUID.randomUUID(), "PO-000002", 2,
        UUID.randomUUID(), originalLocation, UUID.randomUUID(),
        UUID.randomUUID(), "a".repeat(64), actor, at, "test",
    )
    private val candidate = PurchaseOrderCandidateRecord(
        po.candidateId, UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(),
        3, po.quoteId, 1, po.supplierOrganizationId, po.stockItemId,
        BigDecimal("100"), "M", "EGP", BigDecimal("5"), BigDecimal.ZERO,
        BigDecimal("500"), "a".repeat(64), "Original work order", actor, at, "test",
    )
    private val demand = PurchaseRequirementDraftRecord(
        candidate.requirementId, UUID.randomUUID(), org, workId, 1,
        candidate.stockItemId, 1, BigDecimal("100"), "M", "Cable",
        "WAREHOUSE_REVIEW_REQUIRED", null, at, actor, at, "test",
    )
    private val proposal = PurchaseOrderDeliveryAmendmentRecord(
        proposalId, UUID.randomUUID(), org, poId, 1L,
        originalLocation, destination, "Warehouse location needs changing",
        actor, at, "test",
    )
    private val proposals = Mockito.mock(PurchaseOrderDeliveryAmendmentPersistence::class.java)
    private val purchases = Mockito.mock(PurchaseOrderIssuePersistence::class.java)
    private val candidates = Mockito.mock(PurchaseOrderCandidatePersistence::class.java)
    private val requirements = Mockito.mock(PurchaseRequirementDraftPersistence::class.java)
    private val work = Mockito.mock(WorkPersistencePort::class.java)
    private val workOrder = Mockito.mock(WorkOrderSnapshot::class.java)
    private val subject = ApprovalSubjectRef(
        org, "PROCUREMENT_PO_DELIVERY_AMENDMENT", proposalId, 1L,
    )

    private fun guard(enabled: Boolean = true) = PurchaseOrderDeliveryAmendmentSubject(
        proposals, purchases, candidates, requirements, work,
        Clock.fixed(at, ZoneOffset.UTC), enabled,
    )

    private fun allow() {
        Mockito.`when`(proposals.byId(proposalId)).thenReturn(proposal)
        Mockito.`when`(purchases.effectiveState(poId)).thenReturn(
            PurchaseOrderEffectiveState(po, 1L, "ISSUED", originalLocation),
        )
        Mockito.`when`(candidates.byId(candidate.id)).thenReturn(candidate)
        Mockito.`when`(requirements.byId(candidate.requirementId)).thenReturn(demand)
        Mockito.`when`(work.workOrder(workId)).thenReturn(workOrder)
        Mockito.`when`(workOrder.organizationId).thenReturn(org)
        Mockito.`when`(workOrder.projectId).thenReturn(projectId)
        Mockito.`when`(workOrder.siteId).thenReturn(siteId)
        Mockito.`when`(purchases.validDeliveryLocation(
            org, destination, projectId, siteId, at,
        )).thenReturn(true)
    }

    @Test fun currentExactSubjectIsApprovedOnlyForThatOrderAndVersion() {
        allow()
        assertTrue(guard().supports("PROCUREMENT_PO_DELIVERY_AMENDMENT"))
        assertTrue(guard().isCurrent(subject))
        assertFalse(guard().isCurrent(subject.copy(subjectVersion = 2)))
        assertFalse(guard().isCurrent(subject.copy(organizationId = UUID.randomUUID())))
        assertFalse(guard(false).isCurrent(subject))
    }

    @Test fun cancellationOrEarlierApprovalVersionCannotBeReused() {
        allow()
        Mockito.`when`(purchases.effectiveState(poId)).thenReturn(
            PurchaseOrderEffectiveState(po, 2L, "CANCELLED", originalLocation),
        )
        assertFalse(guard().isCurrent(subject))
        Mockito.`when`(purchases.effectiveState(poId)).thenReturn(
            PurchaseOrderEffectiveState(po, 2L, "ISSUED", destination),
        )
        assertFalse(guard().isCurrent(subject))
    }

    @Test fun wrongLocationOrProjectScopeFailsClosed() {
        allow()
        Mockito.`when`(purchases.validDeliveryLocation(
            org, destination, projectId, siteId, at,
        )).thenReturn(false)
        assertFalse(guard().isCurrent(subject))
        Mockito.`when`(purchases.validDeliveryLocation(
            org, destination, projectId, siteId, at,
        )).thenReturn(true)
        Mockito.`when`(workOrder.organizationId).thenReturn(UUID.randomUUID())
        assertFalse(guard().isCurrent(subject))
    }
}
