package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ServiceSupplierCommunicationTest {
    private val jdbc=Mockito.mock(JdbcTemplate::class.java)
    private val orders=Mockito.mock(ServiceOrderService::class.java)
    private val commands=Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit=Mockito.mock(AuditEventWriter::class.java)
    private val clock=Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"),ZoneOffset.UTC)
    private val actor=UUID.randomUUID()
    private val op=UUID.randomUUID()
    private val order=UUID.randomUUID()
    private val ctx=ProcurementDraftContext(op,actor,"supplier-dispatch-test")
    private fun svc(flag:Boolean)=ServiceSupplierCommunicationService(
        jdbc,orders,commands,audit,clock,flag)

    @Test fun disabledCommunicationCannotClaimSupplierWasContacted() {
        val ex=assertThrows<ProductApiException> {
            svc(false).history(actor,order)
        }
        assertEquals("SERVICE_SUPPLIER_COMMUNICATION_UNAVAILABLE",ex.code)
        Mockito.verifyNoInteractions(jdbc,orders,commands,audit)
    }
    @Test fun fabricatedExternalReferenceCannotBeAcceptedAsDispatchProof() {
        val ex=assertThrows<ProductApiException> {
            svc(true).attest(ctx,order,AttestServiceSupplierCommunicationRequest(
                op,"EXTERNAL_DISPATCH_ATTESTED","EMAIL","sent",
                Instant.parse("2026-11-22T20:00:00Z")))
        }
        assertEquals("SERVICE_SUPPLIER_COMMUNICATION_INVALID",ex.code)
        Mockito.verifyNoInteractions(jdbc,orders,commands,audit)
    }
    @Test fun cannotRecordSupplierAcknowledgementFromTheFuture() {
        val ex=assertThrows<ProductApiException> {
            svc(true).attest(ctx,order,AttestServiceSupplierCommunicationRequest(
                op,"SUPPLIER_ACK_ATTESTED","SUPPLIER_PORTAL",
                "REAL-EXTERNAL-REFERENCE-2026-11-24",
                Instant.parse("2026-11-24T00:00:00Z")))
        }
        assertEquals("SERVICE_SUPPLIER_COMMUNICATION_INVALID",ex.code)
        Mockito.verifyNoInteractions(jdbc,orders,commands,audit)
    }
    @Test fun unknownChannelIsNotSilentlyTreatedAsVerified() {
        val ex=assertThrows<ProductApiException> {
            svc(true).attest(ctx,order,AttestServiceSupplierCommunicationRequest(
                op,"EXTERNAL_DISPATCH_ATTESTED","WHATSAPP",
                "REF-TEST-NOT-CONFIGURED",
                Instant.parse("2026-11-22T20:00:00Z")))
        }
        assertEquals("SERVICE_SUPPLIER_COMMUNICATION_INVALID",ex.code)
        Mockito.verifyNoInteractions(jdbc,orders,commands,audit)
    }
}