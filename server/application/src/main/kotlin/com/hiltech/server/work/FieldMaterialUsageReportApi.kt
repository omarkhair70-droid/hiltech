package com.hiltech.server.work

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class FieldMaterialUsageReportRequest(
    val operationId: String,
    val assignmentId: String,
    val assignmentVersion: Long,
    val workVersion: Long,
    val requirementId: String,
    val stockItemId: String,
    val issuedVersion: Long,
    val quantity: String,
    val unitCode: String,
    val clientOccurredAt: String,
)

@RestController
class FieldMaterialUsageReportController(
    private val service: FieldMaterialUsageReportService,
) {
    @PostMapping("/v1/work-orders/{workOrderId}/material-usage-reports")
    fun report(
        request: HttpServletRequest,
        @PathVariable workOrderId: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: FieldMaterialUsageReportRequest,
    ): FieldMaterialUsageReportReceipt {
        val context = HiltechRequestContext.current(request)
        val actor = context.identityId?.let(::parseId)
            ?: throw ProductApiException("AUTHENTICATION_REQUIRED", "A signed-in user is required.", HttpStatus.UNAUTHORIZED)
        val operation = parseId(body.operationId)
        IdempotencyKeyContract.requireMatches(key, operation)
        return service.report(
            FieldMaterialUsageReportCommand(
                operationId = operation,
                workOrderId = parseId(workOrderId),
                actorUserId = actor,
                assignmentId = parseId(body.assignmentId),
                assignmentVersion = body.assignmentVersion,
                workVersion = body.workVersion,
                requirementId = parseId(body.requirementId),
                stockItemId = parseId(body.stockItemId),
                issuedVersion = body.issuedVersion,
                quantity = body.quantity,
                unitCode = body.unitCode,
                clientOccurredAt = runCatching { Instant.parse(body.clientOccurredAt) }.getOrElse { invalid() },
                correlationId = context.correlationId,
            ),
        )
    }
    private fun parseId(raw: String) = runCatching { UUID.fromString(raw) }.getOrElse { invalid() }
    private fun invalid(): Nothing = throw ProductApiException(
        "FIELD_MATERIAL_USAGE_INVALID", "Invalid material usage report identifier or timestamp.", HttpStatus.BAD_REQUEST,
    )
}
