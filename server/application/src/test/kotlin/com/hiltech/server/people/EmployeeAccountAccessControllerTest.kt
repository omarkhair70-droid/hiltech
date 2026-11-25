package com.hiltech.server.people

import com.hiltech.server.identity.IdentityAccessContext
import com.hiltech.server.identity.IdentityAccessException
import com.hiltech.server.identity.IdentitySessionService
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

class EmployeeAccountAccessControllerTest {
    private val actor = UUID.randomUUID()
    private val employee = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val identity = UUID.randomUUID()
    private val membership = UUID.randomUUID()
    private val operation = UUID.randomUUID()
    private val installation = UUID.randomUUID()
    private val service = Mockito.mock(EmployeeAccountAccessService::class.java)
    private val sessions = Mockito.mock(IdentitySessionService::class.java)
    private val context = Mockito.mock(IdentityAccessContext::class.java)
    private val controller = EmployeeAccountAccessController(service, sessions)
    private val request = MockHttpServletRequest().also {
        it.addHeader("X-Correlation-Id", "synthetic-account-controller")
        HiltechRequestContext.bindAuthenticatedIdentity(it, actor, UUID.randomUUID())
    }
    private val jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject("synthetic-subject")
        .claim("name", "Synthetic signed-in administrator").build()
    init { Mockito.`when`(sessions.requireCurrentAccess(jwt, installation)).thenReturn(context) }

    @Test
    fun currentAccountChoiceComesFromTheVerifiedPrincipalAndIsNotCached() {
        val setup = EmployeeAccountAccessResponse(employee.toString(), organization.toString(), 7, null, false,
            OwnAccountCandidateResponse("Synthetic signed-in administrator", 3, membership.toString(), 2), null)
        Mockito.`when`(service.availability(actor, employee, "Synthetic signed-in administrator")).thenReturn(setup)
        val response = controller.current(request, jwt, employee)
        assertEquals(setup, response.body)
        assertTrue(response.headers.cacheControl.orEmpty().contains("no-store"))
    }

    @Test
    fun credentialMutationRequiresFreshSessionBeforeServiceDispatch() {
        Mockito.doThrow(IdentityAccessException("REAUTH_REQUIRED", "Synthetic recent authentication required", HttpStatus.PRECONDITION_REQUIRED))
            .`when`(sessions).requireRecentReauthentication(context)
        val failure = assertThrows<IdentityAccessException> { controller.reissue(request, jwt, employee, operation.toString(), installation, reissue()) }
        assertEquals("REAUTH_REQUIRED", failure.code)
        Mockito.verifyNoInteractions(service)
    }

    @Test
    fun ownAccountLinkAlsoRequiresFreshSession() {
        Mockito.doThrow(IdentityAccessException("REAUTH_REQUIRED", "Synthetic recent authentication required", HttpStatus.PRECONDITION_REQUIRED))
            .`when`(sessions).requireRecentReauthentication(context)
        assertThrows<IdentityAccessException> { controller.linkOwn(request, jwt, employee, operation.toString(), installation, link()) }
        Mockito.verifyNoInteractions(service)
    }

    @Test
    fun mismatchedOperationHeaderCannotReachCredentialOrLinkMutation() {
        val wrong = UUID.randomUUID().toString()
        assertEquals("IDEMPOTENCY_KEY_MISMATCH", assertThrows<ProductApiException> { controller.reissue(request, jwt, employee, wrong, installation, reissue()) }.code)
        assertEquals("IDEMPOTENCY_KEY_MISMATCH", assertThrows<ProductApiException> { controller.linkOwn(request, jwt, employee, wrong, installation, link()) }.code)
        Mockito.verifyNoInteractions(service, sessions)
    }

    @Test
    fun secretResponseIsNeverCacheableAndUsesTheAuthenticatedActor() {
        val command = CredentialReissueCommand(operation, employee, 7, identity, 3, membership, 2, null, null,
            "Synthetic lost handoff", actor, HiltechRequestContext.current(request).correlationId)
        val result = CredentialReissueResponse(operation.toString(), employee.toString(), organization.toString(), identity.toString(),
            "ISSUED", 2, false, "synthetic-password", null)
        Mockito.`when`(service.reissue(command)).thenReturn(result)
        val response = controller.reissue(request, jwt, employee, operation.toString(), installation, reissue())
        assertEquals(result, response.body)
        assertTrue(response.headers.cacheControl.orEmpty().contains("no-store"))
        assertEquals("no-cache", response.headers.getFirst("Pragma"))
        Mockito.verify(sessions).requireRecentReauthentication(context)
        Mockito.verify(service).reissue(command)
    }

    private fun reissue() = ReissueEmployeeCredentialRequest(operation, 7, identity, 3, membership, 2, null, null, "Synthetic lost handoff")
    private fun link() = LinkOwnEmployeeAccountRequest(operation, 7, 3, membership, 2)
}
