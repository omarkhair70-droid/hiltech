package com.hiltech.server.warehouse

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

class FieldUsageWarehouseReviewServiceTest {
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val id = UUID.randomUUID()
    private val binId = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val requirement = UUID.randomUUID()
    private val item = UUID.randomUUID()
    private val employee = UUID.randomUUID()
    private val facility = WarehouseSnapshot(id, org, "WH-A", "Warehouse", null, true, 1)
    private val bin = Mockito.mock(StorageLocationSnapshot::class.java)
    private val warehouses = Mockito.mock(WarehousePersistencePort::class.java)
    private val auth = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val stock = Mockito.mock(StockFulfillmentPersistence::class.java)
    private val read = Mockito.mock(FieldUsageWarehouseReviewReadPort::class.java)
    private fun service(enabled: Boolean = true) =
        FieldUsageWarehouseReviewService(warehouses, auth, stock, read, enabled)
    private fun report(organization: UUID = org) = FieldUsagePendingForWarehouse(
        UUID.randomUUID(), organization, workId, requirement, item, employee,
        "2.000000", "M", 7, 4, Instant.parse("2026-11-21T19:00:00Z"),
    )
    private fun allowedWarehouse() {
        Mockito.`when`(warehouses.warehouse(id)).thenReturn(facility)
        Mockito.`when`(auth.canReserveResources(actor, facility)).thenReturn(true)
    }
    private fun origin(locationWarehouse: UUID = id, allowMove: Boolean = true) {
        Mockito.`when`(stock.origins(item, requirement, employee)).thenReturn(listOf(binId))
        Mockito.`when`(warehouses.storageLocation(binId)).thenReturn(bin)
        Mockito.`when`(bin.organizationId).thenReturn(org)
        Mockito.`when`(bin.warehouseId).thenReturn(locationWarehouse)
        Mockito.`when`(auth.canTransferStock(actor, bin)).thenReturn(allowMove)
    }
    @Test fun `disabled by default even for a warehouse actor`() {
        assertEquals("FIELD_MATERIAL_REVIEW_UNAVAILABLE",
            assertThrows<ProductApiException> { service(false).pending(actor, id) }.code)
        Mockito.verifyNoInteractions(warehouses, auth, stock, read)
    }
    @Test fun `viewer cannot query personal claims without reservation authority`() {
        Mockito.`when`(warehouses.warehouse(id)).thenReturn(facility)
        assertEquals("FIELD_MATERIAL_REVIEW_UNAVAILABLE",
            assertThrows<ProductApiException> { service().pending(actor, id) }.code)
        Mockito.verifyNoInteractions(read, stock)
    }
    @Test fun `only same warehouse and current origin-moving authority may read`() {
        allowedWarehouse()
        origin()
        val row = report()
        Mockito.`when`(read.pending(org, id)).thenReturn(listOf(row))
        assertEquals(row.operationId, service().pending(actor, id).rows.single().operationId)
        origin(locationWarehouse = UUID.randomUUID())
        assertTrue(service().pending(actor, id).rows.isEmpty())
        origin(allowMove = false)
        assertTrue(service().pending(actor, id).rows.isEmpty())
    }
    @Test fun `missing origins and other organization are hidden`() {
        allowedWarehouse()
        Mockito.`when`(read.pending(org, id)).thenReturn(listOf(report()))
        assertTrue(service().pending(actor, id).rows.isEmpty())
        origin()
        Mockito.`when`(read.pending(org, id)).thenReturn(listOf(report(UUID.randomUUID())))
        assertTrue(service().pending(actor, id).rows.isEmpty())
    }
    @Test fun `inactive warehouse never discloses report list`() {
        Mockito.`when`(warehouses.warehouse(id)).thenReturn(facility.copy(active = false))
        assertEquals("FIELD_MATERIAL_REVIEW_UNAVAILABLE",
            assertThrows<ProductApiException> { service().pending(actor, id) }.code)
        Mockito.verifyNoInteractions(read, stock)
    }
    @Test fun `review scans beyond hidden candidates without dropping a visible claim`() {
        allowedWarehouse()
        origin()
        val hidden = List(101) { report(UUID.randomUUID()) }
        val later = report()
        Mockito.`when`(read.pending(org, id, 0)).thenReturn(hidden)
        Mockito.`when`(read.pending(org, id, 101)).thenReturn(listOf(later))
        val page = service().pending(actor, id)
        assertEquals(listOf(later.operationId), page.rows.map { it.operationId })
        assertTrue(!page.limited)
    }
    @Test fun `review indicates more work when there are 101 authorized claims`() {
        allowedWarehouse()
        origin()
        Mockito.`when`(read.pending(org, id, 0)).thenReturn(List(101) { report() })
        val page = service().pending(actor, id)
        assertEquals(100, page.rows.size)
        assertTrue(page.limited)
    }
}
