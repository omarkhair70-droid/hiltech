package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.AssetPassportPersistence
import com.hiltech.server.warehouse.ReservationPersistence
import com.hiltech.server.warehouse.StockItemView
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.warehouse.StorageLocationSnapshot
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class CreateAssetPurchaseNeedRequest(
    val operationId: UUID,
    val assetVersion: Long,
    val incidentId: UUID? = null,
    val maintenanceId: UUID? = null,
    val issueVersion: Long,
    val deliveryStorageLocationId: UUID,
    val deliveryLocationVersion: Long,
    val stockItemId: UUID,
    val stockItemVersion: Long,
    val quantity: String,
    val unitCode: String,
    val justification: String,
)
data class ReviseAssetPurchaseNeedRequest(
    val operationId: UUID, val expectedVersion: Long,
    val assetVersion: Long, val stockItemVersion: Long,
    val quantity: String, val unitCode: String, val justification: String,
)
data class AssetPurchaseNeedView(
    val id: UUID, val organizationId: UUID, val assetId: UUID,
    val assetCode: String, val assetVersion: Long,
    val issueType: String, val issueId: UUID, val issueVersion: Long,
    val deliveryStorageLocationId: UUID,
    val stockItemId: UUID, val stockItemVersion: Long,
    val requestedQuantity: String, val unitCode: String,
    val justification: String, val state: String, val version: Long,
    val assessmentCode: String, val visibleFreeQuantity: String?,
    val sourceCurrent: Boolean, val requestedBy: UUID,
    val canSubmit: Boolean, val canRevise: Boolean,
    val canWithdraw: Boolean, val canTechnicalReview: Boolean,
    val lastReviewReason: String?,
)
data class AssetPurchaseNeedReceipt(val need: AssetPurchaseNeedView, val replayed: Boolean)

/**
 * Spare-part / replacement STOCK procurement sourced from genuine equipment
 * events. External repair SERVICES and registering a new asset are separate
 * domains, not re-labelled StockItems or fabricated WorkOrders.
 */
