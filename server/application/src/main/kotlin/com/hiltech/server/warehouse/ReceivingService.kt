package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.procurement.ProcurementPoReceivingGuard
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
data class AddReceivingAssetLineRequest(
    val recordVersion: Long, val assetTypeId: UUID, val sourceLineRef: String? = null,
)
data class ReceiveAssetLineRequest(
    val recordVersion: Long, val lineVersion: Long, val assetCode: String, val name: String,
    val manufacturer: String? = null, val model: String? = null, val serialNumber: String? = null,
    val ownershipType: String, val condition: AssetCondition, val tagType: String? = null,
)
data class AddReceivingDiscrepancyRequest(
    val recordVersion: Long, val lineId: UUID? = null, val type: ReceivingDiscrepancyType, val description: String,
)
data class RejectPoReceivingStockLineRequest(
    val recordVersion: Long, val lineVersion: Long,
    val quantity: String, val type: ReceivingDiscrepancyType, val reason: String,
)
data class CompleteReceivingRequest(val recordVersion: Long)
data class ReceivingRecordResult(val record: ReceivingRecordView, val replayed: Boolean)
data class ReceivingLineResult(val line: ReceivingLineView, val replayed: Boolean)
data class ReceivingStockReceipt(val line: ReceivingLineView, val movement: StockMovementView, val replayed: Boolean)
data class ReceivingAssetReceipt(
    val line: ReceivingLineView, val asset: AssetIdentityView, val movementId: UUID, val tag: AssetTagView?, val replayed: Boolean,
)
data class ReceivingDiscrepancyResult(
    val discrepancy: ReceivingDiscrepancyView, val record: ReceivingRecordView, val replayed: Boolean,
)
data class ReceivingDiscrepancyRecorded(
    val receivingId: UUID, val discrepancyId: UUID, val lineId: UUID?, val type: ReceivingDiscrepancyType, val actor: UUID,
)
data class ReceivingStockReceived(
    val receivingId: UUID, val lineId: UUID, val movementId: UUID, val stockItemId: UUID, val actor: UUID,
)

