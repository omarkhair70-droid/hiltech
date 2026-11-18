package com.hiltech.server.warehouse

import com.hiltech.server.work.ReadinessRequirementExecutionSnapshot
import com.hiltech.server.work.WorkOrderSnapshot
import com.hiltech.server.work.WorkResourceFact
import com.hiltech.server.work.WorkResourceSource
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Component
class WarehouseWorkResourceSource(
    private val reservations: ReservationPersistence,
    private val availability: AssetServicePersistence,
    private val stock: StockLedgerPersistence,
    private val warehouse: WarehousePersistencePort,
    private val assets: AssetPassportPersistence,
) : WorkResourceSource {
    override fun evaluate(order: WorkOrderSnapshot, requirement: ReadinessRequirementExecutionSnapshot, at: Instant): WorkResourceFact? =
        reservations.demands(order.workOrderId).firstOrNull { it.requirementId == requirement.requirementId }
            ?.let { fact(order, it, at) }

    fun candidates(order: WorkOrderSnapshot, demand: WorkResourceDemand, at: Instant): List<ResourceCandidate> {
        val owned = reservations.demandReservations(demand.requirementId, at)
        demand.assetTypeId?.let { type ->
            return reservations.assets(order.organizationId, type).mapNotNull { (id, code, location) ->
                val health = availability.availability(id, at) ?: return@mapNotNull null
                val mine = owned.any { it.resourceId == id }
                val reasons = health.reasons.filterNot { it.code == "ACTIVE_RESERVATION" && mine }.map { reason ->
                    when (reason.code) {
                        "CALIBRATION_EXPIRED" -> "ASSET_CALIBRATION_EXPIRED"
                        "ACTIVE_RESERVATION" -> "ASSET_RESERVED_FOR_OTHER_WORK"
                        else -> when (reason.state) {
                            "BLOCKED_MAINTENANCE" -> "ASSET_MAINTENANCE_BLOCKED"
                            "BLOCKED_INCIDENT" -> "ASSET_INCIDENT_BLOCKED"
                            "CHECKED_OUT" -> "ASSET_CHECKED_OUT"
                            else -> reason.code
                        }
                    }
                }.toMutableList()
                if (location != null && !eligible(order, location, at)) reasons += "RESOURCE_LOCATION_NOT_ELIGIBLE"
                if (reasons.isEmpty()) reasons += if (mine) "ASSET_RESERVED" else "ASSET_AVAILABLE"
                ResourceCandidate(ResourceKind.ASSET, id, code, location, health.assetVersion, at.toString(), null, null, reasons.distinct())
            }
        }
        val item = demand.stockItemId?.let { stock.item(it) }
            ?.takeIf { it.active && reservations.stockUnitActive(order.organizationId, it.baseUnitCode) } ?: return emptyList()
        return reservations.balances(item.id, at).map { balance ->
            val mine = owned.filter { it.storageLocationId == balance.location }.sumOf { it.quantity?.toBigDecimal() ?: BigDecimal.ZERO }
            val reason = when {
                !eligible(order, balance.location, at) -> "RESOURCE_LOCATION_NOT_ELIGIBLE"
                mine > BigDecimal.ZERO -> "STOCK_RESERVED"
                balance.available > BigDecimal.ZERO -> "STOCK_AVAILABLE"
                balance.reserved > BigDecimal.ZERO -> "STOCK_RESERVED_FOR_OTHER_WORK"
                else -> "STOCK_SHORTAGE"
            }
            ResourceCandidate(ResourceKind.STOCK, item.id, item.itemCode, balance.location, balance.version, at.toString(),
                balance.available.toPlainString(), item.baseUnitCode, listOf(reason))
        }
    }

    fun fact(
        order: WorkOrderSnapshot, demand: WorkResourceDemand, at: Instant, known: List<ResourceCandidate>? = null,
    ): WorkResourceFact {
        if (demand.version == null) return WorkResourceFact(false, "RESOURCE_REQUEST_REQUIRED", demand.requirementId, demand.requirementVersion, at)
        val held = reservations.demandReservations(demand.requirementId, at)
        if (demand.assetTypeId != null) {
            val reservation = held.firstOrNull()
            if (reservation != null) {
                val health = availability.availability(reservation.resourceId, at)
                val blockers = health?.reasons.orEmpty().filterNot { it.code == "ACTIVE_RESERVATION" && it.sourceId == reservation.id }
                val location = assets.custody(reservation.resourceId)?.storageLocationId
                if (health != null && blockers.isEmpty() && location != null && eligible(order, location, at)) {
                    return WorkResourceFact(true, "ASSET_RESERVED", reservation.id, reservation.version, at)
                }
                val first = blockers.firstOrNull()
                val reason = when (first?.state) {
                    "BLOCKED_CALIBRATION" -> if (first.code == "CALIBRATION_EXPIRED") "ASSET_CALIBRATION_EXPIRED" else "ASSET_CALIBRATION_BLOCKED"
                    "BLOCKED_MAINTENANCE" -> "ASSET_MAINTENANCE_BLOCKED"
                    "BLOCKED_INCIDENT" -> "ASSET_INCIDENT_BLOCKED"
                    "CHECKED_OUT" -> "ASSET_CHECKED_OUT"
                    else -> first?.code ?: "RESOURCE_LOCATION_NOT_ELIGIBLE"
                }
                return WorkResourceFact(false, reason, first?.sourceId ?: reservation.id, first?.sourceVersion ?: reservation.version, at)
            }
            val options = known ?: candidates(order, demand, at)
            val best = options.firstOrNull { "ASSET_AVAILABLE" in it.reasonCodes } ?: options.firstOrNull()
            return WorkResourceFact(false, best?.reasonCodes?.firstOrNull() ?: "ASSET_SHORTAGE", best?.id ?: demand.assetTypeId,
                best?.sourceVersion ?: demand.version, at)
        }
        val required = requireNotNull(demand.quantity).toBigDecimal()
        val item = requireNotNull(demand.stockItemId)
        val master = stock.item(item)
        if (master == null || !master.active || !reservations.stockUnitActive(order.organizationId, master.baseUnitCode)) {
            return WorkResourceFact(false, "STOCK_INACTIVE", item, master?.version ?: demand.version, at)
        }
        val eligible = held.filter { it.storageLocationId?.let { id -> eligible(order, id, at) } == true }
        val count = eligible.sumOf { requireNotNull(it.quantity).toBigDecimal() }
        if (count >= required) {
            val source = eligible.first()
            return WorkResourceFact(true, "STOCK_RESERVED", source.id, source.version, at)
        }
        val options = known ?: candidates(order, demand, at)
        val usable = options.filterNot { "RESOURCE_LOCATION_NOT_ELIGIBLE" in it.reasonCodes }
        val free = usable.sumOf { it.availableQuantity?.toBigDecimal() ?: BigDecimal.ZERO }
        val reason = when {
            held.size != eligible.size || (options.isNotEmpty() && usable.isEmpty()) -> "RESOURCE_LOCATION_NOT_ELIGIBLE"
            free + count >= required -> "STOCK_AVAILABLE"
            usable.any { "STOCK_RESERVED_FOR_OTHER_WORK" in it.reasonCodes } -> "STOCK_RESERVED_FOR_OTHER_WORK"
            else -> "STOCK_SHORTAGE"
        }
        val source = usable.firstOrNull()
        return WorkResourceFact(false, reason, source?.id ?: demand.stockItemId, source?.sourceVersion ?: demand.version, at)
    }

    private fun eligible(order: WorkOrderSnapshot, id: UUID, at: Instant): Boolean {
        val location = warehouse.storageLocation(id) ?: return false
        return location.organizationId == order.organizationId && location.lifecycleState == StorageLocationLifecycle.ACTIVE &&
            (location.activeFrom == null || location.activeFrom <= at) && (location.activeUntil == null || location.activeUntil > at) &&
            (location.projectId == null || location.projectId == order.projectId) &&
            (location.siteId == null || location.siteId == order.siteId) &&
            location.warehouseId?.let { warehouse.warehouse(it)?.active } == true
    }
}
