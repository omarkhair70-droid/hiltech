package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalPolicySnapshot
import com.hiltech.server.approval.ApprovalRequestResult
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.work.WorkOrderSnapshot
import com.hiltech.server.work.WorkPersistencePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PurchaseOrderDeliveryAmendmentServiceTest {
    private val at = Instant.parse("2026-11-22T17:00:00Z")
    private val org = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val poId = UUID.randomUUID()
    private val proposalId = UUID.randomUUID()
    private val oldLocation = UUID.randomUUID()
    private val newLocation = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val siteId = UUID.randomUUID()
    private val op = UUID.randomUUID()
    private val proposalOperation = UUID.randomUUID()
    private val approvalId = UUID.randomUUID()
    private val po = PurchaseOrderIssuedRecord(
        poId, UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(),
        "PROCUREMENT_STANDARD", 7, "PROCUREMENT_PO_ISSUER", UUID.randomUUID(),
        "PO-000093", 93, UUID.randomUUID(), oldLocation,
        UUID.randomUUID(), UUID.randomUUID(), "f".repeat(64),
        actor, at, "amendment-test",
    )
    private val candidate = PurchaseOrderCandidateRecord(
        po.candidateId, UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(),
        3, po.quoteId, 1, po.supplierOrganizationId, po.stockItemId,
        BigDecimal("10"), "PCS", "EGP", BigDecimal("25"), BigDecimal.ZERO,
        BigDecimal("250"), po.quoteSha256, "Base supplier quote", actor, at, "test",
    )
    private val demand = PurchaseRequirementDraftRecord(
        candidate.requirementId, UUID.randomUUID(), org, workId, 1,
        candidate.stockItemId, 1, BigDecimal("10"), "PCS",
        "Requested field material", "WAREHOUSE_REVIEW_REQUIRED",
        null, at, actor, at, "test",
    )
    private val proposal = PurchaseOrderDeliveryAmendmentRecord(
        proposalId, proposalOperation, org, poId, 1L, oldLocation,
        newLocation, "Warehouse destination changed before dispatch",
        actor, at, "amendment-test",
    )
    private val ctx = ProcurementDraftContext(op, actor, "s03-amendment-test")
    private val proposals = Mockito.mock(PurchaseOrderDeliveryAmendmentPersistence::class.java)
    private val purchases = Mockito.mock(PurchaseOrderIssuePersistence::class.java)
    private val candidates = Mockito.mock(PurchaseOrderCandidatePersistence::class.java)
    private val requirements = Mockito.mock(PurchaseRequirementDraftPersistence::class.java)
    private val work = Mockito.mock(WorkPersistencePort::class.java)
    private val workOrder = Mockito.mock(WorkOrderSnapshot::class.java)
    private val issuer = Mockito.mock(PurchaseOrderIssueService::class.java)
    private val guard = Mockito.mock(PurchaseOrderDeliveryAmendmentSubject::class.java)
    private val approvals = Mockito.mock(ApprovalEngineService::class.java)
    private val approvalRecords = Mockito.mock(ApprovalPersistencePort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private var applied: PurchaseOrderLifecycleEvent? = null
    private var recorded: PurchaseOrderDeliveryAmendmentRecord? = null
    private val commands = object : IdempotentCommandExecutor {
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome) =
            IdempotentCommandExecution(action(), false)
    }
    private val subject = ApprovalSubjectRef(org, "PROCUREMENT_PO_DELIVERY_AMENDMENT",
        proposalId, 1L)
    private val policy = ApprovalPolicySnapshot(
        org, "PO_DELIVERY_POLICY", 5, true, ApprovalPolicyMode.SINGLE,
        "PROCUREMENT_PO_DELIVERY_APPROVER", true,
    )
    private val decision = PurchaseOrderApprovalSnapshot(
        approvalId, 5, ApprovalRequestState.APPROVED, 1,
    )

    private fun service(
        enabled: Boolean = true,
        policyKey: String = "PO_DELIVERY_POLICY",
    ) = PurchaseOrderDeliveryAmendmentService(
        proposals, purchases, candidates, requirements, work, issuer, guard,
        approvals, approvalRecords, commands, audit,
        Clock.fixed(at, ZoneOffset.UTC), enabled, policyKey,
    )

    private fun allowed() {
        Mockito.`when`(purchases.byId(poId)).thenReturn(po)
        Mockito.`when`(purchases.lockOrder(poId, org)).thenReturn(true)
        Mockito.`when`(purchases.lockDeliveryLocation(org, newLocation)).thenReturn(true)
        Mockito.`when`(purchases.effectiveState(poId)).thenReturn(
            PurchaseOrderEffectiveState(po, 1, "ISSUED", oldLocation),
        )
        Mockito.`when`(purchases.validDeliveryLocation(
            org, newLocation, projectId, siteId, at,
        )).thenReturn(true)
        Mockito.`when`(candidates.byId(po.candidateId)).thenReturn(candidate)
        Mockito.`when`(requirements.byId(candidate.requirementId)).thenReturn(demand)
        Mockito.`when`(work.workOrder(workId)).thenReturn(workOrder)
        Mockito.`when`(workOrder.organizationId).thenReturn(org)
        Mockito.`when`(workOrder.projectId).thenReturn(projectId)
        Mockito.`when`(workOrder.siteId).thenReturn(siteId)
        Mockito.`when`(proposals.byId(proposalId)).thenReturn(proposal)
        Mockito.`when`(guard.isCurrent(subject)).thenReturn(true)
        Mockito.`when`(approvalRecords.loadActivePolicy(
            org, "PO_DELIVERY_POLICY", at,
        )).thenReturn(policy)
        Mockito.`when`(purchases.lockApprovedDeliveryAmendment(
            org, proposalId, "PO_DELIVERY_POLICY",
        )).thenReturn(decision)
        Mockito.doAnswer {
            applied = it.getArgument(0)
            Unit
        }.`when`(purchases).appendLifecycleEvent(
            Mockito.any(PurchaseOrderLifecycleEvent::class.java)
                ?: PurchaseOrderLifecycleEvent(
                    UUID.randomUUID(), op, org, poId, 2L, "CANCELLED",
                    "Safe fallback event", actor, at, "test",
                ),
        )
        Mockito.`when`(purchases.lifecycleEvents(poId)).thenAnswer {
            applied?.let(::listOf) ?: emptyList<PurchaseOrderLifecycleEvent>()
        }
    }

    @Test fun approvedAmendmentAppendsV2AndKeepsCommercialOrderImmutable() {
        allowed()
        val receipt = service().apply(
            ctx, proposalId, ApplyPurchaseOrderDeliveryAmendmentRequest(op, 1),
        )
        assertEquals("ISSUED", receipt.state)
        assertEquals(2L, receipt.orderVersion)
        assertEquals(newLocation, receipt.effectiveDestinationId)
        assertEquals(approvalId, receipt.approvalRequestId)
        assertFalse(receipt.supplierNotified)
        assertFalse(receipt.financeChanged)
        assertFalse(receipt.inventoryChanged)
        val recorded = requireNotNull(applied)
        assertEquals("DELIVERY_AMENDED", recorded.type)
        assertEquals(newLocation, recorded.deliveryStorageLocationId)
        assertEquals(proposalId, recorded.amendmentProposalId)
        assertEquals(approvalId, recorded.approvalRequestId)
        assertEquals("PO-000093", po.code)
    }

    @Test fun pendingOrOldPolicyApprovalCannotApplyCommercialChange() {
        allowed()
        Mockito.`when`(purchases.lockApprovedDeliveryAmendment(
            org, proposalId, "PO_DELIVERY_POLICY",
        )).thenReturn(decision.copy(state = ApprovalRequestState.PENDING))
        assertEquals("PROCUREMENT_PO_DELIVERY_AMENDMENT_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service().apply(ctx, proposalId,
                    ApplyPurchaseOrderDeliveryAmendmentRequest(op, 1))
            }.code)
        Mockito.`when`(purchases.lockApprovedDeliveryAmendment(
            org, proposalId, "PO_DELIVERY_POLICY",
        )).thenReturn(decision)
        Mockito.`when`(approvalRecords.loadActivePolicy(
            org, "PO_DELIVERY_POLICY", at,
        )).thenReturn(policy.copy(versionNumber = 6))
        assertEquals("PROCUREMENT_PO_DELIVERY_AMENDMENT_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service().apply(ctx, proposalId,
                    ApplyPurchaseOrderDeliveryAmendmentRequest(op, 1))
            }.code)
        assertEquals(null, applied)
    }

    @Test fun revokedOrNewerOrderCannotReuseOldApproval() {
        allowed()
        Mockito.`when`(purchases.effectiveState(poId)).thenReturn(
            PurchaseOrderEffectiveState(po, 2, "ISSUED", newLocation),
        )
        assertEquals("PROCUREMENT_PO_DELIVERY_AMENDMENT_STALE",
            assertThrows<ProductApiException> {
                service().apply(ctx, proposalId,
                    ApplyPurchaseOrderDeliveryAmendmentRequest(op, 1))
            }.code)
        Mockito.`when`(purchases.effectiveState(poId)).thenReturn(
            PurchaseOrderEffectiveState(po, 2, "CANCELLED", oldLocation),
        )
        assertEquals("PROCUREMENT_PO_DELIVERY_AMENDMENT_STALE",
            assertThrows<ProductApiException> {
                service().apply(ctx, proposalId,
                    ApplyPurchaseOrderDeliveryAmendmentRequest(op, 1))
            }.code)
    }

    @Test fun proposalRequiresChangedDestinationAndApprovedWorkLocation() {
        allowed()
        Mockito.`when`(proposals.byId(Mockito.any(UUID::class.java)
            ?: proposalId)).thenAnswer { recorded }
        Mockito.doAnswer { invocation ->
            recorded = invocation.getArgument(0)
            Unit
        }.`when`(proposals).insert(
            Mockito.any(PurchaseOrderDeliveryAmendmentRecord::class.java) ?: proposal,
        )
        val chosen = service().propose(
            ctx, poId, ProposePurchaseOrderDeliveryAmendmentRequest(
                op, 1, newLocation, "Change delivery to main warehouse before supplier dispatch",
            ),
        )
        assertEquals(1, chosen.expectedOrderVersion)
        assertEquals("DRAFT_FOR_APPROVAL", chosen.state)
        val row = requireNotNull(recorded)
        assertEquals(oldLocation, row.previousDestinationId)
        assertEquals(newLocation, row.newDestinationId)
        assertEquals(1L, row.baseVersion)
    }

    @Test fun disabledFeatureAndMalformedPolicyCannotRequestOrApplyApproval() {
        allowed()
        assertEquals("PROCUREMENT_PO_DELIVERY_AMENDMENT_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service(enabled = false).apply(ctx, proposalId,
                    ApplyPurchaseOrderDeliveryAmendmentRequest(op, 1))
            }.code)
        assertEquals("PROCUREMENT_PO_DELIVERY_AMENDMENT_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service(policyKey = "invalid configured policy").requestApproval(ctx, proposalId,
                    RequestPurchaseOrderDeliveryAmendmentApproval(op))
            }.code)
        Mockito.verifyNoInteractions(approvals)
    }

    @Test fun blankPolicyUsesCompanyDefaultWithCurrentSourceAndIndependentEngineReview() {
        allowed()
        Mockito.`when`(guard.isCurrent(subject)).thenReturn(false)
        assertEquals("PROCUREMENT_PO_DELIVERY_AMENDMENT_STALE", assertThrows<ProductApiException> {
            service(policyKey = "").requestApproval(ctx, proposalId, RequestPurchaseOrderDeliveryAmendmentApproval(op))
        }.code)
        Mockito.verifyNoInteractions(approvals)
        Mockito.`when`(guard.isCurrent(subject)).thenReturn(true)
        val defaultKey = "PROCUREMENT_PO_DELIVERY_REVIEW"
        Mockito.`when`(approvals.requestException(actor, op, subject, defaultKey, "PROCUREMENT_PO_DELIVERY_CHANGE", null,
            ctx.correlationId, allowAuthorityBypass = false)).thenReturn(ApprovalRequestResult(true, approvalId,
            ApprovalRequestState.PENDING, defaultKey, 1, "PROCUREMENT_PO_DELIVERY_REVIEWER", false))
        val result = service(policyKey = "").requestApproval(ctx, proposalId, RequestPurchaseOrderDeliveryAmendmentApproval(op))
        assertEquals(defaultKey, result.policyKey)
        assertEquals("PENDING", result.approvalState)
        assertEquals(approvalId, result.approvalRequestId)
        assertEquals(null, applied)
    }
}
