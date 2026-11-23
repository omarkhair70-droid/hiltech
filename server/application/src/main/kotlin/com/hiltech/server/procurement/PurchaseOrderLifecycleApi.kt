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
class PurchaseOrderLifecycleController(private val service: PurchaseOrderLifecycleService) {
    @GetMapping("/v1/procurement/organizations/{organizationId}/issued-pos")
    fun issued(
        servlet: HttpServletRequest,
        @PathVariable organizationId: UUID,
    ): List<PurchaseOrderLifecycleSummary> =
        service.issuedOrders(servlet.currentPurchaseIssuer(), organizationId)

    @PostMapping("/v1/procurement/purchase-orders/{purchaseOrderId}/cancel")
    fun cancel(
        servlet: HttpServletRequest,
        @PathVariable purchaseOrderId: UUID,
        @RequestBody body: CancelPurchaseOrderRequest,
    ): CancelPurchaseOrderReceipt {
        val ctx = HiltechRequestContext.current(servlet)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey, body.operationId)
        return service.cancel(
            ProcurementDraftContext(body.operationId, servlet.currentPurchaseIssuer(), ctx.correlationId),
            purchaseOrderId, body,
        )
    }
}

private fun HttpServletRequest.currentPurchaseIssuer(): UUID =
    HiltechRequestContext.current(this).identityId
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            "UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED,
        )
