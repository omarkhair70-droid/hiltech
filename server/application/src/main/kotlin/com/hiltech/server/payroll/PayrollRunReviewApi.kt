package com.hiltech.server.payroll

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
@ConditionalOnProperty(prefix = "hiltech.payroll", name = ["approval-request-enabled"], havingValue = "true", matchIfMissing = true)
class PayrollRunReviewController(private val reviews: PayrollRunReviewService) {
    @GetMapping("/v1/payroll/runs/{runId}/review")
    fun review(request: HttpServletRequest, @PathVariable runId: String): PayrollRunReviewDto {
        val context = HiltechRequestContext.current(request)
        val actor = context.identityId?.let(PayrollSourcePolicy::uuid)
            ?: Payroll9aPolicy.fail("PAYROLL_AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED)
        return reviews.read(actor, PayrollSourcePolicy.uuid(runId))
    }

    @PostMapping("/v1/payroll/runs/{runId}/approval-decisions")
    fun decide(servletRequest: HttpServletRequest, @PathVariable runId: String,
        @RequestHeader(name = "Idempotency-Key") key: String, @RequestBody request: PayrollRunDecisionRequestDto): PayrollRunDecisionResultDto {
        val context = HiltechRequestContext.current(servletRequest)
        val actor = context.identityId?.let(PayrollSourcePolicy::uuid)
            ?: Payroll9aPolicy.fail("PAYROLL_AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED)
        IdempotencyKeyContract.requireMatches(key, PayrollSourcePolicy.uuid(request.operationId))
        return reviews.decide(actor, PayrollSourcePolicy.uuid(runId), request, context.correlationId)
    }
}
