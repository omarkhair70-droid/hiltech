package com.hiltech.server.warehouse

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreateReceivingApiRequest(
    val operationId: UUID, val organizationId: UUID, val destinationStorageLocationId: UUID,
    val sourceType: ReceivingSourceType, val sourceRef: String? = null, val deliveryDocumentRef: String? = null,
)
data class AddReceivingStockLineApiRequest(
    val operationId: UUID, val recordVersion: Long, val stockItemId: UUID, val stockItemVersion: Long,
    val candidateClass: ReceivingCandidateClass, val expectedQuantity: String? = null,
    val unitCode: String, val sourceLineRef: String? = null,
)
data class ReceiveStockLineApiRequest(
    val operationId: UUID, val lineId: UUID, val recordVersion: Long, val lineVersion: Long,
    val stockItemVersion: Long, val quantity: String, val unitCode: String,
    val condition: AssetCondition, val serials: List<String> = emptyList(),
)
data class AddReceivingAssetLineApiRequest(
    val operationId: UUID, val recordVersion: Long, val assetTypeId: UUID, val sourceLineRef: String? = null,
)
data class ReceiveAssetLineApiRequest(
    val operationId: UUID, val lineId: UUID, val recordVersion: Long, val lineVersion: Long,
    val assetCode: String, val name: String, val manufacturer: String? = null, val model: String? = null,
    val serialNumber: String? = null, val ownershipType: String, val condition: AssetCondition, val tagType: String? = null,
)
data class AddReceivingDiscrepancyApiRequest(
    val operationId: UUID, val recordVersion: Long, val lineId: UUID? = null,
    val type: ReceivingDiscrepancyType, val description: String,
)
data class RejectPoReceivingStockLineApiRequest(
    val operationId: UUID, val lineId: UUID,
    val recordVersion: Long, val lineVersion: Long,
    val quantity: String, val type: ReceivingDiscrepancyType, val reason: String,
)
data class CompleteReceivingApiRequest(val operationId: UUID, val recordVersion: Long)

@RestController
class ReceivingController(private val service: ReceivingService) {
    @GetMapping("/v1/receiving")
    fun directory(request: HttpServletRequest, @RequestParam warehouseId: UUID,
        @RequestParam(required = false) afterUpdatedAt: String?,
        @RequestParam(required = false) afterId: UUID?) =
        service.directory(request.stockActor(), warehouseId, afterUpdatedAt, afterId)

    @PostMapping("/v1/receiving")
    fun create(request: HttpServletRequest, @RequestBody body: CreateReceivingApiRequest) =
        service.create(request.receivingContext(body.operationId), CreateReceivingRequest(
            body.organizationId, body.destinationStorageLocationId, body.sourceType, body.sourceRef, body.deliveryDocumentRef))

    @GetMapping("/v1/receiving/{id}")
    fun detail(request: HttpServletRequest, @PathVariable id: UUID) =
        service.detail(request.stockActor(), id)

    @PostMapping("/v1/receiving/{id}/lines")
    fun line(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: AddReceivingStockLineApiRequest) =
        service.addStockLine(request.receivingContext(body.operationId), id, AddReceivingStockLineRequest(
            body.recordVersion, body.stockItemId, body.stockItemVersion, body.candidateClass,
            body.expectedQuantity, body.unitCode, body.sourceLineRef))

    @PostMapping("/v1/receiving/{id}/receive-line")
    fun receive(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: ReceiveStockLineApiRequest) =
        service.receiveStockLine(request.receivingContext(body.operationId), id, body.lineId, ReceiveStockLineRequest(
            body.recordVersion, body.lineVersion, body.stockItemVersion, body.quantity,
            body.unitCode, body.condition, body.serials))

    @PostMapping("/v1/receiving/{id}/asset-lines")
    fun assetLine(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: AddReceivingAssetLineApiRequest) =
        service.addAssetLine(request.receivingContext(body.operationId), id,
            AddReceivingAssetLineRequest(body.recordVersion, body.assetTypeId, body.sourceLineRef))

    @PostMapping("/v1/receiving/{id}/receive-asset-line")
    fun receiveAsset(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: ReceiveAssetLineApiRequest) =
        service.receiveAssetLine(request.receivingContext(body.operationId), id, body.lineId, ReceiveAssetLineRequest(
            body.recordVersion, body.lineVersion, body.assetCode, body.name, body.manufacturer, body.model,
            body.serialNumber, body.ownershipType, body.condition, body.tagType))

    @PostMapping("/v1/receiving/{id}/discrepancies")
    fun discrepancy(request: HttpServletRequest, @PathVariable id: UUID,
        @RequestBody body: AddReceivingDiscrepancyApiRequest) =
        service.addDiscrepancy(request.receivingContext(body.operationId), id, AddReceivingDiscrepancyRequest(
            body.recordVersion, body.lineId, body.type, body.description))

    @PostMapping("/v1/receiving/{id}/reject-po-line")
    fun rejectPoLine(request: HttpServletRequest, @PathVariable id: UUID,
        @RequestBody body: RejectPoReceivingStockLineApiRequest) =
        service.rejectPoStockLine(
            request.receivingContext(body.operationId), id, body.lineId,
            RejectPoReceivingStockLineRequest(
                body.recordVersion, body.lineVersion, body.quantity,
                body.type, body.reason,
            ),
        )

    @PostMapping("/v1/receiving/{id}/complete")
    fun complete(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: CompleteReceivingApiRequest) =
        service.complete(request.receivingContext(body.operationId), id, CompleteReceivingRequest(body.recordVersion))
}

private fun HttpServletRequest.receivingContext(operationId: UUID): ReceivingCommandContext {
    val context = stockContext(operationId)
    return ReceivingCommandContext(context.operationId, context.actor, context.correlationId)
}
