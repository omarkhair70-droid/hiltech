package com.hiltech.server.warehouse

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class IssueReservedStockRequest(
    val operationId: UUID, val workOrderId: UUID, val requirementId: UUID, val employeeId: UUID,
    val reservationId: UUID, val storageLocationId: UUID, val workVersion: Long,
    val requirementVersion: Long, val reservationVersion: Long, val balanceVersion: Long,
    val quantity: String, val unitCode: String,
)

data class AdjustIssuedStockRequest(
    val operationId: UUID, val workOrderId: UUID, val requirementId: UUID, val employeeId: UUID,
    val workVersion: Long, val issuedVersion: Long, val quantity: String, val unitCode: String,
    val storageLocationId: UUID? = null,
)

@RestController
class StockFulfillmentController(private val service: StockFulfillmentService) {
    @PostMapping("/v1/stock-items/{id}/issue")
    fun issue(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: IssueReservedStockRequest) =
        service.issue(request.stockContext(body.operationId), id, body)

    @PostMapping("/v1/stock-items/{id}/return-unused")
    fun returnUnused(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: AdjustIssuedStockRequest) =
        service.returnUnused(request.stockContext(body.operationId), id, body)

    @PostMapping("/v1/stock-items/{id}/consume")
    fun consume(request: HttpServletRequest, @PathVariable id: UUID, @RequestBody body: AdjustIssuedStockRequest) =
        service.consume(request.stockContext(body.operationId), id, body)

    @GetMapping("/v1/stock-items/{id}/issued-context")
    fun issued(request: HttpServletRequest, @PathVariable id: UUID, @RequestParam workOrderId: UUID) =
        service.issued(request.stockActor(), id, workOrderId)
}
