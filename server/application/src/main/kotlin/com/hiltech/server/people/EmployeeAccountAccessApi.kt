package com.hiltech.server.people

import com.hiltech.server.identity.IdentitySessionService
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
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

data class ReissueEmployeeCredentialRequest(
    val operationId: UUID, val baseEmployeeVersion: Long, val expectedIdentityId: UUID, val baseIdentityVersion: Long,
    val expectedMembershipId: UUID, val baseMembershipVersion: Long, val previousOperationId: UUID?, val previousOperationVersion: Long?, val reason: String,
)
data class LinkOwnEmployeeAccountRequest(val operationId: UUID, val baseEmployeeVersion: Long, val baseIdentityVersion: Long,
    val expectedMembershipId: UUID, val baseMembershipVersion: Long)

@RestController
class EmployeeAccountAccessController(private val service: EmployeeAccountAccessService, private val sessions: IdentitySessionService) {
    @GetMapping("/v1/employees/{employeeId}/account-access")
    fun current(request: HttpServletRequest, @AuthenticationPrincipal jwt: Jwt, @PathVariable employeeId: UUID): ResponseEntity<EmployeeAccountAccessResponse> {
        val label = listOf("name", "preferred_username", "email").firstNotNullOfOrNull { claim ->
            jwt.getClaimAsString(claim)?.trim()?.takeIf(String::isNotEmpty)
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.availability(actor(request), employeeId, label))
    }

    @PostMapping("/v1/employees/{employeeId}/credential-reissues")
    fun reissue(servletRequest: HttpServletRequest, @AuthenticationPrincipal jwt: Jwt, @PathVariable employeeId: UUID,
        @RequestHeader("Idempotency-Key") key: String, @RequestHeader("X-Device-Installation-Id") installationId: UUID,
        @RequestBody request: ReissueEmployeeCredentialRequest): ResponseEntity<CredentialReissueResponse> {
        val actor = actor(servletRequest)
        IdempotencyKeyContract.requireMatches(key, request.operationId)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt, installationId))
        val result = service.reissue(CredentialReissueCommand(request.operationId, employeeId, request.baseEmployeeVersion, request.expectedIdentityId,
            request.baseIdentityVersion, request.expectedMembershipId, request.baseMembershipVersion, request.previousOperationId,
            request.previousOperationVersion, request.reason, actor, HiltechRequestContext.current(servletRequest).correlationId))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(result)
    }

    @PostMapping("/v1/employees/{employeeId}/own-account-link")
    fun linkOwn(servletRequest: HttpServletRequest, @AuthenticationPrincipal jwt: Jwt, @PathVariable employeeId: UUID,
        @RequestHeader("Idempotency-Key") key: String, @RequestHeader("X-Device-Installation-Id") installationId: UUID,
        @RequestBody request: LinkOwnEmployeeAccountRequest): ResponseEntity<LinkOwnEmployeeAccountResponse> {
        val actor = actor(servletRequest)
        IdempotencyKeyContract.requireMatches(key, request.operationId)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt, installationId))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.linkOwnAccount(LinkOwnEmployeeAccountCommand(
            request.operationId, employeeId, request.baseEmployeeVersion, request.baseIdentityVersion, request.expectedMembershipId,
            request.baseMembershipVersion, actor, HiltechRequestContext.current(servletRequest).correlationId)))
    }

    private fun actor(request: HttpServletRequest): UUID = HiltechRequestContext.current(request).identityId
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED)
}
