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

class PurchaseOrderCommercialReplacementServiceTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val purchases = Mockito.mock(PurchaseOrderIssuePersistence::class.java)
    private val issuer = Mockito.mock(PurchaseOrderIssueService::class.java)
    private val current = Mockito.mock(PurchaseOrderCandidateApprovalSubject::class.java)
    private val commands = Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val ctx = ProcurementDraftContext(UUID.randomUUID(), UUID.randomUUID(), "synthetic")
    private val oldId = UUID.randomUUID()
    private val newId = UUID.randomUUID()
    private val service = PurchaseOrderCommercialReplacementService(
        jdbc, purchases, issuer, current, commands, audit,
        Clock.fixed(Instant.parse("2026-11-22T18:00:00Z"), ZoneOffset.UTC),
        false,
    )

    @Test fun replacementIsNeverAutoIssuedWithoutCompanyPermission() {
        val request = LinkPurchaseOrderReplacementRequest(
            ctx.operationId, newId, 2L, 1L,
            "Commercial price changed; a new approved source is required.",
        )
        assertEquals("PROCUREMENT_PO_REPLACEMENT_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service.link(ctx, oldId, request)
            }.code)
        Mockito.verifyNoInteractions(jdbc, purchases, issuer, current, commands, audit)
    }
}
