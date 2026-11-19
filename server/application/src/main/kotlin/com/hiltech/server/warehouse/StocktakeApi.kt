package com.hiltech.server.warehouse

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreateStocktakeApiRequest(
    val operationId: UUID,
    val organizationId: UUID,
    val warehouseId: UUID,
    val rootStorageLocationId: UUID,
    val categoryIds: List<UUID> = emptyList(),
    val stockItemIds: List<UUID> = emptyList(),
)
data class StartStocktakeApiRequest(val operationId: UUID, val sessionVersion: Long)
data class RecordStockCountApiRequest(
    val operationId: UUID,
    val sessionVersion: Long,
    val snapshotLineId: UUID,
    val countedQuantity: String,
    val unitCode: String,
    val scanSource: String? = null,
    val notes: String? = null,
)
data class SubmitStocktakeApiRequest(val operationId: UUID, val sessionVersion: Long)
data class RequestInventoryAdjustmentApiRequest(
    val operationId: UUID,
    val discrepancyVersion: Long,
    val reason: String,
)
data class ApplyInventoryAdjustmentApiRequest(
    val operationId: UUID,
    val requestVersion: Long,
)

@RestController
class StocktakeController(private val service: StocktakeService) {
    @PostMapping("/v1/stocktakes")
    fun create(request: HttpServletRequest, @RequestBody body: CreateStocktakeApiRequest) =
        service.create(
            request.stocktakeContext(body.operationId),
            CreateStocktakeRequest(
                body.organizationId,
                body.warehouseId,
                body.rootStorageLocationId,
                body.categoryIds,
                body.stockItemIds,
            ),
        )

    @GetMapping("/v1/stocktakes/{id}")
    fun detail(request: HttpServletRequest, @PathVariable id: UUID) =
        service.detail(request.stockActor(), id)

    @PostMapping("/v1/stocktakes/{id}/start")
    fun start(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: StartStocktakeApiRequest) =
        service.start(
            request.stocktakeContext(body.operationId),
            id,
            StartStocktakeRequest(body.sessionVersion),
        )

    @PostMapping("/v1/stocktakes/{id}/counts")
    fun count(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: RecordStockCountApiRequest) =
        service.recordCount(
            request.stocktakeContext(body.operationId),
            id,
            RecordStockCountRequest(
                body.sessionVersion,
                body.snapshotLineId,
                body.countedQuantity,
                body.unitCode,
                body.scanSource,
                body.notes,
            ),
        )

    @PostMapping("/v1/stocktakes/{id}/submit")
    fun submit(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: SubmitStocktakeApiRequest) =
        service.submit(
            request.stocktakeContext(body.operationId),
            id,
            SubmitStocktakeRequest(body.sessionVersion),
        )

    @GetMapping("/v1/stocktakes/{id}/variances")
    fun variances(request: HttpServletRequest, @PathVariable id: UUID) =
        service.variances(request.stockActor(), id)

    @PostMapping("/v1/inventory-discrepancies/{id}/request-adjustment")
    fun requestAdjustment(
        request: HttpServletRequest,
        @PathVariable id: UUID,
        @RequestBody body: RequestInventoryAdjustmentApiRequest,
    ) = service.requestAdjustment(
        request.stocktakeContext(body.operationId),
        id,
        RequestInventoryAdjustmentRequest(body.discrepancyVersion, body.reason),
    )

    @PostMapping("/v1/inventory-adjustments/{id}/apply")
    fun applyAdjustment(
        request: HttpServletRequest,
        @PathVariable id: UUID,
        @RequestBody body: ApplyInventoryAdjustmentApiRequest,
    ) = service.applyAdjustment(
        request.stocktakeContext(body.operationId),
        id,
        ApplyInventoryAdjustmentRequest(body.requestVersion),
    )
}

private fun HttpServletRequest.stocktakeContext(operationId: UUID): StocktakeCommandContext {
    val context = stockContext(operationId)
    return StocktakeCommandContext(context.operationId, context.actor, context.correlationId)
}
