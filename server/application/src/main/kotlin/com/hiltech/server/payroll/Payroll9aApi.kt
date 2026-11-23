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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * Intentionally disabled by default. Phase 9B computation and 9C payout
 * endpoints do not exist. No employee self-service pay data exists in 9A.
 */
@RestController
@RequestMapping("/v1/payroll/periods")
@ConditionalOnProperty(
    prefix = "hiltech.payroll",
    name = ["periods-enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class Payroll9aController(
    private val service: Payroll9aService,
) {
    @PostMapping
    fun create(
        servletRequest: HttpServletRequest,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
        @RequestBody request: CreatePayrollPeriodRequest,
    ): PayrollPeriodCommandDto {
        val context = HiltechRequestContext.current(servletRequest)
        val operationId = request.operationId.parseUuid()
        IdempotencyKeyContract.requireMatches(idempotencyKey, operationId)
        val result = service.create(
            actorId = context.requirePayrollIdentityId(),
            command = Payroll9aPeriodCommand(
                operationId = operationId,
                organizationId = request.organizationId.parseUuid(),
                periodCode = request.periodCode,
                startDate = request.startDate.parseDate(),
                endDate = request.endDate.parseDate(),
                cutoffDate = request.cutoffDate.parseDate(),
                targetPaymentDate = request.targetPaymentDate?.parseDate(),
            ),
            correlationId = context.correlationId,
        )
        return PayrollPeriodCommandDto(result.first, result.second, context.correlationId)
    }

    @GetMapping
    fun list(
        servletRequest: HttpServletRequest,
        @RequestParam organizationId: String,
    ): PayrollPeriodListDto {
        val context = HiltechRequestContext.current(servletRequest)
        return PayrollPeriodListDto(
            items = service.list(
                actorId = context.requirePayrollIdentityId(),
                organizationId = organizationId.parseUuid(),
            ),
            correlationId = context.correlationId,
        )
    }

    @GetMapping("/{periodId}")
    fun detail(
        servletRequest: HttpServletRequest,
        @PathVariable periodId: String,
    ): PayrollPeriodDetailDto {
        val context = HiltechRequestContext.current(servletRequest)
        return service.detail(context.requirePayrollIdentityId(), periodId.parseUuid())
    }

    @PostMapping("/{periodId}/capture-inputs")
    fun capture(
        servletRequest: HttpServletRequest,
        @PathVariable periodId: String,
    ): PayrollPeriodCommandDto {
        val context = HiltechRequestContext.current(servletRequest)
        val result = service.captureInputs(
            actorId = context.requirePayrollIdentityId(),
            periodId = periodId.parseUuid(),
            correlationId = context.correlationId,
        )
        return PayrollPeriodCommandDto(result.first, result.second, context.correlationId)
    }

    @PostMapping("/{periodId}/exceptions/{exceptionId}/reviews")
    fun review(
        servletRequest: HttpServletRequest,
        @PathVariable periodId: String,
        @PathVariable exceptionId: String,
        @RequestHeader(name = "Idempotency-Key") idempotencyKey: String,
        @RequestBody request: ReviewPayrollExceptionRequest,
    ): PayrollExceptionReviewCommandDto {
        val context = HiltechRequestContext.current(servletRequest)
        val operationId = request.operationId.parseUuid()
        IdempotencyKeyContract.requireMatches(idempotencyKey, operationId)
        val reviewed = service.review(
            actorId = context.requirePayrollIdentityId(),
            periodId = periodId.parseUuid(),
            exceptionId = exceptionId.parseUuid(),
            operationId = operationId,
            note = request.note,
            correlationId = context.correlationId,
        )
        return PayrollExceptionReviewCommandDto(
            exceptionId = exceptionId,
            review = reviewed.first,
            replayed = reviewed.second,
            correlationId = context.correlationId,
        )
    }
}

private fun String.parseUuid(): UUID =
    try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        throw ProductApiException(
            code = "INVALID_PAYROLL_UUID",
            message = "Invalid identifier.",
            status = HttpStatus.BAD_REQUEST,
        )
    }

private fun String.parseDate(): LocalDate =
    try {
        LocalDate.parse(this)
    } catch (_: Exception) {
        throw ProductApiException(
            code = "INVALID_PAYROLL_DATE",
            message = "Invalid calendar date.",
            status = HttpStatus.BAD_REQUEST,
        )
    }

private fun com.hiltech.server.platform.HiltechRequestContextSnapshot
    .requirePayrollIdentityId(): UUID =
    identityId?.let {
        try {
            UUID.fromString(it)
        } catch (_: IllegalArgumentException) {
            null
        }
    } ?: throw ProductApiException(
        code = "PAYROLL_AUTHENTICATION_REQUIRED",
        message = "Authenticated identity required.",
        status = HttpStatus.UNAUTHORIZED,
    )
