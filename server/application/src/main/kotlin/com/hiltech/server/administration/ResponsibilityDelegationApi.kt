package com.hiltech.server.administration

import com.hiltech.server.identity.IdentitySessionService
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class GrantResponsibilityDelegationRequest(
    val operationId: String, val organizationId: String, val expectedIdentityId: String, val baseEmployeeVersion: Long,
    val authorityKeys: List<String>, val canDelegateAdministration: Boolean,
)
data class EndResponsibilityDelegationRequest(
    val operationId: String, val organizationId: String, val expectedIdentityId: String, val baseEmployeeVersion: Long,
    val authorityKey: String, val baseBindingVersion: Long,
)

@RestController
class ResponsibilityDelegationController(private val service: ResponsibilityDelegationService, private val sessions: IdentitySessionService) {
    @GetMapping("/v1/employees/{employeeId}/responsibility-administration")
    fun current(request: HttpServletRequest, @PathVariable employeeId: UUID): ResponseEntity<EmployeeResponsibilityDelegationResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current(actor(request), employeeId))

    @PostMapping("/v1/employees/{employeeId}/responsibility-administration")
    fun grant(servletRequest: HttpServletRequest, @AuthenticationPrincipal jwt: Jwt, @PathVariable employeeId: UUID,
              @RequestHeader("Idempotency-Key") idempotencyKey: String,
              @RequestHeader("X-Device-Installation-Id") installationId: UUID,
              @RequestBody request: GrantResponsibilityDelegationRequest): ResponseEntity<ResponsibilityDelegationReceipt> {
        val actor = actor(servletRequest)
        val operation = uuid(request.operationId)
        IdempotencyKeyContract.requireMatches(idempotencyKey, operation)
        if (request.authorityKeys.size !in 1..CompanyResponsibilityKey.entries.size) throw ProductApiException(
            "RESPONSIBILITY_DELEGATION_INVALID", "Select a bounded set of administration responsibilities.", HttpStatus.BAD_REQUEST)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt, installationId))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.grant(ResponsibilityDelegationCommand(
            operation, employeeId, uuid(request.organizationId), uuid(request.expectedIdentityId), request.baseEmployeeVersion,
            request.authorityKeys.map(::key).toSet(), request.canDelegateAdministration,
            actor = actor, correlationId = HiltechRequestContext.current(servletRequest).correlationId,
        )))
    }

    @PostMapping("/v1/employees/{employeeId}/responsibility-administration/{bindingId}/end")
    fun end(servletRequest: HttpServletRequest, @AuthenticationPrincipal jwt: Jwt, @PathVariable employeeId: UUID,
            @PathVariable bindingId: UUID, @RequestHeader("Idempotency-Key") idempotencyKey: String,
            @RequestHeader("X-Device-Installation-Id") installationId: UUID,
            @RequestBody request: EndResponsibilityDelegationRequest): ResponseEntity<ResponsibilityDelegationReceipt> {
        val actor = actor(servletRequest)
        val operation = uuid(request.operationId)
        IdempotencyKeyContract.requireMatches(idempotencyKey, operation)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt, installationId))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.end(ResponsibilityDelegationCommand(
            operation, employeeId, uuid(request.organizationId), uuid(request.expectedIdentityId), request.baseEmployeeVersion,
            setOf(key(request.authorityKey)), false, bindingId, request.baseBindingVersion,
            actor, HiltechRequestContext.current(servletRequest).correlationId,
        )))
    }
    private fun actor(request: HttpServletRequest): UUID = HiltechRequestContext.current(request).identityId?.let(::uuid)
        ?: throw ProductApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED)
    private fun uuid(value: String): UUID = runCatching { UUID.fromString(value) }.getOrElse {
        throw ProductApiException("RESPONSIBILITY_INVALID_IDENTIFIER", "An administration identifier is invalid.", HttpStatus.BAD_REQUEST)
    }
    private fun key(value: String) = CompanyResponsibilityKey.entries.singleOrNull { it.name == value }
        ?: throw ProductApiException("RESPONSIBILITY_UNKNOWN_KEY", "The responsibility is outside the managed catalog.", HttpStatus.BAD_REQUEST)
}