@Service
class AssetPurchaseNeedService @Autowired constructor(
    private val drafts: PurchaseRequirementDraftPersistence,
    private val sources: AssetPurchaseSource,
    private val assetRepository: AssetPassportPersistence,
    private val stock: StockLedgerPersistence,
    private val reservations: ReservationPersistence,
    private val warehouseAuth: WarehouseAuthorizationPort,
    private val verifier: ProcurementDemandSourceVerifier,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.requirements.enabled:false}")
    private val requirementsEnabled: Boolean = false,
    @Value("\${hiltech.procurement.asset-parts.enabled:false}")
    private val assetPartsEnabled: Boolean = false,
) {
    fun create(ctx: ProcurementDraftContext, assetId: UUID,
               input: CreateAssetPurchaseNeedRequest): AssetPurchaseNeedReceipt {
        requireEnabled()
        if (ctx.operationId != input.operationId ||
            (input.incidentId == null) == (input.maintenanceId == null)
        ) invalid()
        val resolved = checkedSource(ctx.actor, assetId, input, false)
        val item = checkedItem(resolved, input.stockItemId)
        val requested = quantity(input.quantity, item)
        val reason = justification(input.justification)
        if (input.stockItemVersion != item.version ||
            input.unitCode != item.baseUnitCode
        ) stale()
        val id = unique("purchase-requirement", ctx.operationId)
        val fp = fingerprint(assetId, input.assetVersion, input.incidentId,
            input.maintenanceId, input.issueVersion, input.deliveryStorageLocationId,
            input.deliveryLocationVersion, item.id, item.version,
            requested, item.baseUnitCode, reason)
        val receipt = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_ASSET_NEED_CREATED",
            "PURCHASE_REQUIREMENT", id, fp, ctx.correlationId,
        )) {
            // Serialize competing demands on the real asset; no shadow ledger.
            val lockedAsset = assetRepository.asset(assetId, lock = true) ?: hidden()
            val current = checkedSource(ctx.actor, assetId, input, false)
            if (lockedAsset.organizationId != current.asset.organizationId ||
                lockedAsset.version != input.assetVersion) stale()
            val currentItem = checkedItem(current, item.id)
            if (currentItem.version != input.stockItemVersion ||
                currentItem.baseUnitCode != input.unitCode) stale()
            // The same still-open equipment issue and StockItem must not
            // generate two live purchase needs in concurrent sessions.
            val duplicate = drafts.hasOpenAssetNeed(
                current.asset.organizationId, assetId,
                input.incidentId, input.maintenanceId, item.id,
            )
            if (duplicate) throw ProductApiException(
                "ASSET_PURCHASE_NEED_ALREADY_OPEN",
                "An active purchase need already exists for this equipment issue and stock item.",
                HttpStatus.CONFLICT,
            )
            val at = clock.instant()
            val preflight = assess(ctx.actor, current.delivery, currentItem, requested, at)
            drafts.insert(PurchaseRequirementDraftRecord(
                id = id, operationId = ctx.operationId,
                organizationId = current.asset.organizationId,
                workOrderId = null, sourceWorkVersion = null,
                stockItemId = currentItem.id, stockItemVersion = currentItem.version,
                requestedQuantity = requested, unitCode = currentItem.baseUnitCode,
                justification = reason, assessmentCode = preflight.code,
                visibleFreeQuantity = preflight.free, assessedAt = at,
                requestedBy = ctx.actor, createdAt = at, correlationId = ctx.correlationId,
                sourceType = "ASSET_MAINTENANCE_OR_REPLACEMENT",
                assetId = assetId, assetSourceVersion = input.assetVersion,
                assetIncidentId = input.incidentId, assetMaintenanceId = input.maintenanceId,
                assetIssueVersion = input.issueVersion,
                assetDeliveryLocationId = input.deliveryStorageLocationId,
                assetDeliveryLocationVersion = input.deliveryLocationVersion,
            ))
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor, action = "PROCUREMENT_ASSET_NEED_CREATED",
                targetType = "PURCHASE_REQUIREMENT", targetId = id,
                occurredAt = at, correlationId = ctx.correlationId,
            ))
            IdempotentCommandOutcome("ASSET_NEED_CREATED", """{"id":"$id"}""")
        }
        return AssetPurchaseNeedReceipt(detail(ctx.actor, id), receipt.replayed)
    }

    fun list(actor: UUID, assetId: UUID): List<AssetPurchaseNeedView> {
        requireEnabled()
        sources.authorizedAsset(actor, assetId, null) ?: hidden()
        return drafts.forAsset(assetId).map { detail(actor, it.id) }
    }

    fun detail(actor: UUID, id: UUID): AssetPurchaseNeedView {
        requireEnabled()
        val root = assetRoot(id)
        val asset = sources.authorizedAsset(actor, root.assetId ?: hidden(), null) ?: hidden()
        if (root.organizationId != asset.organizationId) hidden()
        val last = drafts.latestTransition(id)
        val state = last?.toState ?: "DRAFT"
        val qty = last?.revised?.quantity ?: root.requestedQuantity
        val itemVersion = last?.revised?.itemVersion ?: root.stockItemVersion
        val unit = last?.revised?.unitCode ?: root.unitCode
        val reason = last?.revised?.justification ?: root.justification
        val locationId = root.assetDeliveryLocationId ?: hidden()
        val context = sources.resolve(asset.id, root.assetIncidentId,
            root.assetMaintenanceId, locationId)
        val current = verifier.isCurrent(root, last)
        val item = stock.item(root.stockItemId)?.takeIf {
            it.active && it.organizationId == root.organizationId
        }
        val preflight = if (context != null && item != null)
            assess(actor, context.delivery, item, qty, clock.instant())
        else StockPreflight("WAREHOUSE_REVIEW_REQUIRED", null, clock.instant())
        val request = context != null && sources.canAccess(actor, context, false)
        val manager = context != null && sources.canAccess(actor, context, true)
        val own = root.requestedBy == actor
        return AssetPurchaseNeedView(
            id, root.organizationId, asset.id, asset.assetCode,
            last?.revised?.workVersion ?: root.assetSourceVersion ?: 0,
            if (root.assetIncidentId != null) "INCIDENT" else "MAINTENANCE",
            root.assetIncidentId ?: root.assetMaintenanceId ?: hidden(),
            root.assetIssueVersion ?: 0, locationId,
            root.stockItemId, itemVersion, qty.toPlainString(), unit, reason,
            state, last?.resultVersion ?: root.version,
            preflight.code, preflight.free?.toPlainString(),
            current, root.requestedBy,
            request && own && current && state == "DRAFT",
            request && own && context != null && state in setOf("DRAFT","NEEDS_INFO"),
            request && own && state in setOf("DRAFT","SUBMITTED","NEEDS_INFO","READY_FOR_SOURCING"),
            manager && !own && state in setOf("SUBMITTED","READY_FOR_SOURCING"),
            if (state == "NEEDS_INFO") last?.reason else null,
        )
    }

    fun submit(ctx: ProcurementDraftContext, id: UUID,
               req: SubmitPurchaseRequirementRequest) =
        transition(ctx, id, req.operationId, req.expectedVersion,
            "SUBMIT", "SUBMITTED", null, null)

    fun withdraw(ctx: ProcurementDraftContext, id: UUID,
                 req: WithdrawPurchaseRequirementRequest) =
        transition(ctx, id, req.operationId, req.expectedVersion,
            "WITHDRAW", "WITHDRAWN", req.reason, null)

    fun review(ctx: ProcurementDraftContext, id: UUID,
               req: ReviewPurchaseRequirementRequest): AssetPurchaseNeedReceipt {
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
               req: ReviseAssetPurchaseNeedRequest): AssetPurchaseNeedReceipt {
        requireEnabled()
        val root = assetRoot(id)
        val item = stock.item(root.stockItemId) ?: hidden()
        return transition(ctx, id, req.operationId, req.expectedVersion,
            "REVISE", "DRAFT", null, RevisionInput(
                req.assetVersion, req.stockItemVersion,
                quantity(req.quantity, item), req.unitCode,
                justification(req.justification),
            ))
    }

    private data class RevisionInput(
        val assetVersion: Long, val itemVersion: Long,
        val quantity: BigDecimal, val unit: String, val reason: String,
    )

    private fun transition(
        ctx: ProcurementDraftContext, id: UUID, operationId: UUID,
        expectedVersion: Long, type: String, target: String,
        rawReason: String?, revised: RevisionInput?,
    ): AssetPurchaseNeedReceipt {
        requireEnabled()
        if (ctx.operationId != operationId || expectedVersion <= 0) invalid()
        val root = assetRoot(id)
        val manager = type in setOf("TECHNICAL_READY", "RETURN_FOR_INFO", "REJECT")
        val asset = sources.authorizedAsset(ctx.actor, root.assetId ?: hidden(), manager)
            ?: hidden()
        if (asset.organizationId != root.organizationId ||
            (!manager && root.requestedBy != ctx.actor) ||
            (manager && root.requestedBy == ctx.actor)
        ) hidden()
        val reason = rawReason?.trim()
        if (reason != null && (reason.isEmpty() || reason.length > 500)) invalid()
        if (type in setOf("RETURN_FOR_INFO","REJECT") && reason == null) invalid()
        val fp = fingerprint(id, expectedVersion, type, target,
            reason, revised?.assetVersion, revised?.itemVersion,
            revised?.quantity, revised?.unit, revised?.reason)
        val execution = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_ASSET_NEED_$type",
            "PURCHASE_REQUIREMENT", id, fp, ctx.correlationId,
        )) {
            if (!drafts.lock(id)) hidden()
            val locked = assetRoot(id)
            val currentAsset = sources.authorizedAsset(ctx.actor,
                locked.assetId ?: hidden(), manager) ?: hidden()
            if (locked.organizationId != currentAsset.organizationId ||
                (!manager && locked.requestedBy != ctx.actor) ||
                (manager && locked.requestedBy == ctx.actor)
            ) hidden()
            val last = drafts.latestTransition(id)
            val from = last?.toState ?: "DRAFT"
            val version = last?.resultVersion ?: locked.version
            if (expectedVersion != version) stale()
            val allowed = when (type) {
                "SUBMIT" -> from == "DRAFT"
                "REVISE" -> from in setOf("DRAFT","NEEDS_INFO")
                "WITHDRAW" -> from in setOf("DRAFT","SUBMITTED","NEEDS_INFO","READY_FOR_SOURCING")
                "TECHNICAL_READY" -> from == "SUBMITTED"
                "RETURN_FOR_INFO", "REJECT" -> from in setOf("SUBMITTED","READY_FOR_SOURCING")
                else -> false
            }
            if (!allowed) stale()
            val context = sources.resolve(
                currentAsset.id, locked.assetIncidentId,
                locked.assetMaintenanceId, locked.assetDeliveryLocationId ?: hidden(),
            )
            val at = clock.instant()
            val item = stock.item(locked.stockItemId)?.takeIf {
                it.active && it.organizationId == locked.organizationId
            }
            val revision = if (type == "REVISE") {
                val input = revised ?: invalid()
                if (context == null || item == null ||
                    currentAsset.version != input.assetVersion ||
                    locked.assetIssueVersion != context.issueVersion ||
                    context.delivery.version != locked.assetDeliveryLocationVersion ||
                    item.version != input.itemVersion || item.baseUnitCode != input.unit
                ) stale()
                val actualQty = quantity(input.quantity.toPlainString(), item)
                val snapshot = assess(ctx.actor, context.delivery, item, actualQty, at)
                PurchaseRequirementRevision(actualQty, input.reason,
                    currentAsset.version, item.version, item.baseUnitCode,
                    snapshot.code, snapshot.free, snapshot.at,
                )
            } else last?.revised
            if (type in setOf("SUBMIT","TECHNICAL_READY") &&
                !verifier.isCurrent(locked, last)
            ) stale()
            drafts.appendTransition(PurchaseRequirementTransitionRecord(
                id = unique("requirement-transition", ctx.operationId),
                operationId = ctx.operationId, requirementId = id,
                organizationId = locked.organizationId,
                sourceVersion = version, resultVersion = version + 1,
                type = type, fromState = from, toState = target, reason = reason,
                actor = ctx.actor, at = at, correlationId = ctx.correlationId,
                revised = revision,
            ))
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor, action = "PROCUREMENT_ASSET_NEED_$type",
                targetType = "PURCHASE_REQUIREMENT", targetId = id,
                occurredAt = at, correlationId = ctx.correlationId,
            ))
            IdempotentCommandOutcome("ASSET_NEED_$type", """{"id":"$id"}""")
        }
        return AssetPurchaseNeedReceipt(detail(ctx.actor, id), execution.replayed)
    }

    private fun checkedSource(actor: UUID, assetId: UUID,
                              input: CreateAssetPurchaseNeedRequest,
                              manager: Boolean?): AssetPartSource {
        val context = sources.resolve(assetId, input.incidentId, input.maintenanceId,
            input.deliveryStorageLocationId) ?: hidden()
        if (context.asset.version != input.assetVersion ||
            context.issueVersion != input.issueVersion ||
            context.delivery.version != input.deliveryLocationVersion
        ) stale()
        if (!sources.canAccess(actor, context, manager)) hidden()
        return context
    }

    private fun assetRoot(id: UUID): PurchaseRequirementDraftRecord =
        drafts.byId(id)?.takeIf {
            it.sourceType == "ASSET_MAINTENANCE_OR_REPLACEMENT" &&
                it.assetId != null && it.workOrderId == null
        } ?: hidden()

    private fun checkedItem(source: AssetPartSource, stockItemId: UUID): StockItemView {
        val item = stock.item(stockItemId)?.takeIf {
            it.active && it.organizationId == source.asset.organizationId
        } ?: hidden()
        if (!reservations.stockUnitActive(item.organizationId, item.baseUnitCode)) hidden()
        return item
    }

    private fun assess(actor: UUID, dest: StorageLocationSnapshot,
                       item: StockItemView, quantity: BigDecimal,
                       at: Instant): StockPreflight {
        if (!warehouseAuth.canViewStorage(actor, dest))
            return StockPreflight("WAREHOUSE_REVIEW_REQUIRED", null, at)
        val balance = reservations.balances(item.id, at)
            .singleOrNull { it.location == dest.storageLocationId }
            ?: return StockPreflight("WAREHOUSE_REVIEW_REQUIRED", null, at)
        val free = balance.available.max(BigDecimal.ZERO)
        return StockPreflight(
            if (free >= quantity) "VISIBLE_STOCK_AVAILABLE" else "VISIBLE_STOCK_SHORTAGE",
            free, at,
        )
    }

    private fun quantity(value: String, item: StockItemView): BigDecimal {
        if (!Regex("[0-9]{1,12}(\\.[0-9]{1,6})?").matches(value)) invalid()
        val number = BigDecimal(value).stripTrailingZeros()
        if (number.signum() <= 0 || (item.serialized && number.scale() > 0))
            invalid()
        return number
    }
    private fun justification(value: String): String = value.trim().takeIf {
        it.length in 12..500 && it.none { c -> c.code < 32 }
    } ?: invalid()
    private fun fingerprint(vararg fields: Any?): String =
        IdempotencyKeyContract.fingerprint(fields.joinToString("") {
            val s = it?.toString() ?: "(null)"
            "${s.length}:$s"
        })
    private fun unique(domain: String, op: UUID): UUID = UUID.nameUUIDFromBytes(
        "hiltech:procurement:$domain:$op".toByteArray(Charsets.UTF_8),
    )
    private fun requireEnabled() {
        if (!requirementsEnabled || !assetPartsEnabled) hidden()
    }
    private fun hidden(): Nothing = throw ProductApiException(
        "ASSET_PURCHASE_NEED_UNAVAILABLE",
        "No authorized equipment-service purchase source is available.",
        HttpStatus.NOT_FOUND,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "ASSET_PURCHASE_NEED_INVALID",
        "Choose an active asset issue, a real warehouse, a stock item and valid quantity.",
        HttpStatus.BAD_REQUEST,
    )
    private fun stale(): Nothing = throw ProductApiException(
        "ASSET_PURCHASE_NEED_SOURCE_CHANGED",
        "Equipment, issue, stock item or delivery Warehouse changed; reload and review.",
        HttpStatus.CONFLICT,
    )
}
