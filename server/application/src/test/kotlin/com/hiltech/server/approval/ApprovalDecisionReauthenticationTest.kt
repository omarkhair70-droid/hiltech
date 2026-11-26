package com.hiltech.server.approval

import com.hiltech.server.identity.IdentityAccessContext
import com.hiltech.server.identity.IdentityAccessException
import com.hiltech.server.identity.IdentitySessionService
import com.hiltech.server.payroll.PayrollRunDecisionRequestDto
import com.hiltech.server.payroll.PayrollRunReviewController
import com.hiltech.server.payroll.PayrollRunReviewService
import com.hiltech.server.payroll.PayrollSourceController
import com.hiltech.server.payroll.PayrollSourceDecisionRequestDto
import com.hiltech.server.payroll.PayrollSourceService
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

class ApprovalDecisionReauthenticationTest {
    private val actor = UUID.randomUUID()
    private val record = UUID.randomUUID()
    private val operation = UUID.randomUUID()
    private val installation = UUID.randomUUID()
    private val sessions = Mockito.mock(IdentitySessionService::class.java)
    private val access = Mockito.mock(IdentityAccessContext::class.java)
    private val engine = Mockito.mock(ApprovalEngineService::class.java)
    private val reads = Mockito.mock(ApprovalReadService::class.java)
    private val sources = Mockito.mock(PayrollSourceService::class.java)
    private val runs = Mockito.mock(PayrollRunReviewService::class.java)
    private val jwt = Jwt.withTokenValue("synthetic-token").header("alg", "none").subject("synthetic-subject").build()
    private val servlet = MockHttpServletRequest().also {
        HiltechRequestContext.bindAuthenticatedIdentity(it, actor, UUID.randomUUID())
    }
    init {
        Mockito.`when`(sessions.requireCurrentAccess(jwt, installation)).thenReturn(access)
        Mockito.doThrow(IdentityAccessException("REAUTH_REQUIRED", "Synthetic expired confirmation", HttpStatus.PRECONDITION_REQUIRED))
            .`when`(sessions).requireRecentReauthentication(access)
    }

    @Test fun genericApprovalCannotBypassTheSourceDecisionReauthenticationWindow() {
        val controller = ApprovalController(reads, engine, sessions)
        assertEquals("REAUTH_REQUIRED", assertThrows<IdentityAccessException> {
            controller.decide(servlet, jwt, installation, record.toString(), operation.toString(),
                ApprovalDecisionRequest(operation.toString(), ApprovalDecisionType.APPROVE, null))
        }.code)
        Mockito.verifyNoInteractions(engine)
    }

    @Test fun payrollSourceDecisionRequiresExistingRecentAuthentication() {
        assertThrows<IdentityAccessException> {
            PayrollSourceController(sources, sessions).decide(servlet, record.toString(), jwt, installation, operation.toString(),
                PayrollSourceDecisionRequestDto(operation.toString(), "APPROVE", null))
        }
        Mockito.verifyNoInteractions(sources)
    }

    @Test fun payrollRunDecisionRequiresExistingRecentAuthentication() {
        assertThrows<IdentityAccessException> {
            PayrollRunReviewController(runs, sessions).decide(servlet, record.toString(), jwt, installation, operation.toString(),
                PayrollRunDecisionRequestDto(operation.toString(), UUID.randomUUID().toString(), "APPROVE", null))
        }
        Mockito.verifyNoInteractions(runs)
    }

    @Test fun operationMismatchIsRejectedBeforeDispatchOrReauthentication() {
        assertEquals("IDEMPOTENCY_KEY_MISMATCH", assertThrows<ProductApiException> {
            ApprovalController(reads, engine, sessions).decide(servlet, jwt, installation, record.toString(), UUID.randomUUID().toString(),
                ApprovalDecisionRequest(operation.toString(), ApprovalDecisionType.APPROVE, null))
        }.code)
        Mockito.verifyNoInteractions(engine)
        Mockito.verify(sessions, Mockito.never()).requireCurrentAccess(jwt, installation)
    }
}
