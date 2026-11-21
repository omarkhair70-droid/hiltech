package com.hiltech.server.work

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.people.EmployeeAggregateSnapshot
import com.hiltech.server.people.EmployeeState
import com.hiltech.server.people.PeoplePersistencePort
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FieldMaterialUsageReportServiceTest {
    private val at = Instant.parse("2026-11-21T18:00:00Z")
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val project = UUID.randomUUID()
    private val site = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val assignmentId = UUID.randomUUID()
    private val employeeId = UUID.randomUUID()
    private val itemId = UUID.randomUUID()
    private val requirementId = UUID.randomUUID()
    private val source = Mockito.mock(WorkAssignmentSourceAuthorityPort::class.java)
    private val authorization = Mockito.mock(WorkAssignmentAuthorizationPort::class.java)
    private val work = Mockito.mock(WorkPersistencePort::class.java)
    private val assignments = Mockito.mock(ReadinessAssignmentPersistencePort::class.java)
    private val people = Mockito.mock(PeoplePersistencePort::class.java)
    private val stock = Mockito.mock(FieldPersonalIssuedStockReadPort::class.java)
    private val reports = Mockito.mock(FieldMaterialUsageReportPersistencePort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private var calls = 0
    private val commands = object : IdempotentCommandExecutor {
        override fun execute(
            spec: IdempotentCommandSpec,
            action: () -> IdempotentCommandOutcome,
        ): IdempotentCommandExecution {
            calls++
            return IdempotentCommandExecution(action(), replayed = false)
        }
    }
    private val clock = Clock.fixed(at, ZoneOffset.UTC)
    private val service = newService(true)

    private fun newService(enabled: Boolean) = FieldMaterialUsageReportService(
        source, authorization, work, assignments, people, stock, reports,
        commands, audit, clock, rolloutEnabled = enabled,
    )

    private fun command(quantity: String = "2.500000", version: Long = 4) =
        FieldMaterialUsageReportCommand(
            UUID.randomUUID(), workId, actor, assignmentId, 3, 12,
            requirementId, itemId, version, quantity, "M", at, "corr-field-s04b",
        )

    private fun authorize(
        outstanding: String = "8.000000",
        serialized: Boolean = false,
        lifecycle: WorkOrderLifecycle = WorkOrderLifecycle.IN_PROGRESS,
    ) {
        Mockito.`when`(source.isCurrentAssignedUser(actor, workId, at)).thenReturn(true)
        Mockito.`when`(authorization.canUseAssignedWork(actor, workId, "can_submit_completion"))
            .thenReturn(true)
        Mockito.`when`(work.lockWorkOrder(workId)).thenReturn(true)
        val order = Mockito.mock(WorkOrderSnapshot::class.java)
        Mockito.`when`(order.workOrderId).thenReturn(workId)
        Mockito.`when`(order.organizationId).thenReturn(organization)
        Mockito.`when`(order.projectId).thenReturn(project)
        Mockito.`when`(order.siteId).thenReturn(site)
        Mockito.`when`(order.version).thenReturn(12L)
        Mockito.`when`(order.lifecycleState).thenReturn(lifecycle)
        Mockito.`when`(work.workOrder(workId)).thenReturn(order)
        val assignment = Mockito.mock(WorkAssignmentRecord::class.java)
        Mockito.`when`(assignment.assignmentId).thenReturn(assignmentId)
        Mockito.`when`(assignment.version).thenReturn(3L)
        Mockito.`when`(assignments.activeAssignment(workId)).thenReturn(assignment)
        val employee = Mockito.mock(EmployeeAggregateSnapshot::class.java)
        Mockito.`when`(employee.employeeState).thenReturn(EmployeeState.ACTIVE)
        Mockito.`when`(employee.organizationId).thenReturn(organization)
        Mockito.`when`(employee.employeeId).thenReturn(employeeId)
        Mockito.`when`(people.loadOwnEmployee(actor, organization, at)).thenReturn(employee)
        Mockito.`when`(stock.ownForWorkOrder(organization, project, site, workId, employeeId))
            .thenReturn(listOf(FieldPersonalIssuedStockRow(
                organization, project, site, workId, employeeId, requirementId, itemId,
                "CAT6", "Cable", "M", outstanding, 4, at, serialized,
            )))
        Mockito.`when`(reports.totalPending(workId, employeeId, requirementId, itemId))
            .thenReturn(BigDecimal.ZERO)
    }

    @Test
    fun `default OFF rejects even assigned actor without accessing authority or stock`() {
        val error = assertThrows<ProductApiException> { newService(false).report(command()) }
        assertEquals("FIELD_MATERIAL_USAGE_REPORT_UNAVAILABLE", error.code)
        Mockito.verifyNoInteractions(source, authorization, work, assignments, people, stock, reports, audit)
        assertEquals(0, calls)
    }

    @Test
    fun `current authorized field employee can only create a pending report never move stock`() {
        authorize()
        val result = service.report(command())
        assertEquals("PENDING_REVIEW", result.status)
        assertFalse(result.stockMoved)
        assertFalse(result.replayed)
        assertEquals(1, calls)
        val captured = ArgumentCaptor.forClass(FieldMaterialUsageReportRecord::class.java)
        Mockito.verify(reports).insertPending(captured.capture())
        assertEquals(employeeId, captured.value.employeeId)
        assertEquals(workId, captured.value.workOrderId)
        assertEquals(itemId, captured.value.stockItemId)
        assertEquals(requirementId, captured.value.requirementId)
        assertEquals(0, captured.value.quantity.compareTo(BigDecimal("2.5")))
        Mockito.verify(audit).append(Mockito.any())
    }

    @Test
    fun `revoked assignment cannot create or retrieve a report`() {
        assertEquals("OBJECT_NOT_VISIBLE",
            assertThrows<ProductApiException> { service.report(command()) }.code)
        assertEquals(0, calls)
        Mockito.verifyNoInteractions(work, people, stock, reports)
    }

    @Test
    fun `read-only Work can view is not authority to report material usage`() {
        Mockito.`when`(source.isCurrentAssignedUser(actor, workId, at)).thenReturn(true)
        assertEquals("OBJECT_NOT_VISIBLE",
            assertThrows<ProductApiException> { service.report(command()) }.code)
        Mockito.verifyNoInteractions(work, people, stock, reports)
    }

    @Test
    fun `stale issued version is rejected without writes`() {
        authorize()
        assertEquals("FIELD_ISSUED_STOCK_VERSION_CONFLICT",
            assertThrows<ProductApiException> { service.report(command(version = 3)) }.code)
        Mockito.verify(reports, Mockito.never()).insertPending(Mockito.any())
    }

    @Test
    fun `outstanding minus other pending reports limits concurrent proposals`() {
        authorize(outstanding = "8.000000")
        Mockito.`when`(reports.totalPending(workId, employeeId, requirementId, itemId))
            .thenReturn(BigDecimal("7.25"))
        assertEquals("FIELD_MATERIAL_USAGE_EXCEEDS_OUTSTANDING",
            assertThrows<ProductApiException> { service.report(command(quantity = "1.000000")) }.code)
        Mockito.verify(reports, Mockito.never()).insertPending(Mockito.any())
    }

    @Test
    fun `cannot propose fractional serial-stock consumption`() {
        authorize(serialized = true)
        assertEquals("FIELD_MATERIAL_USAGE_INVALID",
            assertThrows<ProductApiException> { service.report(command(quantity = "0.5")) }.code)
        Mockito.verify(reports, Mockito.never()).insertPending(Mockito.any())
    }

    @Test
    fun `invalid decimal unit precision and zero are rejected before source access`() {
        listOf("0", "0.000000", "2.0000001", "-1", "1e5", " 2", "2,5")
            .forEach { quantity ->
                assertEquals("FIELD_MATERIAL_USAGE_INVALID",
                    assertThrows<ProductApiException> { service.report(command(quantity)) }.code)
            }
        Mockito.verifyNoInteractions(source, authorization, work, assignments, people, stock, reports)
        assertEquals(0, calls)
    }

    @Test
    fun `reported use is not allowed for not-started or accepted Work`() {
        authorize(lifecycle = WorkOrderLifecycle.ASSIGNED)
        assertEquals("FIELD_WORK_NOT_ACTIVE",
            assertThrows<ProductApiException> { service.report(command()) }.code)
        Mockito.verify(reports, Mockito.never()).insertPending(Mockito.any())
    }
}
