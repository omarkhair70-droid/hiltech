package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.work.WorkOrderSnapshot
import com.hiltech.server.work.WorkPersistencePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class FieldMaterialUsageReconciliationServiceTest {
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val project = UUID.randomUUID()
    private val site = UUID.randomUUID()
    private val warehouseId = UUID.randomUUID()
    private val locationId = UUID.randomUUID()
    private val reportId = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val employee = UUID.randomUUID()
    private val requirement = UUID.randomUUID()
    private val item = UUID.randomUUID()
    private val now = Instant.parse("2026-11-21T20:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val persistence = Mockito.mock(FieldMaterialUsageReconciliationPersistence::class.java)
    private val warehouses = Mockito.mock(WarehousePersistencePort::class.java)
    private val authorization = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val fulfillment = Mockito.mock(StockFulfillmentPersistence::class.java)
    private val stockService = Mockito.mock(StockFulfillmentService::class.java)
    private val work = Mockito.mock(WorkPersistencePort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val location = Mockito.mock(StorageLocationSnapshot::class.java)
    private val order = Mockito.mock(WorkOrderSnapshot::class.java)
    private val stored = AtomicReference<FieldMaterialUsageReconciliationRecord?>()

    private val source = FieldMaterialUsageReviewSource(
        reportId, org, project, site, workId, employee, requirement, item,
        9, 4, BigDecimal("2.000000"), "M",
    )
    private val facility = WarehouseSnapshot(warehouseId, org, "WH-A", "Warehouse", null, true, 1)

    private class ImmediateCommands : IdempotentCommandExecutor {
        override fun execute(
            spec: IdempotentCommandSpec,
            action: () -> IdempotentCommandOutcome,
        ): IdempotentCommandExecution = IdempotentCommandExecution(action(), replayed = false)
    }

    private fun service(enabled: Boolean = true): FieldMaterialUsageReconciliationService =
        FieldMaterialUsageReconciliationService(
            persistence, warehouses, authorization, fulfillment, stockService,
            work, ImmediateCommands(), audit, clock, enabled,
        )

    private fun allowed() {
        Mockito.`when`(persistence.source(reportId)).thenReturn(source)
        Mockito.`when`(persistence.source(reportId, true)).thenReturn(source)
        Mockito.`when`(persistence.reconciliation(reportId)).thenAnswer { stored.get() }
        Mockito.doAnswer {
            stored.set(it.arguments.single() as FieldMaterialUsageReconciliationRecord)
            null
        }.`when`(persistence).insert(Mockito.any(FieldMaterialUsageReconciliationRecord::class.java))
        Mockito.`when`(warehouses.warehouse(warehouseId)).thenReturn(facility)
        Mockito.`when`(authorization.canReserveResources(actor, facility)).thenReturn(true)
        Mockito.`when`(fulfillment.origins(item, requirement, employee)).thenReturn(listOf(locationId))
        Mockito.`when`(warehouses.storageLocation(locationId)).thenReturn(location)
        Mockito.`when`(location.organizationId).thenReturn(org)
        Mockito.`when`(location.warehouseId).thenReturn(warehouseId)
        Mockito.`when`(authorization.canTransferStock(actor, location)).thenReturn(true)
        Mockito.`when`(order.organizationId).thenReturn(org)
        Mockito.`when`(order.projectId).thenReturn(project)
        Mockito.`when`(order.siteId).thenReturn(site)
        Mockito.`when`(order.version).thenReturn(9)
        Mockito.`when`(work.workOrder(workId)).thenReturn(order)
    }

    private fun request(decision: String, reason: String? = null) =
        FieldMaterialUsageReconciliationRequest(UUID.randomUUID(), decision, 9, 4, reason)

    @Test fun `feature is unavailable while rollout flag is off`() {
        val error = assertThrows<ProductApiException> {
            service(false).reconcile(
                StockCommandContext(UUID.randomUUID(), actor, "test"),
                warehouseId, reportId, request("REJECT", "Not used"),
            )
        }
        assertEquals("FIELD_MATERIAL_REVIEW_UNAVAILABLE", error.code)
        Mockito.verifyNoInteractions(persistence)
    }

    @Test fun `consume creates canonical movement link once`() {
        allowed()
        Mockito.`when`(fulfillment.issued(item, workId)).thenReturn(
            listOf(IssuedStockContext(item, employee, project, site, workId, requirement, "5.000000", 4)),
        )
        val movement = UUID.randomUUID()
        Mockito.`when`(
            stockService.consume(
                Mockito.any(StockCommandContext::class.java),
                Mockito.eq(item),
                Mockito.any(AdjustIssuedStockRequest::class.java),
            ),
        ).thenAnswer {
            val body = it.arguments[2] as AdjustIssuedStockRequest
            StockFulfillmentReceipt(
                movement, body.operationId, "CONSUME", item, employee, workId,
                requirement, body.quantity, body.unitCode, replayed = false,
            )
        }

        val operation = UUID.randomUUID()
        val result = service().reconcile(
            StockCommandContext(operation, actor, "consume-test"),
            warehouseId, reportId,
            FieldMaterialUsageReconciliationRequest(operation, "CONSUME", 9, 4),
        )

        assertTrue(result.stockMoved)
        assertEquals(movement, result.movementId)
        assertEquals(FieldMaterialUsageDecision.CONSUME, stored.get()!!.decision)
        assertEquals(movement, stored.get()!!.stockMovementId)
        Mockito.verify(stockService, Mockito.times(1)).consume(
            Mockito.any(StockCommandContext::class.java),
            Mockito.eq(item),
            Mockito.any(AdjustIssuedStockRequest::class.java),
        )
    }

    @Test fun `reject records terminal review without stock movement`() {
        allowed()
        val operation = UUID.randomUUID()
        val result = service().reconcile(
            StockCommandContext(operation, actor, "reject-test"),
            warehouseId, reportId,
            FieldMaterialUsageReconciliationRequest(operation, "REJECT", 9, 4, "Returned unused"),
        )

        assertFalse(result.stockMoved)
        assertEquals(FieldMaterialUsageDecision.REJECT, stored.get()!!.decision)
        assertEquals("Returned unused", stored.get()!!.reason)
        Mockito.verifyNoInteractions(stockService)
    }

    @Test fun `stale issued source blocks consume`() {
        allowed()
        Mockito.`when`(fulfillment.issued(item, workId)).thenReturn(
            listOf(IssuedStockContext(item, employee, project, site, workId, requirement, "5.000000", 5)),
        )
        val operation = UUID.randomUUID()
        val error = assertThrows<ProductApiException> {
            service().reconcile(
                StockCommandContext(operation, actor, "stale-test"),
                warehouseId, reportId,
                FieldMaterialUsageReconciliationRequest(operation, "CONSUME", 9, 4),
            )
        }

        assertEquals("FIELD_MATERIAL_USAGE_ISSUED_STOCK_CHANGED", error.code)
        assertEquals(null, stored.get())
        Mockito.verifyNoInteractions(stockService)
    }
}
