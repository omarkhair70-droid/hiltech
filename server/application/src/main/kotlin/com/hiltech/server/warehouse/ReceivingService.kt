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

data class ReceivingCommandContext(val operationId: UUID, val actor: UUID, val correlationId: String)
data class CreateReceivingRequest(
    val organizationId: UUID, val destinationStorageLocationId: UUID, val sourceType: ReceivingSourceType,
    val sourceRef: String? = null, val deliveryDocumentRef: String? = null,
)
data class AddReceivingStockLineRequest(
    val recordVersion: Long, val stockItemId: UUID, val stockItemVersion: Long,
    val candidateClass: ReceivingCandidateClass, val expectedQuantity: String? = null,
    val unitCode: String, val sourceLineRef: String? = null,
)
data class ReceiveStockLineRequest(
    val recordVersion: Long, val lineVersion: Long, val stockItemVersion: Long,
    val quantity: String, val unitCode: String, val condition: AssetCondition, val serials: List<String> = emptyList(),
)
data class CompleteReceivingRequest(val recordVersion: Long)
data class ReceivingRecordResult(val record: ReceivingRecordView, val replayed: Boolean)
data class ReceivingLineResult(val line: ReceivingLineView, val replayed: Boolean)
data class ReceivingStockReceipt(val line: ReceivingLineView, val movement: StockMovementView, val replayed: Boolean)
data class ReceivingStockReceived(
    val receivingId: UUID, val lineId: UUID, val movementId: UUID, val stockItemId: UUID, val actor: UUID,
)

