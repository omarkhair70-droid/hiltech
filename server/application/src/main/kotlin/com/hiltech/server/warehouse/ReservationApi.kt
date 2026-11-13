package com.hiltech.server.warehouse

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class RequestWorkResource(
    val operationId: UUID, val workVersion: Long, val requirementVersion: Long,
    val assetTypeId: UUID? = null, val stockItemId: UUID? = null, val quantity: String? = null, val unitCode: String? = null,
)
data class ReserveAssetRequest(
    val operationId: UUID, val workOrderId: UUID, val requirementId: UUID, val workVersion: Long,
    val requirementVersion: Long, val resourceVersion: Long, val expiresAt: String,
)
data class ReserveStockRequest(
    val operationId: UUID, val workOrderId: UUID, val requirementId: UUID, val workVersion: Long,
    val requirementVersion: Long, val resourceVersion: Long, val storageLocationId: UUID,
    val quantity: String, val unitCode: String, val expiresAt: String,
)
data class ReleaseReservationRequest(val operationId: UUID, val baseVersion: Long, val reason: String)

@RestController
class ReservationController(private val service: ReservationService) {
    @GetMapping("/v1/work-orders/{id}/resource-candidates")
    fun candidates(request: HttpServletRequest, @PathVariable id: UUID) = service.candidates(request.assetActor(), id)

    @GetMapping("/v1/work-orders/{id}/resource-reservations")
    fun reservations(request: HttpServletRequest, @PathVariable id: UUID) = service.forWork(request.assetActor(), id)

    @PostMapping("/v1/work-orders/{id}/resource-requirements/{requirementId}/request")
    fun request(
        request: HttpServletRequest, @PathVariable id: UUID, @PathVariable requirementId: UUID, @RequestBody body: RequestWorkResource,
    ) = service.request(request.assetContext(body.operationId), id, requirementId, body)

    @GetMapping("/v1/reservations")
    fun queue(request: HttpServletRequest, @RequestParam warehouseId: UUID) = service.queue(request.assetActor(), warehouseId)

    @PostMapping("/v1/assets/{id}/reserve")
    fun reserveAsset(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: ReserveAssetRequest) =
        service.reserveAsset(request.assetContext(body.operationId), id, body)

    @PostMapping("/v1/stock-items/{id}/reserve")
    fun reserveStock(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: ReserveStockRequest) =
        service.reserveStock(request.assetContext(body.operationId), id, body)

    @PostMapping("/v1/asset-reservations/{id}/release")
    fun releaseAsset(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: ReleaseReservationRequest) =
        service.release(request.assetContext(body.operationId), ResourceKind.ASSET, id, body)

    @PostMapping("/v1/stock-reservations/{id}/release")
    fun releaseStock(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: ReleaseReservationRequest) =
        service.release(request.assetContext(body.operationId), ResourceKind.STOCK, id, body)
}
