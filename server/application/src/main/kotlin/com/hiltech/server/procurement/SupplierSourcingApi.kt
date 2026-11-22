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

    @GetMapping("/v1/sourcing/organizations/{orgId}/eligible-needs")
    fun eligibleNeeds(request: HttpServletRequest, @PathVariable orgId: UUID): List<SourcingEligibleNeed> =
        service.eligibleNeeds(request.sourcingActor(), orgId)

    @GetMapping("/v1/sourcing/organizations/{orgId}/suppliers")
    fun eligibleSuppliers(request: HttpServletRequest, @PathVariable orgId: UUID): List<SourcingSupplierCandidate> =
        service.eligibleSuppliers(request.sourcingActor(), orgId)

    @GetMapping("/v1/purchase-requirements/{id}/supplier-rfqs")
    fun roundsForRequirement(request: HttpServletRequest, @PathVariable id: UUID): List<SupplierRfqView> =
        service.roundsForRequirement(request.sourcingActor(), id)

    @PostMapping("/v1/sourcing/rfqs/{rfqId}/document-slots")
    fun createQuoteDocumentSlot(
        request: HttpServletRequest,
        @PathVariable rfqId: UUID,
        @RequestBody body: CreateSupplierQuoteDocumentSlotRequest,
    ): SupplierQuoteDocumentSlotReceipt =
        service.createQuoteDocumentSlot(request.sourcingContext(body.operationId), rfqId, body)

    @PostMapping("/v1/sourcing/rfqs/{rfqId}/quote-evidence")
    fun recordQuote(
        request: HttpServletRequest,
        @PathVariable rfqId: UUID,
        @RequestBody body: RecordSupplierQuoteRequest,
    ): SupplierQuoteReceipt = service.recordQuote(request.sourcingContext(body.operationId), rfqId, body)

    @PostMapping("/v1/sourcing/quotes/{quoteId}/technical-assessments")
    fun assessQuote(
        request: HttpServletRequest,
        @PathVariable quoteId: UUID,
        @RequestBody body: RecordSupplierQuoteTechnicalAssessmentRequest,
    ): SupplierQuoteTechnicalAssessmentReceipt =
        service.recordTechnicalAssessment(request.sourcingContext(body.operationId), quoteId, body)

    @GetMapping("/v1/sourcing/rfqs/{rfqId}/comparison")
    fun comparison(request: HttpServletRequest, @PathVariable rfqId: UUID): SupplierRfqComparisonView =
        service.comparison(request.sourcingActor(), rfqId)
}

/**
 * Separate administrative supplier-registry permission.
 * Ordinary buyers and technical reviewers cannot maintain supplier links.
 */
@RestController
class SupplierMasterDataController(
    private val masterData: SupplierMasterDataService,
) {
    @PostMapping("/v1/sourcing/organizations/{orgId}/supplier-links")
    fun changeSupplierLink(
        request: HttpServletRequest,
        @PathVariable orgId: UUID,
        @RequestBody body: ChangeSupplierLinkRequest,
    ): SupplierLinkEventReceipt =
        masterData.changeSupplierLink(request.sourcingContext(body.operationId), orgId, body)
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
