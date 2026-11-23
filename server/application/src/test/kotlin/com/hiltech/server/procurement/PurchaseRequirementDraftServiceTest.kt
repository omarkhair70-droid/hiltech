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
        Mockito.`when`(drafts.otherOpenDraftIds(org, workId, itemId, draftId))
            .thenReturn(emptyList())
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

    @Test fun `requester submits a draft with an immutable technical-intake event`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val submitId = UUID.randomUUID()
        val submitted = svc.submit(
            ProcurementDraftContext(submitId, actor, "submit-test"),
            draftId, SubmitPurchaseRequirementRequest(submitId, 1),
        )
        assertEquals("SUBMITTED", submitted.draft.state)
        assertEquals(2, submitted.draft.version)
        assertFalse(submitted.draft.canSubmit)
        assertTrue(submitted.draft.canWithdraw)
        assertEquals("SUBMIT", transitioned?.type)
        assertEquals("DRAFT", transitioned?.fromState)
        assertEquals("SUBMITTED", transitioned?.toState)
        assertEquals(1, written?.version)
        assertEquals(2, applied)
    }

    @Test fun `submission requires the original requester and current source versions`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val other = UUID.randomUUID()
        Mockito.`when`(projectAuth.canRequestResources(other, project)).thenReturn(true)
        val op = UUID.randomUUID()
        assertEquals("PROCUREMENT_UNAVAILABLE", assertThrows<ProductApiException> {
            svc.submit(ProcurementDraftContext(op, other, "other"), draftId,
                SubmitPurchaseRequirementRequest(op, 1))
        }.code)
        Mockito.`when`(order.version).thenReturn(6L)
        assertEquals("PROCUREMENT_SOURCE_CHANGED", assertThrows<ProductApiException> {
            svc.submit(ProcurementDraftContext(op, actor, "stale"), draftId,
                SubmitPurchaseRequirementRequest(op, 1))
        }.code)
        assertNull(transitioned)
    }

    @Test fun `manager technical review is scoped and cannot silently issue purchase orders`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val sub = UUID.randomUUID()
        svc.submit(ProcurementDraftContext(sub, actor, "submit"), draftId,
            SubmitPurchaseRequirementRequest(sub, 1))
        val manager = UUID.randomUUID()
        Mockito.`when`(projectAuth.canManageProject(manager, project)).thenReturn(true)
        val rev = UUID.randomUUID()
        val ready = svc.technicalReview(ProcurementDraftContext(rev, manager, "review"), draftId,
            ReviewPurchaseRequirementRequest(rev, 2, "READY_FOR_SOURCING", "Technical requirements checked"))
        assertEquals("READY_FOR_SOURCING", ready.draft.state)
        assertEquals(3, ready.draft.version)
        assertTrue(ready.draft.canTechnicalReview) // manager may return/reject before sourcing
        assertEquals("TECHNICAL_READY", transitioned?.type)
        assertEquals("READY_FOR_SOURCING", transitioned?.toState)
        assertEquals(3, applied)
    }

    @Test fun `ordinary resource requester does not inherit managerial review authority`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val sub = UUID.randomUUID()
        svc.submit(ProcurementDraftContext(sub, actor, "submit"), draftId,
            SubmitPurchaseRequirementRequest(sub, 1))
        val rev = UUID.randomUUID()
        assertEquals("PROCUREMENT_UNAVAILABLE", assertThrows<ProductApiException> {
            svc.technicalReview(ProcurementDraftContext(rev, actor, "review"), draftId,
                ReviewPurchaseRequirementRequest(rev, 2, "READY_FOR_SOURCING"))
        }.code)
        assertEquals("SUBMIT", transitioned?.type)
    }

    @Test fun `manager can request information and requester can revise full immutable spec`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val manager = UUID.randomUUID()
        Mockito.`when`(projectAuth.canManageProject(manager, project)).thenReturn(true)
        val sub = UUID.randomUUID()
        svc.submit(ProcurementDraftContext(sub, actor, "submit"), draftId,
            SubmitPurchaseRequirementRequest(sub, 1))
        val info = UUID.randomUUID()
        val returned = svc.technicalReview(ProcurementDraftContext(info, manager, "info"), draftId,
            ReviewPurchaseRequirementRequest(info, 2, "NEEDS_INFO", "Clarify total amount"))
        assertEquals("NEEDS_INFO", returned.draft.state)
        assertEquals("Clarify total amount", returned.draft.lastReviewReason)
        assertTrue(returned.draft.canRevise.not())
        val amended = UUID.randomUUID()
        val revision = svc.revise(ProcurementDraftContext(amended, actor, "revision"), draftId,
            RevisePurchaseRequirementRequest(amended, 3, 5, 3, "2", "PCS", "Updated cable quantity"))
        assertEquals("DRAFT", revision.draft.state)
        assertEquals("2", revision.draft.requestedQuantity)
        assertEquals("Updated cable quantity", revision.draft.justification)
        assertEquals(4, revision.draft.version)
        assertTrue(revision.draft.canSubmit)
        assertEquals("2", transitioned?.revised?.quantity?.toPlainString())
        assertEquals("3", written?.requestedQuantity?.toPlainString())
        val subAgain = UUID.randomUUID()
        val second = svc.submit(ProcurementDraftContext(subAgain, actor, "submit-again"), draftId,
            SubmitPurchaseRequirementRequest(subAgain, 4))
        assertEquals("SUBMITTED", second.draft.state)
        assertEquals("2", second.draft.requestedQuantity)
        assertEquals("2", transitioned?.revised?.quantity?.toPlainString())
    }

    @Test fun `manager inbox rejects unscoped callers and returns only current project intake`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val manager = UUID.randomUUID()
        assertEquals("PROCUREMENT_UNAVAILABLE", assertThrows<ProductApiException> {
            svc.projectIntake(manager, projectId)
        }.code)
        Mockito.`when`(projectAuth.canManageProject(manager, project)).thenReturn(true)
        Mockito.`when`(drafts.projectIntake(org, projectId)).thenReturn(listOf(written!!))
        val inbox = svc.projectIntake(manager, projectId)
        assertEquals(1, inbox.size)
        assertEquals(draftId, inbox.single().id)
        assertFalse(inbox.single().canWithdraw)
    }

    @Test fun `unexplained managerial rejection and invalid state transitions are denied`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val manager = UUID.randomUUID()
        Mockito.`when`(projectAuth.canManageProject(manager, project)).thenReturn(true)
        val op = UUID.randomUUID()
        assertEquals("INVALID_PURCHASE_REQUIREMENT", assertThrows<ProductApiException> {
            svc.technicalReview(ProcurementDraftContext(op, manager, "bad-review"), draftId,
                ReviewPurchaseRequirementRequest(op, 1, "REJECTED"))
        }.code)
        assertEquals("PROCUREMENT_WORK_ENDED", assertThrows<ProductApiException> {
            svc.technicalReview(ProcurementDraftContext(op, manager, "not-submitted"), draftId,
                ReviewPurchaseRequirementRequest(op, 1, "READY_FOR_SOURCING"))
        }.code)
    }

    @Test fun `manager technical ready fails on changed stock item version`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val op = UUID.randomUUID()
        svc.submit(ProcurementDraftContext(op, actor, "submitted"), draftId,
            SubmitPurchaseRequirementRequest(op, 1))
        val manager = UUID.randomUUID()
        Mockito.`when`(projectAuth.canManageProject(manager, project)).thenReturn(true)
        Mockito.`when`(stock.item(itemId)).thenReturn(item.copy(version = 4))
        val reviewOp = UUID.randomUUID()
        assertEquals("PROCUREMENT_SOURCE_CHANGED", assertThrows<ProductApiException> {
            svc.technicalReview(ProcurementDraftContext(reviewOp, manager, "review"), draftId,
                ReviewPurchaseRequirementRequest(reviewOp, 2, "READY_FOR_SOURCING"))
        }.code)
        assertEquals("SUBMIT", transitioned?.type)
    }

    @Test fun `revise blocks a coworker even when they can request project resources`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val coworker = UUID.randomUUID()
        Mockito.`when`(projectAuth.canRequestResources(coworker, project)).thenReturn(true)
        val op = UUID.randomUUID()
        assertEquals("PROCUREMENT_UNAVAILABLE", assertThrows<ProductApiException> {
            svc.revise(ProcurementDraftContext(op, coworker, "not-owner"), draftId,
                RevisePurchaseRequirementRequest(op, 1, 5, 3, "4", "PCS", "Changed"))
        }.code)
        assertNull(transitioned)
    }

    @Test fun `technically ready requirement can be withdrawn before any sourcing begins`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val manager = UUID.randomUUID()
        Mockito.`when`(projectAuth.canManageProject(manager, project)).thenReturn(true)
        val sub = UUID.randomUUID()
        svc.submit(ProcurementDraftContext(sub, actor, "submit"), draftId,
            SubmitPurchaseRequirementRequest(sub, 1))
        val review = UUID.randomUUID()
        svc.technicalReview(ProcurementDraftContext(review, manager, "ready"), draftId,
            ReviewPurchaseRequirementRequest(review, 2, "READY_FOR_SOURCING"))
        val op = UUID.randomUUID()
        val result = svc.withdraw(ProcurementDraftContext(op, actor, "withdraw"), draftId,
            WithdrawPurchaseRequirementRequest(op, 3, "No longer required"))
        assertEquals("WITHDRAWN", result.draft.state)
        assertEquals(4, result.draft.version)
        assertEquals("READY_FOR_SOURCING", transitioned?.fromState)
        assertFalse(result.draft.canWithdraw)
    }

    @Test fun `project manager can reopen technical ready need for correction before sourcing`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val manager = UUID.randomUUID()
        Mockito.`when`(projectAuth.canManageProject(manager, project)).thenReturn(true)
        val sub = UUID.randomUUID()
        svc.submit(ProcurementDraftContext(sub, actor, "submit"), draftId,
            SubmitPurchaseRequirementRequest(sub, 1))
        val review = UUID.randomUUID()
        svc.technicalReview(ProcurementDraftContext(review, manager, "ready"), draftId,
            ReviewPurchaseRequirementRequest(review, 2, "READY_FOR_SOURCING"))
        val back = UUID.randomUUID()
        val reopened = svc.technicalReview(ProcurementDraftContext(back, manager, "return"), draftId,
            ReviewPurchaseRequirementRequest(back, 3, "NEEDS_INFO", "Material specification changed"))
        assertEquals("NEEDS_INFO", reopened.draft.state)
        assertEquals("READY_FOR_SOURCING", transitioned?.fromState)
        assertEquals(4, reopened.draft.version)
        assertEquals("Material specification changed", reopened.draft.lastReviewReason)
    }

    @Test fun `review preview recalculates available stock and warns about open same-work drafts`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val another = UUID.randomUUID()
        Mockito.`when`(drafts.otherOpenDraftIds(org, workId, itemId, draftId))
            .thenReturn(listOf(another))
        val preview = svc.reviewPreview(actor, draftId)
        assertEquals(draftId, preview.requirementId)
        assertEquals(5L, preview.currentWorkOrderVersion)
        assertEquals(3L, preview.currentStockItemVersion)
        assertTrue(preview.sourceCurrent)
        assertEquals("VISIBLE_STOCK_SHORTAGE", preview.assessmentCode)
        assertEquals("2", preview.visibleFreeQuantity)
        assertEquals(listOf(another), preview.otherOpenDraftIds)
        assertFalse(preview.moreOpenDrafts)
        assertFalse(preview.approvalOrPurchaseAuthorized)
    }

    @Test fun `review preview masks current quantity after storage access is revoked`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        Mockito.`when`(warehouseAuth.canViewStorage(actor, bin)).thenReturn(false)
        val preview = svc.reviewPreview(actor, draftId)
        assertEquals("WAREHOUSE_REVIEW_REQUIRED", preview.assessmentCode)
        assertNull(preview.visibleFreeQuantity)
        assertTrue(preview.sourceCurrent)
    }

    @Test fun `review preview does not present a stale work snapshot as current stock`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        Mockito.`when`(order.version).thenReturn(6L)
        val preview = svc.reviewPreview(actor, draftId)
        assertFalse(preview.sourceCurrent)
        assertEquals("SOURCE_CHANGED", preview.assessmentCode)
        assertNull(preview.visibleFreeQuantity)
        assertEquals(6L, preview.currentWorkOrderVersion)
    }

    @Test fun `review preview refuses cross-organization draft access`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        written = written!!.copy(organizationId = UUID.randomUUID())
        assertEquals("PROCUREMENT_UNAVAILABLE", assertThrows<ProductApiException> {
            svc.reviewPreview(actor, draftId)
        }.code)
        Mockito.verify(drafts, Mockito.never())
            .otherOpenDraftIds(org, workId, itemId, draftId)
    }

    @Test fun `review preview hides quantities of withdrawn request`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        val changeOperation = UUID.randomUUID()
        svc.withdraw(
            ProcurementDraftContext(changeOperation, actor, "withdraw-test"), draftId,
            WithdrawPurchaseRequirementRequest(changeOperation, 1),
        )
        val preview = svc.reviewPreview(actor, draftId)
        assertEquals("WITHDRAWN", preview.state)
        assertEquals("REQUIREMENT_WITHDRAWN", preview.assessmentCode)
        assertFalse(preview.sourceCurrent)
        assertNull(preview.visibleFreeQuantity)
    }

    @Test fun `review preview limits warnings to twenty without hiding truncation`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        Mockito.`when`(drafts.otherOpenDraftIds(org, workId, itemId, draftId))
            .thenReturn((1..21).map { UUID.randomUUID() })
        val preview = svc.reviewPreview(actor, draftId)
        assertEquals(20, preview.otherOpenDraftIds.size)
        assertTrue(preview.moreOpenDrafts)
    }

    @Test fun `review preview requires current project permission`() {
        allow()
        val svc = service()
        svc.create(context, workId, request)
        Mockito.`when`(projectAuth.canRequestResources(actor, project)).thenReturn(false)
        assertEquals("PROCUREMENT_UNAVAILABLE", assertThrows<ProductApiException> {
            svc.reviewPreview(actor, draftId)
        }.code)
    }

    @Test fun `review preview disabled is not an information disclosure endpoint`() {
        allow()
        assertEquals("PROCUREMENT_UNAVAILABLE", assertThrows<ProductApiException> {
            service(false).reviewPreview(actor, draftId)
        }.code)
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
