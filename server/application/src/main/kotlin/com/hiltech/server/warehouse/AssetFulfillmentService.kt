package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.work.WorkPersistencePort
import com.hiltech.server.work.WorkResourceChanged
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class CheckoutReservedAssetRequest(
    val operationId: UUID, val assetVersion: Long, val sourceMovementId: UUID,
    val workOrderId: UUID, val workVersion: Long, val reservationId: UUID,
    val reservationVersion: Long, val employeeId: UUID, val expectedReturnAt: String? = null,
)
data class ReturnPhysicalAssetRequest(
    val operationId: UUID, val assetVersion: Long, val sourceMovementId: UUID,
    val storageLocationId: UUID, val condition: AssetCondition,
)
data class TransferPhysicalAssetRequest(
    val operationId: UUID, val assetVersion: Long, val sourceMovementId: UUID, val storageLocationId: UUID,
)
data class InspectPhysicalReturnRequest(
    val operationId: UUID, val assetVersion: Long, val condition: AssetCondition,
    val notes: String? = null, val damageIncidentId: UUID? = null,
)
data class PhysicalAssetReceipt(val recordId: UUID, val assetId: UUID, val kind: String, val replayed: Boolean)

@Service
class AssetFulfillmentService(
    private val assets: AssetPassportPersistence,
    private val physical: AssetFulfillmentPersistence,
    private val passport: AssetPassportService,
    private val reservations: ReservationPersistence,
    private val warehouse: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
    private val work: WorkPersistencePort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun recipients(actor: UUID, warehouseId: UUID, search: String): List<FulfillmentEmployeeChoice> {
        val facility = warehouse.warehouse(warehouseId)?.takeIf { it.active && authorization.canReserveResources(actor, it) }
            ?: physicalHidden()
        val query = search.trim()
        if (query.length > 160) invalidPhysical()
        return physical.recipients(facility.organizationId, query)
    }

    fun dueReturns(actor: UUID, warehouseId: UUID, afterExpectedReturnAt: String? = null,
        afterAssetCode: String? = null, afterAssetId: UUID? = null): AssetDueReturnQueue {
        val facility = warehouse.warehouse(warehouseId)?.takeIf { authorization.canReserveResources(actor, it) }
            ?: physicalHidden()
        val supplied = listOf(afterExpectedReturnAt, afterAssetCode, afterAssetId).count { it != null }
        if (supplied !in listOf(0, 3)) invalidPhysical()
        val due = afterExpectedReturnAt?.let { runCatching { Instant.parse(it) }.getOrElse { invalidPhysical() } }
        val code = afterAssetCode?.trim()?.also { if (it.isEmpty() || it.length > 128) invalidPhysical() }
        return physical.dueReturns(facility.organizationId, facility.warehouseId, clock.instant(), due, code, afterAssetId)
    }

    fun checkout(context: AssetCommandContext, id: UUID, request: CheckoutReservedAssetRequest): PhysicalAssetReceipt {
        requireOperator(context.actor, getAsset(id))
        val due = request.expectedReturnAt?.let { runCatching { Instant.parse(it) }.getOrElse { invalidPhysical() } }
        return move(context, id, "CHECKOUT", request) { now ->
            if (!work.lockWorkOrder(request.workOrderId)) physicalHidden()
            val order = work.workOrder(request.workOrderId) ?: physicalHidden()
            if (order.version != request.workVersion) physicalStale()
            val asset = locked(context.actor, id, request.assetVersion)
            if (asset.organizationId != order.organizationId) physicalHidden()
            val prior = custody(id, request.sourceMovementId)
            requireLocation(context.actor, asset, prior.storageLocationId ?: physicalHidden())
            val held = reservations.reservation(ResourceKind.ASSET, request.reservationId, now) ?: physicalHidden()
            if (held.resourceId != id || held.workOrderId != order.workOrderId) physicalHidden()
            if (held.state != "ACTIVE" || held.version != request.reservationVersion) physicalStale()
            if (due != null && due <= now) invalidPhysical()
            if (!assets.employeeActive(asset.organizationId, request.employeeId)) physicalHidden()
            post(context, asset, prior, AssetDestination(null, request.employeeId, order.projectId, order.siteId,
                order.workOrderId), "CHECKOUT", asset.condition, held.id, due, now)
        }
    }

    fun returnAsset(context: AssetCommandContext, id: UUID, request: ReturnPhysicalAssetRequest): PhysicalAssetReceipt {
        requireLocation(context.actor, getAsset(id), request.storageLocationId)
        return move(context, id, "RETURN", request) { now ->
            lockPriorWork(id)
            val asset = locked(context.actor, id, request.assetVersion)
            val prior = custody(id, request.sourceMovementId)
            val location = requireLocation(context.actor, asset, request.storageLocationId)
            post(context, asset, prior, AssetDestination(location.storageLocationId, null, location.projectId,
                location.siteId, null), "RETURN", request.condition, null, null, now)
        }
    }

    fun transfer(context: AssetCommandContext, id: UUID, request: TransferPhysicalAssetRequest): PhysicalAssetReceipt {
        requireLocation(context.actor, getAsset(id), request.storageLocationId)
        return move(context, id, "TRANSFER", request) { now ->
            lockPriorWork(id)
            val asset = locked(context.actor, id, request.assetVersion)
            val prior = custody(id, request.sourceMovementId)
            requireLocation(context.actor, asset, prior.storageLocationId ?: physicalHidden())
            val location = requireLocation(context.actor, asset, request.storageLocationId)
            post(context, asset, prior, AssetDestination(location.storageLocationId, null, location.projectId,
                location.siteId, null), "TRANSFER", asset.condition, null, null, now)
        }
    }

    fun inspect(context: AssetCommandContext, id: UUID, returned: UUID, request: InspectPhysicalReturnRequest): PhysicalAssetReceipt {
        requireOperator(context.actor, getAsset(id))
        val notes = request.notes?.trim()?.takeIf { it.isNotEmpty() }
        if ((notes?.length ?: 0) > 2000) invalidPhysical()
        return move(context, id, "INSPECT", listOf(returned, request.copy(notes = notes))) { now ->
            val asset = locked(context.actor, id, request.assetVersion)
            val prior = custody(id, returned)
            requireLocation(context.actor, asset, prior.storageLocationId ?: physicalHidden())
            physical.inspect(id, context, returned, request.condition, notes, request.damageIncidentId, now)
        }
    }

    private fun lockPriorWork(id: UUID) {
        val prior = assets.custody(id) ?: physicalHidden()
        prior.workOrderId?.let { if (!work.lockWorkOrder(it)) physicalHidden() }
    }
    private fun getAsset(id: UUID) = assets.asset(id) ?: physicalHidden()
    private fun locked(actor: UUID, id: UUID, version: Long): AssetIdentityView {
        val asset = assets.asset(id, lock = true) ?: physicalHidden()
        requireOperator(actor, asset)
        if (asset.version != version) physicalStale()
        return asset
    }
    private fun custody(id: UUID, movement: UUID) = assets.custody(id)?.takeIf { it.sourceMovementId == movement } ?: physicalStale()
    private fun requireOperator(actor: UUID, asset: AssetIdentityView) {
        val facility = asset.warehouseId?.let(warehouse::warehouse)?.takeIf { it.organizationId == asset.organizationId }
            ?: physicalHidden()
        if (!authorization.canReserveResources(actor, facility)) physicalHidden()
    }
    private fun requireLocation(actor: UUID, asset: AssetIdentityView, id: UUID): StorageLocationSnapshot {
        requireOperator(actor, asset)
        val location = warehouse.storageLocation(id)?.takeIf {
            it.organizationId == asset.organizationId && it.warehouseId == asset.warehouseId
        } ?: physicalHidden()
        if (!authorization.canTransferStock(actor, location)) physicalHidden()
        return location
    }
    private fun post(context: AssetCommandContext, asset: AssetIdentityView, prior: AssetCustodyView,
        target: AssetDestination, kind: String, condition: AssetCondition, reservation: UUID?, due: Instant?, now: Instant): UUID {
        val movement = physical.post(asset, context, prior, target, kind, condition, reservation, due, now)
        passport.project(context, getAsset(asset.id), target, now, prior)
        listOfNotNull(prior.workOrderId, target.workOrderId).distinct().forEach { workId ->
            val eventId = UUID.nameUUIDFromBytes("physical-asset:$movement:$workId".toByteArray(Charsets.UTF_8))
            events.publishEvent(WorkResourceChanged(eventId, workId, context.actor, context.correlationId))
        }
        return movement
    }
    private fun move(context: AssetCommandContext, id: UUID, kind: String, request: Any,
        apply: (Instant) -> UUID): PhysicalAssetReceipt {
        val action = "ASSET_PHYSICAL_$kind"
        try {
            val result = commands.execute(IdempotentCommandSpec(context.operationId, context.actor, action, "ASSET", id,
                warehouseCommandFingerprint(request), context.correlationId)) {
                val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
                val movement = apply(now)
                audit.append(AuditEventRecord(actorUserId = context.actor, action = action, targetType = "ASSET",
                    targetId = id, newStateRef = movement.toString(), occurredAt = now, correlationId = context.correlationId))
                IdempotentCommandOutcome(action, """{"recordId":"$movement"}""")
            }
            return PhysicalAssetReceipt(physical.id(context.operationId), id, kind, result.replayed)
        } catch (_: DataIntegrityViolationException) {
            throw ProductApiException("ASSET_PHYSICAL_CONFLICT", "Asset custody, reservation or inspection changed.", HttpStatus.CONFLICT)
        }
    }
}

private fun physicalHidden(): Nothing = throw ProductApiException("ASSET_PHYSICAL_UNAVAILABLE", "Asset or custody context is unavailable.", HttpStatus.NOT_FOUND)
private fun physicalStale(): Nothing = throw ProductApiException("ASSET_PHYSICAL_VERSION_CONFLICT", "Reload Asset custody and reservation.", HttpStatus.CONFLICT)
private fun invalidPhysical(): Nothing = throw ProductApiException("INVALID_ASSET_PHYSICAL", "Invalid return time or inspection notes.", HttpStatus.BAD_REQUEST)
