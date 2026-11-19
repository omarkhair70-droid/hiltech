package com.hiltech.server.warehouse

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class AssetFulfillmentController(private val service: AssetFulfillmentService) {
    @GetMapping("/v1/warehouses/{id}/fulfillment-recipients")
    fun recipients(request: HttpServletRequest, @PathVariable id: UUID,
        @RequestParam(defaultValue = "") search: String) =
        service.recipients(request.assetActor(), id, search)

    @GetMapping("/v1/warehouses/{id}/due-asset-returns")
    fun dueReturns(request: HttpServletRequest, @PathVariable id: UUID,
        @RequestParam(required = false) afterExpectedReturnAt: String?,
        @RequestParam(required = false) afterAssetCode: String?,
        @RequestParam(required = false) afterAssetId: UUID?) =
        service.dueReturns(request.assetActor(), id, afterExpectedReturnAt, afterAssetCode, afterAssetId)

    @PostMapping("/v1/assets/{id}/checkout")
    fun checkout(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: CheckoutReservedAssetRequest) =
        service.checkout(request.assetContext(body.operationId), id, body)
    @PostMapping("/v1/assets/{id}/return")
    fun returnAsset(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: ReturnPhysicalAssetRequest) =
        service.returnAsset(request.assetContext(body.operationId), id, body)
    @PostMapping("/v1/assets/{id}/transfer")
    fun transfer(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: TransferPhysicalAssetRequest) =
        service.transfer(request.assetContext(body.operationId), id, body)
    @PostMapping("/v1/assets/{id}/returns/{movementId}/inspect")
    fun inspect(request: HttpServletRequest, @PathVariable id: UUID, @PathVariable movementId: UUID,
        @RequestBody body: InspectPhysicalReturnRequest) =
        service.inspect(request.assetContext(body.operationId), id, movementId, body)
}
