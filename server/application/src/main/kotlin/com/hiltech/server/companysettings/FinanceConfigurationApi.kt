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
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
class FinanceConfigurationController(private val service: FinanceConfigurationService, private val sessions: IdentitySessionService) {
    @GetMapping("/v1/organizations/{organizationId}/administration/financial-configuration")
    fun page(request: HttpServletRequest,@PathVariable organizationId: UUID,
        @RequestParam kind: FinanceConfigurationKind,@RequestParam(defaultValue="0") offset: Int): ResponseEntity<FinanceConfigurationPage> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(actor(request),organizationId,kind,offset))

    @GetMapping("/v1/organizations/{organizationId}/administration/financial-configuration/treasury-instruments")
    fun treasuryInstruments(request: HttpServletRequest,@PathVariable organizationId: UUID,
        @RequestParam accountKind: String):ResponseEntity<FinanceTreasuryInstrumentPage> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.treasuryInstruments(actor(request),organizationId,accountKind))

    @GetMapping("/v1/organizations/{organizationId}/administration/financial-configuration/financing-accounts")
    fun financingAccounts(request:HttpServletRequest,@PathVariable organizationId:UUID,
        @RequestParam financingKind:String,@RequestParam currency:String):ResponseEntity<FinanceFinancingAccountPage> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.financingAccounts(actor(request),organizationId,financingKind,currency))

    @PostMapping("/v1/organizations/{organizationId}/administration/financial-configuration")
    fun create(request: HttpServletRequest,@AuthenticationPrincipal jwt: Jwt,@PathVariable organizationId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-Device-Installation-Id") installationId: UUID,
        @RequestBody body: FinanceConfigurationInput): ResponseEntity<FinanceConfigurationReceipt> {
        val actor = actor(request)
        IdempotencyKeyContract.requireMatches(idempotencyKey,body.operationId)
        sessions.requireRecentReauthentication(sessions.requireCurrentAccess(jwt,installationId))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.create(actor,organizationId,body,HiltechRequestContext.current(request).correlationId))
    }
    private fun actor(request: HttpServletRequest): UUID = HiltechRequestContext.current(request).identityId?.let {
        runCatching { UUID.fromString(it) }.getOrNull()
    } ?: throw ProductApiException("UNAUTHENTICATED","Authentication is required.",HttpStatus.UNAUTHORIZED)
}
