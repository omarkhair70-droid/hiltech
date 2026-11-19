package com.hiltech.server.warehouse

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
data class RecordStockCountRequest(
    val sessionVersion: Long, val snapshotLineId: UUID, val countedQuantity: String, val unitCode: String,
    val scanSource: String? = null, val notes: String? = null,
)
data class StocktakeSessionResult(
    val session: StocktakeSessionView, val snapshotLines: List<StocktakeSnapshotLineView> = emptyList(),
    val replayed: Boolean,
)
data class StockCountResult(val count: StockCountRecordView, val replayed: Boolean)
data class StocktakeStarted(val sessionId: UUID, val actor: UUID, val snapshotAt: String)
data class StockCountRecorded(val sessionId: UUID, val snapshotLineId: UUID, val countId: UUID, val actor: UUID)

@Service
class StocktakeService(
    private val stocktakes: StocktakePersistence,
    private val stock: StockLedgerPersistence,
    private val warehouse: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
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

    private fun unavailable(): Nothing = throw ProductApiException(
        "STOCKTAKE_UNAVAILABLE", "Stocktake context is unavailable.", HttpStatus.NOT_FOUND)
    private fun stale(): Nothing = throw ProductApiException(
        "STOCKTAKE_VERSION_CONFLICT", "Reload the Stocktake session.", HttpStatus.CONFLICT)
    private fun invalid(): Nothing = throw ProductApiException(
        "INVALID_STOCKTAKE", "Invalid Stocktake input.", HttpStatus.BAD_REQUEST)
    private fun empty(): Nothing = throw ProductApiException(
        "STOCKTAKE_SCOPE_EMPTY", "Stocktake scope has no authoritative Stock balances.", HttpStatus.CONFLICT)
}
