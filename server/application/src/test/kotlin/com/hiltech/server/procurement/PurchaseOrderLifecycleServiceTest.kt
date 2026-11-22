package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PurchaseOrderLifecycleServiceTest {
    private val org = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val poId = UUID.randomUUID()
    private val operation = UUID.randomUUID()
    private val at = Instant.parse("2026-11-22T16:00:00Z")
    private val order = PurchaseOrderIssuedRecord(
        poId, UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(),
        "PROCUREMENT_STANDARD", 2, "PROCUREMENT_PO_ISSUER",
        UUID.randomUUID(), "PO-000031", 31, UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        "f".repeat(64), actor, at, "test",
    )
    private val ctx = ProcurementDraftContext(operation, actor, "cancel-test")
    private val purchases = Mockito.mock(PurchaseOrderIssuePersistence::class.java)
    private val issuer = Mockito.mock(PurchaseOrderIssueService::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private var stored: PurchaseOrderLifecycleEvent? = null
    private val commands = object : IdempotentCommandExecutor {
        override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome) =
            IdempotentCommandExecution(action(), false)
    }
    private fun service(enabled: Boolean = true) = PurchaseOrderLifecycleService(
        purchases, issuer, commands, audit,
        Clock.fixed(at, ZoneOffset.UTC), enabled,
    )
    private fun request(revision: Long = 1L, reason: String = "Duplicate order cancelled before sending") =
        CancelPurchaseOrderRequest(operation, revision, reason)

    private fun allowed() {
        Mockito.`when`(purchases.byId(poId)).thenReturn(order)
        Mockito.`when`(purchases.lockOrder(poId, org)).thenReturn(true)
        Mockito.`when`(purchases.lifecycleEvents(poId)).thenAnswer {
            stored?.let(::listOf) ?: emptyList<PurchaseOrderLifecycleEvent>()
        }
        Mockito.doAnswer {
            stored = it.getArgument(0)
            Unit
        }.`when`(purchases).appendLifecycleEvent(
            Mockito.any(PurchaseOrderLifecycleEvent::class.java)
                ?: PurchaseOrderLifecycleEvent(
                    UUID.randomUUID(), operation, org, poId, 2,
                    "CANCELLED", "Fixture cancellation", actor, at, "test",
                ),
        )
    }

    @Test fun approvedIssuedOrderIsNeverOverwrittenOrSentWhenCancelled() {
        allowed()
        val receipt = service().cancel(ctx, poId, request())
        assertEquals("CANCELLED", receipt.state)
        assertEquals("PO-000031", receipt.code)
        assertEquals(2L, receipt.eventVersion)
        assertFalse(receipt.supplierNotified)
        assertFalse(receipt.inventoryChanged)
        assertFalse(receipt.paymentChanged)
        assertEquals("CANCELLED", requireNotNull(stored).type)
        assertEquals(poId, requireNotNull(stored).orderId)
        assertEquals(1, Mockito.mockingDetails(audit).invocations.count {
            it.method.name == "append" &&
                (it.arguments.single() as AuditEventRecord).action == "PROCUREMENT_PO_CANCELLED"
        })
    }

    @Test fun repeatedOrVersionStaleCancellationCannotModifyIssuedHistory() {
        allowed()
        assertEquals("PROCUREMENT_PO_CANCELLATION_INVALID", assertThrows<ProductApiException> {
            service().cancel(ctx, poId, request(revision = 2))
        }.code)
        stored = PurchaseOrderLifecycleEvent(
            UUID.randomUUID(), UUID.randomUUID(), org, poId, 2L,
            "CANCELLED", "Previously cancelled order", actor, at, "other",
        )
        assertEquals("PROCUREMENT_PO_LIFECYCLE_CHANGED", assertThrows<ProductApiException> {
            service().cancel(ctx, poId, request())
        }.code)
        assertEquals(0, Mockito.mockingDetails(audit).invocations.count {
            it.method.name == "append"
        })
    }

    @Test fun sentOrUnavailablePurchaseOrderCannotBeCancelled() {
        allowed()
        Mockito.`when`(purchases.lockOrder(poId, org)).thenReturn(false)
        assertEquals("PROCUREMENT_PO_CANCELLATION_UNAVAILABLE", assertThrows<ProductApiException> {
            service().cancel(ctx, poId, request())
        }.code)
        assertEquals(null, stored)
    }

    @Test fun sourcePermissionAndFlagFailClosed() {
        allowed()
        assertEquals("PROCUREMENT_PO_CANCELLATION_UNAVAILABLE", assertThrows<ProductApiException> {
            service(false).cancel(ctx, poId, request())
        }.code)
        Mockito.`when`(issuer.requireIssuerForLifecycle(actor, org)).thenThrow(
            ProductApiException(
                "PROCUREMENT_PO_ISSUE_UNAVAILABLE", "Issuer permission revoked",
                org.springframework.http.HttpStatus.NOT_FOUND,
            ),
        )
        assertEquals("PROCUREMENT_PO_ISSUE_UNAVAILABLE", assertThrows<ProductApiException> {
            service().cancel(ctx, poId, request())
        }.code)
        assertEquals(null, stored)
    }
}
