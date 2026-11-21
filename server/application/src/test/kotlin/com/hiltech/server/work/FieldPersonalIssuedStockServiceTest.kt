package com.hiltech.server.work

import com.hiltech.server.people.EmployeeAggregateSnapshot
import com.hiltech.server.people.EmployeeState
import com.hiltech.server.people.PeoplePersistencePort
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FieldPersonalIssuedStockServiceTest {
    private val at = Instant.parse("2026-11-21T18:00:00Z")
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val project = UUID.randomUUID()
    private val site = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val employeeId = UUID.randomUUID()
    private val assignments = Mockito.mock(WorkAssignmentSourceAuthorityPort::class.java)
    private val authorization = Mockito.mock(WorkAssignmentAuthorizationPort::class.java)
    private val work = Mockito.mock(WorkPersistencePort::class.java)
    private val people = Mockito.mock(PeoplePersistencePort::class.java)
    private val stock = Mockito.mock(FieldPersonalIssuedStockReadPort::class.java)
    private val service = FieldPersonalIssuedStockService(
        assignments, authorization, work, people, stock, Clock.fixed(at, ZoneOffset.UTC),
    )

    private fun authorize() {
        Mockito.`when`(assignments.isCurrentAssignedUser(actor, workId, at)).thenReturn(true)
        Mockito.`when`(authorization.canUseAssignedWork(actor, workId, "can_view")).thenReturn(true)
        val order = Mockito.mock(WorkOrderSnapshot::class.java)
        Mockito.`when`(order.organizationId).thenReturn(organization)
        Mockito.`when`(order.projectId).thenReturn(project)
        Mockito.`when`(order.siteId).thenReturn(site)
        Mockito.`when`(order.workOrderId).thenReturn(workId)
        Mockito.`when`(order.version).thenReturn(12L)
        Mockito.`when`(work.workOrder(workId)).thenReturn(order)
    }

    private fun ownEmployee(state: EmployeeState = EmployeeState.ACTIVE, org: UUID = organization) {
        val employee = Mockito.mock(EmployeeAggregateSnapshot::class.java)
        Mockito.`when`(employee.organizationId).thenReturn(org)
        Mockito.`when`(employee.employeeState).thenReturn(state)
        Mockito.`when`(employee.employeeId).thenReturn(employeeId)
        Mockito.`when`(people.loadOwnEmployee(actor, organization, at)).thenReturn(employee)
    }

    private fun row(
        owningEmployee: UUID = employeeId,
        org: UUID = organization,
    ) = FieldPersonalIssuedStockRow(
        organizationId = org,
        projectId = project,
        siteId = site,
        workOrderId = workId,
        employeeId = owningEmployee,
        requirementId = UUID.randomUUID(),
        stockItemId = UUID.randomUUID(),
        stockItemCode = "CAT6-001",
        stockItemName = "UTP Cable",
        unitCode = "M",
        outstandingQuantity = "30.250000",
        sourceVersion = 4,
        updatedAt = at,
    )

    @Test
    fun `denied current assignment hides work and never queries stock`() {
        val error = assertThrows<ProductApiException> { service.ownIssued(actor, workId) }
        assertEquals("OBJECT_NOT_VISIBLE", error.code)
        Mockito.verifyNoInteractions(work, people, stock)
        Mockito.verify(authorization, Mockito.never()).canUseAssignedWork(actor, workId, "can_view")
    }

    @Test
    fun `denied field view permission hides stock`() {
        Mockito.`when`(assignments.isCurrentAssignedUser(actor, workId, at)).thenReturn(true)
        val error = assertThrows<ProductApiException> { service.ownIssued(actor, workId) }
        assertEquals(404, error.status.value())
        Mockito.verifyNoInteractions(work, people, stock)
    }

    @Test
    fun `only own active employee issued row returned without employee identity or warehouse costs`() {
        authorize()
        ownEmployee()
        Mockito.`when`(stock.ownForWorkOrder(organization, project, site, workId, employeeId))
            .thenReturn(listOf(row()))
        val result = service.ownIssued(actor, workId)
        assertEquals("SOURCE_CURRENT", result.status)
        assertEquals(12L, result.workOrderVersion)
        assertEquals("30.250000", result.items.single().outstandingQuantity)
        assertEquals("M", result.items.single().unitCode)
        assertEquals("CAT6-001", result.items.single().stockItemCode)
        assertTrue(result.items.single().toString().contains("UTP Cable"))
    }

    @Test
    fun `no linked employee does not query issued stock`() {
        authorize()
        val result = service.ownIssued(actor, workId)
        assertEquals("EMPLOYEE_CONTEXT_UNAVAILABLE", result.status)
        assertTrue(result.items.isEmpty())
        Mockito.verifyNoInteractions(stock)
    }

    @Test
    fun `inactive employee cannot read even outstanding historical rows`() {
        authorize()
        ownEmployee(state = EmployeeState.FORMER)
        assertEquals("EMPLOYEE_CONTEXT_UNAVAILABLE", service.ownIssued(actor, workId).status)
        Mockito.verifyNoInteractions(stock)
    }

    @Test
    fun `employee mapped to another organization is never used`() {
        authorize()
        ownEmployee(org = UUID.randomUUID())
        assertEquals("EMPLOYEE_CONTEXT_UNAVAILABLE", service.ownIssued(actor, workId).status)
        Mockito.verifyNoInteractions(stock)
    }

    @Test
    fun `repository cannot leak different employee issued rows`() {
        authorize()
        ownEmployee()
        Mockito.`when`(stock.ownForWorkOrder(organization, project, site, workId, employeeId))
            .thenReturn(listOf(row(owningEmployee = UUID.randomUUID())))
        val error = assertThrows<ProductApiException> { service.ownIssued(actor, workId) }
        assertEquals("FIELD_ISSUED_STOCK_SOURCE_MISMATCH", error.code)
    }

    @Test
    fun `repository cannot leak another organization issued rows`() {
        authorize()
        ownEmployee()
        Mockito.`when`(stock.ownForWorkOrder(organization, project, site, workId, employeeId))
            .thenReturn(listOf(row(org = UUID.randomUUID())))
        assertEquals("FIELD_ISSUED_STOCK_SOURCE_MISMATCH",
            assertThrows<ProductApiException> { service.ownIssued(actor, workId) }.code)
    }

    @Test
    fun `empty own issued projection is not a simulated warehouse balance`() {
        authorize()
        ownEmployee()
        Mockito.`when`(stock.ownForWorkOrder(organization, project, site, workId, employeeId))
            .thenReturn(emptyList())
        val result = service.ownIssued(actor, workId)
        assertEquals("NO_ISSUED_CONTEXT", result.status)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `excessive row count fails closed rather than silently trimming`() {
        authorize()
        ownEmployee()
        Mockito.`when`(stock.ownForWorkOrder(organization, project, site, workId, employeeId))
            .thenReturn(List(101) { row() })
        assertEquals("FIELD_ISSUED_STOCK_TOO_MANY_ROWS",
            assertThrows<ProductApiException> { service.ownIssued(actor, workId) }.code)
    }
}
