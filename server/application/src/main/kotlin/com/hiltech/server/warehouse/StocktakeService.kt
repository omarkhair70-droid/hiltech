package com.hiltech.server.warehouse

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

data class StocktakeCommandContext(val operationId: UUID, val actor: UUID, val correlationId: String)
data class CreateStocktakeRequest(
    val organizationId: UUID, val warehouseId: UUID, val rootStorageLocationId: UUID,
    val categoryIds: List<UUID> = emptyList(), val stockItemIds: List<UUID> = emptyList(),
)
data class StartStocktakeRequest(val sessionVersion: Long)
data class SubmitStocktakeRequest(val sessionVersion: Long)
data class RequestInventoryAdjustmentRequest(val discrepancyVersion: Long, val reason: String)
data class ApplyInventoryAdjustmentRequest(val requestVersion: Long)
data class RecordStockCountRequest(
    val sessionVersion: Long, val snapshotLineId: UUID, val countedQuantity: String, val unitCode: String,
    val scanSource: String? = null, val notes: String? = null,
)
data class StocktakeSessionResult(
    val session: StocktakeSessionView, val snapshotLines: List<StocktakeSnapshotLineView> = emptyList(),
    val replayed: Boolean,
)
data class StockCountResult(val count: StockCountRecordView, val replayed: Boolean)
data class StocktakeSubmitResult(
    val session: StocktakeSessionView, val discrepancies: List<InventoryDiscrepancyView>, val replayed: Boolean,
)
data class InventoryAdjustmentRequestResult(val request: InventoryAdjustmentRequestView, val replayed: Boolean)
data class InventoryAdjustmentApplyResult(
    val request: InventoryAdjustmentRequestView,
    val movement: StockMovementView,
    val session: StocktakeSessionView,
    val replayed: Boolean,
)
data class StocktakeDetailResult(
    val session: StocktakeSessionView,
    val snapshotLines: List<StocktakeSnapshotLineView>,
    val countRecords: List<StockCountRecordView>,
    val discrepancies: List<InventoryDiscrepancyView>,
    val adjustmentRequests: List<InventoryAdjustmentRequestView>,
)
data class StocktakeVariancesResult(
    val session: StocktakeSessionView,
    val discrepancies: List<InventoryDiscrepancyView>,
    val adjustmentRequests: List<InventoryAdjustmentRequestView>,
)
data class StocktakeStarted(val sessionId: UUID, val actor: UUID, val snapshotAt: String)
data class StockCountRecorded(val sessionId: UUID, val snapshotLineId: UUID, val countId: UUID, val actor: UUID)
data class StocktakeSubmitted(val sessionId: UUID, val actor: UUID, val discrepancyCount: Int)
data class InventoryAdjustmentRequested(
    val requestId: UUID, val discrepancyId: UUID, val sessionId: UUID, val actor: UUID,
)
data class InventoryAdjustmentApplied(
    val requestId: UUID, val discrepancyId: UUID, val movementId: UUID, val sessionId: UUID, val actor: UUID,
)

