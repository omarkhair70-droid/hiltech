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
class PurchaseRequirementDraftController(private val service: PurchaseRequirementDraftService) {
    @PostMapping("/v1/work-orders/{workOrderId}/purchase-requirements")
    fun create(
        request: HttpServletRequest,
        @PathVariable workOrderId: UUID,
        @RequestBody body: CreatePurchaseRequirementDraftRequest,
    ): PurchaseRequirementDraftReceipt =
        service.create(request.purchaseDraftContext(body.operationId), workOrderId, body)

    @GetMapping("/v1/work-orders/{workOrderId}/purchase-requirements")
    fun forWork(request: HttpServletRequest, @PathVariable workOrderId: UUID): List<PurchaseRequirementDraftView> =
        service.forWork(request.purchaseDraftActor(), workOrderId)

    @GetMapping("/v1/purchase-requirements/{id}")
    fun detail(request: HttpServletRequest, @PathVariable id: UUID): PurchaseRequirementDraftView =
        service.detail(request.purchaseDraftActor(), id)
}

private fun HttpServletRequest.purchaseDraftActor(): UUID =
    HiltechRequestContext.current(this).identityId
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            "UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED,
        )

private fun HttpServletRequest.purchaseDraftContext(operationId: UUID): ProcurementDraftContext {
    val actor = purchaseDraftActor()
    val context = HiltechRequestContext.current(this)
    IdempotencyKeyContract.requireMatches(context.idempotencyKey, operationId)
    return ProcurementDraftContext(operationId, actor, context.correlationId)
}
