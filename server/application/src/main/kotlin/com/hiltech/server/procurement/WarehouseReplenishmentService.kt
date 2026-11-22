package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.ReservationPersistence
import com.hiltech.server.warehouse.StockItemView
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.warehouse.StorageLocationLifecycle
import com.hiltech.server.warehouse.StorageLocationSnapshot
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import com.hiltech.server.warehouse.WarehousePersistencePort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class CreateWarehouseReplenishmentRequest(
    val operationId: UUID, val baseLocationVersion: Long,
    val stockItemId: UUID, val stockItemVersion: Long,
    val quantity: String, val unitCode: String, val justification: String,
)
data class ReviseWarehouseReplenishmentRequest(
    val operationId: UUID, val expectedVersion: Long, val baseLocationVersion: Long,
    val stockItemVersion: Long, val quantity: String,
    val unitCode: String, val justification: String,
)
data class WarehouseReplenishmentView(
    val id: UUID, val organizationId: UUID, val storageLocationId: UUID,
    val storageLocationVersion: Long, val stockItemId: UUID,
    val stockItemVersion: Long, val requestedQuantity: String,
    val unitCode: String, val justification: String, val state: String,
    val assessmentCode: String, val visibleFreeQuantity: String?,
    val sourceCurrent: Boolean, val version: Long, val requestedBy: UUID,
    val canSubmit: Boolean, val canRevise: Boolean,
    val canWithdraw: Boolean, val canTechnicalReview: Boolean,
    val lastReviewReason: String?,
)
data class WarehouseReplenishmentReceipt(
    val requirement: WarehouseReplenishmentView, val replayed: Boolean,
)

/**
 * Typed S01 Warehouse replenishment. Same immutable procurement requirement
 * root, transitions and RFQ/PO pipeline, but no fabricated WorkOrder/project.
 * Warehouse operator creates/changes their own demand; an independently
 * authorized current Warehouse Manager performs technical source validation.
 */
