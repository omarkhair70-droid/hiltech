package com.hiltech.server.companysettings

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

data class ConfigurePurchaseOrderNumberingRequest(val operationId: UUID, val expectedVersion: Long,
    val expectedSource: String, val expectedPrefix: String?, val prefix: String, val reason: String)

@RestController
class PurchaseOrderNumberingController(private val service: PurchaseOrderNumberingService, private val sessions: IdentitySessionService) {
    @GetMapping("/v1/organizations/{organizationId}/administration/purchase-order-numbering")
    fun current(request: HttpServletRequest, @PathVariable organizationId: UUID): ResponseEntity<PurchaseOrderNumberingResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current(actor(request), organizationId))

    @PostMapping("/v1/organizations/{organizationId}/administration/purchase-order-numbering")
    fun configure(request: HttpServletRequest, @AuthenticationPrincipal jwt: Jwt, @PathVariable organizationId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-Device-Installation-Id") installationId: UUID,
        @RequestBody body: ConfigurePurchaseOrderNumberingRequest): ResponseEntity<ConfigurePurchaseOrderNumberingResponse> {
        val actor = actor(request)
        IdempotencyKeyContract.requireMatches(idempotencyKey, body.operationId)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt, installationId))
        val source = PurchaseOrderNumberingSource.entries.singleOrNull { it.name == body.expectedSource }
            ?: throw ProductApiException("COMPANY_NUMBERING_INVALID", "The current numbering configuration is required.", HttpStatus.BAD_REQUEST)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.configure(ConfigurePurchaseOrderNumberingCommand(
            body.operationId, organizationId, body.expectedVersion, source, body.expectedPrefix, body.prefix,
            body.reason, actor, HiltechRequestContext.current(request).correlationId)))
    }
    private fun actor(request: HttpServletRequest): UUID = HiltechRequestContext.current(request).identityId?.let {
        runCatching { UUID.fromString(it) }.getOrNull()
    } ?: throw ProductApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED)
}
