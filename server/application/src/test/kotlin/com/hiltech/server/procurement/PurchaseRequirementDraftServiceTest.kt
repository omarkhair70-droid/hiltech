package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectSnapshot
import com.hiltech.server.projects.ProjectsPersistencePort
import com.hiltech.server.warehouse.ReservationPersistence
import com.hiltech.server.warehouse.StockCapacity
import com.hiltech.server.warehouse.StockClassification
import com.hiltech.server.warehouse.StockItemView
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.warehouse.StorageLocationLifecycle
import com.hiltech.server.warehouse.StorageLocationSnapshot
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import com.hiltech.server.warehouse.WarehousePersistencePort
import com.hiltech.server.work.WorkOrderLifecycle
import com.hiltech.server.work.WorkOrderSnapshot
import com.hiltech.server.work.WorkPersistencePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PurchaseRequirementDraftServiceTest {
    private val at = Instant.parse("2026-11-22T08:00:00Z")
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val itemId = UUID.randomUUID()
    private val binId = UUID.randomUUID()
    private val operation = UUID.randomUUID()
    private val order = Mockito.mock(WorkOrderSnapshot::class.java)
    private val project = Mockito.mock(ProjectSnapshot::class.java)
    private val bin = Mockito.mock(StorageLocationSnapshot::class.java)
    private val drafts = Mockito.mock(PurchaseRequirementDraftPersistence::class.java)
    private val work = Mockito.mock(WorkPersistencePort::class.java)
    private val projects = Mockito.mock(ProjectsPersistencePort::class.java)
    private val projectAuth = Mockito.mock(ProjectAuthorizationPort::class.java)
    private val stock = Mockito.mock(StockLedgerPersistence::class.java)
    private val reservations = Mockito.mock(ReservationPersistence::class.java)
    private val warehouse = Mockito.mock(WarehousePersistencePort::class.java)
    private val warehouseAuth = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private var written: PurchaseRequirementDraftRecord? = null
    private var transitioned: PurchaseRequirementTransitionRecord? = null
    private val draftId = UUID.nameUUIDFromBytes(
        "hiltech:procurement:purchase-requirement:${operation}".toByteArray(Charsets.UTF_8),
    )
    private var applied = 0
    private val commands = object : IdempotentCommandExecutor {
        override fun execute(
            spec: IdempotentCommandSpec,
            action: () -> IdempotentCommandOutcome,
        ): IdempotentCommandExecution {
            applied++
            return IdempotentCommandExecution(action(), false)
        }
    }
    private val clock = Clock.fixed(at, ZoneOffset.UTC)
    private val context = ProcurementDraftContext(operation, actor, "test-correlation")
    private val request = CreatePurchaseRequirementDraftRequest(operation, 5, itemId, 3, "3", "PCS", "Cable required")
    private val item = StockItemView(
        itemId, org, "CABLE-01", "Cable", null, null, null, UUID.randomUUID(), 1,
        "PCS", StockClassification.STOCK_QUANTITY, false, false, true, null, 3,
    )

    private fun service(enabled: Boolean = true) = PurchaseRequirementDraftService(
        drafts, work, projects, projectAuth, stock, reservations, warehouse,
        warehouseAuth, commands, audit, clock, enabled,
    )

    private fun allow() {
        Mockito.`when`(work.workOrder(workId)).thenReturn(order)
        Mockito.`when`(work.lockWorkOrder(workId)).thenReturn(true)
        Mockito.`when`(order.workOrderId).thenReturn(workId)
        Mockito.`when`(order.projectId).thenReturn(projectId)
        Mockito.`when`(order.organizationId).thenReturn(org)
        Mockito.`when`(order.version).thenReturn(5)
        Mockito.`when`(order.lifecycleState).thenReturn(WorkOrderLifecycle.PLANNED)
        Mockito.`when`(projects.project(projectId, at)).thenReturn(project)
        Mockito.`when`(project.projectId).thenReturn(projectId)
        Mockito.`when`(project.organizationId).thenReturn(org)
        Mockito.`when`(projectAuth.canRequestResources(actor, project)).thenReturn(true)
        Mockito.`when`(stock.item(itemId)).thenReturn(item)
        Mockito.`when`(reservations.stockUnitActive(org, "PCS")).thenReturn(true)
        Mockito.`when`(reservations.balances(itemId, at)).thenReturn(
            listOf(StockCapacity(itemId, binId, BigDecimal("2"), 7, BigDecimal("4"))),
        )
        Mockito.`when`(warehouse.storageLocation(binId)).thenReturn(bin)
        Mockito.`when`(bin.organizationId).thenReturn(org)
        Mockito.`when`(bin.lifecycleState).thenReturn(StorageLocationLifecycle.ACTIVE)
        Mockito.`when`(warehouseAuth.canViewStorage(actor, bin)).thenReturn(true)
        // Mockito's Java matcher returns null during stubbing; Kotlin non-null parameters
        // require a concrete fallback value while the matcher remains registered.
        val placeholder = PurchaseRequirementDraftRecord(
            UUID.randomUUID(), operation, org, workId, 5, itemId, 3,
            BigDecimal("3"), "PCS", "Test", "WAREHOUSE_REVIEW_REQUIRED",
            null, at, actor, at, "test-correlation",
        )
        Mockito.doAnswer { invocation ->
            written = invocation.getArgument<PurchaseRequirementDraftRecord>(0)
            Unit
        }.`when`(drafts).insert(any(PurchaseRequirementDraftRecord::class.java) ?: placeholder)
        Mockito.`when`(drafts.byId(any(UUID::class.java) ?: UUID.randomUUID()))
            .thenAnswer { written }
        Mockito.`when`(drafts.lock(draftId)).thenReturn(true)
        val placeholderAction = PurchaseRequirementTransitionRecord(
            UUID.randomUUID(), UUID.randomUUID(), draftId, org, 1, 2,
            "WITHDRAW", "DRAFT", "WITHDRAWN", null, actor, at, "test-correlation",
        )
        Mockito.doAnswer { invocation ->
            transitioned = invocation.getArgument<PurchaseRequirementTransitionRecord>(0)
            Unit
        }.`when`(drafts).appendTransition(
            any(PurchaseRequirementTransitionRecord::class.java) ?: placeholderAction,
        )
        Mockito.`when`(drafts.latestTransition(draftId)).thenAnswer { transitioned }
    }

    @Test fun `feature is off by default without reading source`() {
        val failure = assertThrows<ProductApiException> {
            service(false).create(context, workId, request)
        }
        assertEquals("PROCUREMENT_UNAVAILABLE", failure.code)
        Mockito.verifyNoInteractions(work, projects, stock, reservations, drafts)
    }

    @Test fun `requester must have current project resource request authority`() {
        allow()
        Mockito.`when`(projectAuth.canRequestResources(actor, project)).thenReturn(false)
        val failure = assertThrows<ProductApiException> {
            service().create(context, workId, request)
        }
        assertEquals("PROCUREMENT_UNAVAILABLE", failure.code)
        assertEquals(0, applied)
        Mockito.verifyNoInteractions(drafts)
    }

    @Test fun `different organization stock is hidden`() {
        allow()
        Mockito.`when`(stock.item(itemId)).thenReturn(item.copy(organizationId = UUID.randomUUID()))
        assertEquals("PROCUREMENT_UNAVAILABLE",
            assertThrows<ProductApiException> { service().create(context, workId, request) }.code)
        assertEquals(0, applied)
    }

    @Test fun `inactive canonical stock unit is rejected`() {
        allow()
        Mockito.`when`(reservations.stockUnitActive(org, "PCS")).thenReturn(false)
        assertEquals("PROCUREMENT_UNAVAILABLE",
            assertThrows<ProductApiException> { service().create(context, workId, request) }.code)
        assertNull(written)
    }

    @Test fun `stale WorkOrder is rejected before any draft is inserted`() {
        allow()
        val failure = assertThrows<ProductApiException> {
            service().create(context, workId, request.copy(baseWorkOrderVersion = 4))
        }
        assertEquals("PROCUREMENT_SOURCE_CHANGED", failure.code)
        assertNull(written)
    }

    @Test fun `stock preflight subtracts live reservations and stores only a snapshot`() {
        allow()
        val receipt = service().create(context, workId, request)
        assertFalse(receipt.replayed)
        assertEquals("VISIBLE_STOCK_SHORTAGE", receipt.draft.assessmentCode)
        assertEquals("2", receipt.draft.observedFreeQuantity)
        assertEquals("DRAFT", receipt.draft.state)
        assertTrue(receipt.draft.canWithdraw)
        assertTrue(receipt.draft.assessmentRequiresRefresh)
        assertEquals("2", written?.visibleFreeQuantity?.toPlainString())
        assertEquals(workId, written?.workOrderId)
        assertEquals(1, applied)
    }

    @Test fun `unpermitted stock location hides its quantity but allows request for warehouse review`() {
        allow()
        Mockito.`when`(warehouseAuth.canViewStorage(actor, bin)).thenReturn(false)
        val receipt = service().create(context, workId, request)
        assertEquals("WAREHOUSE_REVIEW_REQUIRED", receipt.draft.assessmentCode)
        assertNull(receipt.draft.observedFreeQuantity)
        assertNull(written?.visibleFreeQuantity)
    }

    @Test fun `draft never silently authorizes a terminal WorkOrder`() {
        allow()
        Mockito.`when`(order.lifecycleState).thenReturn(WorkOrderLifecycle.CANCELLED)
        assertEquals("PROCUREMENT_WORK_ENDED",
            assertThrows<ProductApiException> { service().create(context, workId, request) }.code)
        assertNull(written)
    }

    @Test fun `owner can withdraw own draft without altering original stock proof`() {
        allow()
        val svc = service()
        val original = svc.create(context, workId, request).draft
        val withdrawalId = UUID.randomUUID()
        val result = svc.withdraw(
            ProcurementDraftContext(withdrawalId, actor, "withdraw-test"),
            draftId,
            WithdrawPurchaseRequirementRequest(withdrawalId, 1, "Demand changed"),
        )
        assertEquals("DRAFT", original.state)
        assertEquals("WITHDRAWN", result.draft.state)
        assertFalse(result.draft.canWithdraw)
        assertEquals(2, result.draft.version)
        assertFalse(result.draft.sourceCurrent)
        assertEquals(1, written?.version)
        assertEquals("WITHDRAW", transitioned?.type)
        assertEquals("Demand changed", transitioned?.reason)
        assertEquals(1, transitioned?.sourceVersion)
        assertEquals(2, transitioned?.resultVersion)
        assertEquals(2, applied)
    }

    @Test fun `second withdrawal cannot create another transition`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val first = UUID.randomUUID()
        svc.withdraw(
            ProcurementDraftContext(first, actor, "withdraw-test"),
            draftId, WithdrawPurchaseRequirementRequest(first, 1),
        )
        val second = UUID.randomUUID()
        assertEquals("PROCUREMENT_WORK_ENDED", assertThrows<ProductApiException> {
            svc.withdraw(
                ProcurementDraftContext(second, actor, "withdraw-test-2"),
                draftId, WithdrawPurchaseRequirementRequest(second, 2),
            )
        }.code)
        assertEquals(first, transitioned?.operationId)
    }

    @Test fun `authorized coworker can read but never gets requester withdrawal capability`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val coworker = UUID.randomUUID()
        Mockito.`when`(projectAuth.canRequestResources(coworker, project)).thenReturn(true)
        val view = svc.detail(coworker, draftId)
        assertEquals("DRAFT", view.state)
        assertFalse(view.canWithdraw)
    }

    @Test fun `different user cannot withdraw another requester draft`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val differentActor = UUID.randomUUID()
        Mockito.`when`(projectAuth.canRequestResources(differentActor, project)).thenReturn(true)
        val withdrawalId = UUID.randomUUID()
        assertEquals("PROCUREMENT_UNAVAILABLE", assertThrows<ProductApiException> {
            svc.withdraw(
                ProcurementDraftContext(withdrawalId, differentActor, "withdraw-test"),
                draftId, WithdrawPurchaseRequirementRequest(withdrawalId, 1),
            )
        }.code)
        assertNull(transitioned)
    }

    @Test fun `withdrawal is off when procurement flag is disabled`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val withdrawalId = UUID.randomUUID()
        assertEquals("PROCUREMENT_UNAVAILABLE", assertThrows<ProductApiException> {
            service(false).withdraw(
                ProcurementDraftContext(withdrawalId, actor, "withdraw-test"),
                draftId, WithdrawPurchaseRequirementRequest(withdrawalId, 1),
            )
        }.code)
        assertNull(transitioned)
    }

    @Test fun `lost warehouse access redacts an earlier quantity snapshot`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        Mockito.`when`(warehouseAuth.canViewStorage(actor, bin)).thenReturn(false)
        val view = svc.detail(actor, written!!.id)
        assertEquals("WAREHOUSE_REVIEW_REQUIRED", view.assessmentCode)
        assertNull(view.observedFreeQuantity)
    }
}
