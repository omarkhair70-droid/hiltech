package com.hiltech.server.procurement

import com.hiltech.server.companysettings.PurchaseOrderNumberingResponse
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

/** Company-authorized PO issuance, NOT vendor dispatch, stock receipt or payment. */
@RestController
class PurchaseOrderIssueController(private val service: PurchaseOrderIssueService) {
    @GetMapping("/v1/procurement/organizations/{organizationId}/po-numbering")
    fun numbering(servlet: HttpServletRequest, @PathVariable organizationId: UUID): PurchaseOrderNumberingResponse =
        service.numbering(currentActor(servlet), organizationId)

    @GetMapping("/v1/procurement/organizations/{organizationId}/po-issuer-queue")
    fun queue(
        servlet: HttpServletRequest,
        @PathVariable organizationId: UUID,
    ): List<PurchaseOrderIssuerQueueItem> = service.issuerQueue(currentActor(servlet), organizationId)

    @GetMapping("/v1/procurement/po-candidates/{candidateId}/delivery-destinations")
    fun destinations(
        servlet: HttpServletRequest,
        @PathVariable candidateId: UUID,
    ): List<PurchaseOrderDeliveryDestination> =
        service.deliveryDestinations(currentActor(servlet), candidateId)

    private fun currentActor(servlet: HttpServletRequest): UUID =
        HiltechRequestContext.current(servlet).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED,
            )

    @PostMapping("/v1/procurement/po-candidates/{candidateId}/issue")
    fun issue(
        servlet: HttpServletRequest,
        @PathVariable candidateId: UUID,
        @RequestBody body: IssuePurchaseOrderRequest,
    ): PurchaseOrderIssueReceipt {
        val request = HiltechRequestContext.current(servlet)
        IdempotencyKeyContract.requireMatches(request.idempotencyKey, body.operationId)
        val actor = request.identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED,
            )
        return service.issue(
            ProcurementDraftContext(body.operationId, actor, request.correlationId),
            candidateId, body,
        )
    }
}
