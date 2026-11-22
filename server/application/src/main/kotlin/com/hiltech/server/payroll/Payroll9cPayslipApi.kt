package com.hiltech.server.payroll

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Employee-only, online, no coworker parameter. This route does not grant any
 * visibility before independent publication and Finance-result providers exist.
 */
@RestController
@RequestMapping("/v1/me/payroll")
@ConditionalOnProperty(
    prefix = "hiltech.payroll",
    name = ["payslips-enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class OwnPayrollPayslipController(
    private val service: Payroll9cPayslipService,
) {
    @GetMapping("/payslips")
    fun list(
        servletRequest: HttpServletRequest,
        @RequestParam organizationId: String,
    ): OwnPayrollPayslipsDto {
        val ctx = HiltechRequestContext.current(servletRequest)
        val actor = try {
            UUID.fromString(ctx.identityId)
        } catch (_: Exception) {
            throw ProductApiException(
                code = "PAYROLL_AUTHENTICATION_REQUIRED",
                message = "Authenticated identity required.",
                status = HttpStatus.UNAUTHORIZED,
            )
        }
        val org = try {
            UUID.fromString(organizationId)
        } catch (_: IllegalArgumentException) {
            throw ProductApiException(
                code = "INVALID_PAYROLL_UUID",
                message = "Invalid organization identifier.",
                status = HttpStatus.BAD_REQUEST,
            )
        }
        return service.own(actor, org, ctx.correlationId)
    }
}