@Service
class ReceivingService(
    private val receiving: ReceivingPersistence,
    private val stock: StockLedgerPersistence,
    private val warehouse: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun create(context: ReceivingCommandContext, request: CreateReceivingRequest): ReceivingRecordResult {
        val destination = requireDestination(context.actor, request.organizationId, request.destinationStorageLocationId)
        val source = optionalText(request.sourceRef, 255)
        val document = optionalText(request.deliveryDocumentRef, 255)
        val id = UUID.nameUUIDFromBytes(("hiltech:receiving:" + context.operationId).toByteArray(Charsets.UTF_8))
        val replayed = execute(context, "RECEIVING_CREATED", "RECEIVING", id,
            request.organizationId, destination.storageLocationId, request.sourceType, source, document) {
            requireDestination(context.actor, request.organizationId, request.destinationStorageLocationId)
            receiving.insertRecord(id, request.organizationId, destination.storageLocationId, request.sourceType,
                source, document, context.actor, clock.instant(), context.correlationId)
        }
        return ReceivingRecordResult(receiving.record(id) ?: unavailable(), replayed)
    }

    fun addStockLine(context: ReceivingCommandContext, recordId: UUID,
        request: AddReceivingStockLineRequest): ReceivingLineResult {
        val visible = receiving.record(recordId) ?: unavailable()
        requireDestination(context.actor, visible.organizationId, visible.destinationStorageLocationId)
        val expected = request.expectedQuantity?.let(::quantity)
        val lineId = UUID.nameUUIDFromBytes(("hiltech:receiving-line:" + context.operationId).toByteArray(Charsets.UTF_8))
        val sourceLine = optionalText(request.sourceLineRef, 255)
        val replayed = execute(context, "RECEIVING_STOCK_LINE_ADDED", "RECEIVING_LINE", lineId,
            recordId, request.recordVersion, request.stockItemId, request.stockItemVersion,
            request.candidateClass, expected?.toPlainString(), request.unitCode, sourceLine) {
            val record = receiving.record(recordId, lock = true) ?: unavailable()
            if (record.version != request.recordVersion ||
                record.state !in listOf(ReceivingState.DRAFT, ReceivingState.IN_PROGRESS)) stale()
            requireDestination(context.actor, record.organizationId, record.destinationStorageLocationId)
            val item = stock.item(request.stockItemId, lock = true)?.takeIf {
                it.active && it.organizationId == record.organizationId && it.version == request.stockItemVersion
            } ?: stale()
            val requiredClass = when (item.classification) {
                StockClassification.STOCK_QUANTITY -> ReceivingCandidateClass.STOCK_QUANTITY
                StockClassification.STOCK_SERIALIZED -> ReceivingCandidateClass.STOCK_SERIALIZED
            }
            if (request.candidateClass != requiredClass || request.unitCode != item.baseUnitCode) invalid()
            receiving.insertLine(lineId, record, requiredClass, item.id, expected, item.baseUnitCode, sourceLine,
                context.actor, clock.instant())
            if (!receiving.touchInProgress(record.id, record.version, clock.instant())) stale()
        }
        return ReceivingLineResult(receiving.line(lineId) ?: unavailable(), replayed)
    }

    fun receiveStockLine(context: ReceivingCommandContext, recordId: UUID, lineId: UUID,
        request: ReceiveStockLineRequest): ReceivingStockReceipt {
        val visible = receiving.record(recordId) ?: unavailable()
        requireDestination(context.actor, visible.organizationId, visible.destinationStorageLocationId)
        val amount = quantity(request.quantity)
        val serials = request.serials.map { serial ->
            serial.trim().takeIf { it.length in 1..160 } ?: invalid()
        }
        if (serials.distinct().size != serials.size || request.condition == AssetCondition.UNKNOWN) invalid()
        val replayed = execute(context, "RECEIVING_STOCK_RECEIVED", "RECEIVING_LINE", lineId,
            recordId, request.recordVersion, lineId, request.lineVersion, request.stockItemVersion,
            amount.toPlainString(), request.unitCode, request.condition, serials) {
            val record = receiving.record(recordId, lock = true) ?: unavailable()
            if (record.version != request.recordVersion || record.state != ReceivingState.IN_PROGRESS) stale()
            requireDestination(context.actor, record.organizationId, record.destinationStorageLocationId)
            val line = receiving.line(lineId, lock = true)?.takeIf { it.receivingRecordId == record.id } ?: unavailable()
            if (line.version != request.lineVersion || line.receivedAt != null) stale()
            val itemId = line.expectedStockItemId ?: invalid()
            val item = stock.item(itemId, lock = true)?.takeIf {
                it.active && it.organizationId == record.organizationId && it.version == request.stockItemVersion
            } ?: stale()
            if (request.unitCode != item.baseUnitCode || line.unitCode != item.baseUnitCode) invalid()
            when (item.classification) {
                StockClassification.STOCK_QUANTITY -> if (serials.isNotEmpty() ||
                    line.candidateClass != ReceivingCandidateClass.STOCK_QUANTITY) invalid()
                StockClassification.STOCK_SERIALIZED -> {
                    if (line.candidateClass != ReceivingCandidateClass.STOCK_SERIALIZED ||
                        amount.stripTrailingZeros().scale() > 0 ||
                        BigDecimal(serials.size).compareTo(amount) != 0) invalid()
                }
            }
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            if (!receiving.markLineReceived(line.id, line.version, amount, request.condition, serials, context.actor, now)) stale()
            if (!receiving.touchInProgress(record.id, record.version, now)) stale()
            val movement = stock.appendReceiving(item, context.operationId, context.actor, amount,
                record.destinationStorageLocationId, line.id, now, now, context.correlationId)
            events.publishEvent(ReceivingStockReceived(record.id, line.id, movement, item.id, context.actor))
        }
        return ReceivingStockReceipt(
            receiving.line(lineId) ?: unavailable(),
            stock.movementForReceivingLine(lineId) ?: unavailable(),
            replayed,
        )
    }

    fun complete(context: ReceivingCommandContext, id: UUID, request: CompleteReceivingRequest): ReceivingRecordResult {
        val visible = receiving.record(id) ?: unavailable()
        requireDestination(context.actor, visible.organizationId, visible.destinationStorageLocationId)
        val replayed = execute(context, "RECEIVING_COMPLETED", "RECEIVING", id, request.recordVersion) {
            val record = receiving.record(id, lock = true) ?: unavailable()
            if (record.version != request.recordVersion || record.state != ReceivingState.IN_PROGRESS) stale()
            requireDestination(context.actor, record.organizationId, record.destinationStorageLocationId)
            val lines = receiving.lines(record.id)
            if (lines.isEmpty() || lines.any { it.receivedAt == null }) incomplete()
            val target = if (receiving.hasDiscrepancy(record.id))
                ReceivingState.RECEIVED_WITH_DISCREPANCY else ReceivingState.RECEIVED
            if (!receiving.complete(record.id, record.version, target, context.actor,
                    clock.instant().truncatedTo(ChronoUnit.MICROS))) stale()
        }
        return ReceivingRecordResult(receiving.record(id) ?: unavailable(), replayed)
    }

    private fun requireDestination(actor: UUID, org: UUID, id: UUID): StorageLocationSnapshot {
        val now = clock.instant()
        return warehouse.storageLocation(id)?.takeIf {
            it.organizationId == org && it.lifecycleState == StorageLocationLifecycle.ACTIVE &&
                (it.activeFrom == null || it.activeFrom <= now) && (it.activeUntil == null || it.activeUntil > now) &&
                (it.warehouseId == null || warehouse.warehouse(it.warehouseId)?.active == true) &&
                authorization.canReceiveAt(actor, it)
        } ?: unavailable()
    }

    private fun execute(context: ReceivingCommandContext, action: String, targetType: String, target: UUID,
        vararg fields: Any?, write: () -> Unit): Boolean = try {
        commands.execute(IdempotentCommandSpec(
            context.operationId, context.actor, action, targetType, target,
            warehouseCommandFingerprint(*fields), context.correlationId,
        )) {
            write()
            audit.append(AuditEventRecord(
                actorUserId = context.actor, action = action, targetType = targetType, targetId = target,
                occurredAt = clock.instant(), correlationId = context.correlationId,
            ))
            IdempotentCommandOutcome(action, """{"targetId":"$target"}""")
        }.replayed
    } catch (_: DataIntegrityViolationException) {
        throw ProductApiException("RECEIVING_CONFLICT", "Receiving source truth changed.", HttpStatus.CONFLICT)
    }

    private fun quantity(raw: String): BigDecimal = runCatching { BigDecimal(raw.trim()) }.getOrNull()?.let {
        if (it.signum() <= 0 || it.scale() > 6 || it.precision() > 20) invalid() else it
    } ?: invalid()

    private fun optionalText(raw: String?, max: Int): String? = raw?.trim()?.takeIf { it.isNotEmpty() }?.also {
        if (it.length > max) invalid()
    }

    private fun unavailable(): Nothing = throw ProductApiException(
        "RECEIVING_UNAVAILABLE", "Receiving context is unavailable.", HttpStatus.NOT_FOUND)
    private fun stale(): Nothing = throw ProductApiException(
        "RECEIVING_VERSION_CONFLICT", "Reload the Receiving record.", HttpStatus.CONFLICT)
    private fun invalid(): Nothing = throw ProductApiException(
        "INVALID_RECEIVING", "Invalid Receiving input.", HttpStatus.BAD_REQUEST)
    private fun incomplete(): Nothing = throw ProductApiException(
        "RECEIVING_INCOMPLETE", "Every Receiving line must be physically received first.", HttpStatus.CONFLICT)
}
