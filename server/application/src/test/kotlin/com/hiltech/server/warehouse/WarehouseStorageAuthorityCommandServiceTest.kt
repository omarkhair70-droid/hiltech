package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class WarehouseStorageAuthorityCommandServiceTest {
    private val persistence = Mockito.mock(WarehousePersistencePort::class.java)
    private val authorization = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val sourceAuthority = Mockito.mock(RoleTeamSourceAuthorityPort::class.java)
    private val projection = Mockito.mock(WarehouseAuthorizationProjectionBridge::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val executor = RecordingExecutor()
    private val clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC)
    private val service = WarehouseStorageAuthorityCommandService(
        persistence, authorization, sourceAuthority, executor, projection, audit, clock,
    )
    private val actor = UUID.randomUUID()
    private val organizationId = UUID.randomUUID()
    private val warehouseId = UUID.randomUUID()
    private val storageId = UUID.randomUUID()
    private val principalId = UUID.randomUUID()

    @Test
    fun differentWarehouseManagerCannotGrantLocationAuthority() {
        val location = storage(warehouseId)
        val warehouse = warehouse()
        Mockito.`when`(persistence.storageLocation(storageId)).thenReturn(location)
        Mockito.`when`(
            authorization.canManageInventoryOrganization(actor, organizationId),
        ).thenReturn(true)
        Mockito.`when`(persistence.warehouse(warehouseId)).thenReturn(warehouse)

        val failure = assertThrows<ProductApiException> {
            service.grant(grant())
        }
        assertEquals("STORAGE_AUTHORITY_UNAVAILABLE", failure.code)
        assertEquals(0, executor.calls)
        assertTrue(
            Mockito.mockingDetails(persistence).invocations
                .none { it.method.name == "insertStorageAuthorityBinding" },
        )
    }

    @Test
    fun inactiveStorageCannotBeGrantedAuthority() {
        Mockito.`when`(persistence.storageLocation(storageId))
            .thenReturn(storage(null).copy(lifecycleState = StorageLocationLifecycle.RETIRED))
        Mockito.`when`(authorization.canManageInventoryOrganization(actor, organizationId))
            .thenReturn(true)
        val failure = assertThrows<ProductApiException> {
            service.grant(grant())
        }
        assertEquals("STORAGE_AUTHORITY_UNAVAILABLE", failure.code)
        assertEquals(0, executor.calls)
    }

    @Test
    fun staleTargetMembershipDeniesGrant() {
        authorizeStorage()
        val failure = assertThrows<ProductApiException> {
            service.grant(grant())
        }
        assertEquals("STORAGE_PRINCIPAL_NOT_CURRENT", failure.code)
        assertEquals(0, executor.calls)
    }

    @Test
    fun futureGrantDoesNotCreateUnprojectedPermission() {
        authorizeStorage()
        val failure = assertThrows<ProductApiException> {
            service.grant(grant().copy(effectiveFrom = clock.instant().plusSeconds(60)))
        }
        assertEquals("STORAGE_FUTURE_GRANT_UNSUPPORTED", failure.code)
        assertEquals(0, executor.calls)
    }

    @Test
    fun projectSiteStorageCanReceiveExplicitOperatorAuthority() {
        authorizeStorage()
        Mockito.`when`(
            sourceAuthority.isOrganizationMemberCurrent(principalId, organizationId, clock.instant()),
        ).thenReturn(true)
        val request = grant()
        val bindingId = UUID.nameUUIDFromBytes(
            ("hiltech:storage-authority:" + request.operationId)
                .toByteArray(StandardCharsets.UTF_8),
        )
        val binding = binding(bindingId)
        Mockito.`when`(
            persistence.storageAuthorityBinding(bindingId, clock.instant()),
        ).thenReturn(binding)
        val outcome = service.grant(request)
        assertEquals(binding, outcome.binding)
        assertFalse(outcome.replayed)
        assertEquals(1, executor.calls)
        Mockito.verify(persistence).insertStorageAuthorityBinding(
            bindingId, organizationId, storageId, StorageLocationAuthorityKey.LOCATION_OPERATOR,
            InventoryPrincipalType.USER, principalId, clock.instant(), actor, clock.instant(),
        )
        assertEquals(
            1, Mockito.mockingDetails(projection).invocations
                .count { it.method.name == "storageAuthority" },
        )
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
    }

    @Test
    fun endingLocationAuthorityRevalidatesVersion() {
        authorizeStorage()
        val bindingId = UUID.randomUUID()
        val active = binding(bindingId)
        Mockito.`when`(
            persistence.storageAuthorityBinding(bindingId, clock.instant()),
        ).thenReturn(active.copy(version = 4))
        val failure = assertThrows<ProductApiException> {
            service.end(end(bindingId, 1))
        }
        assertEquals("STORAGE_AUTHORITY_VERSION_CONFLICT", failure.code)
        assertTrue(
            Mockito.mockingDetails(persistence).invocations
                .none { it.method.name == "endStorageAuthorityBinding" },
        )
    }

    @Test
    fun locationAuthorityEndWritesAuditAndProjection() {
        authorizeStorage()
        val bindingId = UUID.randomUUID()
        val active = binding(bindingId)
        val ended = active.copy(current = false, version = 2, effectiveTo = clock.instant())
        Mockito.`when`(
            persistence.storageAuthorityBinding(bindingId, clock.instant()),
        ).thenReturn(active, active, ended)
        Mockito.`when`(
            persistence.endStorageAuthorityBinding(bindingId, storageId, 1, clock.instant()),
        ).thenReturn(true)

        val outcome = service.end(end(bindingId, 1))
        assertFalse(outcome.binding.current)
        assertEquals(2L, outcome.binding.version)
        assertEquals(1, executor.calls)
        Mockito.verify(persistence).endStorageAuthorityBinding(
            bindingId, storageId, 1, clock.instant(),
        )
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
    }

    private fun authorizeStorage() {
        Mockito.`when`(persistence.storageLocation(storageId)).thenReturn(storage(null))
        Mockito.`when`(
            authorization.canManageInventoryOrganization(actor, organizationId),
        ).thenReturn(true)
    }

    private fun warehouse() = WarehouseSnapshot(
        warehouseId = warehouseId, organizationId = organizationId,
        code = "WH-1", name = "Main Warehouse", facilityRef = null,
        active = true, version = 1,
    )

    private fun storage(warehouse: UUID?) = StorageLocationSnapshot(
        storageLocationId = storageId, organizationId = organizationId,
        code = "ST-1", name = "Project Storage",
        kind = if (warehouse == null) StorageLocationKind.PROJECT_STORAGE else StorageLocationKind.WAREHOUSE,
        parentStorageLocationId = null, warehouseId = warehouse,
        projectId = if (warehouse == null) UUID.randomUUID() else null,
        siteId = null, temporary = warehouse == null,
        lifecycleState = StorageLocationLifecycle.ACTIVE,
        activeFrom = null, activeUntil = null, restrictedAccess = false,
        addressOrLocationRef = null, notes = null, version = 1,
    )

    private fun grant() = GrantStorageAuthorityCommand(
        operationId = UUID.randomUUID(),
        storageLocationId = storageId, authorityKey = StorageLocationAuthorityKey.LOCATION_OPERATOR,
        principalType = InventoryPrincipalType.USER, principalId = principalId,
        effectiveFrom = clock.instant(), actorUserId = actor,
        clientOccurredAt = clock.instant(), correlationId = "storage-authority-test",
    )
    private fun end(bindingId: UUID, version: Long) = EndStorageAuthorityCommand(
        operationId = UUID.randomUUID(), storageLocationId = storageId,
        bindingId = bindingId, baseVersion = version, actorUserId = actor,
        clientOccurredAt = clock.instant(), correlationId = "storage-authority-test",
    )
    private fun binding(id: UUID) = StorageAuthorityBindingSnapshot(
        bindingId = id, organizationId = organizationId, storageLocationId = storageId,
        authorityKey = StorageLocationAuthorityKey.LOCATION_OPERATOR,
        principalType = InventoryPrincipalType.USER, principalId = principalId,
        effectiveFrom = clock.instant(), effectiveTo = null, current = true, version = 1,
    )
    private class RecordingExecutor : IdempotentCommandExecutor {
        var calls = 0
        override fun execute(
            spec: IdempotentCommandSpec,
            action: () -> IdempotentCommandOutcome,
        ): IdempotentCommandExecution {
            calls++
            return IdempotentCommandExecution(action(), replayed = false)
        }
    }
}
