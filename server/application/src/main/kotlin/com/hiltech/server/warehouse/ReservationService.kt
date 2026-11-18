package com.hiltech.server.warehouse

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectsPersistencePort
import com.hiltech.server.work.WorkOrderLifecycle
import com.hiltech.server.work.WorkOrderSnapshot
import com.hiltech.server.work.WorkPersistencePort
import com.hiltech.server.work.WorkResourceChanged
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class ReservationService(
    private val records: ReservationPersistence,
    private val sources: WarehouseWorkResourceSource,
    private val work: WorkPersistencePort,
    private val projects: ProjectsPersistencePort,
    private val projectAuth: ProjectAuthorizationPort,
    private val warehouse: WarehousePersistencePort,
    private val warehouseAuth: WarehouseAuthorizationPort,
    private val assets: AssetPassportPersistence,
    private val stock: StockLedgerPersistence,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)
    fun catalog(actor: UUID, id: UUID): WorkResourceCatalog {
        val order = order(id)
        requireProjectView(actor, order)
        return WorkResourceCatalog(assets.types(order.organizationId, clock.instant()).map { ResourceCatalogChoice(it.id, it.name, null) },
            stock.items(order.organizationId, null).filter { it.active }.map { ResourceCatalogChoice(it.id, "${it.itemCode} · ${it.name}", it.baseUnitCode) })
    }

    fun candidates(actor: UUID, id: UUID): WorkResourceCandidates {
        val order = order(id)
        requireProjectView(actor, order)
        val now = clock.instant()
        return WorkResourceCandidates(id, order.version, canRequest(actor, order), records.demands(id).map { demand ->
            val current = sources.candidates(order, demand, now)
            ResourceRequirementCandidates(demand, sources.fact(order, demand, now, current).reason, current)
        })
    }

    fun forWork(actor: UUID, id: UUID): List<ReservationView> {
        requireProjectView(actor, order(id))
        return records.workReservations(id, clock.instant())
    }

    fun queue(actor: UUID, warehouseId: UUID): WarehouseReservationQueue {
        val facility = warehouse.warehouse(warehouseId)?.takeIf { warehouseAuth.canViewWarehouse(actor, it) } ?: hidden()
        val at = clock.instant()
        val operational = warehouseAuth.canReserveResources(actor, facility)
        val reservations = records.warehouseReservations(warehouseId, at)
        val pending = if (operational) records.openWork(facility.organizationId).mapNotNull { id ->
            val order = work.workOrder(id) ?: return@mapNotNull null
            val requirements = records.demands(id).filter { it.version != null }.mapNotNull { demand ->
                val candidates = sources.candidates(order, demand, at).filter {
                    val location = it.locationId?.let(warehouse::storageLocation)
                    location?.warehouseId == warehouseId
                }
                if (candidates.isEmpty()) null else ResourceRequirementCandidates(demand, sources.fact(order, demand, at, candidates).reason, candidates)
            }
            if (requirements.isEmpty()) null else WarehouseWorkRequest(id, order.workOrderCode, order.title, order.version, requirements)
        } else emptyList()
        return WarehouseReservationQueue(operational, pending, reservations)
    }

    fun request(context: AssetCommandContext, id: UUID, requirement: UUID, request: RequestWorkResource): WorkResourceDemand {
        if (!canRequest(context.actor, order(id))) hidden()
        val quantity = request.quantity?.let(::quantity) ?: BigDecimal.ONE
        execute(context, id, requirement, "WORK_RESOURCE_REQUESTED", request) { now ->
            val order = lockOrder(id, request.workVersion)
            if (!canRequest(context.actor, order)) hidden()
            val demand = demand(order, requirement, request.requirementVersion)
            if (demand.version != null) conflict("RESOURCE_REQUEST_EXISTS", "This requirement already has a resource request.")
            val isStock = demand.family == "MATERIAL" || demand.type == "MATERIAL"
            if (isStock) {
                val item = request.stockItemId?.let { stock.item(it) }
                    ?.takeIf { it.active && it.organizationId == order.organizationId } ?: hidden()
                if (request.assetTypeId != null || request.quantity == null || item.baseUnitCode != request.unitCode ||
                    !records.stockUnitActive(item.organizationId, item.baseUnitCode) ||
                    (item.serialized && quantity.stripTrailingZeros().scale() > 0)) invalid("Enter a canonical Stock quantity and unit.")
            } else {
                val type = request.assetTypeId ?: invalid("Select an Asset type.")
                if (request.stockItemId != null || request.unitCode != null || quantity.compareTo(BigDecimal.ONE) != 0 ||
                    assets.types(order.organizationId, now).none { it.id == type }) invalid("Select an Asset type for one Asset.")
            }
            records.request(order, requirement, request, quantity, context.actor, now)
        }
        return records.demands(id).singleOrNull { it.requirementId == requirement } ?: hidden()
    }

    fun reserveAsset(context: AssetCommandContext, assetId: UUID, request: ReserveAssetRequest): ReservationView {
        requireAssetOperator(context.actor, assetId)
        val id = reservationId(context.operationId)
        val expires = timestamp(request.expiresAt)
        execute(context, request.workOrderId, id, "ASSET_RESERVED", assetId, request) { now ->
            val order = lockOrder(request.workOrderId, request.workVersion)
            val demand = demand(order, request.requirementId, request.requirementVersion)
            val asset = assets.asset(assetId, lock = true) ?: hidden()
            requireAssetOperator(context.actor, assetId)
            if (asset.version != request.resourceVersion) stale()
            if (asset.organizationId != order.organizationId || asset.typeId != demand.assetTypeId) hidden()
            if (expires <= now) invalid("Expiry must be in the future.")
            records.reserve(ResourceKind.ASSET, id, assetId, order, demand.requirementId, context.actor, now, expires)
            records.advanceAsset(assetId)
        }
        return result(ResourceKind.ASSET, id)
    }

    fun reserveStock(context: AssetCommandContext, itemId: UUID, request: ReserveStockRequest): ReservationView {
        requireStockOperator(context.actor, request.storageLocationId)
        val id = reservationId(context.operationId)
        val amount = quantity(request.quantity)
        val expires = timestamp(request.expiresAt)
        execute(context, request.workOrderId, id, "STOCK_RESERVED", itemId, request) { now ->
            val order = lockOrder(request.workOrderId, request.workVersion)
            val demand = demand(order, request.requirementId, request.requirementVersion)
            val item = stock.item(itemId, lock = true) ?: hidden()
            requireStockOperator(context.actor, request.storageLocationId)
            if (item.organizationId != order.organizationId || demand.stockItemId != itemId) hidden()
            if (request.unitCode != item.baseUnitCode || (item.serialized && amount.stripTrailingZeros().scale() > 0)) {
                invalid("Use the Stock item's canonical unit and supported quantity.")
            }
            val capacity = records.balances(itemId, now, request.storageLocationId).singleOrNull() ?: hidden()
            if (capacity.version != request.resourceVersion) stale()
            if (amount > capacity.available) conflict("STOCK_SHORTAGE", "The location cannot cover this quantity.")
            if (expires <= now) invalid("Expiry must be in the future.")
            records.reserve(ResourceKind.STOCK, id, itemId, order, demand.requirementId, context.actor, now, expires,
                request.storageLocationId, amount, request.unitCode)
        }
        return result(ResourceKind.STOCK, id)
    }

    fun release(context: AssetCommandContext, kind: ResourceKind, id: UUID, request: ReleaseReservationRequest): ReservationView {
        val initial = result(kind, id)
        requireReservationOperator(context.actor, initial)
        val reason = request.reason.trim().takeIf { it.isNotEmpty() && it.length <= 2000 } ?: invalid("Enter a release reason.")
        execute(context, initial.workOrderId, id, "${kind.name}_RESERVATION_RELEASED", request.baseVersion, reason) { now ->
            work.lockWorkOrder(initial.workOrderId)
            lockResource(initial)
            val current = records.reservation(kind, id, now) ?: hidden()
            requireReservationOperator(context.actor, current)
            if (current.version != request.baseVersion || current.state != "ACTIVE" ||
                !records.end(current, "RELEASED", context.actor, now, reason)) stale()
            if (kind == ResourceKind.ASSET) records.advanceAsset(current.resourceId)
        }
        return result(kind, id)
    }

    fun expireDue() {
        val at = clock.instant()
        records.expired(at).forEach { candidate ->
            transaction.executeWithoutResult {
                work.lockWorkOrder(candidate.workOrderId)
                lockResource(candidate)
                if (records.end(candidate, "EXPIRED", null, at, "Reservation window ended")) {
                    if (candidate.kind == ResourceKind.ASSET) records.advanceAsset(candidate.resourceId)
                    val event = UUID.nameUUIDFromBytes("reservation-expired:${candidate.id}:${candidate.version}".toByteArray())
                    audit.append(AuditEventRecord(actorUserId = null, action = "${candidate.kind.name}_RESERVATION_EXPIRED",
                        targetType = "RESOURCE_RESERVATION", targetId = candidate.id, occurredAt = at, correlationId = event.toString()))
                    events.publishEvent(WorkResourceChanged(event, candidate.workOrderId, candidate.requestedBy, event.toString()))
                }
            }
        }
    }

    fun reevaluateDrift() {
        records.readinessDrift(clock.instant()).distinctBy { it.first }.forEach { (id, actor, version) ->
            transaction.executeWithoutResult {
                if (!work.lockWorkOrder(id)) return@executeWithoutResult
                if (order(id).version != version) return@executeWithoutResult
                val eventId = UUID.nameUUIDFromBytes("resource-readiness-drift:$id:$version".toByteArray())
                events.publishEvent(WorkResourceChanged(eventId, id, actor, eventId.toString()))
            }
        }
    }

    private fun requireReservationOperator(actor: UUID, reservation: ReservationView) {
        when (reservation.kind) {
            ResourceKind.ASSET -> requireAssetOperator(actor, reservation.resourceId, allowMoved = true)
            ResourceKind.STOCK -> requireStockOperator(actor, requireNotNull(reservation.storageLocationId))
        }
    }

    private fun lockResource(reservation: ReservationView) {
        when (reservation.kind) {
            ResourceKind.ASSET -> assets.asset(reservation.resourceId, lock = true)
            ResourceKind.STOCK -> stock.item(reservation.resourceId, lock = true)
        } ?: hidden()
    }

    private fun requireAssetOperator(actor: UUID, id: UUID, allowMoved: Boolean = false) {
        val asset = assets.asset(id) ?: hidden()
        val facility = asset.warehouseId?.let(warehouse::warehouse) ?: hidden()
        if (!warehouseAuth.canReserveResources(actor, facility)) hidden()
        if (!allowMoved) requireStockOperator(actor, assets.custody(id)?.storageLocationId ?: hidden())
    }

    private fun requireStockOperator(actor: UUID, id: UUID) {
        val location = warehouse.storageLocation(id) ?: hidden()
        val facility = location.warehouseId?.let(warehouse::warehouse) ?: hidden()
        if (facility.organizationId != location.organizationId || !warehouseAuth.canReserveResources(actor, facility)) hidden()
    }

    private fun requireProjectView(actor: UUID, order: WorkOrderSnapshot) {
        val project = project(order)
        if (!projectAuth.canViewProject(actor, project) && !projectAuth.canRequestResources(actor, project)) hidden()
    }

    private fun canRequest(actor: UUID, order: WorkOrderSnapshot) = projectAuth.canRequestResources(actor, project(order))
    private fun project(order: WorkOrderSnapshot) = projects.project(order.projectId, clock.instant()) ?: hidden()
    private fun order(id: UUID) = work.workOrder(id) ?: hidden()

    private fun lockOrder(id: UUID, version: Long): WorkOrderSnapshot {
        if (!work.lockWorkOrder(id)) hidden()
        val order = order(id)
        if (order.version != version) stale()
        if (order.lifecycleState !in setOf(WorkOrderLifecycle.PLANNED, WorkOrderLifecycle.ASSIGNED, WorkOrderLifecycle.IN_PROGRESS, WorkOrderLifecycle.BLOCKED)) {
            conflict("WORK_RESOURCE_CONTEXT_ENDED", "Work no longer accepts reservations.")
        }
        return order
    }

    private fun demand(order: WorkOrderSnapshot, id: UUID, version: Long): WorkResourceDemand {
        val demand = records.demands(order.workOrderId).firstOrNull { it.requirementId == id } ?: hidden()
        if (demand.requirementVersion != version) stale()
        return demand
    }

    private fun result(kind: ResourceKind, id: UUID) = records.reservation(kind, id, clock.instant()) ?: hidden()

    private fun execute(
        context: AssetCommandContext, workId: UUID, source: UUID, action: String, vararg fields: Any?, apply: (Instant) -> Unit,
    ) {
        try {
            commands.execute(IdempotentCommandSpec(context.operationId, context.actor, action, "WORK_ORDER", workId,
                warehouseCommandFingerprint(source, *fields), context.correlationId)) {
                val at = clock.instant().truncatedTo(ChronoUnit.MICROS)
                apply(at)
                audit.append(AuditEventRecord(actorUserId = context.actor, action = action, targetType = "WORK_ORDER",
                    targetId = workId, newStateRef = source.toString(), occurredAt = at, correlationId = context.correlationId))
                events.publishEvent(WorkResourceChanged(reservationId(context.operationId), workId, context.actor, context.correlationId))
                IdempotentCommandOutcome(action, """{"sourceId":"$source"}""")
            }
        } catch (_: DataIntegrityViolationException) {
            conflict("RESERVATION_CAPACITY_CONFLICT", "Resource capacity, eligibility or Work demand changed.")
        }
    }
}

