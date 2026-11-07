package com.hiltech.server.warehouse

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
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Fast authorization and integrity tests. Real Postgres/OpenFGA proof is separate. */
class WarehouseStorageLocationCreateServiceTest {
    private val persistence = Mockito.mock(WarehousePersistencePort::class.java)
    private val authorization = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val projection = Mockito.mock(WarehouseAuthorizationProjectionBridge::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val warehouse = warehouse(organization)
    private val clock = Clock.systemUTC()
    private val executingIdempotency = object : IdempotentCommandExecutor {
        override fun execute(
            spec: IdempotentCommandSpec,
            action: () -> IdempotentCommandOutcome,
        ): IdempotentCommandExecution =
            IdempotentCommandExecution(outcome = action(), replayed = false)
    }
    private val service = WarehouseStorageLocationCreateService(
        persistence, authorization, executingIdempotency, projection, audit, clock,
    )

    @Test
    fun deniedOrganizationManagerCannotReadOrWriteContext() {
        val failure = assertThrows<ProductApiException> {
            service.create(command(warehouseId = warehouse.warehouseId))
        }
        assertEquals("WAREHOUSE_MANAGEMENT_FORBIDDEN", failure.code)
        Mockito.verifyNoInteractions(persistence, projection, audit)
    }

    @Test
    fun managerOfAnotherWarehouseCannotCreateStorageUnderUnmanagedWarehouse() {
        authorizeOrganizationManager()
        Mockito.`when`(persistence.warehouse(warehouse.warehouseId)).thenReturn(warehouse)
        // Even valid organization authority does NOT confer authority in every Warehouse.
        val failure = assertThrows<ProductApiException> {
            service.create(command(warehouseId = warehouse.warehouseId))
        }
        assertEquals("WAREHOUSE_CONTEXT_UNAVAILABLE", failure.code)
        Mockito.verify(persistence, Mockito.never()).insertStorageLocation(
            Mockito.any(UUID::class.java), Mockito.any(CreateStorageLocationCommand::class.java),
        )
    }

    @Test
    fun siteStorageRequiresBothProjectAndSite() {
        authorizeOrganizationManager()
        val failure = assertThrows<ProductApiException> {
            service.create(
                command(
                    warehouseId = null,
                    kind = StorageLocationKind.SITE_STORAGE,
                    projectId = null,
                    siteId = UUID.randomUUID(),
                    temporary = true,
                ),
            )
        }
        assertEquals("INVALID_STORAGE_LOCATION_CONTEXT", failure.code)
        Mockito.verifyNoInteractions(persistence, projection, audit)
    }

    @Test
    fun warehouseStorageCannotBeMarkedTemporary() {
        authorizeOrganizationManager()
        val failure = assertThrows<ProductApiException> {
            service.create(command(warehouseId = warehouse.warehouseId, temporary = true))
        }
        assertEquals("INVALID_STORAGE_LOCATION_CONTEXT", failure.code)
        Mockito.verifyNoInteractions(persistence, projection, audit)
    }

    @Test
    fun crossOrganizationParentIsRejectedBeforeWrite() {
        authorizeOrganizationManager()
        val parentId = UUID.randomUUID()
        val wrongOrganizationParent = storage(
            locationId = parentId,
            organizationId = UUID.randomUUID(),
            warehouseId = null,
        )
        Mockito.`when`(persistence.storageLocation(parentId)).thenReturn(wrongOrganizationParent)
        val failure = assertThrows<ProductApiException> {
            service.create(
                command(
                    warehouseId = null,
                    kind = StorageLocationKind.PROJECT_STORAGE,
                    projectId = UUID.randomUUID(),
                    temporary = true,
                    parentId = parentId,
                ),
            )
        }
        assertEquals("STORAGE_PARENT_UNAVAILABLE", failure.code)
        Mockito.verify(persistence, Mockito.never()).insertStorageLocation(
            Mockito.any(UUID::class.java), Mockito.any(CreateStorageLocationCommand::class.java),
        )
    }

    @Test
    fun retiredParentIsRejectedBeforeWrite() {
        authorizeOrganizationManager()
        val parentId = UUID.randomUUID()
        val retiredParent = storage(
            locationId = parentId, organizationId = organization, warehouseId = null,
            lifecycle = StorageLocationLifecycle.RETIRED,
        )
        Mockito.`when`(persistence.storageLocation(parentId)).thenReturn(retiredParent)
        val failure = assertThrows<ProductApiException> {
            service.create(
                command(
                    warehouseId = null,
                    kind = StorageLocationKind.PROJECT_STORAGE,
                    projectId = UUID.randomUUID(),
                    temporary = true,
                    parentId = parentId,
                ),
            )
        }
        assertEquals("STORAGE_PARENT_UNAVAILABLE", failure.code)
    }

    @Test
    fun authorizedWarehouseLocationUsesSameTransactionalProjectionAndAudit() {
        authorizeOrganizationManager()
        Mockito.`when`(persistence.warehouse(warehouse.warehouseId)).thenReturn(warehouse)
        Mockito.`when`(authorization.canManageWarehouse(actor, warehouse)).thenReturn(true)
        val request = command(warehouseId = warehouse.warehouseId, code = " loc-001 ")
        val locationId = UUID.nameUUIDFromBytes(
            ("hiltech:storage-location:" + request.operationId)
                .toByteArray(StandardCharsets.UTF_8),
        )
        val saved = storage(
            locationId = locationId, organizationId = organization,
            warehouseId = warehouse.warehouseId, code = "LOC-001",
        )
        Mockito.`when`(persistence.storageLocation(locationId)).thenReturn(saved)

        val result = service.create(request)
        assertEquals(saved, result.storageLocation)
        assertFalse(result.replayed)
        Mockito.verify(persistence).insertStorageLocation(
            locationId, request.copy(code = "LOC-001"),
        )
        Mockito.verify(projection).storageCreated(saved, saved.version)
        Mockito.verify(audit).append(Mockito.any())
    }

    private fun authorizeOrganizationManager() {
        Mockito.`when`(
            authorization.canManageInventoryOrganization(actor, organization),
        ).thenReturn(true)
    }

    private fun command(
        warehouseId: UUID?,
        code: String = "LOC-001",
        kind: StorageLocationKind =
            if (warehouseId == null) StorageLocationKind.PROJECT_STORAGE else StorageLocationKind.WAREHOUSE,
        projectId: UUID? = null,
        siteId: UUID? = null,
        temporary: Boolean = false,
        parentId: UUID? = null,
    ) = CreateStorageLocationCommand(
        operationId = UUID.randomUUID(),
        warehouseId = warehouseId,
        organizationId = organization,
        code = code,
        name = "Main storage",
        kind = kind,
        parentStorageLocationId = parentId,
        projectId = projectId,
        siteId = siteId,
        temporary = temporary,
        activeFrom = null,
        restrictedAccess = false,
        addressOrLocationRef = null,
        notes = null,
        actorUserId = actor,
        clientOccurredAt = Instant.parse("2026-11-07T12:00:00Z"),
        correlationId = "phase5-storage-test",
    )

    private fun warehouse(org: UUID) = WarehouseSnapshot(
        warehouseId = UUID.randomUUID(),
        organizationId = org,
        code = "WH-1",
        name = "Warehouse",
        facilityRef = null,
        active = true,
        version = 1,
    )

    private fun storage(
        locationId: UUID,
        organizationId: UUID,
        warehouseId: UUID?,
        lifecycle: StorageLocationLifecycle = StorageLocationLifecycle.ACTIVE,
        code: String = "LOC-001",
    ) = StorageLocationSnapshot(
        storageLocationId = locationId,
        organizationId = organizationId,
        code = code,
        name = "Main storage",
        kind = if (warehouseId != null) StorageLocationKind.WAREHOUSE
            else StorageLocationKind.PROJECT_STORAGE,
        parentStorageLocationId = null,
        warehouseId = warehouseId,
        projectId = null,
        siteId = null,
        temporary = false,
        lifecycleState = lifecycle,
        activeFrom = null,
        activeUntil = null,
        restrictedAccess = false,
        addressOrLocationRef = null,
        notes = null,
        version = 1,
    )
}
