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
class WarehouseReplenishmentController(
    private val replenishment: WarehouseReplenishmentService,
) {
    @PostMapping("/v1/warehouse/storage-locations/{locationId}/purchase-requirements")
    fun create(servlet: HttpServletRequest,
               @PathVariable locationId: UUID,
               @RequestBody body: CreateWarehouseReplenishmentRequest):
        WarehouseReplenishmentReceipt =
        replenishment.create(servlet.procurementCommand(body.operationId), locationId, body)

    @GetMapping("/v1/warehouse/storage-locations/{locationId}/purchase-requirements")
    fun list(servlet: HttpServletRequest, @PathVariable locationId: UUID) =
        replenishment.list(servlet.actor(), locationId)

    @GetMapping("/v1/warehouse-replenishment/{id}")
    fun detail(servlet: HttpServletRequest, @PathVariable id: UUID) =
        replenishment.detail(servlet.actor(), id)

    @PostMapping("/v1/warehouse-replenishment/{id}/submit")
    fun submit(servlet: HttpServletRequest, @PathVariable id: UUID,
               @RequestBody body: SubmitPurchaseRequirementRequest) =
        replenishment.submit(servlet.procurementCommand(body.operationId), id, body)

    @PostMapping("/v1/warehouse-replenishment/{id}/revise")
    fun revise(servlet: HttpServletRequest, @PathVariable id: UUID,
               @RequestBody body: ReviseWarehouseReplenishmentRequest) =
        replenishment.revise(servlet.procurementCommand(body.operationId), id, body)

    @PostMapping("/v1/warehouse-replenishment/{id}/withdraw")
    fun withdraw(servlet: HttpServletRequest, @PathVariable id: UUID,
                 @RequestBody body: WithdrawPurchaseRequirementRequest) =
        replenishment.withdraw(servlet.procurementCommand(body.operationId), id, body)

    @PostMapping("/v1/warehouse-replenishment/{id}/technical-review")
    fun review(servlet: HttpServletRequest, @PathVariable id: UUID,
               @RequestBody body: ReviewPurchaseRequirementRequest) =
        replenishment.technicalReview(servlet.procurementCommand(body.operationId), id, body)

    private fun HttpServletRequest.actor(): UUID =
        HiltechRequestContext.current(this).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException("UNAUTHENTICATED",
                "Authentication required.", HttpStatus.UNAUTHORIZED)

    private fun HttpServletRequest.procurementCommand(operationId: UUID): ProcurementDraftContext {
        val context = HiltechRequestContext.current(this)
        IdempotencyKeyContract.requireMatches(context.idempotencyKey, operationId)
        return ProcurementDraftContext(operationId, actor(), context.correlationId)
    }
}
