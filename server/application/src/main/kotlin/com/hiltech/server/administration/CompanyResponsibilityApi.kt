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

data class GrantEmployeeCompanyResponsibilityRequest(
    val operationId: String, val organizationId: String, val baseEmployeeVersion: Long,
    val expectedIdentityId: String, val authorityKey: String,
)
data class EndEmployeeCompanyResponsibilityRequest(
    val operationId: String, val organizationId: String, val baseEmployeeVersion: Long,
    val expectedIdentityId: String, val authorityKey: String, val baseBindingVersion: Long,
)

@RestController
class CompanyResponsibilityController(
    private val service: CompanyResponsibilityService,
    private val sessions: IdentitySessionService,
) {
    @GetMapping("/v1/employees/{employeeId}/company-responsibilities")
    fun current(servletRequest: HttpServletRequest, @PathVariable employeeId: String): ResponseEntity<EmployeeCompanyResponsibilitiesResponse> {
        val actor = requireActor(servletRequest)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current(actor, parseUuid(employeeId)))
    }

    @PostMapping("/v1/employees/{employeeId}/company-responsibilities")
    fun grant(
        servletRequest: HttpServletRequest, @AuthenticationPrincipal jwt: Jwt, @PathVariable employeeId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-Device-Installation-Id") installationId: String,
        @RequestBody request: GrantEmployeeCompanyResponsibilityRequest,
    ): ResponseEntity<CompanyResponsibilityCommandResponse> {
        val actor = requireActor(servletRequest)
        val operation = parseUuid(request.operationId)
        IdempotencyKeyContract.requireMatches(idempotencyKey, operation)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt, parseUuid(installationId)))
        val response = service.grant(CompanyResponsibilityCommand(
            operation, parseUuid(employeeId), parseUuid(request.organizationId), request.baseEmployeeVersion,
            parseUuid(request.expectedIdentityId), parseKey(request.authorityKey), actorUserId = actor,
            correlationId = HiltechRequestContext.current(servletRequest).correlationId,
        ))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response)
    }

    @PostMapping("/v1/employees/{employeeId}/company-responsibilities/{bindingId}/end")
    fun end(
        servletRequest: HttpServletRequest, @AuthenticationPrincipal jwt: Jwt, @PathVariable employeeId: String,
        @PathVariable bindingId: String, @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-Device-Installation-Id") installationId: String,
        @RequestBody request: EndEmployeeCompanyResponsibilityRequest,
    ): ResponseEntity<CompanyResponsibilityCommandResponse> {
        val actor = requireActor(servletRequest)
        val operation = parseUuid(request.operationId)
        IdempotencyKeyContract.requireMatches(idempotencyKey, operation)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt, parseUuid(installationId)))
        val response = service.end(CompanyResponsibilityCommand(
            operation, parseUuid(employeeId), parseUuid(request.organizationId), request.baseEmployeeVersion,
            parseUuid(request.expectedIdentityId), parseKey(request.authorityKey), parseUuid(bindingId), request.baseBindingVersion,
            actor, HiltechRequestContext.current(servletRequest).correlationId,
        ))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response)
    }

    private fun requireActor(request: HttpServletRequest): UUID = HiltechRequestContext.current(request).identityId
        ?.let(::parseUuid) ?: throw ProductApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED)

    private fun parseUuid(value: String): UUID = runCatching { UUID.fromString(value) }.getOrElse {
        throw ProductApiException("RESPONSIBILITY_INVALID_IDENTIFIER", "A responsibility identifier is invalid.", HttpStatus.BAD_REQUEST)
    }

    private fun parseKey(value: String): CompanyResponsibilityKey = CompanyResponsibilityKey.entries.singleOrNull { it.name == value }
        ?: throw ProductApiException("RESPONSIBILITY_UNKNOWN_KEY", "The requested responsibility is not in the managed catalog.", HttpStatus.BAD_REQUEST)
}
