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

/** Reads canonical People offboarding case; no financial operations here. */
@RestController
@ConditionalOnProperty(
    prefix = "hiltech.payroll",
    name = ["final-pay-review-enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class Payroll9cFinalPayController(private val service: Payroll9cFinalPayService) {
    @GetMapping("/v1/payroll/offboarding/{caseId}/final-pay-readiness")
    fun readiness(
        servletRequest: HttpServletRequest,
        @PathVariable caseId: String,
    ): PayrollFinalPayReadinessDto {
        val ctx = HiltechRequestContext.current(servletRequest)
        val actor = ctx.identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "PAYROLL_AUTHENTICATION_REQUIRED", "Authenticated identity required",
                HttpStatus.UNAUTHORIZED,
            )
        val parsed = runCatching { UUID.fromString(caseId) }.getOrElse {
            throw ProductApiException(
                "INVALID_PAYROLL_UUID", "Invalid offboarding case",
                HttpStatus.BAD_REQUEST,
            )
        }
        return service.readiness(actor, parsed)
    }
}
