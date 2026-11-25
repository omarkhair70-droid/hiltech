package com.hiltech.server.payroll

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class PayrollFinanceHandoffRequestDto(val operationId: String)

/** Transfers the exact released manifest to Finance review without executing payment. */
@RestController
@ConditionalOnProperty(
    prefix = "hiltech.payroll",
    name = ["finance-handoff-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class Payroll9cFinanceHandoffController(
    private val service: Payroll9cFinanceHandoffService,
) {
    @PostMapping("/v1/payroll/runs/{runId}/finance-review-handoffs")
    fun handoff(
        request: HttpServletRequest,
        @PathVariable runId: String,
        @RequestHeader(name = "Idempotency-Key") key: String,
        @RequestBody body: PayrollFinanceHandoffRequestDto,
    ): PayrollFinanceHandoffDto {
        val ctx = HiltechRequestContext.current(request)
        val actor = ctx.identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "PAYROLL_AUTHENTICATION_REQUIRED",
                "Authenticated identity required", HttpStatus.UNAUTHORIZED,
            )
        val id = runCatching { UUID.fromString(runId) }.getOrElse {
            throw ProductApiException(
                "INVALID_PAYROLL_UUID", "Invalid run", HttpStatus.BAD_REQUEST,
            )
        }
        val operation = runCatching { UUID.fromString(body.operationId) }.getOrElse {
            throw ProductApiException(
                "INVALID_PAYROLL_UUID", "Invalid command", HttpStatus.BAD_REQUEST,
            )
        }
        IdempotencyKeyContract.requireMatches(key, operation)
        return service.handoff(actor, id, operation, ctx.correlationId)
    }
}
