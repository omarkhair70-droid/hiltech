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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class FieldExecutionRequest(
    val operationId: String,
    val assignmentId: String,
    val assignmentVersion: Long,
    val baseVersion: Long,
    val clientOccurredAt: String,
    val reason: String? = null,
    val blockerId: String? = null,
)

data class FieldExecutionResponse(
    val workOrderId: String,
    val lifecycleState: String,
    val version: Long,
    val blockerId: String?,
    val duplicateReplay: Boolean,
    val correlationId: String,
)

@RestController
@RequestMapping("/v1/work-orders")
class FieldExecutionController(private val service: FieldExecutionService) {
    @PostMapping("/{id}/start")
    fun start(req: HttpServletRequest, @PathVariable id: String,
              @RequestHeader("Idempotency-Key") key: String, @RequestBody body: FieldExecutionRequest) =
        execute(req, id, key, body, FieldExecutionAction.START)

    @PostMapping("/{id}/block")
    fun block(req: HttpServletRequest, @PathVariable id: String,
              @RequestHeader("Idempotency-Key") key: String, @RequestBody body: FieldExecutionRequest) =
        execute(req, id, key, body, FieldExecutionAction.BLOCK)

    @PostMapping("/{id}/resume")
    fun resume(req: HttpServletRequest, @PathVariable id: String,
               @RequestHeader("Idempotency-Key") key: String, @RequestBody body: FieldExecutionRequest) =
        execute(req, id, key, body, FieldExecutionAction.RESUME)

    private fun execute(req: HttpServletRequest, id: String, key: String,
                        body: FieldExecutionRequest, action: FieldExecutionAction): FieldExecutionResponse {
        val context = HiltechRequestContext.current(req)
        val actor = context.identityId?.let { parseId(it) }
            ?: throw ProductApiException("AUTHENTICATION_REQUIRED", "A HILTECH identity is required.", HttpStatus.UNAUTHORIZED)
        val operation = parseId(body.operationId)
        IdempotencyKeyContract.requireMatches(key, operation)
        val result = service.execute(FieldExecutionCommand(
            operationId = operation,
            workOrderId = parseId(id),
            action = action,
            assignmentId = parseId(body.assignmentId),
            assignmentVersion = body.assignmentVersion,
            baseVersion = body.baseVersion,
            clientOccurredAt = parseTime(body.clientOccurredAt),
            actorUserId = actor,
            correlationId = context.correlationId,
            reason = body.reason,
            blockerId = body.blockerId?.let(::parseId),
        ))
        return FieldExecutionResponse(
            result.workOrderId.toString(), result.lifecycleState.name, result.version,
            result.blockerId?.toString(), result.replayed, context.correlationId,
        )
    }

    private fun parseId(s: String): UUID = runCatching { UUID.fromString(s) }.getOrElse {
        throw ProductApiException("INVALID_ID", "The field identifier is invalid.", HttpStatus.BAD_REQUEST)
    }
    private fun parseTime(s: String): Instant = runCatching { Instant.parse(s) }.getOrElse {
        throw ProductApiException("INVALID_INSTANT", "The field action timestamp is invalid.", HttpStatus.BAD_REQUEST)
    }
}
