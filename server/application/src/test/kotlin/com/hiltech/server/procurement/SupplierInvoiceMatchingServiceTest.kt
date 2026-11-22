package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalPersistencePort
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class SupplierInvoiceMatchingServiceTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val po = Mockito.mock(PurchaseOrderIssuePersistence::class.java)
    private val approval = Mockito.mock(ApprovalPersistencePort::class.java)
    private val commands = Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val now = Instant.parse("2026-11-22T18:00:00Z")
    private val actor = UUID.randomUUID()
    private val op = UUID.randomUUID()
    private val ctx = ProcurementDraftContext(op, actor, "contract-test")
    private val poId = UUID.randomUUID()

    private fun service(enabled: Boolean, key: String = "") =
        SupplierInvoiceMatchingService(
            jdbc, po, approval, commands, audit,
            Clock.fixed(now, ZoneOffset.UTC), enabled, key,
        )

    @Test fun withoutCompanyFinancePolicyNoInvoiceIsCaptured() {
        val denied = assertThrows<ProductApiException> {
            service(false).createSlot(ctx, poId, CreateSupplierInvoiceDocumentSlotRequest(op))
        }
        assertEquals("PROCUREMENT_SUPPLIER_INVOICE_UNAVAILABLE", denied.code)
        Mockito.verifyNoInteractions(jdbc, po, approval, commands, audit)
    }

    @Test fun enabledWithoutActualFinanceAuthorityKeyFailsClosed() {
        assertEquals("PROCUREMENT_SUPPLIER_INVOICE_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service(true).list(actor, UUID.randomUUID())
            }.code)
        Mockito.verifyNoInteractions(jdbc, po, approval, commands, audit)
    }

    @Test fun untrustedInvoiceNumberCannotBecomeFinanceEvidence() {
        val bad = CaptureSupplierInvoiceRequest(
            op, UUID.randomUUID(), UUID.randomUUID(), "\n", LocalDate.parse("2026-11-22"),
            "1", "EGP", "100", "14",
        )
        assertEquals("PROCUREMENT_SUPPLIER_INVOICE_INVALID",
            assertThrows<ProductApiException> {
                service(true, "FINANCE_APPROVER").capture(ctx, poId, bad)
            }.code)
        Mockito.verifyNoInteractions(jdbc, po, approval, commands, audit)
    }
}
