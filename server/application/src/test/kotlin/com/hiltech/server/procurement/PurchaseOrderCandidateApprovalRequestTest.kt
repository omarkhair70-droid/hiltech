package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalRequestResult
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class PurchaseOrderCandidateApprovalRequestTest {
    private val org = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val candidateId = UUID.randomUUID()
    private val rfq = UUID.randomUUID()
    private val supplier = UUID.randomUUID()
    private val quote = UUID.randomUUID()
    private val op = UUID.randomUUID()
    private val approvalId = UUID.randomUUID()
    private val ctx = ProcurementDraftContext(op, actor, "s03-approval")
    private val candidate = PurchaseOrderCandidateRecord(
        candidateId, UUID.randomUUID(), org, rfq, UUID.randomUUID(), 3L,
        quote, 2, supplier, UUID.randomUUID(), BigDecimal("100"), "M",
        "EGP", BigDecimal("2"), BigDecimal.ZERO, BigDecimal("200"),
        "a".repeat(64), "Buyer sourcing decision rationale",
        actor, Instant.parse("2026-11-22T16:00:00Z"), "s03-approval",
    )
    private val candidates = Mockito.mock(PurchaseOrderCandidatePersistence::class.java)
    private val buyer = Mockito.mock(SupplierSourcingAuthorizationPort::class.java)
    private val guard = Mockito.mock(PurchaseOrderCandidateApprovalSubject::class.java)
    private val approvals = Mockito.mock(ApprovalEngineService::class.java)
    private val subject = ApprovalSubjectRef(org, "PROCUREMENT_PO_CANDIDATE", candidateId, 1L)
    private val request = RequestPurchaseCandidateApprovalRequest(op)

    private fun service(
        candidatesEnabled: Boolean = true,
        requestsEnabled: Boolean = true,
        policy: String = "PROCUREMENT_STANDARD",
    ) = PurchaseOrderCandidateApprovalRequestService(
        candidates, buyer, guard, approvals, candidatesEnabled, requestsEnabled, policy,
    )

    private fun allow() {
        Mockito.`when`(candidates.byId(candidateId)).thenReturn(candidate)
        Mockito.`when`(buyer.canManageSourcing(actor, org)).thenReturn(true)
        Mockito.`when`(guard.isCurrent(subject)).thenReturn(true)
        Mockito.`when`(approvals.requestException(
            actor, op, subject, "PROCUREMENT_STANDARD",
            "PROCUREMENT_PO_CANDIDATE_FOR_APPROVAL",
            null, "s03-approval", false,
        )).thenReturn(ApprovalRequestResult(
            approvalRequired = true, approvalRequestId = approvalId,
            state = ApprovalRequestState.PENDING,
            policyKey = "PROCUREMENT_STANDARD", policyVersion = 7,
            authorityKey = "PROCUREMENT_APPROVER", replayed = false,
        ))
    }

    @Test fun configuredPolicyAndCurrentSourceRequestExistingCanonicalApprovalEngine() {
        allow()
        val result = service().request(ctx, candidateId, request)
        assertEquals(approvalId, result.approvalRequestId)
        assertEquals("PENDING", result.approvalState)
        assertEquals(7, result.policyVersion)
        assertTrue(result.approvalRequired)
        assertFalse(result.purchaseAuthorized)
        assertFalse(result.purchaseOrderIssued)
        val calls = Mockito.mockingDetails(approvals).invocations
            .filter { it.method.name == "requestException" }
        assertTrue(calls.isNotEmpty())
        val actual = calls.last()
        assertEquals(false, actual.arguments.last())
        assertEquals(subject, actual.arguments[2])
    }

    @Test fun unknownPolicyNeverGetsInventedOrGrantsApproval() {
        allow()
        assertEquals("PROCUREMENT_APPROVAL_POLICY_NOT_CONFIGURED", assertThrows<ProductApiException> {
            service(policy = "invalid policy!").request(ctx, candidateId, request)
        }.code)
    }

    @Test fun buyerPermissionAndCurrentSourceAreRechecked() {
        allow()
        Mockito.`when`(buyer.canManageSourcing(actor, org)).thenReturn(false)
        assertEquals("PROCUREMENT_APPROVAL_REQUEST_UNAVAILABLE", assertThrows<ProductApiException> {
            service().request(ctx, candidateId, request)
        }.code)
        Mockito.`when`(buyer.canManageSourcing(actor, org)).thenReturn(true)
        Mockito.`when`(guard.isCurrent(subject)).thenReturn(false)
        assertEquals("PROCUREMENT_APPROVAL_SOURCE_CHANGED", assertThrows<ProductApiException> {
            service().request(ctx, candidateId, request)
        }.code)
    }

    @Test fun explicitIncidentOverridesKeepApprovalUnavailable() {
        allow()
        assertEquals("PROCUREMENT_APPROVAL_REQUEST_UNAVAILABLE", assertThrows<ProductApiException> {
            service(requestsEnabled = false).request(ctx, candidateId, request)
        }.code)
        assertEquals("PROCUREMENT_APPROVAL_REQUEST_UNAVAILABLE", assertThrows<ProductApiException> {
            service(candidatesEnabled = false).request(ctx, candidateId, request)
        }.code)
    }

    @Test fun noApprovalRequiredByExplicitCompanyPolicyIsNotAPurchaseOrder() {
        allow()
        Mockito.`when`(approvals.requestException(
            actor, op, subject, "PROCUREMENT_STANDARD",
            "PROCUREMENT_PO_CANDIDATE_FOR_APPROVAL",
            null, "s03-approval", false,
        )).thenReturn(ApprovalRequestResult(
            approvalRequired = false, approvalRequestId = null,
            state = null, policyKey = "PROCUREMENT_STANDARD", policyVersion = 8,
            authorityKey = null, replayed = false,
        ))
        val result = service().request(ctx, candidateId, request)
        assertFalse(result.approvalRequired)
        assertFalse(result.purchaseAuthorized)
        assertFalse(result.purchaseOrderIssued)
    }
}
