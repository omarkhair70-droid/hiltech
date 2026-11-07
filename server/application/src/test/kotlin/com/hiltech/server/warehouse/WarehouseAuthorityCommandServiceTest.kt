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

class WarehouseAuthorityCommandServiceTest {
    private val persistence = Mockito.mock(WarehousePersistencePort::class.java)
    private val authorization = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val sourceAuthority = Mockito.mock(RoleTeamSourceAuthorityPort::class.java)
    private val projection = Mockito.mock(WarehouseAuthorizationProjectionBridge::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val executor = RecordingExecutor()
    private val clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC)
    private val service = WarehouseAuthorityCommandService(
        persistence, authorization, sourceAuthority, executor, projection, audit, clock,
    )
    private val actor = UUID.randomUUID()
    private val organizationId = UUID.randomUUID()
    private val warehouseId = UUID.randomUUID()
    private val principalId = UUID.randomUUID()

    @Test
    fun deniesSensitiveCapabilitiesEvenForCurrentWarehouseManager() {
        authorizeManager()
        val error = assertThrows<ProductApiException> {
            service.grant(grant(WarehouseAuthorityKey.INVENTORY_VALUE_VIEWER))
        }
        assertEquals("WAREHOUSE_PRIVILEGED_AUTHORITY_REQUIRES_POLICY", error.code)
        assertEquals(0, executor.calls)
        assertTrue(
            Mockito.mockingDetails(persistence).invocations
                .none { it.method.name == "insertWarehouseAuthorityBinding" },
        )
    }

    @Test
    fun staleOrganizationMemberCannotReceiveDelegatedAuthority() {
        authorizeManager()
        val error = assertThrows<ProductApiException> {
            service.grant(grant(WarehouseAuthorityKey.WAREHOUSE_VIEWER))
        }
        assertEquals("WAREHOUSE_PRINCIPAL_NOT_CURRENT", error.code)
        assertEquals(0, executor.calls)
    }

    @Test
    fun operatorGrantProjectsAndAuditsExactlyOnce() {
        authorizeManager()
        Mockito.`when`(
            sourceAuthority.isOrganizationMemberCurrent(principalId, organizationId, clock.instant()),
        ).thenReturn(true)
        val request = grant(WarehouseAuthorityKey.WAREHOUSE_OPERATOR)
        val bindingId = UUID.nameUUIDFromBytes(
            ("hiltech:warehouse-authority:" + request.operationId)
                .toByteArray(StandardCharsets.UTF_8),
        )
        val binding = snapshot(bindingId, WarehouseAuthorityKey.WAREHOUSE_OPERATOR)
        Mockito.`when`(
            persistence.warehouseAuthorityBinding(bindingId, clock.instant()),
        ).thenReturn(binding)

        val result = service.grant(request)
        assertEquals(binding, result.binding)
        assertFalse(result.replayed)
        assertEquals(1, executor.calls)
        Mockito.verify(persistence).insertWarehouseAuthorityBinding(
            bindingId, organizationId, warehouseId,
            WarehouseAuthorityKey.WAREHOUSE_OPERATOR,
            InventoryPrincipalType.USER, principalId,
            request.effectiveFrom, actor, clock.instant(),
        )
        assertEquals(
            1,
            Mockito.mockingDetails(projection).invocations
                .count { it.method.name == "warehouseAuthority" },
        )
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
    }

    @Test
    fun endingBindingChecksExpectedVersionAndProjectsRemoval() {
        authorizeManager()
        val bindingId = UUID.randomUUID()
        val active = snapshot(bindingId, WarehouseAuthorityKey.WAREHOUSE_VIEWER)
        val ended = active.copy(current = false, version = 2, effectiveTo = clock.instant())
        Mockito.`when`(
            persistence.warehouseAuthorityBinding(bindingId, clock.instant()),
        ).thenReturn(active, active, ended)
        Mockito.`when`(
            persistence.endWarehouseAuthorityBinding(bindingId, warehouseId, 1, clock.instant()),
        ).thenReturn(true)
        val request = end(bindingId, version = 1)

        val result = service.end(request)
        assertFalse(result.binding.current)
        assertEquals(2L, result.binding.version)
        Mockito.verify(persistence).endWarehouseAuthorityBinding(
            bindingId, warehouseId, 1, clock.instant(),
        )
        assertEquals(1, Mockito.mockingDetails(audit).invocations.size)
        assertEquals(1, executor.calls)
    }

    @Test
    fun obsoleteVersionCannotRevokeCurrentBinding() {
        authorizeManager()
        val bindingId = UUID.randomUUID()
        val active = snapshot(bindingId, WarehouseAuthorityKey.WAREHOUSE_OPERATOR)
            .copy(version = 3)
        Mockito.`when`(
            persistence.warehouseAuthorityBinding(bindingId, clock.instant()),
        ).thenReturn(active)
        val error = assertThrows<ProductApiException> {
            service.end(end(bindingId, version = 1))
        }
        assertEquals("WAREHOUSE_AUTHORITY_VERSION_CONFLICT", error.code)
        assertTrue(
            Mockito.mockingDetails(persistence).invocations
                .none { it.method.name == "endWarehouseAuthorityBinding" },
        )
    }

    private fun authorizeManager() {
        val w = warehouse()
        Mockito.`when`(persistence.warehouse(warehouseId)).thenReturn(w)
        Mockito.`when`(authorization.canManageWarehouse(actor, w)).thenReturn(true)
    }

    private fun warehouse() = WarehouseSnapshot(
        warehouseId = warehouseId,
        organizationId = organizationId,
        code = "WH-1",
        name = "Warehouse",
        facilityRef = null,
        active = true,
        version = 1,
    )

    private fun grant(key: WarehouseAuthorityKey) = GrantWarehouseAuthorityCommand(
        operationId = UUID.randomUUID(),
        warehouseId = warehouseId,
        authorityKey = key,
        principalType = InventoryPrincipalType.USER,
        principalId = principalId,
        effectiveFrom = clock.instant(),
        actorUserId = actor,
        clientOccurredAt = clock.instant(),
        correlationId = "warehouse-authority-test",
    )

    private fun end(bindingId: UUID, version: Long) = EndWarehouseAuthorityCommand(
        operationId = UUID.randomUUID(),
        warehouseId = warehouseId,
        bindingId = bindingId,
        baseVersion = version,
        actorUserId = actor,
        clientOccurredAt = clock.instant(),
        correlationId = "warehouse-authority-test",
    )

    private fun snapshot(bindingId: UUID, key: WarehouseAuthorityKey) =
        WarehouseAuthorityBindingSnapshot(
            bindingId = bindingId,
            organizationId = organizationId,
            warehouseId = warehouseId,
            authorityKey = key,
            principalType = InventoryPrincipalType.USER,
            principalId = principalId,
            effectiveFrom = clock.instant(),
            effectiveTo = null,
            current = true,
            version = 1,
        )

    private class RecordingExecutor : IdempotentCommandExecutor {
        var calls = 0
        override fun execute(
            spec: IdempotentCommandSpec,
            action: () -> IdempotentCommandOutcome,
        ): IdempotentCommandExecution {
            calls += 1
            return IdempotentCommandExecution(action(), replayed = false)
        }
    }
}
