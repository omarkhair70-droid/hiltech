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

class SupplierPoCommunicationServiceTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val orders = Mockito.mock(PurchaseOrderIssuePersistence::class.java)
    private val issuer = Mockito.mock(PurchaseOrderIssueService::class.java)
    private val commands = Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val at = Instant.parse("2026-11-22T17:00:00Z")
    private val ctx = ProcurementDraftContext(UUID.randomUUID(), UUID.randomUUID(), "synthetic")
    private val poId = UUID.randomUUID()

    private fun service(enabled: Boolean) = SupplierPoCommunicationService(
        jdbc, orders, issuer, commands, audit, Clock.fixed(at, ZoneOffset.UTC), enabled,
    )

    @Test fun disabledSupplierCommunicationFailsClosedWithoutReadingPriceHistory() {
        assertEquals("PROCUREMENT_PO_COMMUNICATION_UNAVAILABLE",
            assertThrows<ProductApiException> { service(false).history(ctx.actor, poId) }.code)
        Mockito.verifyNoInteractions(jdbc, orders, issuer, commands, audit)
    }

    @Test fun fabricatedCommunicationProofCannotCreateSentState() {
        val request = AttestSupplierPoCommunicationRequest(
            ctx.operationId, 1, "EXTERNAL_DISPATCH_ATTESTED", "EMAIL",
            "fake", at,
        )
        assertEquals("PROCUREMENT_PO_COMMUNICATION_INVALID",
            assertThrows<ProductApiException> {
                service(true).attest(ctx, poId, request)
            }.code)
        Mockito.verifyNoInteractions(jdbc, orders, issuer, commands, audit)
    }

    @Test fun acknowledgementCannotUseUnsupportedChannel() {
        val request = AttestSupplierPoCommunicationRequest(
            ctx.operationId, 1, "SUPPLIER_ACK_ATTESTED", "UNKNOWN_CHANNEL",
            "provider-receipt-20261122", at,
        )
        assertEquals("PROCUREMENT_PO_COMMUNICATION_INVALID",
            assertThrows<ProductApiException> {
                service(true).attest(ctx, poId, request)
            }.code)
    }
}
