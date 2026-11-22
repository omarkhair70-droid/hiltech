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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class PurchaseOrderDeliveryAmendmentController(
    private val service: PurchaseOrderDeliveryAmendmentService,
) {
    @GetMapping("/v1/procurement/purchase-orders/{orderId}/amendment-destinations")
    fun destinations(
        servlet: HttpServletRequest,
        @PathVariable orderId: UUID,
    ): List<PurchaseOrderDeliveryDestination> =
        service.destinations(servlet.currentIssuerForAmendment(), orderId)

    @GetMapping("/v1/procurement/purchase-orders/{orderId}/current-delivery-amendment")
    fun current(
        servlet: HttpServletRequest,
        @PathVariable orderId: UUID,
    ): PurchaseOrderDeliveryAmendmentStatus? =
        service.pending(servlet.currentIssuerForAmendment(), orderId)

    @PostMapping("/v1/procurement/purchase-orders/{orderId}/delivery-amendments")
    fun propose(
        servlet: HttpServletRequest,
        @PathVariable orderId: UUID,
        @RequestBody body: ProposePurchaseOrderDeliveryAmendmentRequest,
    ): PurchaseOrderDeliveryAmendmentProposalReceipt {
        val ctx = HiltechRequestContext.current(servlet)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey, body.operationId)
        return service.propose(
            ProcurementDraftContext(
                body.operationId, servlet.currentIssuerForAmendment(), ctx.correlationId,
            ),
            orderId, body,
        )
    }

    @PostMapping("/v1/procurement/po-delivery-amendments/{proposalId}/request-approval")
    fun requestApproval(
        servlet: HttpServletRequest,
        @PathVariable proposalId: UUID,
        @RequestBody body: RequestPurchaseOrderDeliveryAmendmentApproval,
    ): PurchaseOrderDeliveryAmendmentApprovalReceipt {
        val ctx = HiltechRequestContext.current(servlet)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey, body.operationId)
        return service.requestApproval(
            ProcurementDraftContext(
                body.operationId, servlet.currentIssuerForAmendment(), ctx.correlationId,
            ),
            proposalId, body,
        )
    }

    @PostMapping("/v1/procurement/po-delivery-amendments/{proposalId}/apply")
    fun apply(
        servlet: HttpServletRequest,
        @PathVariable proposalId: UUID,
        @RequestBody body: ApplyPurchaseOrderDeliveryAmendmentRequest,
    ): PurchaseOrderDeliveryAmendmentAppliedReceipt {
        val ctx = HiltechRequestContext.current(servlet)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey, body.operationId)
        return service.apply(
            ProcurementDraftContext(
                body.operationId, servlet.currentIssuerForAmendment(), ctx.correlationId,
            ),
            proposalId, body,
        )
    }
}

private fun HttpServletRequest.currentIssuerForAmendment(): UUID =
    HiltechRequestContext.current(this).identityId
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            "UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED,
        )
