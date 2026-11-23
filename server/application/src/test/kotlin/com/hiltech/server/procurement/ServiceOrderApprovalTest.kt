package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ServiceOrderApprovalTest {
    private val jdbc=Mockito.mock(JdbcTemplate::class.java)
    private val approval=Mockito.mock(ApprovalPersistencePort::class.java)
    private val engine=Mockito.mock(ApprovalEngineService::class.java)
    private val guard=Mockito.mock(ServiceOrderCandidateApprovalSubject::class.java)
    private val commands=Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit=Mockito.mock(AuditEventWriter::class.java)
    private val clock=Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"),ZoneOffset.UTC)
    private val actor=UUID.randomUUID()
    private val op=UUID.randomUUID()
    private val ctx=ProcurementDraftContext(op,actor,"approval-test")
    private fun service(enabled:Boolean=false,sourcing:Boolean=false,
                        buyer:String="",issuer:String="",policy:String="")=
        ServiceOrderService(jdbc,approval,engine,guard,commands,audit,clock,
            enabled,sourcing,buyer,issuer,policy)

    @Test fun noOrderIsCreatedWithoutExplicitFeatureAndCurrentAuthority() {
        val c=ServiceOrderCandidateApprovalSubject(jdbc,clock,false)
        assertFalse(c.isCurrent(com.hiltech.server.approval.ApprovalSubjectRef(
            UUID.randomUUID(),"SERVICE_ORDER_CANDIDATE",UUID.randomUUID(),1)))
        val error=assertThrows<ProductApiException> {
            service().queue(actor,UUID.randomUUID())
        }
        assertEquals("SERVICE_ORDER_UNAVAILABLE",error.code)
        Mockito.verifyNoInteractions(jdbc,approval,engine,commands,audit)
    }

    @Test fun wrongIdempotencyOperationCannotOpenCommercialCandidate() {
        val error=assertThrows<ProductApiException> {
            service(true,true).create(ctx,CreateServiceOrderCandidateRequest(
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()))
        }
        assertEquals("SERVICE_ORDER_INVALID",error.code)
        Mockito.verifyNoInteractions(jdbc,approval,engine,guard,commands,audit)
    }

    @Test fun missingIssuerAuthorityCannotIssueOrderEvenWhenFeatureIsOn() {
        val error=assertThrows<ProductApiException> {
            service(true,true, "SERVICE_BUYER","", "SERVICE_APPROVAL")
                .issue(ctx,UUID.randomUUID(),IssueServiceOrderRequest(op,2))
        }
        assertEquals("SERVICE_ORDER_INVALID",error.code)
        Mockito.verifyNoInteractions(jdbc,approval,engine,guard,commands,audit)
    }

    @Test fun requestForApprovalIsNotAvailableWithoutStoredCandidate() {
        val error=assertThrows<ProductApiException> {
            service(true,true,"SERVICE_BUYER","SERVICE_ISSUER","SERVICE_APPROVAL")
                .requestApproval(ctx,UUID.randomUUID(),
                    RequestServiceOrderApprovalRequest(UUID.randomUUID()))
        }
        assertEquals("SERVICE_ORDER_INVALID",error.code)
        Mockito.verifyNoInteractions(jdbc,approval,engine,guard,commands,audit)
    }
}