@Service
class StocktakeService(
    private val stocktakes: StocktakePersistence,
    private val stock: StockLedgerPersistence,
    private val warehouse: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val approvals: ApprovalEngineService,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun create(context: StocktakeCommandContext, request: CreateStocktakeRequest): StocktakeSessionResult {
        requireCountScope(context.actor, request.organizationId, request.warehouseId, request.rootStorageLocationId)
        val categories = request.categoryIds.distinct().also { if (it.size > 200) invalid() }
        val items = request.stockItemIds.distinct().also { if (it.size > 500) invalid() }
        val now = clock.instant()
        val activeCategories = stock.categories(request.organizationId, now).map { it.id }.toSet()
        if (categories.any { it !in activeCategories }) invalid()
        if (items.any { id -> stock.item(id)?.let { !it.active || it.organizationId != request.organizationId } != false }) invalid()
        val id = UUID.nameUUIDFromBytes(("hiltech:stocktake:" + context.operationId).toByteArray(Charsets.UTF_8))
        val replayed = execute(context, "STOCKTAKE_CREATED", "STOCKTAKE", id,
            request.organizationId, request.warehouseId, request.rootStorageLocationId, categories, items) {
            requireCountScope(context.actor, request.organizationId, request.warehouseId, request.rootStorageLocationId)
            stocktakes.insertSession(id, request.organizationId, request.warehouseId, request.rootStorageLocationId,
                categories, items, context.actor, clock.instant(), context.correlationId)
        }
        return StocktakeSessionResult(stocktakes.session(id) ?: unavailable(), replayed = replayed)
    }

    fun start(context: StocktakeCommandContext, id: UUID, request: StartStocktakeRequest): StocktakeSessionResult {
        val visible = stocktakes.session(id) ?: unavailable()
        requireCountScope(context.actor, visible.organizationId, visible.warehouseId,
            visible.rootStorageLocationId ?: unavailable())
        val replayed = execute(context, "STOCKTAKE_STARTED", "STOCKTAKE", id, request.sessionVersion) {
            val session = stocktakes.session(id, lock = true) ?: unavailable()
            if (session.version != request.sessionVersion || session.state != StocktakeState.DRAFT) stale()
            requireCountScope(context.actor, session.organizationId, session.warehouseId,
                session.rootStorageLocationId ?: unavailable())
            val at = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val candidates = stocktakes.snapshotCandidates(session, at)
            if (candidates.isEmpty()) empty()
            candidates.forEach { row ->
                val location = warehouse.storageLocation(row.storageLocationId)?.takeIf {
                    it.organizationId == session.organizationId && it.warehouseId == session.warehouseId &&
                        authorization.canCountAt(context.actor, it)
                } ?: unavailable()
                val lineId = UUID.nameUUIDFromBytes(
                    ("hiltech:stocktake-snapshot:" + session.id + ":" + row.stockItemId + ":" + location.storageLocationId)
                        .toByteArray(Charsets.UTF_8),
                )
                stocktakes.insertSnapshot(lineId, session, row, at)
            }
            if (!stocktakes.activate(session.id, session.version, at)) stale()
            events.publishEvent(StocktakeStarted(session.id, context.actor, at.toString()))
        }
        val session = stocktakes.session(id) ?: unavailable()
        return StocktakeSessionResult(session, stocktakes.snapshotLines(id), replayed)
    }

    fun recordCount(context: StocktakeCommandContext, id: UUID, request: RecordStockCountRequest): StockCountResult {
        val visible = stocktakes.session(id) ?: unavailable()
        if (visible.version != request.sessionVersion || visible.state != StocktakeState.ACTIVE) stale()
        val line = stocktakes.snapshotLine(request.snapshotLineId)?.takeIf { it.sessionId == id } ?: unavailable()
        val location = warehouse.storageLocation(line.storageLocationId)?.takeIf {
            it.organizationId == visible.organizationId && it.warehouseId == visible.warehouseId &&
                authorization.canCountAt(context.actor, it)
        } ?: unavailable()
        if (request.unitCode != line.unitCode) invalid()
        val amount = quantity(request.countedQuantity)
        val scan = optionalText(request.scanSource, 255)
        val notes = optionalText(request.notes, 2000)
        val countId = UUID.nameUUIDFromBytes(("hiltech:stock-count:" + context.operationId).toByteArray(Charsets.UTF_8))
        val replayed = execute(context, "STOCK_COUNT_RECORDED", "STOCK_COUNT", countId,
            id, request.sessionVersion, request.snapshotLineId, amount.toPlainString(), request.unitCode, scan, notes) {
            val session = stocktakes.session(id, lock = true) ?: unavailable()
            if (session.version != request.sessionVersion || session.state != StocktakeState.ACTIVE) stale()
            warehouse.storageLocation(location.storageLocationId)?.takeIf {
                it.organizationId == session.organizationId && authorization.canCountAt(context.actor, it)
            } ?: unavailable()
            val currentLine = stocktakes.snapshotLine(request.snapshotLineId)?.takeIf { it.sessionId == session.id } ?: unavailable()
            if (currentLine.unitCode != request.unitCode) invalid()
            val latest = stocktakes.latestCount(currentLine.id)
            val at = clock.instant().truncatedTo(ChronoUnit.MICROS)
            stocktakes.insertCount(countId, context.operationId, session, currentLine, amount, context.actor,
                at, scan, notes, latest?.id, context.correlationId)
            events.publishEvent(StockCountRecorded(session.id, currentLine.id, countId, context.actor))
        }
        return StockCountResult(stocktakes.count(countId) ?: unavailable(), replayed)
    }

    fun submit(context: StocktakeCommandContext, id: UUID, request: SubmitStocktakeRequest): StocktakeSubmitResult {
        val visible = stocktakes.session(id) ?: unavailable()
        requireCountScope(context.actor, visible.organizationId, visible.warehouseId,
            visible.rootStorageLocationId ?: unavailable())
        val replayed = execute(context, "STOCKTAKE_SUBMITTED", "STOCKTAKE", id, request.sessionVersion) {
            val session = stocktakes.session(id, lock = true) ?: unavailable()
            if (session.version != request.sessionVersion || session.state != StocktakeState.ACTIVE) stale()
            requireCountScope(context.actor, session.organizationId, session.warehouseId,
                session.rootStorageLocationId ?: unavailable())
            val lines = stocktakes.snapshotLines(session.id)
            if (lines.isEmpty()) empty()
            val latestCounts = lines.associateWith { stocktakes.latestCount(it.id) }
            if (latestCounts.values.any { it == null }) incomplete()
            val at = clock.instant().truncatedTo(ChronoUnit.MICROS)
            var discrepancyCount = 0
            latestCounts.forEach { (line, countOrNull) ->
                val count = requireNotNull(countOrNull)
                val counted = BigDecimal(count.countedQuantity)
                val expected = BigDecimal(line.expectedOnHand)
                val variance = counted.subtract(expected)
                val current = stock.balances(line.stockItemId)
                    .singleOrNull { it.storageLocationId == line.storageLocationId } ?: unavailable()
                if (variance.compareTo(BigDecimal.ZERO) != 0) {
                    val discrepancyId = UUID.nameUUIDFromBytes(
                        ("hiltech:inventory-discrepancy:" + session.id + ":" + line.id).toByteArray(Charsets.UTF_8),
                    )
                    stocktakes.insertDiscrepancy(
                        discrepancyId, session, line, count, counted, variance, current.version,
                        current.version != line.balanceVersion, context.actor, at,
                    )
                    discrepancyCount += 1
                }
            }
            if (!stocktakes.submit(session.id, session.version, context.actor, at)) stale()
            events.publishEvent(StocktakeSubmitted(session.id, context.actor, discrepancyCount))
        }
        return StocktakeSubmitResult(
            stocktakes.session(id) ?: unavailable(), stocktakes.discrepancies(id), replayed,
        )
    }

    fun detail(actor: UUID, id: UUID): StocktakeDetailResult {
        val session = stocktakes.session(id) ?: unavailable()
        requireCountScope(
            actor, session.organizationId, session.warehouseId,
            session.rootStorageLocationId ?: unavailable(),
        )
        return StocktakeDetailResult(
            session = session,
            snapshotLines = stocktakes.snapshotLines(id),
            countRecords = stocktakes.countsForSession(id),
            discrepancies = stocktakes.discrepancies(id),
            adjustmentRequests = stocktakes.adjustmentRequests(id).map(::canonicalAdjustment),
        )
    }

    fun variances(actor: UUID, id: UUID): StocktakeVariancesResult {
        val session = stocktakes.session(id) ?: unavailable()
        requireCountScope(
            actor, session.organizationId, session.warehouseId,
            session.rootStorageLocationId ?: unavailable(),
        )
        return StocktakeVariancesResult(
            session = session,
            discrepancies = stocktakes.discrepancies(id),
            adjustmentRequests = stocktakes.adjustmentRequests(id).map(::canonicalAdjustment),
        )
    }

    fun requestAdjustment(
        context: StocktakeCommandContext, discrepancyId: UUID, request: RequestInventoryAdjustmentRequest,
    ): InventoryAdjustmentRequestResult {
        val visible = stocktakes.discrepancy(discrepancyId) ?: unavailable()
        val session = stocktakes.session(visible.sessionId) ?: unavailable()
        val facility = warehouse.warehouse(session.warehouseId)?.takeIf {
            it.active && it.organizationId == visible.organizationId &&
                authorization.canRequestAdjustment(context.actor, it)
        } ?: unavailable()
        if (visible.sourceChangedSinceSnapshot) reconciliationRequired()
        val line = stocktakes.snapshotLine(visible.snapshotLineId)?.takeIf {
            it.sessionId == session.id && it.organizationId == facility.organizationId &&
                it.stockItemId == visible.stockItemId && it.storageLocationId == visible.storageLocationId
        } ?: unavailable()
        val reason = requiredText(request.reason, 2000)
        val requestId = UUID.nameUUIDFromBytes(
            ("hiltech:inventory-adjustment-request:" + context.operationId).toByteArray(Charsets.UTF_8),
        )
        val replayed = execute(
            context, "INVENTORY_ADJUSTMENT_REQUESTED", "INVENTORY_ADJUSTMENT_REQUEST", requestId,
            discrepancyId, request.discrepancyVersion, visible.varianceQuantity, line.unitCode, reason,
        ) {
            val current = stocktakes.discrepancy(discrepancyId, lock = true) ?: unavailable()
            if (current.version != request.discrepancyVersion ||
                current.state !in setOf("OPEN", "INVESTIGATING")) stale()
            if (current.sourceChangedSinceSnapshot) reconciliationRequired()
            val currentSession = stocktakes.session(current.sessionId) ?: unavailable()
            val currentFacility = warehouse.warehouse(currentSession.warehouseId)?.takeIf {
                it.active && it.organizationId == current.organizationId &&
                    authorization.canRequestAdjustment(context.actor, it)
            } ?: unavailable()
            val currentLine = stocktakes.snapshotLine(current.snapshotLineId)?.takeIf {
                it.sessionId == currentSession.id && it.organizationId == currentFacility.organizationId &&
                    it.stockItemId == current.stockItemId && it.storageLocationId == current.storageLocationId
            } ?: unavailable()
            val delta = BigDecimal(current.varianceQuantity)
            if (delta.compareTo(BigDecimal.ZERO) == 0) invalid()
            val at = clock.instant().truncatedTo(ChronoUnit.MICROS)
            stocktakes.insertAdjustmentRequest(
                requestId, current, currentLine.unitCode, reason, context.actor, at,
            )
            if (!stocktakes.markAdjustmentRequested(current.id, current.version, at)) stale()
            events.publishEvent(
                InventoryAdjustmentRequested(requestId, current.id, current.sessionId, context.actor),
            )
        }
        var stored = stocktakes.adjustmentRequest(requestId) ?: unavailable()
        var subject = adjustmentSubject(stored)
        var status = approvals.subjectStatus(subject, INVENTORY_ADJUSTMENT_POLICY_KEY)
        if (status == null) {
            val approvalOperationId = UUID.nameUUIDFromBytes(
                ("hiltech:inventory-adjustment-approval:" + stored.id).toByteArray(Charsets.UTF_8),
            )
            val approval = approvals.requestException(
                actorUserId = context.actor,
                operationId = approvalOperationId,
                subject = subject,
                policyKey = INVENTORY_ADJUSTMENT_POLICY_KEY,
                reasonCode = INVENTORY_ADJUSTMENT_REASON_CODE,
                safeReasonSummary = stored.reason,
                correlationId = context.correlationId,
                allowAuthorityBypass = false,
            )
            val approvalId = approval.approvalRequestId
                ?.takeIf { approval.approvalRequired && approval.state == ApprovalRequestState.PENDING }
                ?: approvalUnavailable()
            if (!stocktakes.linkApproval(stored.id, approvalId)) approvalUnavailable()
            stored = stocktakes.adjustmentRequest(requestId) ?: unavailable()
            subject = adjustmentSubject(stored)
            status = approvals.subjectStatus(subject, INVENTORY_ADJUSTMENT_POLICY_KEY)
                ?: approvalUnavailable()
        } else if (!stocktakes.linkApproval(stored.id, status.approvalRequestId)) {
            approvalUnavailable()
        }
        return InventoryAdjustmentRequestResult(canonicalAdjustment(stored), replayed)
    }

    fun applyAdjustment(
        context: StocktakeCommandContext,
        id: UUID,
        request: ApplyInventoryAdjustmentRequest,
    ): InventoryAdjustmentApplyResult {
        val visible = stocktakes.adjustmentRequest(id) ?: unavailable()
        val visibleSession = stocktakes.session(visible.sessionId) ?: unavailable()
        warehouse.warehouse(visibleSession.warehouseId)?.takeIf {
            it.active && it.organizationId == visible.organizationId &&
                authorization.canApproveAdjustment(context.actor, it)
        } ?: unavailable()

        val replayed = execute(
            context,
            "INVENTORY_ADJUSTMENT_APPLIED",
            "INVENTORY_ADJUSTMENT_REQUEST",
            id,
            request.requestVersion,
        ) {
            val current = stocktakes.adjustmentRequest(id, lock = true) ?: unavailable()
            if (current.version != request.requestVersion || current.appliedMovementId != null) stale()

            val session = stocktakes.session(current.sessionId, lock = true) ?: unavailable()
            val facility = warehouse.warehouse(session.warehouseId)?.takeIf {
                it.active && it.organizationId == current.organizationId &&
                    authorization.canApproveAdjustment(context.actor, it)
            } ?: unavailable()
            val discrepancy = stocktakes.discrepancy(current.discrepancyId, lock = true) ?: unavailable()
            if (discrepancy.state != "ADJUSTMENT_REQUESTED" ||
                discrepancy.sourceChangedSinceSnapshot
            ) reconciliationRequired()

            val approvalId = current.approvalRequestId ?: approvalRequired()
            val approval = approvals.subjectStatus(
                adjustmentSubject(current),
                INVENTORY_ADJUSTMENT_POLICY_KEY,
            ) ?: approvalRequired()
            if (approval.approvalRequestId != approvalId ||
                approval.state != ApprovalRequestState.APPROVED
            ) approvalRequired()

            val item = stock.item(current.stockItemId)?.takeIf {
                it.active && it.organizationId == current.organizationId &&
                    it.baseUnitCode == current.unitCode
            } ?: unavailable()
            val location = warehouse.storageLocation(current.storageLocationId)?.takeIf {
                it.organizationId == current.organizationId &&
                    it.warehouseId == facility.warehouseId &&
                    it.lifecycleState == StorageLocationLifecycle.ACTIVE &&
                    (it.activeFrom == null || it.activeFrom <= clock.instant()) &&
                    (it.activeUntil == null || it.activeUntil > clock.instant())
            } ?: unavailable()

            val delta = BigDecimal(current.deltaQuantity)
            if (delta.compareTo(BigDecimal(discrepancy.varianceQuantity)) != 0) stale()
            val balance = stock.adjustmentBalance(item.id, location.storageLocationId, lock = true)
                ?: unavailable()
            if (balance.version != discrepancy.currentBalanceVersion) reconciliationRequired()
            val absolute = delta.abs()
            if (delta.signum() < 0 &&
                balance.onHand.subtract(balance.reserved).subtract(balance.damaged).subtract(balance.quarantine)
                    .compareTo(absolute) < 0
            ) negativeStock()

            val at = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val movementId = stock.appendAdjustment(
                item = item,
                operationId = context.operationId,
                actor = context.actor,
                delta = delta,
                locationId = location.storageLocationId,
                adjustmentRequestId = current.id,
                occurredAt = at,
                now = at,
                correlationId = context.correlationId,
            )
            if (!stocktakes.markApplied(current.id, current.version, movementId, context.actor, at)) stale()
            if (!stocktakes.closeDiscrepancy(discrepancy.id, discrepancy.version, at)) stale()
            if (!stocktakes.reconcileSession(session.id, session.version, context.actor, at)) stale()
            events.publishEvent(
                InventoryAdjustmentApplied(current.id, discrepancy.id, movementId, session.id, context.actor),
            )
        }

        val applied = stocktakes.adjustmentRequest(id) ?: unavailable()
        val movement = applied.appliedMovementId?.let(stock::movement)
            ?: stock.movementForAdjustmentRequest(id)
            ?: unavailable()
        val session = stocktakes.session(applied.sessionId) ?: unavailable()
        return InventoryAdjustmentApplyResult(canonicalAdjustment(applied), movement, session, replayed)
    }

    private fun adjustmentSubject(request: InventoryAdjustmentRequestView) = ApprovalSubjectRef(
        organizationId = request.organizationId,
        subjectType = INVENTORY_ADJUSTMENT_SUBJECT_TYPE,
        subjectId = request.id,
        subjectVersion = request.version,
    )

    private fun canonicalAdjustment(request: InventoryAdjustmentRequestView): InventoryAdjustmentRequestView {
        if (request.appliedMovementId != null) {
            return request.copy(approvalStatus = "APPROVED")
        }
        val status = approvals.subjectStatus(adjustmentSubject(request), INVENTORY_ADJUSTMENT_POLICY_KEY)
        return request.copy(
            approvalRequestId = status?.approvalRequestId ?: request.approvalRequestId,
            approvalStatus = status?.state?.name ?: request.approvalStatus,
        )
    }

    private fun requireCountScope(actor: UUID, org: UUID, warehouseId: UUID, rootId: UUID): StorageLocationSnapshot {
        val facility = warehouse.warehouse(warehouseId)?.takeIf { it.active && it.organizationId == org } ?: unavailable()
        return warehouse.storageLocation(rootId)?.takeIf {
            it.organizationId == org && it.warehouseId == facility.warehouseId &&
                it.lifecycleState == StorageLocationLifecycle.ACTIVE &&
                (it.activeFrom == null || it.activeFrom <= clock.instant()) &&
                (it.activeUntil == null || it.activeUntil > clock.instant()) &&
                authorization.canCountAt(actor, it)
        } ?: unavailable()
    }

    private fun execute(context: StocktakeCommandContext, action: String, targetType: String, targetId: UUID,
        vararg fields: Any?, write: () -> Unit): Boolean = try {
        commands.execute(IdempotentCommandSpec(
            context.operationId, context.actor, action, targetType, targetId,
            warehouseCommandFingerprint(*fields), context.correlationId,
        )) {
            write()
            audit.append(AuditEventRecord(
                actorUserId = context.actor, action = action, targetType = targetType, targetId = targetId,
                occurredAt = clock.instant(), correlationId = context.correlationId,
            ))
            IdempotentCommandOutcome(action, """{"targetId":"$targetId"}""")
        }.replayed
    } catch (_: DataIntegrityViolationException) {
        throw ProductApiException("STOCKTAKE_CONFLICT", "Stocktake source truth changed.", HttpStatus.CONFLICT)
    }

    private fun quantity(raw: String): BigDecimal = runCatching { BigDecimal(raw.trim()) }.getOrNull()?.let {
        if (it.signum() < 0 || it.scale() > 6 || it.precision() > 20) invalid() else it
    } ?: invalid()

    private fun optionalText(raw: String?, max: Int): String? = raw?.trim()?.takeIf { it.isNotEmpty() }?.also {
        if (it.length > max) invalid()
    }
    private fun requiredText(raw: String, max: Int): String = optionalText(raw, max) ?: invalid()

    private fun unavailable(): Nothing = throw ProductApiException(
        "STOCKTAKE_UNAVAILABLE", "Stocktake context is unavailable.", HttpStatus.NOT_FOUND)
    private fun stale(): Nothing = throw ProductApiException(
        "STOCKTAKE_VERSION_CONFLICT", "Reload the Stocktake session.", HttpStatus.CONFLICT)
    private fun invalid(): Nothing = throw ProductApiException(
        "INVALID_STOCKTAKE", "Invalid Stocktake input.", HttpStatus.BAD_REQUEST)
    private fun empty(): Nothing = throw ProductApiException(
        "STOCKTAKE_SCOPE_EMPTY", "Stocktake scope has no authoritative Stock balances.", HttpStatus.CONFLICT)
    private fun incomplete(): Nothing = throw ProductApiException(
        "STOCKTAKE_INCOMPLETE", "Every Stocktake snapshot line requires a physical count.", HttpStatus.CONFLICT)
    private fun reconciliationRequired(): Nothing = throw ProductApiException(
        "STOCKTAKE_RECONCILIATION_REQUIRED",
        "Authoritative inventory changed after the Stocktake snapshot; reconcile before requesting adjustment.",
        HttpStatus.CONFLICT,
    )
    private fun approvalUnavailable(): Nothing = throw ProductApiException(
        "INVENTORY_ADJUSTMENT_APPROVAL_UNAVAILABLE",
        "Inventory adjustment approval is not currently configured or available.",
        HttpStatus.SERVICE_UNAVAILABLE,
    )
    private fun approvalRequired(): Nothing = throw ProductApiException(
        "INVENTORY_ADJUSTMENT_APPROVAL_REQUIRED",
        "This exact Inventory Adjustment request requires canonical approval.",
        HttpStatus.CONFLICT,
    )
    private fun negativeStock(): Nothing = throw ProductApiException(
        "INVENTORY_ADJUSTMENT_NEGATIVE_STOCK",
        "The approved adjustment would consume Stock that is reserved, damaged, quarantined or unavailable.",
        HttpStatus.CONFLICT,
    )
}
