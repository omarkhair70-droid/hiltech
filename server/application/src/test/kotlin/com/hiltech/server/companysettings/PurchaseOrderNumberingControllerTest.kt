package com.hiltech.server.companysettings

import com.hiltech.server.identity.IdentityAccessContext
import com.hiltech.server.identity.IdentityAccessException
import com.hiltech.server.identity.IdentitySessionService
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.HiltechRequestHeaders
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

class PurchaseOrderNumberingControllerTest {
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val operation = UUID.randomUUID()
    private val installation = UUID.randomUUID()
    private val service = Mockito.mock(PurchaseOrderNumberingService::class.java)
    private val sessions = Mockito.mock(IdentitySessionService::class.java)
    private val access = Mockito.mock(IdentityAccessContext::class.java)
    private val controller = PurchaseOrderNumberingController(service, sessions)
    private val request = MockHttpServletRequest().also {
        it.addHeader(HiltechRequestHeaders.CORRELATION_ID, "synthetic-numbering-controller")
        HiltechRequestContext.bindAuthenticatedIdentity(it, actor, UUID.randomUUID())
    }
    private val jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject("synthetic-subject").build()
    init { Mockito.`when`(sessions.requireCurrentAccess(jwt, installation)).thenReturn(access) }

    @Test fun currentConfigurationUsesVerifiedActorAndIsNotCacheable() {
        val current = PurchaseOrderNumberingResponse(organization.toString(), "NOT_CONFIGURED", null, 0, false, null, null)
        Mockito.`when`(service.current(actor, organization)).thenReturn(current)
        val result = controller.current(request, organization)
        assertEquals(current, result.body)
        assertTrue(result.headers.cacheControl.orEmpty().contains("no-store"))
    }

    @Test fun configurationMutationRequiresRecentAuthenticationBeforeDispatch() {
        Mockito.doThrow(IdentityAccessException("REAUTH_REQUIRED", "Synthetic recent authentication required", HttpStatus.PRECONDITION_REQUIRED))
            .`when`(sessions).requireRecentReauthentication(access)
        assertEquals("REAUTH_REQUIRED", assertThrows<IdentityAccessException> {
            controller.configure(request, jwt, organization, operation.toString(), installation, input())
        }.code)
        Mockito.verifyNoInteractions(service)
    }

    @Test fun mismatchedOperationHeaderCannotReachConfigurationMutation() {
        assertEquals("IDEMPOTENCY_KEY_MISMATCH", assertThrows<ProductApiException> {
            controller.configure(request, jwt, organization, UUID.randomUUID().toString(), installation, input())
        }.code)
        Mockito.verifyNoInteractions(service, sessions)
    }

    @Test fun serverBindsCompanyAndActorWhilePassingExactVersionAndReason() {
        val command = ConfigurePurchaseOrderNumberingCommand(operation, organization, 0, PurchaseOrderNumberingSource.NOT_CONFIGURED,
            null, "HT-PO", "Synthetic numbering setup", actor, HiltechRequestContext.current(request).correlationId)
        val receipt = ConfigurePurchaseOrderNumberingResponse(operation.toString(), organization.toString(), 1, "HT-PO", true, false)
        Mockito.`when`(service.configure(command)).thenReturn(receipt)
        val result = controller.configure(request, jwt, organization, operation.toString(), installation, input())
        assertEquals(receipt, result.body)
        assertTrue(result.headers.cacheControl.orEmpty().contains("no-store"))
        Mockito.verify(sessions).requireRecentReauthentication(access)
        Mockito.verify(service).configure(command)
    }
    private fun input() = ConfigurePurchaseOrderNumberingRequest(operation, 0, "NOT_CONFIGURED", null, "HT-PO", "Synthetic numbering setup")
}
