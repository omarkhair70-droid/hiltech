package com.hiltech.server.executive

import com.hiltech.server.inbox.InboxService
import com.hiltech.server.projects.ProjectsService
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.AssetAvailabilityView
import com.hiltech.server.warehouse.AssetService
import com.hiltech.server.warehouse.AssetServiceQueueEntry
import com.hiltech.server.warehouse.WarehouseReadService
import com.hiltech.server.warehouse.WarehouseSnapshot
import com.hiltech.server.work.ReviewProgressHealthService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.http.HttpStatus
import java.util.UUID

class CurrentExecutiveSourcesTest {
    private val actor = UUID.fromString("11111111-2222-4333-8444-555555555555")
    private val organization = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")

    private val inbox = Mockito.mock(InboxService::class.java)
    private val projects = Mockito.mock(ProjectsService::class.java)
    private val health = Mockito.mock(ReviewProgressHealthService::class.java)
    private val warehouses = Mockito.mock(WarehouseReadService::class.java)
    private val assets = Mockito.mock(AssetService::class.java)

    private fun warehouse(code: String, organizationId: UUID = organization) = WarehouseSnapshot(
        warehouseId = UUID.nameUUIDFromBytes(code.toByteArray()),
        organizationId = organizationId,
        code = code,
        name = "Warehouse $code",
        facilityRef = null,
        active = true,
        version = 1,
    )

    private fun queue(size: Int) = (1..size).map { index ->
        AssetServiceQueueEntry(
            id = UUID.nameUUIDFromBytes(("asset-$index").toByteArray()),
            assetCode = "A-$index",
            name = "Asset $index",
            availability = AssetAvailabilityView(
                state = "BLOCKED",
                assetVersion = 3,
                evaluatedAt = "2026-11-23T00:00:00Z",
                reasons = emptyList(),
                calibrationStatus = "DUE",
                calibrationDueAt = null,
                certificateOnFile = false,
                maintenanceStatus = "NONE",
                maintenanceDueAt = null,
            ),
        )
    }

    private fun subject() = CurrentExecutiveSources(inbox, projects, health, warehouses, assets)

    @Test
    fun hardWindowStopsAdditionalWarehouseQueriesAndDeclaresPartial() {
        val w1 = warehouse("A")
        val w2 = warehouse("B")
        Mockito.`when`(warehouses.warehouses(actor, organization)).thenReturn(listOf(w2, w1))
        Mockito.`when`(assets.queue(actor, w1.warehouseId, null)).thenReturn(queue(31))

        val response = subject().assetExceptions(actor, organization)

        assertEquals("PARTIAL", response.status)
        assertTrue(response.partial)
        assertEquals(30, response.items.size)
        assertEquals(w1.warehouseId.toString(), response.items.first().warehouseId)
        verify(assets, times(1)).queue(actor, w1.warehouseId, null)
        verify(assets, never()).queue(actor, w2.warehouseId, null)
    }

    @Test
    fun sourceQueueExactlyAtPageLimitNeverClaimsCompleteness() {
        val w = warehouse("A")
        Mockito.`when`(warehouses.warehouses(actor, organization)).thenReturn(listOf(w))
        Mockito.`when`(assets.queue(actor, w.warehouseId, null)).thenReturn(queue(100))

        val response = subject().assetExceptions(actor, organization)

        assertEquals(30, response.items.size)
        assertEquals("PARTIAL", response.status)
        assertTrue(response.partial)
    }

    @Test
    fun deniedWarehouseQueueIsPartialNotZeroOrCurrent() {
        val w = warehouse("A")
        Mockito.`when`(warehouses.warehouses(actor, organization)).thenReturn(listOf(w))
        Mockito.`when`(assets.queue(actor, w.warehouseId, null)).thenThrow(
            ProductApiException(
                code = "ASSET_UNAVAILABLE",
                message = "Queue unavailable.",
                status = HttpStatus.NOT_FOUND,
            ),
        )

        val response = subject().assetExceptions(actor, organization)

        assertEquals("PARTIAL", response.status)
        assertTrue(response.items.isEmpty())
        assertTrue(response.partial)
        assertEquals("SOME_WAREHOUSE_QUEUES_UNAVAILABLE", response.unavailableReason)
    }

    @Test
    fun filtersMismatchedOrganizationBeforeAnyAssetRead() {
        val foreign = warehouse("X", UUID.randomUUID())
        Mockito.`when`(warehouses.warehouses(actor, organization)).thenReturn(listOf(foreign))

        val response = subject().assetExceptions(actor, organization)

        assertEquals("CURRENT", response.status)
        assertTrue(response.items.isEmpty())
        verify(assets, never()).queue(actor, foreign.warehouseId, null)
    }
}
