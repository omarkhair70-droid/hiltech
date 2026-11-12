package com.hiltech.server.warehouse

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreateStockItemRequest(
    val operationId: UUID, val warehouseId: UUID, val itemCode: String, val name: String,
    val description: String? = null, val brand: String? = null, val partNumber: String? = null,
    val categoryDefinitionId: UUID, val baseUnitCode: String, val classification: StockClassification,
    val lotTracked: Boolean? = null,
)

data class RegisterStockUnitRequest(val operationId: UUID, val warehouseId: UUID, val code: String, val name: String)
data class ApproveStockItemRequest(
    val operationId: UUID, val baseVersion: Long, val itemCode: String, val name: String, val lotTracked: Boolean? = null,
)
data class InitializeStockOpeningRequest(
    val operationId: UUID, val baseVersion: Long, val sourceImportRowId: UUID, val storageLocationId: UUID,
    val quantity: String, val unitCode: String, val cutoverAt: String,
)
data class TransferStockRequest(
    val operationId: UUID, val baseVersion: Long, val fromStorageLocationId: UUID, val toStorageLocationId: UUID,
    val quantity: String, val unitCode: String,
)

@RestController
class StockLedgerController(private val service: StockLedgerService) {
    @GetMapping("/v1/stock-catalog")
    fun catalog(request: HttpServletRequest, @RequestParam organizationId: UUID) =
        service.catalog(request.stockActor(), organizationId)

    @PostMapping("/v1/stock-units")
    fun unit(request: HttpServletRequest, @RequestBody body: RegisterStockUnitRequest) =
        service.registerUnit(request.stockContext(body.operationId), body.warehouseId, body.code, body.name)

    @GetMapping("/v1/stock-items")
    fun items(request: HttpServletRequest, @RequestParam organizationId: UUID, @RequestParam(required = false) afterCode: String?) =
        service.items(request.stockActor(), organizationId, afterCode)

    @PostMapping("/v1/stock-items")
    fun create(request: HttpServletRequest, @RequestBody body: CreateStockItemRequest) =
        service.createItem(request.stockContext(body.operationId), body)

    @GetMapping("/v1/stock-items/{id}")
    fun item(request: HttpServletRequest, @PathVariable id: UUID) = service.item(request.stockActor(), id)

    @GetMapping("/v1/stock-items/{id}/balances")
    fun balances(request: HttpServletRequest, @PathVariable id: UUID) = service.balances(request.stockActor(), id)

    @GetMapping("/v1/stock-items/{id}/movements")
    fun movements(request: HttpServletRequest, @PathVariable id: UUID) = service.movements(request.stockActor(), id)

    @PostMapping("/v1/stock-items/{id}/opening-balance")
    fun opening(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: InitializeStockOpeningRequest) =
        service.opening(request.stockContext(body.operationId), id, body)

    @PostMapping("/v1/stock-items/{id}/transfer")
    fun transfer(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: TransferStockRequest) =
        service.transfer(request.stockContext(body.operationId), id, body)

    @PostMapping("/v1/inventory-imports/{batchId}/rows/{rowId}/approve-stock-item")
    fun approve(
        request: HttpServletRequest, @PathVariable batchId: UUID, @PathVariable rowId: UUID,
        @RequestBody body: ApproveStockItemRequest,
    ) = service.approveImport(request.stockContext(body.operationId), batchId, rowId, body)
}

private fun HttpServletRequest.stockActor(): UUID =
    HiltechRequestContext.current(this).identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED)

private fun HttpServletRequest.stockContext(operationId: UUID): StockCommandContext {
    val actor = stockActor()
    val context = HiltechRequestContext.current(this)
    IdempotencyKeyContract.requireMatches(context.idempotencyKey, operationId)
    return StockCommandContext(operationId, actor, context.correlationId)
}
