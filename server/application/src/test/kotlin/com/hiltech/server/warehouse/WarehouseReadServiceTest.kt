package com.hiltech.server.warehouse

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.util.UUID

/**
 * Cheap security boundary tests; no database or OpenFGA instance required.
 * Database/OpenFGA current-source proofs remain mandatory before Slice 01 merge.
 */
class WarehouseReadServiceTest {
    private val persistence = Mockito.mock(WarehousePersistencePort::class.java)
    private val authorization = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val service = WarehouseReadService(persistence, authorization)
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()

    @Test
    fun warehouseDirectoryShowsOnlyAuthorizedSameOrganizationRows() {
        val visible = warehouse(organization = organization)
        val hidden = warehouse(organization = organization)
        val otherOrganization = warehouse(organization = UUID.randomUUID())

        Mockito.`when`(persistence.warehouses(organization))
            .thenReturn(listOf(visible, hidden, otherOrganization))
        Mockito.`when`(authorization.canViewWarehouse(actor, visible))
            .thenReturn(true)
        Mockito.`when`(authorization.canViewWarehouse(actor, hidden))
            .thenReturn(false)
        Mockito.`when`(authorization.canViewWarehouse(actor, otherOrganization))
            .thenReturn(true)

        assertEquals(listOf(visible), service.warehouses(actor, organization))
        Mockito.verify(authorization, Mockito.never()).canViewWarehouse(
            actor,
            otherOrganization,
        )
    }

    @Test
    fun warehouseDetailDoesNotDiscloseUnpermittedObject() {
        val hidden = warehouse(organization = organization)
        Mockito.`when`(persistence.warehouse(hidden.warehouseId))
            .thenReturn(hidden)

        val error = assertThrows<ProductApiException> {
            service.warehouse(actor, hidden.warehouseId)
        }
        assertEquals("WAREHOUSE_NOT_FOUND", error.code)
        assertEquals(404, error.status.value())
    }

    @Test
    fun storageListRejectsWrongOrganizationAndWrongWarehouseBeforeAuthorization() {
        val wantedWarehouse = UUID.randomUUID()
        val visible = storage(organization, wantedWarehouse)
        val wrongWarehouse = storage(organization, UUID.randomUUID())
        val wrongOrganization = storage(UUID.randomUUID(), wantedWarehouse)

        Mockito.`when`(persistence.storageLocations(organization, wantedWarehouse))
            .thenReturn(listOf(visible, wrongWarehouse, wrongOrganization))
        Mockito.`when`(authorization.canViewStorage(actor, visible))
            .thenReturn(true)
        Mockito.`when`(authorization.canViewStorage(actor, wrongWarehouse))
            .thenReturn(true)
        Mockito.`when`(authorization.canViewStorage(actor, wrongOrganization))
            .thenReturn(true)

        assertEquals(
            listOf(visible),
            service.storageLocations(actor, organization, wantedWarehouse),
        )
        Mockito.verify(authorization, Mockito.never()).canViewStorage(actor, wrongWarehouse)
        Mockito.verify(authorization, Mockito.never()).canViewStorage(actor, wrongOrganization)
    }

    @Test
    fun storageDetailDoesNotDiscloseUnpermittedLocation() {
        val hidden = storage(organization, warehouseId = null)
        Mockito.`when`(persistence.storageLocation(hidden.storageLocationId))
            .thenReturn(hidden)

        val error = assertThrows<ProductApiException> {
            service.storageLocation(actor, hidden.storageLocationId)
        }
        assertEquals("STORAGE_LOCATION_NOT_FOUND", error.code)
        assertEquals(404, error.status.value())
    }

    @Test
    fun warehouseDirectoryIsEmptyWhenNoRowsAreVisible() {
        val hidden = warehouse(organization)
        Mockito.`when`(persistence.warehouses(organization))
            .thenReturn(listOf(hidden))
        assertTrue(service.warehouses(actor, organization).isEmpty())
        assertFalse(service.warehouses(actor, organization).isNotEmpty())
    }

    private fun warehouse(organization: UUID) = WarehouseSnapshot(
        warehouseId = UUID.randomUUID(),
        organizationId = organization,
        code = "WH-1",
        name = "Warehouse",
        facilityRef = null,
        active = true,
        version = 1,
    )

    private fun storage(organization: UUID, warehouseId: UUID?) = StorageLocationSnapshot(
        storageLocationId = UUID.randomUUID(),
        organizationId = organization,
        code = "LOC-1",
        name = "Location",
        kind = if (warehouseId == null) StorageLocationKind.PROJECT_STORAGE
            else StorageLocationKind.WAREHOUSE,
        parentStorageLocationId = null,
        warehouseId = warehouseId,
        projectId = null,
        siteId = null,
        temporary = false,
        lifecycleState = StorageLocationLifecycle.ACTIVE,
        activeFrom = null,
        activeUntil = null,
        restrictedAccess = false,
        addressOrLocationRef = null,
        notes = null,
        version = 1,
    )
}
