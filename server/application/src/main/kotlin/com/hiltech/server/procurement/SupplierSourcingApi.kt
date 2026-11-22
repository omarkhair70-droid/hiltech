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

/**
 * Internal sourcing-only endpoints; enabled=false and authority default-deny.
 * Nothing here emails a supplier, publishes competitor quotes or issues a PO.
 */
@RestController
class SupplierSourcingController(private val service: SupplierSourcingService) {
    @PostMapping("/v1/purchase-requirements/{id}/supplier-rfqs")
    fun openRfq(
        request: HttpServletRequest,
        @PathVariable id: UUID,
        @RequestBody body: OpenSupplierRfqRequest,
    ): SupplierRfqReceipt = service.openRfq(request.sourcingContext(body.operationId), id, body)

    @PostMapping("/v1/sourcing/rfqs/{rfqId}/quote-evidence")
    fun recordQuote(
        request: HttpServletRequest,
        @PathVariable rfqId: UUID,
        @RequestBody body: RecordSupplierQuoteRequest,
    ): SupplierQuoteReceipt = service.recordQuote(request.sourcingContext(body.operationId), rfqId, body)

    @GetMapping("/v1/sourcing/rfqs/{rfqId}/comparison")
    fun comparison(request: HttpServletRequest, @PathVariable rfqId: UUID): SupplierRfqComparisonView =
        service.comparison(request.sourcingActor(), rfqId)
}

private fun HttpServletRequest.sourcingActor(): UUID =
    HiltechRequestContext.current(this).identityId
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            "UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED,
        )

private fun HttpServletRequest.sourcingContext(operationId: UUID): ProcurementDraftContext {
    val actor = sourcingActor()
    val ctx = HiltechRequestContext.current(this)
    IdempotencyKeyContract.requireMatches(ctx.idempotencyKey, operationId)
    return ProcurementDraftContext(operationId, actor, ctx.correlationId)
}