data class ResourceCatalogChoice(val id: UUID, val label: String, val unitCode: String?)
data class WorkResourceCatalog(val assetTypes: List<ResourceCatalogChoice>, val stockItems: List<ResourceCatalogChoice>)

data class WarehouseWorkRequest(
    val workOrderId: UUID, val code: String, val title: String, val workVersion: Long, val requirements: List<ResourceRequirementCandidates>,
)
data class WarehouseReservationQueue(val canReserve: Boolean, val requests: List<WarehouseWorkRequest>, val reservations: List<ReservationView>)

@Component
class ReservationExpiry(private val reservations: ReservationService) {
    @Scheduled(fixedDelayString = "\${hiltech.warehouse.reservation-expiry-ms:30000}")
    fun expire() {
        reservations.expireDue()
        reservations.reevaluateDrift()
    }
}

private fun reservationId(operation: UUID) = UUID.nameUUIDFromBytes("hiltech:reservation:$operation".toByteArray())
private fun quantity(value: String): BigDecimal =
    value.takeIf { it.matches(Regex("[0-9]{1,14}(\\.[0-9]{1,6})?")) }?.toBigDecimal()
        ?.takeIf { it.signum() > 0 } ?: invalid("Enter a positive exact decimal with up to six decimal places.")
private fun timestamp(value: String): Instant =
    runCatching { Instant.parse(value).truncatedTo(ChronoUnit.MICROS) }.getOrElse { invalid("Enter a valid expiry timestamp.") }
private fun hidden(): Nothing = throw ProductApiException("RESOURCE_UNAVAILABLE", "Work or resource is unavailable.", HttpStatus.NOT_FOUND)
private fun invalid(message: String): Nothing = throw ProductApiException("INVALID_RESERVATION", message, HttpStatus.BAD_REQUEST)
private fun stale(): Nothing = conflict("RESERVATION_VERSION_CONFLICT", "Reload the Work requirement and resource before retrying.")
private fun conflict(code: String, message: String): Nothing = throw ProductApiException(code, message, HttpStatus.CONFLICT)