@Service
class ReceivingService(
    private val receiving: ReceivingPersistence,
    private val stock: StockLedgerPersistence,
    private val assetWriter: ReceivingAssetWriter,
    private val warehouse: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    private val poReceiving: ProcurementPoReceivingGuard,
) {
    fun directory(actor: UUID, warehouseId: UUID, afterUpdatedAt: String? = null,
        afterId: UUID? = null): ReceivingDirectoryView {
        val facility = warehouse.warehouse(warehouseId)?.takeIf { it.active } ?: unavailable()
        val supplied = listOf(afterUpdatedAt, afterId).count { it != null }
        if (supplied !in listOf(0, 2)) invalid()
        val after = afterUpdatedAt?.let { runCatching { java.time.Instant.parse(it) }.getOrElse { invalid() } }
        val visible = warehouse.storageLocations(facility.organizationId, null)
            .filter { it.warehouseId == facility.warehouseId && authorization.canReceiveAt(actor, it) }
            .map { it.storageLocationId }
        return receiving.directory(facility.organizationId, visible, after, afterId)
    }

    fun detail(actor: UUID, id: UUID): ReceivingDetailView {
        val record = receiving.record(id) ?: unavailable()
        requireVisible(actor, record)
        return ReceivingDetailView(
            record, receiving.lines(id), receiving.discrepancies(id),
            receiving.poRejectedLineIds(id),
            receiving.hasReadyPoDeliveryNote(id, record.organizationId),
        )
    }

    fun create(context: ReceivingCommandContext, request: CreateReceivingRequest): ReceivingRecordResult {
        val destination = requireDestination(context.actor, request.organizationId, request.destinationStorageLocationId)
        val source = optionalText(request.sourceRef, 255)
        val document = optionalText(request.deliveryDocumentRef, 255)
        val id = UUID.nameUUIDFromBytes(("hiltech:receiving:" + context.operationId).toByteArray(Charsets.UTF_8))
        val replayed = execute(context, "RECEIVING_CREATED", "RECEIVING", id,
            request.organizationId, destination.storageLocationId, request.sourceType, source, document) {
            requireDestination(context.actor, request.organizationId, request.destinationStorageLocationId)
            val now = clock.instant()
            receiving.insertRecord(id, request.organizationId, destination.storageLocationId, request.sourceType,
                source, document, context.actor, now, context.correlationId)
            poReceiving.linkNewRecord(id, request.organizationId, destination.storageLocationId,
                request.sourceType, source, context.actor, now, context.correlationId)
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
            poReceiving.requireStockLine(record, item.id, item.baseUnitCode, expected)
            receiving.insertLine(lineId, record, requiredClass, item.id, expected, item.baseUnitCode, sourceLine,
                context.actor, clock.instant())
            if (!receiving.touchInProgress(record.id, record.version, clock.instant())) stale()
        }
        return ReceivingLineResult(receiving.line(lineId) ?: unavailable(), replayed)
    }

    fun addAssetLine(context: ReceivingCommandContext, recordId: UUID,
        request: AddReceivingAssetLineRequest): ReceivingLineResult {
        val visible = receiving.record(recordId) ?: unavailable()
        requireDestination(context.actor, visible.organizationId, visible.destinationStorageLocationId)
        val sourceLine = optionalText(request.sourceLineRef, 255)
        val lineId = UUID.nameUUIDFromBytes(("hiltech:receiving-line:" + context.operationId).toByteArray(Charsets.UTF_8))
        val replayed = execute(context, "RECEIVING_ASSET_LINE_ADDED", "RECEIVING_LINE", lineId,
            recordId, request.recordVersion, request.assetTypeId, sourceLine) {
            val record = receiving.record(recordId, lock = true) ?: unavailable()
            if (record.version != request.recordVersion ||
                record.state !in listOf(ReceivingState.DRAFT, ReceivingState.IN_PROGRESS)) stale()
            requireDestination(context.actor, record.organizationId, record.destinationStorageLocationId)
            poReceiving.requireStockOnly(record)
            assetWriter.requireActiveType(record.organizationId, request.assetTypeId)
            receiving.insertAssetLine(lineId, record, request.assetTypeId, sourceLine, context.actor, clock.instant())
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
            if (line.version != request.lineVersion || line.receivedAt != null ||
                receiving.hasPoRejection(line.id)) stale()
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
            // Damaged/unfit PO goods must be rejected with discrepancy evidence;
            // accepting them into StockLedger would inflate usable inventory.
            if (poReceiving.isGovernedPo(record) &&
                request.condition in setOf(AssetCondition.DAMAGED, AssetCondition.UNFIT)
            ) invalid()
            poReceiving.requireStockReceipt(record, item.id, item.baseUnitCode, amount)
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

    fun receiveAssetLine(context: ReceivingCommandContext, recordId: UUID, lineId: UUID,
        request: ReceiveAssetLineRequest): ReceivingAssetReceipt {
        val visible = receiving.record(recordId) ?: unavailable()
        requireDestination(context.actor, visible.organizationId, visible.destinationStorageLocationId)
        if (request.condition == AssetCondition.UNKNOWN) invalid()
        var movementId: UUID? = null
        var assetId: UUID? = null
        val replayed = execute(context, "RECEIVING_ASSET_RECEIVED", "RECEIVING_LINE", lineId,
            recordId, request.recordVersion, lineId, request.lineVersion, request.assetCode, request.name,
            request.manufacturer, request.model, request.serialNumber, request.ownershipType, request.condition, request.tagType) {
            val record = receiving.record(recordId, lock = true) ?: unavailable()
            if (record.version != request.recordVersion || record.state != ReceivingState.IN_PROGRESS) stale()
            val destination = requireDestination(context.actor, record.organizationId, record.destinationStorageLocationId)
            val line = receiving.line(lineId, lock = true)?.takeIf {
                it.receivingRecordId == record.id && it.candidateClass == ReceivingCandidateClass.COMPANY_ASSET
            } ?: unavailable()
            if (line.version != request.lineVersion || line.receivedAt != null) stale()
            poReceiving.requireStockOnly(record)
            val typeId = line.expectedAssetTypeDefinitionId ?: invalid()
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val receipt = assetWriter.receive(context, record, line, destination, typeId, request, now) { asset ->
                if (!receiving.markAssetLineReceived(line.id, line.version, asset.id, request.condition,
                        asset.serialNumber, context.actor, now)) stale()
            }
            assetId = receipt.asset.id
            movementId = receipt.movementId
            if (!receiving.touchInProgress(record.id, record.version, now)) stale()
        }
        val line = receiving.line(lineId) ?: unavailable()
        val asset = line.receivedAssetId?.let(assetWriter::asset) ?: unavailable()
        val movement = assetWriter.receivingMovement(lineId) ?: unavailable()
        return ReceivingAssetReceipt(line, asset, movement.id, assetWriter.primaryTag(asset.id), replayed)
    }

    fun addDiscrepancy(context: ReceivingCommandContext, id: UUID,
        request: AddReceivingDiscrepancyRequest): ReceivingDiscrepancyResult {
        val visible = receiving.record(id) ?: unavailable()
        requireVisible(context.actor, visible)
        val description = request.description.trim().takeIf { it.length in 1..2000 } ?: invalid()
        val discrepancyId = UUID.nameUUIDFromBytes(
            ("hiltech:receiving-discrepancy:" + context.operationId).toByteArray(Charsets.UTF_8),
        )
        val replayed = execute(context, "RECEIVING_DISCREPANCY_RECORDED", "RECEIVING_DISCREPANCY", discrepancyId,
            id, request.recordVersion, request.lineId, request.type, description) {
            val record = receiving.record(id, lock = true) ?: unavailable()
            if (record.version != request.recordVersion ||
                record.state !in listOf(ReceivingState.DRAFT, ReceivingState.IN_PROGRESS)) stale()
            requireDestination(context.actor, record.organizationId, record.destinationStorageLocationId)
            poReceiving.requireBoundCurrent(record)
            request.lineId?.let { lineId ->
                receiving.line(lineId, lock = true)?.takeIf { it.receivingRecordId == record.id } ?: unavailable()
            }
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            receiving.insertDiscrepancy(discrepancyId, record, request.lineId, request.type, description, context.actor, now)
            if (!receiving.touchInProgress(record.id, record.version, now)) stale()
            events.publishEvent(ReceivingDiscrepancyRecorded(record.id, discrepancyId, request.lineId, request.type, context.actor))
        }
        val discrepancy = receiving.discrepancies(id).singleOrNull { it.id == discrepancyId } ?: unavailable()
        return ReceivingDiscrepancyResult(discrepancy, receiving.record(id) ?: unavailable(), replayed)
    }

    /**
     * Physical rejection: preserve a typed discrepancy with an immutable PO
     * rejection event, and never append a stock movement for rejected goods.
     */
    fun rejectPoStockLine(
        context: ReceivingCommandContext, recordId: UUID, lineId: UUID,
        request: RejectPoReceivingStockLineRequest,
    ): ReceivingDiscrepancyResult {
        val visible = receiving.record(recordId) ?: unavailable()
        requireDestination(context.actor, visible.organizationId, visible.destinationStorageLocationId)
        val rejected = quantity(request.quantity)
        val reason = request.reason.trim()
        if (request.type !in setOf(
                ReceivingDiscrepancyType.WRONG_ITEM,
                ReceivingDiscrepancyType.DAMAGED_ON_RECEIPT,
                ReceivingDiscrepancyType.SERIAL_MISMATCH,
                ReceivingDiscrepancyType.UNEXPECTED_ITEM,
                ReceivingDiscrepancyType.DOCUMENT_MISMATCH,
            ) || reason.length !in 12..2000 || reason.any { it.code < 32 }
        ) invalid()
        val eventId = UUID.nameUUIDFromBytes(
            "hiltech:po-receiving-rejection:${context.operationId}".toByteArray(Charsets.UTF_8),
        )
        val discrepancyId = UUID.nameUUIDFromBytes(
            "hiltech:po-receiving-rejection-discrepancy:${context.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        val replayed = execute(
            context, "RECEIVING_PO_LINE_REJECTED", "RECEIVING_PO_REJECTION",
            eventId, recordId, lineId, request.recordVersion, request.lineVersion,
            rejected.toPlainString(), request.type, reason,
        ) {
            val record = receiving.record(recordId, lock = true) ?: unavailable()
            if (record.version != request.recordVersion ||
                record.state != ReceivingState.IN_PROGRESS) stale()
            requireDestination(context.actor, record.organizationId, record.destinationStorageLocationId)
            if (record.sourceType != ReceivingSourceType.PO) invalid()
            poReceiving.requireBoundCurrent(record)
            val line = receiving.line(lineId, lock = true)
                ?.takeIf { it.receivingRecordId == record.id } ?: unavailable()
            if (line.version != request.lineVersion || line.receivedAt != null ||
                receiving.hasPoRejection(line.id) ||
                line.candidateClass !in setOf(
                    ReceivingCandidateClass.STOCK_QUANTITY,
                    ReceivingCandidateClass.STOCK_SERIALIZED,
                )
            ) stale()
            val expected = line.expectedQuantity?.let(::quantity)
            // This command resolves an entire delivery line; never record a
            // partial rejection and silently close its uninspected balance.
            if (expected == null || rejected.compareTo(expected) != 0) invalid()
            val at = clock.instant().truncatedTo(ChronoUnit.MICROS)
            receiving.insertPoRejection(
                eventId, context, record, line.id, rejected, request.type, reason, at,
            )
            receiving.insertDiscrepancy(
                discrepancyId, record, line.id, request.type, reason, context.actor, at,
            )
            if (!receiving.touchInProgress(record.id, record.version, at)) stale()
            events.publishEvent(ReceivingDiscrepancyRecorded(
                record.id, discrepancyId, line.id, request.type, context.actor,
            ))
        }
        return ReceivingDiscrepancyResult(
            receiving.discrepancies(recordId).singleOrNull { it.id == discrepancyId }
                ?: unavailable(),
            receiving.record(recordId) ?: unavailable(),
            replayed,
        )
    }

    fun complete(context: ReceivingCommandContext, id: UUID, request: CompleteReceivingRequest): ReceivingRecordResult {
        val visible = receiving.record(id) ?: unavailable()
        requireDestination(context.actor, visible.organizationId, visible.destinationStorageLocationId)
        val replayed = execute(context, "RECEIVING_COMPLETED", "RECEIVING", id, request.recordVersion) {
            val record = receiving.record(id, lock = true) ?: unavailable()
            if (record.version != request.recordVersion) stale()
            requireDestination(context.actor, record.organizationId, record.destinationStorageLocationId)
            poReceiving.requireBoundCurrent(record)
            poReceiving.requireReadyDeliveryNote(record)
            val lines = receiving.lines(record.id)
            if (lines.isEmpty() || lines.any { it.receivedAt == null && !receiving.hasPoRejection(it.id) }) incomplete()
            if (record.state != ReceivingState.IN_PROGRESS) stale()
            val target = if (receiving.hasDiscrepancy(record.id))
                ReceivingState.RECEIVED_WITH_DISCREPANCY else ReceivingState.RECEIVED
            if (!receiving.complete(record.id, record.version, target, context.actor,
                    clock.instant().truncatedTo(ChronoUnit.MICROS))) stale()
        }
        return ReceivingRecordResult(receiving.record(id) ?: unavailable(), replayed)
    }

    private fun requireVisible(actor: UUID, record: ReceivingRecordView) {
        val location = warehouse.storageLocation(record.destinationStorageLocationId)?.takeIf {
            it.organizationId == record.organizationId && authorization.canReceiveAt(actor, it)
        } ?: unavailable()
        val warehouseId = location.warehouseId ?: unavailable()
        warehouse.warehouse(warehouseId)?.takeIf { it.organizationId == record.organizationId } ?: unavailable()
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
        "RECEIVING_INCOMPLETE", "Each receiving line must be physically accepted or explicitly rejected.", HttpStatus.CONFLICT)
}
