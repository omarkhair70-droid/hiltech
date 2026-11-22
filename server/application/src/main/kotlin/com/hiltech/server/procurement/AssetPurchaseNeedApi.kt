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
class AssetPurchaseNeedController(private val needs: AssetPurchaseNeedService) {
    @PostMapping("/v1/assets/{assetId}/purchase-needs")
    fun create(req: HttpServletRequest, @PathVariable assetId: UUID,
               @RequestBody input: CreateAssetPurchaseNeedRequest): AssetPurchaseNeedReceipt =
        needs.create(req.command(input.operationId), assetId, input)

    @GetMapping("/v1/assets/{assetId}/purchase-needs")
    fun forAsset(req: HttpServletRequest, @PathVariable assetId: UUID): List<AssetPurchaseNeedView> =
        needs.list(req.actor(), assetId)

    @GetMapping("/v1/asset-purchase-needs/{id}")
    fun detail(req: HttpServletRequest, @PathVariable id: UUID): AssetPurchaseNeedView =
        needs.detail(req.actor(), id)

    @PostMapping("/v1/asset-purchase-needs/{id}/submit")
    fun submit(req: HttpServletRequest, @PathVariable id: UUID,
               @RequestBody input: SubmitPurchaseRequirementRequest): AssetPurchaseNeedReceipt =
        needs.submit(req.command(input.operationId), id, input)

    @PostMapping("/v1/asset-purchase-needs/{id}/revise")
    fun revise(req: HttpServletRequest, @PathVariable id: UUID,
               @RequestBody input: ReviseAssetPurchaseNeedRequest): AssetPurchaseNeedReceipt =
        needs.revise(req.command(input.operationId), id, input)

    @PostMapping("/v1/asset-purchase-needs/{id}/withdraw")
    fun withdraw(req: HttpServletRequest, @PathVariable id: UUID,
                 @RequestBody input: WithdrawPurchaseRequirementRequest): AssetPurchaseNeedReceipt =
        needs.withdraw(req.command(input.operationId), id, input)

    @PostMapping("/v1/asset-purchase-needs/{id}/technical-review")
    fun review(req: HttpServletRequest, @PathVariable id: UUID,
               @RequestBody input: ReviewPurchaseRequirementRequest): AssetPurchaseNeedReceipt =
        needs.review(req.command(input.operationId), id, input)

    private fun HttpServletRequest.actor(): UUID =
        HiltechRequestContext.current(this).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException("UNAUTHENTICATED",
                "Authentication required.", HttpStatus.UNAUTHORIZED)

    private fun HttpServletRequest.command(op: UUID): ProcurementDraftContext {
        val request = HiltechRequestContext.current(this)
        IdempotencyKeyContract.requireMatches(request.idempotencyKey, op)
        return ProcurementDraftContext(op, actor(), request.correlationId)
    }
}
