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

data class PayrollRunReleaseRequestDto(
    val operationId: String,
)

/**
 * Only records a governed company release for Finance review.
 * No payment preparation, bank file, transfer or retry can be reached here.
 */
@RestController
@ConditionalOnProperty(
    prefix = "hiltech.payroll",
    name = ["release-enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class Payroll9bRunReleaseController(
    private val releases: Payroll9bRunReleaseService,
) {
    @PostMapping("/v1/payroll/runs/{runId}/release")
    fun release(
        http: HttpServletRequest,
        @PathVariable runId: String,
        @RequestHeader(name = "Idempotency-Key") key: String,
        @RequestBody body: PayrollRunReleaseRequestDto,
    ): PayrollRunReleaseDto {
        val ctx = HiltechRequestContext.current(http)
        val actor = ctx.identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                code = "PAYROLL_AUTHENTICATION_REQUIRED",
                message = "Authenticated identity required.",
                status = HttpStatus.UNAUTHORIZED,
            )
        val run = runCatching { UUID.fromString(runId) }.getOrElse {
            throw ProductApiException(
                code = "INVALID_PAYROLL_UUID",
                message = "Invalid run identifier.",
                status = HttpStatus.BAD_REQUEST,
            )
        }
        val operation = runCatching { UUID.fromString(body.operationId) }.getOrElse {
            throw ProductApiException(
                code = "INVALID_PAYROLL_UUID",
                message = "Invalid command identifier.",
                status = HttpStatus.BAD_REQUEST,
            )
        }
        IdempotencyKeyContract.requireMatches(key, operation)
        return releases.release(
            actorId = actor,
            runId = run,
            operationId = operation,
            correlationId = ctx.correlationId,
        )
    }
}
