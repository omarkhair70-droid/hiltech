package com.hiltech.server.administration

import com.hiltech.server.people.EmployeeAggregateSnapshot
import com.hiltech.server.people.EmployeeState
import com.hiltech.server.people.PeopleService
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import com.hiltech.server.warehouse.InventoryPrincipalType
import com.hiltech.server.warehouse.WarehouseAuthorityBindingSnapshot
import com.hiltech.server.warehouse.WarehouseAuthorityCommandService
import com.hiltech.server.warehouse.WarehouseAuthorityKey
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import com.hiltech.server.warehouse.WarehouseReadService
import com.hiltech.server.warehouse.WarehouseSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class EmployeeWarehouseResponsibilitiesServiceTest {
    private val people = Mockito.mock(PeopleService::class.java)
    private val reads = Mockito.mock(WarehouseReadService::class.java)
    private val authorization = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val commands = Mockito.mock(WarehouseAuthorityCommandService::class.java)
    private val source = Mockito.mock(RoleTeamSourceAuthorityPort::class.java)
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val service = EmployeeWarehouseResponsibilitiesService(
        people, reads, authorization, commands, source, Clock.fixed(at, ZoneOffset.UTC),
    )
    private val actor = UUID.randomUUID()
    private val employeeId = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val identity = UUID.randomUUID()
    private val warehouseId = UUID.randomUUID()

    @Test
    fun warehouseOperatorWithoutPeopleAdministrationCannotReadEmployeeAccountOrBindings() {
        Mockito.`when`(people.detail(actor, employeeId)).thenThrow(ProductApiException(
            "PEOPLE_ACCESS_DENIED", "People administration required", HttpStatus.FORBIDDEN,
        ))
        val failure = assertThrows<ProductApiException> { service.current(actor, employeeId) }
        assertEquals("PEOPLE_ACCESS_DENIED", failure.code)
        Mockito.verifyNoInteractions(reads, authorization, commands, source)
    }

    @Test
    fun peopleAdministratorDoesNotInheritWarehouseManagement() {
        currentAccount()
        val warehouse = warehouse()
        Mockito.`when`(reads.warehouses(actor, organization)).thenReturn(listOf(warehouse))
        val result = service.current(actor, employeeId)
        assertEquals("MANAGED_WAREHOUSES_ONLY", result.scope)
        assertEquals("CURRENT", result.accountStatus)
        assertTrue(result.warehouses.isEmpty())
        Mockito.verifyNoInteractions(commands)
    }

    @Test
    fun noCurrentLinkedAccountDoesNotMeanAccountNeverExisted() {
        Mockito.`when`(people.detail(actor, employeeId)).thenReturn(employee().copy(linkedIdentityId = null))
        val result = service.current(actor, employeeId)
        assertEquals("NO_CURRENT_LINKED_ACCOUNT", result.accountStatus)
        assertEquals(null, result.linkedIdentityId)
        assertTrue(result.warehouses.isEmpty())
        Mockito.verifyNoInteractions(reads, authorization, commands, source)
    }

    @Test
    fun revokedRecipientMembershipBetweenEmployeeReadAndPreviewPreventsAssignment() {
        Mockito.`when`(people.detail(actor, employeeId)).thenReturn(employee())
        // A previously linked identity is insufficient after source membership ends.
        Mockito.`when`(source.isOrganizationMemberCurrent(identity, organization, at)).thenReturn(false)
        val result = service.current(actor, employeeId)
        assertEquals("NO_CURRENT_LINKED_ACCOUNT", result.accountStatus)
        assertTrue(result.warehouses.isEmpty())
        Mockito.verifyNoInteractions(reads, authorization, commands)
    }

    @Test
    fun onlyExactActiveManagedWarehouseAndEmployeeBindingsAreReturned() {
        currentAccount()
        val managed = warehouse()
        val foreign = warehouse().copy(warehouseId = UUID.randomUUID(), organizationId = UUID.randomUUID())
        val inactive = warehouse().copy(warehouseId = UUID.randomUUID(), active = false)
        val viewerOnly = warehouse().copy(warehouseId = UUID.randomUUID())
        Mockito.`when`(reads.warehouses(actor, organization)).thenReturn(listOf(foreign, inactive, viewerOnly, managed))
        Mockito.`when`(authorization.canManageWarehouse(actor, managed)).thenReturn(true)
        val visible = binding(WarehouseAuthorityKey.WAREHOUSE_VIEWER)
        val wrongEmployee = visible.copy(bindingId = UUID.randomUUID(), principalId = UUID.randomUUID())
        val ended = visible.copy(bindingId = UUID.randomUUID(), current = false)
        val wrongWarehouse = visible.copy(bindingId = UUID.randomUUID(), warehouseId = UUID.randomUUID())
        val wrongOrganization = visible.copy(bindingId = UUID.randomUUID(), organizationId = UUID.randomUUID())
        Mockito.`when`(commands.currentBindings(actor, warehouseId)).thenReturn(
            listOf(visible, wrongEmployee, ended, wrongWarehouse, wrongOrganization),
        )
        val result = service.current(actor, employeeId)
        assertEquals(listOf(warehouseId.toString()), result.warehouses.map { it.warehouseId })
        assertEquals(listOf(visible.bindingId.toString()), result.warehouses.single().bindings.map { it.bindingId })
        Mockito.verify(authorization, Mockito.never()).canManageWarehouse(actor, foreign)
        Mockito.verify(authorization, Mockito.never()).canManageWarehouse(actor, inactive)
        Mockito.verify(commands, Mockito.never()).currentBindings(actor, viewerOnly.warehouseId)
    }

    @Test
    fun directDelegatedAccessCanEndButTeamAndManagerAccessCannot() {
        currentAccount()
        val managed = warehouse()
        Mockito.`when`(reads.warehouses(actor, organization)).thenReturn(listOf(managed))
        Mockito.`when`(authorization.canManageWarehouse(actor, managed)).thenReturn(true)
        val team = UUID.randomUUID()
        val formerTeam = UUID.randomUUID()
        Mockito.`when`(source.isTeamMemberCurrent(identity, team, at)).thenReturn(true)
        val direct = binding(WarehouseAuthorityKey.WAREHOUSE_OPERATOR)
        val manager = binding(WarehouseAuthorityKey.WAREHOUSE_MANAGER)
        val inherited = binding(WarehouseAuthorityKey.WAREHOUSE_VIEWER).copy(
            principalType = InventoryPrincipalType.TEAM, principalId = team,
        )
        val expiredTeam = inherited.copy(bindingId = UUID.randomUUID(), principalId = formerTeam)
        Mockito.`when`(commands.currentBindings(actor, warehouseId)).thenReturn(listOf(direct, manager, inherited, expiredTeam))
        val result = service.current(actor, employeeId).warehouses.single()
        assertEquals(setOf("WAREHOUSE_VIEWER", "WAREHOUSE_OPERATOR"), result.grantableAuthorityKeys.toSet())
        assertEquals(3, result.bindings.size)
        assertTrue(result.bindings.single { it.bindingId == direct.bindingId.toString() }.canEnd)
        assertFalse(result.bindings.single { it.bindingId == manager.bindingId.toString() }.canEnd)
        val teamView = result.bindings.single { it.bindingId == inherited.bindingId.toString() }
        assertEquals("TEAM", teamView.assignmentSource)
        assertFalse(teamView.canEnd)
    }

    @Test
    fun revokedManagerBetweenDirectoryAndBindingReadProducesNoStalePreview() {
        currentAccount()
        val managed = warehouse()
        Mockito.`when`(reads.warehouses(actor, organization)).thenReturn(listOf(managed))
        Mockito.`when`(authorization.canManageWarehouse(actor, managed)).thenReturn(true)
        Mockito.`when`(commands.currentBindings(actor, warehouseId)).thenThrow(ProductApiException(
            "WAREHOUSE_AUTHORITY_UNAVAILABLE", "Authority no longer current", HttpStatus.NOT_FOUND,
        ))
        assertThrows<ProductApiException> { service.current(actor, employeeId) }
    }

    private fun currentAccount() {
        Mockito.`when`(people.detail(actor, employeeId)).thenReturn(employee())
        Mockito.`when`(source.isOrganizationMemberCurrent(identity, organization, at)).thenReturn(true)
    }

    private fun employee() = EmployeeAggregateSnapshot(
        employeeId = employeeId, organizationId = organization, personId = UUID.randomUUID(),
        employeeCode = "SYNTH-EMPLOYEE", employeeState = EmployeeState.ACTIVE,
        hireDate = null, endDate = null, employeeVersion = 3, displayName = "Synthetic employee",
        legalName = null, mobile = null, email = null, personVersion = 1,
        activeEmploymentId = null, employmentTypeCode = null, employmentStartDate = null,
        employmentEndDate = null, employmentVersion = null, linkedIdentityId = identity,
        createdAt = at, updatedAt = at,
    )

    private fun warehouse() = WarehouseSnapshot(
        warehouseId, organization, "SYNTH-WH", "Synthetic warehouse", null, true, 1,
    )

    private fun binding(key: WarehouseAuthorityKey) = WarehouseAuthorityBindingSnapshot(
        UUID.randomUUID(), organization, warehouseId, key, InventoryPrincipalType.USER, identity,
        at, null, true, 1,
    )
}
