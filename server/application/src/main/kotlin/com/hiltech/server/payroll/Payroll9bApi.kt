package com.hiltech.server.payroll

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreatePayrollDraftRequest(val operationId: String)

/**
 * Separate opt-in from 9A. Real approved-source and policy provider must be
 * installed before this creates a draft; never accepts amounts from an API user.
 */
@RestController
@ConditionalOnProperty(
    prefix = "hiltech.payroll",
    name = ["draft-runs-enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class Payroll9bController(private val service: Payroll9bDraftService) {
    @PostMapping("/v1/payroll/periods/{periodId}/runs/drafts")
    fun create(
        request: HttpServletRequest,
        @PathVariable periodId: String,
        @RequestHeader(name = "Idempotency-Key") key: String,
        @RequestBody body: CreatePayrollDraftRequest,
    ): Payroll9bDraftCommandDto {
        val ctx = HiltechRequestContext.current(request)
        val operation = body.operationId.payrollUuid()
        IdempotencyKeyContract.requireMatches(key, operation)
        val (draft, replayed) = service.create(
            periodId = periodId.payrollUuid(),
            operationId = operation,
            actorUserId = ctx.payrollIdentity(),
            correlationId = ctx.correlationId,
        )
        return Payroll9bDraftCommandDto(draft, replayed, ctx.correlationId)
    }

    @GetMapping("/v1/payroll/runs/{runId}")
    fun read(
        request: HttpServletRequest,
        @PathVariable runId: String,
    ): Payroll9bRunDto {
        val ctx = HiltechRequestContext.current(request)
        return service.read(ctx.payrollIdentity(), runId.payrollUuid())
    }
}

private fun String.payrollUuid(): UUID =
    try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        throw ProductApiException(
            code = "INVALID_PAYROLL_UUID",
            message = "Invalid identifier",
            status = HttpStatus.BAD_REQUEST,
        )
    }

private fun com.hiltech.server.platform.HiltechRequestContextSnapshot.payrollIdentity(): UUID =
    identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException(
            code = "PAYROLL_AUTHENTICATION_REQUIRED",
            message = "Authenticated identity required",
            status = HttpStatus.UNAUTHORIZED,
        )
