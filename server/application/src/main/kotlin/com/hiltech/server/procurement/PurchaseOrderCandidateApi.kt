package com.hiltech.server.procurement

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Buyer-only, explicitly gated S03 draft candidate.
 * This controller cannot approve spending or create/issue a Purchase Order.
 */
@RestController
class PurchaseOrderCandidateController(
    private val service: PurchaseOrderCandidateService,
) {
    @GetMapping("/v1/sourcing/rfqs/{rfqId}/po-candidate-preflight")
    fun preview(
        request: HttpServletRequest,
        @PathVariable rfqId: UUID,
        @RequestParam(name = "quoteId") quoteId: UUID,
    ): PurchaseOrderCandidatePreview =
        service.preview(request.currentBuyer(), rfqId, quoteId)

    @PostMapping("/v1/sourcing/rfqs/{rfqId}/po-candidates")
    fun propose(
        request: HttpServletRequest,
        @PathVariable rfqId: UUID,
        @RequestBody body: ProposePurchaseOrderCandidateRequest,
    ): PurchaseOrderCandidateReceipt {
        val context = HiltechRequestContext.current(request)
        IdempotencyKeyContract.requireMatches(context.idempotencyKey, body.operationId)
        return service.propose(
            ProcurementDraftContext(body.operationId, request.currentBuyer(), context.correlationId),
            rfqId,
            body,
        )
    }
}

private fun HttpServletRequest.currentBuyer(): UUID =
    HiltechRequestContext.current(this).identityId
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            "UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED,
        )
