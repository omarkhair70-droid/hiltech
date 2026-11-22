package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectsPersistencePort
import com.hiltech.server.warehouse.ReservationPersistence
import com.hiltech.server.warehouse.StockItemView
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.warehouse.StorageLocationLifecycle
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import com.hiltech.server.warehouse.WarehousePersistencePort
import com.hiltech.server.work.WorkOrderLifecycle
import com.hiltech.server.work.WorkOrderSnapshot
import com.hiltech.server.work.WorkPersistencePort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class CreatePurchaseRequirementDraftRequest(
    val operationId: UUID,
    val baseWorkOrderVersion: Long,
    val stockItemId: UUID,
    val stockItemVersion: Long,
    val quantity: String,
    val unitCode: String,
    val justification: String,
)

data class ProcurementDraftContext(val operationId: UUID, val actor: UUID, val correlationId: String)

data class WithdrawPurchaseRequirementRequest(
    val operationId: UUID,
    val expectedVersion: Long,
    val reason: String? = null,
)

data class SubmitPurchaseRequirementRequest(val operationId: UUID, val expectedVersion: Long)

data class ReviewPurchaseRequirementRequest(
    val operationId: UUID,
    val expectedVersion: Long,
    /** READY_FOR_SOURCING, NEEDS_INFO, REJECTED. Technical validation, NOT PO authority. */
    val decision: String,
    val reason: String? = null,
)

data class RevisePurchaseRequirementRequest(
    val operationId: UUID,
    val expectedVersion: Long,
    val baseWorkOrderVersion: Long,
    val stockItemVersion: Long,
    val quantity: String,
    val unitCode: String,
    val justification: String,
)

data class PurchaseRequirementDraftView(
    val id: UUID,
    val workOrderId: UUID,
    val workOrderVersionAtCreation: Long,
    val stockItemId: UUID,
    val stockItemVersionAtCreation: Long,
    val requestedQuantity: String,
    val unitCode: String,
    val justification: String,
    val state: String,
    val assessmentCode: String,
    val observedFreeQuantity: String?,
    val assessedAt: Instant,
    val createdBy: UUID,
    val createdAt: Instant,
    val sourceCurrent: Boolean,
    val assessmentRequiresRefresh: Boolean = true,
    val version: Long = 1,
    val canWithdraw: Boolean = false,
    val canSubmit: Boolean = false,
    val canRevise: Boolean = false,
    val canTechnicalReview: Boolean = false,
    val lastReviewReason: String? = null,
)

data class PurchaseRequirementDraftReceipt(val draft: PurchaseRequirementDraftView, val replayed: Boolean)

/**
 * A new, read-only snapshot for the authorized WorkOrder requester.
 * There is no purchase approval or reservation attached to this answer.
 * Never treat the available quantity as an unconditional buying decision.
 */
data class PurchaseRequirementReviewPreview(
    val requirementId: UUID,
    val state: String,
    val currentWorkOrderVersion: Long,
    val currentStockItemVersion: Long?,
    val sourceCurrent: Boolean,
    val assessmentCode: String,
    val visibleFreeQuantity: String?,
    val assessedAt: Instant,
    val otherOpenDraftIds: List<UUID>,
    val moreOpenDrafts: Boolean,
    val approvalOrPurchaseAuthorized: Boolean = false,
)

internal data class StockPreflight(val code: String, val free: BigDecimal?, val at: Instant)

