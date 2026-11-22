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

    @PostMapping("/v1/purchase-requirements/{id}/submit")
    fun submit(
        request: HttpServletRequest,
        @PathVariable id: UUID,
        @RequestBody body: SubmitPurchaseRequirementRequest,
    ): PurchaseRequirementDraftReceipt =
        service.submit(request.purchaseDraftContext(body.operationId), id, body)

    @PostMapping("/v1/purchase-requirements/{id}/revise")
    fun revise(
        request: HttpServletRequest,
        @PathVariable id: UUID,
        @RequestBody body: RevisePurchaseRequirementRequest,
    ): PurchaseRequirementDraftReceipt =
        service.revise(request.purchaseDraftContext(body.operationId), id, body)

    @PostMapping("/v1/purchase-requirements/{id}/technical-review")
    fun technicalReview(
        request: HttpServletRequest,
        @PathVariable id: UUID,
        @RequestBody body: ReviewPurchaseRequirementRequest,
    ): PurchaseRequirementDraftReceipt =
        service.technicalReview(request.purchaseDraftContext(body.operationId), id, body)

    @GetMapping("/v1/projects/{projectId}/purchase-requirements/intake")
    fun projectIntake(
        request: HttpServletRequest,
        @PathVariable projectId: UUID,
    ): List<PurchaseRequirementDraftView> =
        service.projectIntake(request.purchaseDraftActor(), projectId)

    @PostMapping("/v1/purchase-requirements/{id}/withdraw")
    fun withdraw(
        request: HttpServletRequest,
        @PathVariable id: UUID,
        @RequestBody body: WithdrawPurchaseRequirementRequest,
    ): PurchaseRequirementDraftReceipt =
        service.withdraw(request.purchaseDraftContext(body.operationId), id, body)

    @GetMapping("/v1/work-orders/{workOrderId}/purchase-requirements")
    fun forWork(request: HttpServletRequest, @PathVariable workOrderId: UUID): List<PurchaseRequirementDraftView> =
        service.forWork(request.purchaseDraftActor(), workOrderId)

    @GetMapping("/v1/purchase-requirements/{id}/review-preview")
    fun reviewPreview(
        request: HttpServletRequest,
        @PathVariable id: UUID,
    ): PurchaseRequirementReviewPreview = service.reviewPreview(request.purchaseDraftActor(), id)

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
