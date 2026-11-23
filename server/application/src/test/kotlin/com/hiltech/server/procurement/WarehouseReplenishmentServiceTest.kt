package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.ReservationPersistence
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import com.hiltech.server.warehouse.WarehousePersistencePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.math.BigDecimal
import java.util.UUID

class WarehouseReplenishmentServiceTest {
    private val drafts = Mockito.mock(PurchaseRequirementDraftPersistence::class.java)
    private val warehouse = Mockito.mock(WarehousePersistencePort::class.java)
    private val warehouseAuth = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val stock = Mockito.mock(StockLedgerPersistence::class.java)
    private val reservations = Mockito.mock(ReservationPersistence::class.java)
    private val verifier = Mockito.mock(ProcurementDemandSourceVerifier::class.java)
    private val commands = Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val actor = UUID.randomUUID()
    private val locationId = UUID.randomUUID()
    private val op = UUID.randomUUID()
    private val ctx = ProcurementDraftContext(op, actor, "warehouse-test")
    private val time = Clock.fixed(Instant.parse("2026-11-22T20:00:00Z"), ZoneOffset.UTC)

    private fun service(requirements: Boolean, warehouseEnabled: Boolean) =
        WarehouseReplenishmentService(
            drafts, warehouse, warehouseAuth, stock, reservations, verifier,
            commands, audit, time, requirements, warehouseEnabled,
        )

    @Test fun independentlyDisabledWarehouseSourceFailsWithoutReadingCompanyData() {
        val request = CreateWarehouseReplenishmentRequest(
            op, 1, UUID.randomUUID(), 1, "3", "PCS", "Additional stock needed",
        )
        assertEquals("PROCUREMENT_WAREHOUSE_REPLENISHMENT_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service(true, false).create(ctx, locationId, request)
            }.code)
        Mockito.verifyNoInteractions(drafts, warehouse, warehouseAuth, stock,
            reservations, verifier, commands, audit)
    }

    @Test fun existingProcurementFlagStillBlocksWarehouseSourceWhenOff() {
        assertEquals("PROCUREMENT_WAREHOUSE_REPLENISHMENT_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service(false, true).list(actor, locationId)
            }.code)
        Mockito.verifyNoInteractions(drafts, warehouse, warehouseAuth, stock,
            reservations, verifier, commands, audit)
    }

    @Test fun randomOrHiddenStorageIdCannotBecomeCompanyNeed() {
        val request = CreateWarehouseReplenishmentRequest(
            op, 1, UUID.randomUUID(), 1, "10", "PCS", "Warehouse buffer replenishment",
        )
        assertEquals("PROCUREMENT_WAREHOUSE_REPLENISHMENT_UNAVAILABLE",
            assertThrows<ProductApiException> {
                service(true, true).create(ctx, locationId, request)
            }.code)
        Mockito.verifyNoInteractions(drafts, stock, reservations, verifier, commands, audit)
    }
}
