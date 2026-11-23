package com.hiltech.server.payroll

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Read-only Finance results; no approve, retry, bank or pay endpoint. */
@RestController
@ConditionalOnProperty(
    prefix = "hiltech.payroll",
    name = ["finance-results-enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class Payroll9cRunFinanceStatusController(
    private val service: Payroll9cRunFinanceStatusService,
) {
    @GetMapping("/v1/payroll/runs/{runId}/finance-status")
    fun status(
        request: HttpServletRequest,
        @PathVariable runId: String,
    ): Payroll9cRunFinanceStatusDto {
        val ctx = HiltechRequestContext.current(request)
        val actor = ctx.identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                code = "PAYROLL_AUTHENTICATION_REQUIRED",
                message = "Authenticated identity required.",
                status = HttpStatus.UNAUTHORIZED,
            )
        val run = runCatching { UUID.fromString(runId) }.getOrElse {
            throw ProductApiException(
                code = "INVALID_PAYROLL_UUID",
                message = "Invalid run.",
                status = HttpStatus.BAD_REQUEST,
            )
        }
        return service.status(actor, run)
    }
}
