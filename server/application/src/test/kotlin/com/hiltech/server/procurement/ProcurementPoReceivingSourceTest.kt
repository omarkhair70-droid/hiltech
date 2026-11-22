package com.hiltech.server.procurement

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ProcurementPoReceivingSourceTest {
    private val org = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val poId = UUID.randomUUID()
    private val destination = UUID.randomUUID()
    private val item = UUID.randomUUID()
    private val candidateId = UUID.randomUUID()
    private val at = Instant.parse("2026-11-22T18:00:00Z")
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val purchases = Mockito.mock(PurchaseOrderIssuePersistence::class.java)
    private val candidates = Mockito.mock(PurchaseOrderCandidatePersistence::class.java)
    private val po = PurchaseOrderIssuedRecord(
        poId, UUID.randomUUID(), org, candidateId, UUID.randomUUID(),
        "PROCUREMENT_STANDARD", 1, "PROCUREMENT_PO_ISSUER",
        UUID.randomUUID(), "PO-000008", 8L, UUID.randomUUID(),
        destination, item, UUID.randomUUID(), "a".repeat(64),
        UUID.randomUUID(), at, "s04-source",
    )
    private val candidate = PurchaseOrderCandidateRecord(
        candidateId, UUID.randomUUID(), org, UUID.randomUUID(),
        UUID.randomUUID(), 3L, po.quoteId, 1, po.supplierOrganizationId, item,
        BigDecimal("12"), "EA", "EGP", BigDecimal("2"), BigDecimal.ZERO,
        BigDecimal("24"), po.quoteSha256,
        "Approved project stock need", UUID.randomUUID(), at, "s04-source",
    )
    private fun source(enabled: Boolean = true) = JdbcProcurementPoReceivingSource(
        jdbc, purchases, candidates, Clock.fixed(at, ZoneOffset.UTC), enabled,
    )

    private fun allow() {
        Mockito.`when`(purchases.lockOrder(poId, org)).thenReturn(true)
        Mockito.`when`(purchases.effectiveState(poId)).thenReturn(
            PurchaseOrderEffectiveState(po, 1L, "ISSUED", destination),
        )
        Mockito.`when`(candidates.byId(candidateId)).thenReturn(candidate)
    }

    @Test fun validTypedPoCreatesOnlyScopedReceivingSourceAndNoStockEntry() {
        allow()
        val result = source().preflightCreate(
            org, destination, "po:$poId", "DELIVERY-NOTE-2026-001",
        )
        assertEquals(poId, result.purchaseOrderId)
        assertEquals(1L, result.orderVersion)
        assertEquals(item, result.stockItemId)
        assertEquals(destination, result.destinationId)
        assertEquals(0, result.orderedQuantity.compareTo(BigDecimal("12")))
        assertEquals("EA", result.unitCode)
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test fun featureOffOrForgedPoRefDeniesSourceBeforeLinkCreation() {
        allow()
        for (ref in listOf("PO-000008", "po:not-a-uuid", "po:$poId/extra")) {
            assertEquals("PROCUREMENT_PO_RECEIVING_SOURCE_INVALID",
                assertThrows<ProductApiException> {
                    source().preflightCreate(org, destination, ref, "DELIVERY-NOTE-2026-001")
                }.code)
        }
        assertEquals("PROCUREMENT_PO_RECEIVING_SOURCE_INVALID",
            assertThrows<ProductApiException> {
                source(false).preflightCreate(
                    org, destination, "po:$poId", "DELIVERY-NOTE-2026-001",
                )
            }.code)
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test fun cancelledOrCrossTenantOrWrongDestinationDenied() {
        allow()
        assertEquals("PROCUREMENT_PO_RECEIVING_SOURCE_INVALID",
            assertThrows<ProductApiException> {
                source().preflightCreate(other, destination, "po:$poId", "DELIVERY-NOTE-2026-001")
            }.code)
        assertEquals("PROCUREMENT_PO_RECEIVING_SOURCE_INVALID",
            assertThrows<ProductApiException> {
                source().preflightCreate(org, UUID.randomUUID(), "po:$poId", "DELIVERY-NOTE-2026-001")
            }.code)
        Mockito.`when`(purchases.effectiveState(poId)).thenReturn(
            PurchaseOrderEffectiveState(po, 2L, "CANCELLED", destination),
        )
        assertEquals("PROCUREMENT_PO_RECEIVING_SOURCE_INVALID",
            assertThrows<ProductApiException> {
                source().preflightCreate(org, destination, "po:$poId", "DELIVERY-NOTE-2026-001")
            }.code)
    }

    @Test fun unsignedOrMissingDeliveryDocumentCannotBeTreatedAsReceiptEvidence() {
        allow()
        assertEquals("PROCUREMENT_PO_RECEIVING_SOURCE_INVALID",
            assertThrows<ProductApiException> {
                source().preflightCreate(org, destination, "po:$poId", "short")
            }.code)
        assertEquals("PROCUREMENT_PO_RECEIVING_SOURCE_INVALID",
            assertThrows<ProductApiException> {
                source().preflightCreate(org, destination, "po:$poId", null)
            }.code)
    }

    @Test fun defaultDenyPortNeverPermitsPoStockPosting() {
        assertEquals("PROCUREMENT_PO_RECEIVING_DISABLED",
            assertThrows<ProductApiException> {
                DenyPoReceivingSource.preflightCreate(org, destination, "po:$poId", "DOCUMENT-001")
            }.code)
        assertEquals(true, DenyPoReceivingSource.hasAnyLinkedReceipt(poId))
    }
}