@Service
class PurchaseRequirementDraftService @Autowired constructor(
    private val drafts: PurchaseRequirementDraftPersistence,
    private val work: WorkPersistencePort,
    private val projects: ProjectsPersistencePort,
    private val projectAuth: ProjectAuthorizationPort,
    private val stock: StockLedgerPersistence,
    private val reservations: ReservationPersistence,
    private val warehouse: WarehousePersistencePort,
    private val warehouseAuth: WarehouseAuthorizationPort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.requirements.enabled:false}")
    private val enabled: Boolean = false,
) {
    fun create(
        context: ProcurementDraftContext,
        workOrderId: UUID,
        request: CreatePurchaseRequirementDraftRequest,
    ): PurchaseRequirementDraftReceipt {
        requireEnabled()
        if (context.operationId != request.operationId) invalid()
        val order = authorizedOrder(context.actor, workOrderId)
        val item = matchingItem(order, request.stockItemId)
        val quantity = parseQuantity(request.quantity)
        val justification = request.justification.trim()
        if (justification.isEmpty() || justification.length > 500 ||
            request.unitCode != item.baseUnitCode ||
            (item.serialized && quantity.stripTrailingZeros().scale() > 0)
        ) invalid()
        val id = UUID.nameUUIDFromBytes(
            "hiltech:procurement:purchase-requirement:${context.operationId}".toByteArray(Charsets.UTF_8),
        )
        val fields = listOf(
            workOrderId.toString(), request.baseWorkOrderVersion.toString(),
            request.stockItemId.toString(), request.stockItemVersion.toString(),
            quantity.toPlainString(), request.unitCode, justification,
        )
        val canonical = fields.joinToString("") { "${it.length}:$it" }
        val execution = commands.execute(
            IdempotentCommandSpec(
                context.operationId, context.actor, "PROCUREMENT_REQUIREMENT_DRAFT_CREATED",
                "PURCHASE_REQUIREMENT", id,
                IdempotencyKeyContract.fingerprint(canonical), context.correlationId,
            ),
        ) {
            if (!work.lockWorkOrder(workOrderId)) hidden()
            val current = authorizedOrder(context.actor, workOrderId)
            if (current.version != request.baseWorkOrderVersion) stale()
            if (!acceptsDemand(current.lifecycleState)) ended()
            val currentItem = matchingItem(current, request.stockItemId)
            if (currentItem.version != request.stockItemVersion ||
                currentItem.baseUnitCode != request.unitCode
            ) stale()
            val now = clock.instant()
            val assessment = assess(context.actor, current, currentItem, quantity, now)
            drafts.insert(
                PurchaseRequirementDraftRecord(
                    id, context.operationId, current.organizationId, workOrderId,
                    current.version, currentItem.id, currentItem.version, quantity,
                    currentItem.baseUnitCode, justification, assessment.code,
                    assessment.free, assessment.at, context.actor, now, context.correlationId,
                ),
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = context.actor,
                    action = "PROCUREMENT_REQUIREMENT_DRAFT_CREATED",
                    targetType = "PURCHASE_REQUIREMENT",
                    targetId = id,
                    occurredAt = now,
                    correlationId = context.correlationId,
                ),
            )
            IdempotentCommandOutcome("PROCUREMENT_REQUIREMENT_DRAFT_CREATED", """{"id":"$id"}""")
        }
        val record = drafts.byId(id) ?: hidden()
        return PurchaseRequirementDraftReceipt(view(context.actor, order, record), execution.replayed)
    }

    /** Submit a legitimate demand for technical validation. Never creates a PO. */
    fun submit(
        context: ProcurementDraftContext, id: UUID, request: SubmitPurchaseRequirementRequest,
    ): PurchaseRequirementDraftReceipt {
        requireEnabled()
        if (context.operationId != request.operationId) invalid()
        val record = drafts.byId(id) ?: hidden()
        val initialOrder = authorizedOrder(context.actor, record.workOrderId)
        if (record.organizationId != initialOrder.organizationId || record.requestedBy != context.actor) hidden()
        val execution = commands.execute(
            IdempotentCommandSpec(
                context.operationId, context.actor, "PROCUREMENT_REQUIREMENT_SUBMITTED",
                "PURCHASE_REQUIREMENT", id,
                IdempotencyKeyContract.fingerprint("$id:${request.expectedVersion}"), context.correlationId,
            ),
        ) {
            if (!drafts.lock(id)) hidden()
            val root = drafts.byId(id) ?: hidden()
            if (root.requestedBy != context.actor || root.organizationId != initialOrder.organizationId) hidden()
            val previous = drafts.latestTransition(id)
            val currentVersion = previous?.resultVersion ?: root.version
            if (currentVersion != request.expectedVersion) stale()
            if ((previous?.toState ?: "DRAFT") != "DRAFT") ended()
            if (!work.lockWorkOrder(root.workOrderId)) hidden()
            val currentOrder = authorizedOrder(context.actor, root.workOrderId)
            if (!acceptsDemand(currentOrder.lifecycleState)) ended()
            val spec = effectiveSpec(root, previous)
            val currentItem = matchingItem(currentOrder, root.stockItemId)
            if (currentOrder.version != spec.workVersion || currentItem.version != spec.itemVersion ||
                currentItem.baseUnitCode != spec.unitCode
            ) stale()
            val now = clock.instant()
            assess(context.actor, currentOrder, currentItem, spec.quantity, now)
            drafts.appendTransition(
                PurchaseRequirementTransitionRecord(
                    transitionId(context.operationId), context.operationId, id, root.organizationId,
                    currentVersion, currentVersion + 1, "SUBMIT", "DRAFT", "SUBMITTED", null,
                    context.actor, now, context.correlationId, previous?.revised,
                ),
            )
            audit.append(AuditEventRecord(
                actorUserId = context.actor, action = "PROCUREMENT_REQUIREMENT_SUBMITTED",
                targetType = "PURCHASE_REQUIREMENT", targetId = id,
                occurredAt = now, correlationId = context.correlationId,
            ))
            IdempotentCommandOutcome("PROCUREMENT_REQUIREMENT_SUBMITTED", """{"id":"$id"}""")
        }
        return PurchaseRequirementDraftReceipt(
            view(context.actor, initialOrder, drafts.byId(id) ?: hidden()), execution.replayed,
        )
    }

    /** Append a full new spec snapshot; never rewrite the original V0039 evidence. */
    fun revise(
        context: ProcurementDraftContext, id: UUID, request: RevisePurchaseRequirementRequest,
    ): PurchaseRequirementDraftReceipt {
        requireEnabled()
        if (context.operationId != request.operationId) invalid()
        val record = drafts.byId(id) ?: hidden()
        val initialOrder = authorizedOrder(context.actor, record.workOrderId)
        if (record.organizationId != initialOrder.organizationId || record.requestedBy != context.actor) hidden()
        val quantity = parseQuantity(request.quantity)
        val justification = request.justification.trim()
        if (justification.isEmpty() || justification.length > 500) invalid()
        val fields = listOf(id.toString(), request.expectedVersion.toString(),
            request.baseWorkOrderVersion.toString(), request.stockItemVersion.toString(),
            quantity.toPlainString(), request.unitCode, justification)
        val canonical = fields.joinToString("") { "${it.length}:$it" }
        val execution = commands.execute(
            IdempotentCommandSpec(
                context.operationId, context.actor, "PROCUREMENT_REQUIREMENT_REVISED",
                "PURCHASE_REQUIREMENT", id,
                IdempotencyKeyContract.fingerprint(canonical), context.correlationId,
            ),
        ) {
            if (!drafts.lock(id)) hidden()
            val root = drafts.byId(id) ?: hidden()
            if (root.requestedBy != context.actor || root.organizationId != initialOrder.organizationId) hidden()
            val previous = drafts.latestTransition(id)
            val version = previous?.resultVersion ?: root.version
            val state = previous?.toState ?: "DRAFT"
            if (version != request.expectedVersion) stale()
            if (state !in setOf("DRAFT", "NEEDS_INFO")) ended()
            if (!work.lockWorkOrder(root.workOrderId)) hidden()
            val current = authorizedOrder(context.actor, root.workOrderId)
            if (!acceptsDemand(current.lifecycleState)) ended()
            if (current.version != request.baseWorkOrderVersion) stale()
            val currentItem = matchingItem(current, root.stockItemId)
            if (currentItem.version != request.stockItemVersion ||
                currentItem.baseUnitCode != request.unitCode ||
                (currentItem.serialized && quantity.stripTrailingZeros().scale() > 0)
            ) stale()
            val at = clock.instant()
            val stockCheck = assess(context.actor, current, currentItem, quantity, at)
            val revised = PurchaseRequirementRevision(
                quantity, justification, current.version, currentItem.version, currentItem.baseUnitCode,
                stockCheck.code, stockCheck.free, stockCheck.at,
            )
            drafts.appendTransition(PurchaseRequirementTransitionRecord(
                transitionId(context.operationId), context.operationId, id, root.organizationId,
                version, version + 1, "REVISE", state, "DRAFT", null,
                context.actor, at, context.correlationId, revised,
            ))
            audit.append(AuditEventRecord(
                actorUserId = context.actor, action = "PROCUREMENT_REQUIREMENT_REVISED",
                targetType = "PURCHASE_REQUIREMENT", targetId = id,
                occurredAt = at, correlationId = context.correlationId,
            ))
            IdempotentCommandOutcome("PROCUREMENT_REQUIREMENT_REVISED", """{"id":"$id"}""")
        }
        return PurchaseRequirementDraftReceipt(
            view(context.actor, initialOrder, drafts.byId(id) ?: hidden()), execution.replayed,
        )
    }

    /** Project technical validation only. Procurement/PO authority remains a separate boundary. */
    fun technicalReview(
        context: ProcurementDraftContext, id: UUID, request: ReviewPurchaseRequirementRequest,
    ): PurchaseRequirementDraftReceipt {
        requireEnabled()
        if (context.operationId != request.operationId) invalid()
        val type = when (request.decision) {
            "READY_FOR_SOURCING" -> "TECHNICAL_READY"
            "NEEDS_INFO" -> "RETURN_FOR_INFO"
            "REJECTED" -> "REJECT"
            else -> invalid()
        }
        val reason = request.reason?.trim()
        if ((type != "TECHNICAL_READY" && reason.isNullOrEmpty()) ||
            (reason != null && reason.length > 500)
        ) invalid()
        val root = drafts.byId(id) ?: hidden()
        val initialOrder = managedOrder(context.actor, root.workOrderId)
        if (root.organizationId != initialOrder.organizationId) hidden()
        val fields = listOf(id.toString(), request.expectedVersion.toString(), type, reason.orEmpty())
        val canonical = fields.joinToString("") { "${it.length}:$it" }
        val execution = commands.execute(
            IdempotentCommandSpec(
                context.operationId, context.actor, "PROCUREMENT_REQUIREMENT_$type",
                "PURCHASE_REQUIREMENT", id,
                IdempotencyKeyContract.fingerprint(canonical), context.correlationId,
            ),
        ) {
            if (!drafts.lock(id)) hidden()
            val locked = drafts.byId(id) ?: hidden()
            if (locked.organizationId != initialOrder.organizationId) hidden()
            val previous = drafts.latestTransition(id)
            val version = previous?.resultVersion ?: locked.version
            if (version != request.expectedVersion) stale()
            if ((previous?.toState ?: "DRAFT") != "SUBMITTED") ended()
            if (!work.lockWorkOrder(locked.workOrderId)) hidden()
            val current = managedOrder(context.actor, locked.workOrderId)
            if (type == "TECHNICAL_READY") {
                if (!acceptsDemand(current.lifecycleState)) ended()
                val spec = effectiveSpec(locked, previous)
                val currentItem = matchingItem(current, locked.stockItemId)
                if (current.version != spec.workVersion || currentItem.version != spec.itemVersion ||
                    currentItem.baseUnitCode != spec.unitCode
                ) stale()
                // Live assessment is advisory; technical validation never authorizes buying.
                assess(context.actor, current, currentItem, spec.quantity, clock.instant())
            }
            val at = clock.instant()
            drafts.appendTransition(PurchaseRequirementTransitionRecord(
                transitionId(context.operationId), context.operationId, id, locked.organizationId,
                version, version + 1, type, "SUBMITTED", request.decision, reason,
                context.actor, at, context.correlationId, previous?.revised,
            ))
            audit.append(AuditEventRecord(
                actorUserId = context.actor, action = "PROCUREMENT_REQUIREMENT_$type",
                targetType = "PURCHASE_REQUIREMENT", targetId = id,
                occurredAt = at, correlationId = context.correlationId,
            ))
            IdempotentCommandOutcome("PROCUREMENT_REQUIREMENT_$type", """{"id":"$id"}""")
        }
        return PurchaseRequirementDraftReceipt(
            view(context.actor, initialOrder, drafts.byId(id) ?: hidden()), execution.replayed,
        )
    }

    /** Scoped project manager queue. This is NOT a general buyer's inbox. */
    fun projectIntake(actor: UUID, projectId: UUID): List<PurchaseRequirementDraftView> {
        requireEnabled()
        val project = projects.project(projectId, clock.instant()) ?: hidden()
        if (project.projectId != projectId || !projectAuth.canManageProject(actor, project)) hidden()
        return drafts.projectIntake(project.organizationId, projectId).mapNotNull { record ->
            val order = work.workOrder(record.workOrderId)
            if (order != null && order.organizationId == project.organizationId &&
                order.projectId == projectId && record.organizationId == project.organizationId
            ) view(actor, order, record) else null
        }
    }

    /** Requester may retract before technical acceptance. Immutable prior evidence remains. */
    fun withdraw(
        context: ProcurementDraftContext,
        id: UUID,
        request: WithdrawPurchaseRequirementRequest,
    ): PurchaseRequirementDraftReceipt {
        requireEnabled()
        if (context.operationId != request.operationId) invalid()
        val record = drafts.byId(id) ?: hidden()
        val order = authorizedOrder(context.actor, record.workOrderId)
        if (record.organizationId != order.organizationId || record.requestedBy != context.actor) hidden()
        val reason = request.reason?.trim()
        if (request.reason != null && (reason.isNullOrEmpty() || reason.length > 500)) invalid()
        val semantic = listOf(id.toString(), request.expectedVersion.toString(), reason.orEmpty())
            .joinToString("") { "${it.length}:$it" }
        val execution = commands.execute(
            IdempotentCommandSpec(
                context.operationId, context.actor, "PROCUREMENT_REQUIREMENT_WITHDRAWN",
                "PURCHASE_REQUIREMENT", id,
                IdempotencyKeyContract.fingerprint(semantic), context.correlationId,
            ),
        ) {
            if (!drafts.lock(id)) hidden()
            val locked = drafts.byId(id) ?: hidden()
            val source = authorizedOrder(context.actor, locked.workOrderId)
            if (source.organizationId != locked.organizationId || locked.requestedBy != context.actor) hidden()
            val previous = drafts.latestTransition(id)
            val currentVersion = previous?.resultVersion ?: locked.version
            val currentState = previous?.toState ?: "DRAFT"
            if (currentVersion != request.expectedVersion) stale()
            if (currentState !in setOf("DRAFT", "SUBMITTED", "NEEDS_INFO")) ended()
            val now = clock.instant()
            drafts.appendTransition(
                PurchaseRequirementTransitionRecord(
                    transitionId(context.operationId),
                    context.operationId, locked.id, locked.organizationId, currentVersion, currentVersion + 1,
                    "WITHDRAW", currentState, "WITHDRAWN", reason, context.actor, now, context.correlationId,
                    previous?.revised,
                ),
            )
            audit.append(
                AuditEventRecord(
                    actorUserId = context.actor,
                    action = "PROCUREMENT_REQUIREMENT_WITHDRAWN",
                    targetType = "PURCHASE_REQUIREMENT",
                    targetId = id,
                    occurredAt = now,
                    correlationId = context.correlationId,
                ),
            )
            IdempotentCommandOutcome("PROCUREMENT_REQUIREMENT_WITHDRAWN", """{"id":"$id"}""")
        }
        return PurchaseRequirementDraftReceipt(
            view(context.actor, order, drafts.byId(id) ?: hidden()), execution.replayed,
        )
    }

    /**
     * Read-only, live source and stock recheck. A current positive balance is
     * only evidence, never a reservation, approval or permission to buy.
     * Other drafts are strictly same organization + same WorkOrder + same item.
     */
    fun reviewPreview(actor: UUID, id: UUID): PurchaseRequirementReviewPreview {
        requireEnabled()
        val record = drafts.byId(id) ?: hidden()
        val order = authorizedOrder(actor, record.workOrderId, allowManager = true)
        if (record.organizationId != order.organizationId) hidden()
        val latest = drafts.latestTransition(id)
        val state = latest?.toState ?: "DRAFT"
        val spec = effectiveSpec(record, latest)
        val item = stock.item(record.stockItemId)
            ?.takeIf { it.organizationId == record.organizationId && it.active }
        val sourceCurrent = state !in setOf("WITHDRAWN", "REJECTED") &&
            item != null &&
            order.version == spec.workVersion &&
            item.version == spec.itemVersion &&
            item.baseUnitCode == spec.unitCode &&
            reservations.stockUnitActive(record.organizationId, item.baseUnitCode) &&
            acceptsDemand(order.lifecycleState)
        val at = clock.instant()
        val check = when {
            state == "WITHDRAWN" -> StockPreflight("REQUIREMENT_WITHDRAWN", null, at)
            state == "REJECTED" -> StockPreflight("REQUIREMENT_REJECTED", null, at)
            !sourceCurrent -> StockPreflight("SOURCE_CHANGED", null, at)
            else -> assess(actor, order, requireNotNull(item), spec.quantity, at)
        }
        val other = drafts.otherOpenDraftIds(
            order.organizationId, record.workOrderId, record.stockItemId, record.id,
        )
        return PurchaseRequirementReviewPreview(
            requirementId = record.id,
            state = state,
            currentWorkOrderVersion = order.version,
            currentStockItemVersion = item?.version,
            sourceCurrent = sourceCurrent,
            assessmentCode = check.code,
            visibleFreeQuantity = check.free?.toPlainString(),
            assessedAt = check.at,
            otherOpenDraftIds = other.take(20),
            moreOpenDrafts = other.size > 20,
        )
    }

    fun forWork(actor: UUID, workOrderId: UUID): List<PurchaseRequirementDraftView> {
        requireEnabled()
        val order = authorizedOrder(actor, workOrderId, allowManager = true)
        return drafts.forWork(workOrderId).filter { it.organizationId == order.organizationId }
            .map { view(actor, order, it) }
    }

    fun detail(actor: UUID, id: UUID): PurchaseRequirementDraftView {
        requireEnabled()
        val record = drafts.byId(id) ?: hidden()
        val order = authorizedOrder(actor, record.workOrderId, allowManager = true)
        if (record.organizationId != order.organizationId) hidden()
        return view(actor, order, record)
    }

    private fun effectiveSpec(
        root: PurchaseRequirementDraftRecord, latest: PurchaseRequirementTransitionRecord?,
    ): PurchaseRequirementRevision =
        latest?.revised ?: PurchaseRequirementRevision(
            root.requestedQuantity, root.justification, root.sourceWorkVersion, root.stockItemVersion,
            root.unitCode, root.assessmentCode, root.visibleFreeQuantity, root.assessedAt,
        )

    private fun transitionId(operationId: UUID): UUID = UUID.nameUUIDFromBytes(
        "hiltech:procurement:requirement-transition:$operationId".toByteArray(Charsets.UTF_8),
    )

    private fun view(
        actor: UUID, order: WorkOrderSnapshot, record: PurchaseRequirementDraftRecord,
    ): PurchaseRequirementDraftView {
        val latest = drafts.latestTransition(record.id)
        val spec = effectiveSpec(record, latest)
        val state = latest?.toState ?: "DRAFT"
        val item = stock.item(record.stockItemId)
            ?.takeIf { it.organizationId == order.organizationId && it.active }
        val current = state !in setOf("WITHDRAWN", "REJECTED") && item != null &&
            item.version == spec.itemVersion && order.version == spec.workVersion &&
            item.baseUnitCode == spec.unitCode &&
            reservations.stockUnitActive(order.organizationId, spec.unitCode) &&
            acceptsDemand(order.lifecycleState)
        // Historical stock evidence is redacted once a reader loses storage visibility.
        val canReadSnapshot = item != null &&
            assess(actor, order, item, spec.quantity, clock.instant()).code != "WAREHOUSE_REVIEW_REQUIRED"
        val manager = projects.project(order.projectId, clock.instant())?.let {
            it.projectId == order.projectId && it.organizationId == order.organizationId &&
                projectAuth.canManageProject(actor, it)
        } ?: false
        return PurchaseRequirementDraftView(
            record.id, record.workOrderId, record.sourceWorkVersion,
            record.stockItemId, record.stockItemVersion, spec.quantity.toPlainString(),
            spec.unitCode, spec.justification, state,
            if (canReadSnapshot) spec.assessmentCode else "WAREHOUSE_REVIEW_REQUIRED",
            if (canReadSnapshot) spec.freeQuantity?.toPlainString() else null,
            spec.assessedAt, record.requestedBy, record.createdAt, current,
            version = latest?.resultVersion ?: record.version,
            canWithdraw = state in setOf("DRAFT", "SUBMITTED", "NEEDS_INFO") && record.requestedBy == actor,
            canSubmit = state == "DRAFT" && current && record.requestedBy == actor,
            canRevise = state in setOf("DRAFT", "NEEDS_INFO") && record.requestedBy == actor,
            canTechnicalReview = state == "SUBMITTED" && manager,
            lastReviewReason = if (state == "NEEDS_INFO") latest?.reason else null,
        )
    }

    private fun assess(
        actor: UUID, order: WorkOrderSnapshot, item: StockItemView,
        requested: BigDecimal, at: Instant,
    ): StockPreflight {
        val balances = reservations.balances(item.id, at)
        if (balances.isEmpty()) return StockPreflight("WAREHOUSE_REVIEW_REQUIRED", null, at)
        var free = BigDecimal.ZERO
        for (balance in balances) {
            val location = warehouse.storageLocation(balance.location)
                ?: return StockPreflight("WAREHOUSE_REVIEW_REQUIRED", null, at)
            if (location.organizationId != order.organizationId ||
                location.lifecycleState != StorageLocationLifecycle.ACTIVE ||
                (location.activeFrom != null && location.activeFrom > at) ||
                (location.activeUntil != null && location.activeUntil <= at) ||
                (location.projectId != null && location.projectId != order.projectId) ||
                (location.siteId != null && location.siteId != order.siteId) ||
                (location.warehouseId != null &&
                    warehouse.warehouse(location.warehouseId)?.active != true) ||
                !warehouseAuth.canViewStorage(actor, location)
            ) return StockPreflight("WAREHOUSE_REVIEW_REQUIRED", null, at)
            free += balance.available.max(BigDecimal.ZERO)
        }
        return StockPreflight(
            if (free >= requested) "VISIBLE_STOCK_AVAILABLE" else "VISIBLE_STOCK_SHORTAGE",
            free, at,
        )
    }

    private fun authorizedOrder(
        actor: UUID, id: UUID, allowManager: Boolean = false,
    ): WorkOrderSnapshot {
        val order = work.workOrder(id) ?: hidden()
        val project = projects.project(order.projectId, clock.instant()) ?: hidden()
        if (project.projectId != order.projectId || project.organizationId != order.organizationId ||
            (!projectAuth.canRequestResources(actor, project) &&
                !(allowManager && projectAuth.canManageProject(actor, project)))
        ) hidden()
        return order
    }

    private fun managedOrder(actor: UUID, id: UUID): WorkOrderSnapshot {
        val order = work.workOrder(id) ?: hidden()
        val project = projects.project(order.projectId, clock.instant()) ?: hidden()
        if (project.projectId != order.projectId || project.organizationId != order.organizationId ||
            !projectAuth.canManageProject(actor, project)
        ) hidden()
        return order
    }

    private fun matchingItem(order: WorkOrderSnapshot, id: UUID): StockItemView {
        val item = stock.item(id)?.takeIf { it.active && it.organizationId == order.organizationId } ?: hidden()
        if (!reservations.stockUnitActive(order.organizationId, item.baseUnitCode)) hidden()
        return item
    }

    private fun parseQuantity(raw: String): BigDecimal {
        if (!Regex("[0-9]{1,12}(\\.[0-9]{1,6})?").matches(raw)) invalid()
        return BigDecimal(raw).stripTrailingZeros().takeIf { it.signum() > 0 } ?: invalid()
    }

    private fun acceptsDemand(state: WorkOrderLifecycle): Boolean =
        state in setOf(WorkOrderLifecycle.PLANNED, WorkOrderLifecycle.ASSIGNED,
            WorkOrderLifecycle.IN_PROGRESS, WorkOrderLifecycle.BLOCKED)

    private fun requireEnabled() {
        if (!enabled) hidden()
    }
    private fun hidden(): Nothing = throw ProductApiException(
        "PROCUREMENT_UNAVAILABLE", "Purchase requirements are unavailable.", HttpStatus.NOT_FOUND,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "INVALID_PURCHASE_REQUIREMENT", "Enter a valid stock requirement and canonical unit.", HttpStatus.BAD_REQUEST,
    )
    private fun stale(): Nothing = throw ProductApiException(
        "PROCUREMENT_SOURCE_CHANGED", "The work order or stock item changed. Reload first.", HttpStatus.CONFLICT,
    )
    private fun ended(): Nothing = throw ProductApiException(
        "PROCUREMENT_WORK_ENDED", "This work order no longer accepts new requirements.", HttpStatus.CONFLICT,
    )
}