@Service
class WarehouseReplenishmentService @Autowired constructor(
    private val drafts: PurchaseRequirementDraftPersistence,
    private val warehouse: WarehousePersistencePort,
    private val warehouseAuth: WarehouseAuthorizationPort,
    private val stock: StockLedgerPersistence,
    private val reservations: ReservationPersistence,
    private val sourceVerifier: ProcurementDemandSourceVerifier,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.requirements.enabled:false}")
    private val requirementsEnabled: Boolean = false,
    @Value("\${hiltech.procurement.warehouse-replenishment.enabled:false}")
    private val warehouseEnabled: Boolean = false,
) {
    fun create(ctx: ProcurementDraftContext, storageLocationId: UUID,
               req: CreateWarehouseReplenishmentRequest): WarehouseReplenishmentReceipt {
        available()
        if (ctx.operationId != req.operationId) invalid()
        val source = allowedLocation(ctx.actor, storageLocationId, false)
        val item = checkedItem(source, req.stockItemId)
        val qty = quantity(req.quantity)
        val reason = justification(req.justification)
        if (item.version != req.stockItemVersion ||
            source.version != req.baseLocationVersion ||
            item.baseUnitCode != req.unitCode) stale()
        if (item.serialized && qty.stripTrailingZeros().scale() > 0) invalid()
        val id = deterministic("purchase-requirement", ctx.operationId)
        val fingerprint = fingerprint(storageLocationId, req.baseLocationVersion,
            req.stockItemId, req.stockItemVersion, qty, req.unitCode, reason)
        val executed = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_WAREHOUSE_REQUIREMENT_CREATED",
            "PURCHASE_REQUIREMENT", id, fingerprint, ctx.correlationId,
        )) {
            val currentSource = allowedLocation(ctx.actor, storageLocationId, false)
            val currentItem = checkedItem(currentSource, req.stockItemId)
            if (currentSource.version != req.baseLocationVersion ||
                currentItem.version != req.stockItemVersion ||
                currentItem.baseUnitCode != req.unitCode) stale()
            val at = clock.instant()
            val assessment = assess(ctx.actor, currentSource, currentItem, qty, at)
            drafts.insert(PurchaseRequirementDraftRecord(
                id = id, operationId = ctx.operationId,
                organizationId = currentSource.organizationId,
                workOrderId = null, sourceWorkVersion = null,
                stockItemId = currentItem.id, stockItemVersion = currentItem.version,
                requestedQuantity = qty, unitCode = currentItem.baseUnitCode,
                justification = reason, assessmentCode = assessment.code,
                visibleFreeQuantity = assessment.free, assessedAt = at,
                requestedBy = ctx.actor, createdAt = at,
                correlationId = ctx.correlationId,
                sourceType = "WAREHOUSE_REPLENISHMENT",
                warehouseStorageLocationId = currentSource.storageLocationId,
                warehouseSourceVersion = currentSource.version,
            ))
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor,
                action = "PROCUREMENT_WAREHOUSE_REQUIREMENT_CREATED",
                targetType = "PURCHASE_REQUIREMENT", targetId = id,
                occurredAt = at, correlationId = ctx.correlationId,
            ))
            IdempotentCommandOutcome("WAREHOUSE_REPLENISHMENT_CREATED", """{"id":"$id"}""")
        }
        return WarehouseReplenishmentReceipt(detail(ctx.actor, id), executed.replayed)
    }

    fun detail(actor: UUID, id: UUID): WarehouseReplenishmentView {
        available()
        val root = warehouseRoot(id)
        val sourceId = root.warehouseStorageLocationId ?: hidden()
        val location = allowedLocation(actor, sourceId, null)
        if (root.organizationId != location.organizationId) hidden()
        val previous = drafts.latestTransition(id)
        val state = previous?.toState ?: "DRAFT"
        val qty = previous?.revised?.quantity ?: root.requestedQuantity
        val reason = previous?.revised?.justification ?: root.justification
        val item = stock.item(root.stockItemId)
            ?.takeIf { it.organizationId == root.organizationId && it.active }
        val current = sourceVerifier.isCurrent(root, previous)
        val snapshot = if (item != null)
            assess(actor, location, item, qty, clock.instant())
            else StockPreflight("WAREHOUSE_REVIEW_REQUIRED", null, clock.instant())
        val canRequest = warehouseAuth.canReceiveAt(actor, location)
        val manager = sourceManager(actor, location)
        return WarehouseReplenishmentView(
            root.id, root.organizationId, sourceId,
            previous?.revised?.workVersion ?: root.warehouseSourceVersion ?: 0,
            root.stockItemId, previous?.revised?.itemVersion ?: root.stockItemVersion,
            qty.toPlainString(), previous?.revised?.unitCode ?: root.unitCode,
            reason, state, snapshot.code,
            snapshot.free?.toPlainString(), current,
            previous?.resultVersion ?: root.version, root.requestedBy,
            canRequest && root.requestedBy == actor && state == "DRAFT" && current,
            canRequest && root.requestedBy == actor && state in setOf("DRAFT","NEEDS_INFO"),
            canRequest && root.requestedBy == actor &&
                state in setOf("DRAFT","SUBMITTED","NEEDS_INFO","READY_FOR_SOURCING"),
            manager && state in setOf("SUBMITTED","READY_FOR_SOURCING"),
            if (state == "NEEDS_INFO") previous?.reason else null,
        )
    }

    fun list(actor: UUID, locationId: UUID): List<WarehouseReplenishmentView> {
        available()
        val location = allowedLocation(actor, locationId, null)
        return drafts.forWarehouseLocation(location.organizationId, locationId)
            .map { detail(actor, it.id) }
    }

    fun submit(ctx: ProcurementDraftContext, id: UUID,
               req: SubmitPurchaseRequirementRequest): WarehouseReplenishmentReceipt =
        transition(ctx, id, req.operationId, req.expectedVersion,
            "SUBMIT", "SUBMITTED", null, null)

    fun withdraw(ctx: ProcurementDraftContext, id: UUID,
                 req: WithdrawPurchaseRequirementRequest): WarehouseReplenishmentReceipt =
        transition(ctx, id, req.operationId, req.expectedVersion,
            "WITHDRAW", "WITHDRAWN", req.reason, null)

    fun technicalReview(ctx: ProcurementDraftContext, id: UUID,
                        req: ReviewPurchaseRequirementRequest): WarehouseReplenishmentReceipt {
        val action = when (req.decision) {
            "READY_FOR_SOURCING" -> "TECHNICAL_READY"
            "NEEDS_INFO" -> "RETURN_FOR_INFO"
            "REJECTED" -> "REJECT"
            else -> invalid()
        }
        return transition(ctx, id, req.operationId, req.expectedVersion,
            action, req.decision, req.reason, null)
    }

    fun revise(ctx: ProcurementDraftContext, id: UUID,
               req: ReviseWarehouseReplenishmentRequest): WarehouseReplenishmentReceipt {
        val qty = quantity(req.quantity)
        val reason = justification(req.justification)
        return transition(ctx, id, req.operationId, req.expectedVersion,
            "REVISE", "DRAFT", null, RevisedInput(
                req.baseLocationVersion, req.stockItemVersion,
                qty, req.unitCode, reason,
            ))
    }

    private data class RevisedInput(
        val sourceVersion: Long, val stockItemVersion: Long,
        val qty: BigDecimal, val unit: String, val reason: String,
    )

    private fun transition(
        ctx: ProcurementDraftContext, id: UUID, operationId: UUID,
        expectedVersion: Long, type: String, toState: String,
        rawReason: String?, revisedInput: RevisedInput?,
    ): WarehouseReplenishmentReceipt {
        available()
        if (ctx.operationId != operationId || expectedVersion < 1) invalid()
        val root = warehouseRoot(id)
        val actorIsManager = type in setOf("TECHNICAL_READY","RETURN_FOR_INFO","REJECT")
        val source = allowedLocation(ctx.actor,
            root.warehouseStorageLocationId ?: hidden(), actorIsManager)
        if (source.organizationId != root.organizationId ||
            (!actorIsManager && root.requestedBy != ctx.actor)) hidden()
        val reason = rawReason?.trim()
        if (reason != null && (reason.isBlank() || reason.length > 500)) invalid()
        if (type in setOf("RETURN_FOR_INFO", "REJECT") && reason == null) invalid()
        val fingerprint = fingerprint(id, expectedVersion, type, toState,
            reason, revisedInput?.sourceVersion, revisedInput?.stockItemVersion,
            revisedInput?.qty, revisedInput?.unit, revisedInput?.reason)
        val executed = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_WAREHOUSE_REQUIREMENT_$type",
            "PURCHASE_REQUIREMENT", id, fingerprint, ctx.correlationId,
        )) {
            if (!drafts.lock(id)) hidden()
            val currentRoot = warehouseRoot(id)
            val location = allowedLocation(ctx.actor,
                currentRoot.warehouseStorageLocationId ?: hidden(), actorIsManager)
            if (currentRoot.organizationId != location.organizationId ||
                (!actorIsManager && currentRoot.requestedBy != ctx.actor)) hidden()
            val last = drafts.latestTransition(id)
            val version = last?.resultVersion ?: currentRoot.version
            val state = last?.toState ?: "DRAFT"
            if (version != expectedVersion) stale()
            val allowed = when (type) {
                "SUBMIT" -> state == "DRAFT"
                "REVISE" -> state in setOf("DRAFT","NEEDS_INFO")
                "WITHDRAW" -> state in setOf("DRAFT","SUBMITTED","NEEDS_INFO","READY_FOR_SOURCING")
                "TECHNICAL_READY" -> state == "SUBMITTED"
                "RETURN_FOR_INFO", "REJECT" -> state in setOf("SUBMITTED","READY_FOR_SOURCING")
                else -> false
            }
            if (!allowed) stale()
            val item = checkedItem(location, currentRoot.stockItemId)
            val at = clock.instant()
            val revision = when (type) {
                "REVISE" -> {
                    val input = revisedInput ?: invalid()
                    if (location.version != input.sourceVersion ||
                        item.version != input.stockItemVersion ||
                        item.baseUnitCode != input.unit ||
                        (item.serialized && input.qty.stripTrailingZeros().scale() > 0)
                    ) stale()
                    val snapshot = assess(ctx.actor, location, item, input.qty, at)
                    PurchaseRequirementRevision(
                        input.qty, input.reason, location.version, item.version,
                        item.baseUnitCode, snapshot.code, snapshot.free, snapshot.at,
                    )
                }
                else -> last?.revised
            }
            if (type == "SUBMIT" || type == "TECHNICAL_READY") {
                if (!sourceVerifier.isCurrent(currentRoot, last)) stale()
                val spec = last?.revised
                assess(ctx.actor, location, item,
                    spec?.quantity ?: currentRoot.requestedQuantity, at)
            }
            drafts.appendTransition(PurchaseRequirementTransitionRecord(
                id = deterministic("requirement-transition", ctx.operationId),
                operationId = ctx.operationId, requirementId = id,
                organizationId = currentRoot.organizationId,
                sourceVersion = version, resultVersion = version + 1,
                type = type, fromState = state, toState = toState,
                reason = reason, actor = ctx.actor, at = at,
                correlationId = ctx.correlationId, revised = revision,
            ))
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor,
                action = "PROCUREMENT_WAREHOUSE_REQUIREMENT_$type",
                targetType = "PURCHASE_REQUIREMENT", targetId = id,
                occurredAt = at, correlationId = ctx.correlationId,
            ))
            IdempotentCommandOutcome(
                "WAREHOUSE_REQUIREMENT_$type", """{"id":"$id"}""",
            )
        }
        return WarehouseReplenishmentReceipt(detail(ctx.actor, id), executed.replayed)
    }

    private fun warehouseRoot(id: UUID): PurchaseRequirementDraftRecord =
        drafts.byId(id)?.takeIf {
            it.sourceType == "WAREHOUSE_REPLENISHMENT" &&
                it.warehouseStorageLocationId != null &&
                it.workOrderId == null
        } ?: hidden()

    /**
     * Need submitter: current canReceiveAt (location/team/organization).
     * Technical reviewer: a current manager of its actual parent warehouse.
     * Read-only: either role; never enumerate a different organization.
     */
    private fun allowedLocation(actor: UUID, id: UUID, manager: Boolean?): StorageLocationSnapshot {
        val now = clock.instant()
        val location = warehouse.storageLocation(id) ?: hidden()
        val facility = location.warehouseId?.let { warehouse.warehouse(it) } ?: hidden()
        if (location.organizationId != facility.organizationId || !facility.active ||
            location.lifecycleState != StorageLocationLifecycle.ACTIVE ||
            (location.activeFrom != null && location.activeFrom > now) ||
            (location.activeUntil != null && location.activeUntil <= now) ||
            location.projectId != null || location.siteId != null
        ) hidden()
        val canRequest = warehouseAuth.canReceiveAt(actor, location)
        val canReview = warehouseAuth.canManageWarehouse(actor, facility)
        if (when (manager) {
            true -> !canReview
            false -> !canRequest
            null -> !(canRequest || canReview)
        }) hidden()
        return location
    }

    private fun sourceManager(actor: UUID, location: StorageLocationSnapshot): Boolean =
        location.warehouseId?.let { warehouse.warehouse(it) }
            ?.let { warehouseAuth.canManageWarehouse(actor, it) } == true

    private fun checkedItem(source: StorageLocationSnapshot, itemId: UUID): StockItemView {
        val item = stock.item(itemId)
            ?.takeIf { it.active && it.organizationId == source.organizationId }
            ?: hidden()
        if (!reservations.stockUnitActive(source.organizationId, item.baseUnitCode)) hidden()
        return item
    }

    private fun assess(actor: UUID, location: StorageLocationSnapshot,
                       item: StockItemView, qty: BigDecimal, at: Instant): StockPreflight {
        if (!warehouseAuth.canViewStorage(actor, location))
            return StockPreflight("WAREHOUSE_REVIEW_REQUIRED", null, at)
        val exact = reservations.balances(item.id, at)
            .singleOrNull { it.location == location.storageLocationId }
            ?: return StockPreflight("WAREHOUSE_REVIEW_REQUIRED", null, at)
        val available = exact.available.max(BigDecimal.ZERO)
        return StockPreflight(
            if (available >= qty) "VISIBLE_STOCK_AVAILABLE" else "VISIBLE_STOCK_SHORTAGE",
            available, at,
        )
    }

    private fun quantity(raw: String): BigDecimal {
        if (!Regex("[0-9]{1,12}(\\.[0-9]{1,6})?").matches(raw)) invalid()
        return BigDecimal(raw).stripTrailingZeros().takeIf { it.signum() > 0 } ?: invalid()
    }
    private fun justification(raw: String): String = raw.trim().takeIf {
        it.length in 12..500 && it.none { c -> c.code < 32 }
    } ?: invalid()
    private fun fingerprint(vararg fields: Any?): String {
        val canonical = fields.joinToString("") {
            val v = it?.toString() ?: "(null)"
            "${v.length}:$v"
        }
        return IdempotencyKeyContract.fingerprint(canonical)
    }
    private fun deterministic(domain: String, op: UUID): UUID = UUID.nameUUIDFromBytes(
        "hiltech:procurement:$domain:$op".toByteArray(Charsets.UTF_8),
    )
    private fun available() { if (!requirementsEnabled || !warehouseEnabled) hidden() }
    private fun hidden(): Nothing = throw ProductApiException(
        "PROCUREMENT_WAREHOUSE_REPLENISHMENT_UNAVAILABLE",
        "No authorized Warehouse replenishment source is available.", HttpStatus.NOT_FOUND,
    )
    private fun stale(): Nothing = throw ProductApiException(
        "PROCUREMENT_WAREHOUSE_SOURCE_CHANGED",
        "Warehouse, requirement or StockItem changed; reload its current version.",
        HttpStatus.CONFLICT,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "INVALID_WAREHOUSE_REPLENISHMENT",
        "Invalid canonical Warehouse replenishment quantity, unit or source.",
        HttpStatus.BAD_REQUEST,
    )
}
