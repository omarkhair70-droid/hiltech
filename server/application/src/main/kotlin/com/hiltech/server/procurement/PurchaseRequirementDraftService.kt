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
)

data class PurchaseRequirementDraftReceipt(val draft: PurchaseRequirementDraftView, val replayed: Boolean)

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

    /** A requester can retract their own unsubmitted draft; root evidence stays immutable. */
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
            if (currentState != "DRAFT") ended()
            val now = clock.instant()
            drafts.appendTransition(
                PurchaseRequirementTransitionRecord(
                    UUID.nameUUIDFromBytes(
                        "hiltech:procurement:requirement-transition:${context.operationId}".toByteArray(Charsets.UTF_8),
                    ),
                    context.operationId, locked.id, locked.organizationId, currentVersion, currentVersion + 1,
                    "WITHDRAW", "DRAFT", "WITHDRAWN", reason, context.actor, now, context.correlationId,
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

    fun forWork(actor: UUID, workOrderId: UUID): List<PurchaseRequirementDraftView> {
        requireEnabled()
        val order = authorizedOrder(actor, workOrderId)
        return drafts.forWork(workOrderId).filter { it.organizationId == order.organizationId }
            .map { view(actor, order, it) }
    }

    fun detail(actor: UUID, id: UUID): PurchaseRequirementDraftView {
        requireEnabled()
        val record = drafts.byId(id) ?: hidden()
        val order = authorizedOrder(actor, record.workOrderId)
        if (record.organizationId != order.organizationId) hidden()
        return view(actor, order, record)
    }

    private fun view(
        actor: UUID, order: WorkOrderSnapshot, record: PurchaseRequirementDraftRecord,
    ): PurchaseRequirementDraftView {
        val item = stock.item(record.stockItemId)
        val latest = drafts.latestTransition(record.id)
        val effectiveState = latest?.toState ?: "DRAFT"
        val current = effectiveState == "DRAFT" && item != null && item.active && item.organizationId == order.organizationId &&
            item.version == record.stockItemVersion && order.version == record.sourceWorkVersion &&
            acceptsDemand(order.lifecycleState)
        // Never disclose an earlier quantitative snapshot after warehouse visibility is revoked.
        val canReadSnapshot = item != null && assess(
            actor, order, item, record.requestedQuantity, clock.instant(),
        ).code != "WAREHOUSE_REVIEW_REQUIRED"
        return PurchaseRequirementDraftView(
            record.id, record.workOrderId, record.sourceWorkVersion,
            record.stockItemId, record.stockItemVersion, record.requestedQuantity.toPlainString(),
            record.unitCode, record.justification, effectiveState,
            if (canReadSnapshot) record.assessmentCode else "WAREHOUSE_REVIEW_REQUIRED",
            if (canReadSnapshot) record.visibleFreeQuantity?.toPlainString() else null,
            record.assessedAt, record.requestedBy, record.createdAt, current,
            version = latest?.resultVersion ?: record.version,
            canWithdraw = effectiveState == "DRAFT" && record.requestedBy == actor,
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

    private fun authorizedOrder(actor: UUID, id: UUID): WorkOrderSnapshot {
        val order = work.workOrder(id) ?: hidden()
        val project = projects.project(order.projectId, clock.instant()) ?: hidden()
        if (project.projectId != order.projectId || project.organizationId != order.organizationId ||
            !projectAuth.canRequestResources(actor, project)
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
