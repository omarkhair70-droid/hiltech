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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@ConditionalOnProperty(prefix = "hiltech.payroll", name = ["periods-enabled"], havingValue = "true", matchIfMissing = true)
class PayrollSourceController(private val service: PayrollSourceService) {
    @GetMapping("/v1/payroll/periods/{periodId}/sources")
    fun list(servletRequest: HttpServletRequest, @PathVariable periodId: String): PayrollSourcesDto =
        service.list(actor(servletRequest), PayrollSourcePolicy.uuid(periodId))

    @PostMapping("/v1/payroll/periods/{periodId}/sources")
    fun create(servletRequest: HttpServletRequest, @PathVariable periodId: String,
        @RequestHeader("Idempotency-Key") key: String, @RequestBody request: PayrollSourceRequestDto): PayrollSourceCommandDto {
        IdempotencyKeyContract.requireMatches(key, PayrollSourcePolicy.uuid(request.operationId))
        return service.create(actor(servletRequest), PayrollSourcePolicy.uuid(periodId), request,
            HiltechRequestContext.current(servletRequest).correlationId)
    }

    @PostMapping("/v1/payroll/sources/{recordId}/approval-requests")
    fun requestApproval(servletRequest: HttpServletRequest, @PathVariable recordId: String,
        @RequestHeader("Idempotency-Key") key: String, @RequestBody request: PayrollSourceApprovalRequestDto): PayrollSourceCommandDto {
        val operationId = PayrollSourcePolicy.uuid(request.operationId)
        IdempotencyKeyContract.requireMatches(key, operationId)
        return service.requestApproval(actor(servletRequest), PayrollSourcePolicy.uuid(recordId), operationId,
            HiltechRequestContext.current(servletRequest).correlationId)
    }

    @PostMapping("/v1/payroll/sources/{recordId}/decisions")
    fun decide(servletRequest: HttpServletRequest, @PathVariable recordId: String,
        @RequestHeader("Idempotency-Key") key: String, @RequestBody request: PayrollSourceDecisionRequestDto): PayrollSourceCommandDto {
        IdempotencyKeyContract.requireMatches(key, PayrollSourcePolicy.uuid(request.operationId))
        return service.decide(actor(servletRequest), PayrollSourcePolicy.uuid(recordId), request,
            HiltechRequestContext.current(servletRequest).correlationId)
    }

    private fun actor(request: HttpServletRequest): UUID = HiltechRequestContext.current(request).identityId
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: Payroll9aPolicy.fail("PAYROLL_AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED)
}

/** Remains readable when a specific operational route is paused for an incident. */
@RestController
class PayrollCapabilityController(private val service: PayrollSourceService) {
    @GetMapping("/v1/payroll/capabilities")
    fun capabilities(request: HttpServletRequest, @RequestParam organizationId: String): PayrollCapabilitiesDto {
        val actor = HiltechRequestContext.current(request).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: Payroll9aPolicy.fail("PAYROLL_AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED)
        return service.capabilities(actor, PayrollSourcePolicy.uuid(organizationId))
    }
}
