package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class RequestPurchaseCandidateApprovalRequest(val operationId: UUID)

data class PurchaseCandidateApprovalReceipt(
    val candidateId: UUID,
    val candidateVersion: Long,
    val approvalRequestId: UUID?,
    val approvalRequired: Boolean,
    val approvalState: String?,
    val policyKey: String,
    val policyVersion: Int,
    val replayed: Boolean,
    val purchaseAuthorized: Boolean = false,
    val purchaseOrderIssued: Boolean = false,
)

/**
 * Bridge into the existing Phase-2 ApprovalEngine. Never provisions or guesses
 * a company approval policy or decision maker, never issues a PO.
 */
@Service
class PurchaseOrderCandidateApprovalRequestService @Autowired constructor(
    private val candidates: PurchaseOrderCandidatePersistence,
    private val buyerAuthorization: SupplierSourcingAuthorizationPort,
    private val subjectVersion: PurchaseOrderCandidateApprovalSubject,
    private val approvals: ApprovalEngineService,
    @Value("\${hiltech.procurement.po-candidates.enabled:false}")
    private val candidatesEnabled: Boolean = false,
    @Value("\${hiltech.procurement.approval-request.enabled:false}")
    private val requestsEnabled: Boolean = false,
    @Value("\${hiltech.procurement.approval-policy-key:PROCUREMENT_PO_REVIEW}")
    private val configuredPolicyKey: String = "",
) {
    fun request(
        context: ProcurementDraftContext,
        candidateId: UUID,
        request: RequestPurchaseCandidateApprovalRequest,
    ): PurchaseCandidateApprovalReceipt {
        if (!candidatesEnabled || !requestsEnabled) unavailable()
        if (request.operationId != context.operationId) malformed()
        val candidate = candidates.byId(candidateId) ?: unavailable()
        if (!buyerAuthorization.canManageSourcing(context.actor, candidate.organizationId))
            unavailable()
        val key = procurementReviewPolicyKey(configuredPolicyKey)
        if (!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) {
            throw ProductApiException(
                "PROCUREMENT_APPROVAL_POLICY_NOT_CONFIGURED",
                "HILTECH has not activated an approval policy for purchase candidates.",
                HttpStatus.SERVICE_UNAVAILABLE,
            )
        }
        val subject = ApprovalSubjectRef(
            organizationId = candidate.organizationId,
            subjectType = "PROCUREMENT_PO_CANDIDATE",
            subjectId = candidate.id,
            subjectVersion = 1L,
        )
        if (!subjectVersion.isCurrent(subject)) {
            throw ProductApiException(
                "PROCUREMENT_APPROVAL_SOURCE_CHANGED",
                "Quote or underlying project, supplier or technical evidence changed.",
                HttpStatus.CONFLICT,
            )
        }
        val result = approvals.requestException(
            actorUserId = context.actor,
            operationId = context.operationId,
            subject = subject,
            policyKey = key,
            reasonCode = "PROCUREMENT_PO_CANDIDATE_FOR_APPROVAL",
            safeReasonSummary = null,
            correlationId = context.correlationId,
            allowAuthorityBypass = false,
        )
        return PurchaseCandidateApprovalReceipt(
            candidateId = candidate.id,
            candidateVersion = 1L,
            approvalRequestId = result.approvalRequestId,
            approvalRequired = result.approvalRequired,
            approvalState = result.state?.name,
            policyKey = result.policyKey,
            policyVersion = result.policyVersion,
            replayed = result.replayed,
        )
    }

    private fun unavailable(): Nothing = throw ProductApiException(
        "PROCUREMENT_APPROVAL_REQUEST_UNAVAILABLE",
        "Purchase approval request is not available.",
        HttpStatus.NOT_FOUND,
    )
    private fun malformed(): Nothing = throw ProductApiException(
        "PROCUREMENT_APPROVAL_REQUEST_INVALID",
        "Purchase approval operation is invalid.",
        HttpStatus.BAD_REQUEST,
    )
}

@RestController
class PurchaseOrderCandidateApprovalRequestController(
    private val service: PurchaseOrderCandidateApprovalRequestService,
) {
    @PostMapping("/v1/sourcing/po-candidates/{candidateId}/request-approval")
    fun request(
        servlet: HttpServletRequest,
        @PathVariable candidateId: UUID,
        @RequestBody body: RequestPurchaseCandidateApprovalRequest,
    ): PurchaseCandidateApprovalReceipt {
        val context = HiltechRequestContext.current(servlet)
        IdempotencyKeyContract.requireMatches(context.idempotencyKey, body.operationId)
        val actor = context.identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED,
            )
        return service.request(
            ProcurementDraftContext(body.operationId, actor, context.correlationId),
            candidateId,
            body,
        )
    }
}
