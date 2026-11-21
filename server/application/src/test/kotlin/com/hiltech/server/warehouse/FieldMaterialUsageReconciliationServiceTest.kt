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

    private val source = FieldMaterialUsageReviewSource(
        reportId, org, project, site, workId, employee, requirement, item,
        9, 4, BigDecimal("2.000000"), "M",
    )
    private val persistence = FakePersistence(source)
    private val warehouses = Mockito.mock(WarehousePersistencePort::class.java)
    private val authorization = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val fulfillment = Mockito.mock(StockFulfillmentPersistence::class.java)
    private val stockService = Mockito.mock(StockFulfillmentService::class.java)
    private val work = Mockito.mock(WorkPersistencePort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val location = Mockito.mock(StorageLocationSnapshot::class.java)
    private val order = Mockito.mock(WorkOrderSnapshot::class.java)
    private val facility = WarehouseSnapshot(warehouseId, org, "WH-A", "Warehouse", null, true, 1)

    private class FakePersistence(
        private val sourceValue: FieldMaterialUsageReviewSource,
    ) : FieldMaterialUsageReconciliationPersistencePort {
        var resolution: FieldMaterialUsageReconciliationRecord? = null

        override fun source(reportOperationId: UUID, lock: Boolean): FieldMaterialUsageReviewSource? =
            sourceValue.takeIf { it.operationId == reportOperationId }

        override fun reconciliation(reportOperationId: UUID): FieldMaterialUsageReconciliationRecord? =
            resolution?.takeIf { it.reportOperationId == reportOperationId }

        override fun insert(record: FieldMaterialUsageReconciliationRecord) {
            check(resolution == null)
            resolution = record
        }
    }

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

    @Test fun `feature is unavailable while rollout flag is off`() {
        val operation = UUID.randomUUID()
        val error = assertThrows<ProductApiException> {
            service(false).reconcile(
                StockCommandContext(operation, actor, "test"),
                warehouseId, reportId,
                FieldMaterialUsageReconciliationRequest(operation, "REJECT", 9, 4, "Not used"),
            )
        }
        assertEquals("FIELD_MATERIAL_REVIEW_UNAVAILABLE", error.code)
        assertEquals(null, persistence.resolution)
    }

    @Test fun `consume creates canonical movement link once`() {
        allowed()
        Mockito.`when`(fulfillment.issued(item, workId)).thenReturn(
            listOf(IssuedStockContext(item, employee, project, site, workId, requirement, "5.000000", 4)),
        )
        val operation = UUID.randomUUID()
        val stockOperation = UUID.nameUUIDFromBytes(
            "hiltech:field-material-usage:consume:$reportId".toByteArray(Charsets.UTF_8),
        )
        val stockContext = StockCommandContext(stockOperation, actor, "consume-test")
        val stockRequest = AdjustIssuedStockRequest(
            stockOperation, workId, requirement, employee, 9, 4, "2", "M",
        )
        val movement = UUID.randomUUID()
        Mockito.`when`(stockService.consume(stockContext, item, stockRequest)).thenReturn(
            StockFulfillmentReceipt(
                movement, stockOperation, "CONSUME", item, employee, workId,
                requirement, "2", "M", replayed = false,
            ),
        )

        val result = service().reconcile(
            StockCommandContext(operation, actor, "consume-test"),
            warehouseId, reportId,
            FieldMaterialUsageReconciliationRequest(operation, "CONSUME", 9, 4),
        )

        assertTrue(result.stockMoved)
        assertEquals(movement, result.movementId)
        assertEquals(FieldMaterialUsageDecision.CONSUME, persistence.resolution!!.decision)
        assertEquals(movement, persistence.resolution!!.stockMovementId)
        Mockito.verify(stockService, Mockito.times(1)).consume(stockContext, item, stockRequest)
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
        assertEquals(FieldMaterialUsageDecision.REJECT, persistence.resolution!!.decision)
        assertEquals("Returned unused", persistence.resolution!!.reason)
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
        assertEquals(null, persistence.resolution)
        Mockito.verifyNoInteractions(stockService)
    }
}
