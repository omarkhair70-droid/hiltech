package com.hiltech.server.procurement

import com.hiltech.server.warehouse.AssetIdentityView
import com.hiltech.server.warehouse.AssetPassportPersistence
import com.hiltech.server.warehouse.AssetServicePersistence
import com.hiltech.server.warehouse.StorageLocationLifecycle
import com.hiltech.server.warehouse.StorageLocationSnapshot
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import com.hiltech.server.warehouse.WarehousePersistencePort
import com.hiltech.server.warehouse.WarehouseSnapshot
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * Only real asset-service incidents or active maintenance can generate stock
 * purchasing needs. This is not an authorization to buy outside services.
 */
data class AssetPartSource(
    val asset: AssetIdentityView,
    val facility: WarehouseSnapshot,
    val delivery: StorageLocationSnapshot,
    val issueType: String,
    val issueId: UUID,
    val issueVersion: Long,
)

@Component
class AssetPurchaseSource(
    private val assets: AssetPassportPersistence,
    private val services: AssetServicePersistence,
    private val warehouse: WarehousePersistencePort,
    private val authority: WarehouseAuthorizationPort,
    private val clock: Clock,
) {
    fun resolve(
        assetId: UUID,
        incidentId: UUID?,
        maintenanceId: UUID?,
        destinationId: UUID,
    ): AssetPartSource? {
        if ((incidentId == null) == (maintenanceId == null)) return null
        val asset = assets.asset(assetId) ?: return null
        if (asset.lifecycle != "ACTIVE" || asset.warehouseId == null) return null
        val facility = warehouse.warehouse(asset.warehouseId) ?: return null
        if (!facility.active || facility.organizationId != asset.organizationId) return null
        val delivery = warehouse.storageLocation(destinationId) ?: return null
        val at = clock.instant()
        if (delivery.organizationId != asset.organizationId ||
            delivery.warehouseId != asset.warehouseId ||
            delivery.kind.name !in setOf("MAIN_WAREHOUSE", "WAREHOUSE") ||
            delivery.projectId != null || delivery.siteId != null ||
            delivery.lifecycleState != StorageLocationLifecycle.ACTIVE ||
            (delivery.activeFrom != null && delivery.activeFrom > at) ||
            (delivery.activeUntil != null && delivery.activeUntil <= at)
        ) return null

        val issue = if (incidentId != null) {
            val incident = services.incident(assetId, incidentId) ?: return null
            if (incident.type.name !in setOf("DAMAGE", "MISSING", "LOST") ||
                incident.state.name !in setOf("OPEN", "UNDER_REVIEW")
            ) return null
            Triple("INCIDENT", incidentId, incident.version)
        } else {
            val service = services.service(assetId, maintenanceId!!) ?: return null
            if (service.state != "IN_PROGRESS") return null
            Triple("MAINTENANCE", maintenanceId, service.version)
        }
        return AssetPartSource(asset, facility, delivery, issue.first, issue.second, issue.third)
    }

    /** Historical needs remain readable after an incident closes, but no
     * stale need can progress to a new supplier or PO decision. */
    fun authorizedAsset(actor: UUID, assetId: UUID, manager: Boolean?): AssetIdentityView? {
        val asset = assets.asset(assetId) ?: return null
        val facility = asset.warehouseId?.let { warehouse.warehouse(it) } ?: return null
        if (!facility.active || facility.organizationId != asset.organizationId) return null
        val allowed = when (manager) {
            true -> authority.canManageAssetService(actor, facility)
            false -> authority.canOperateAssetService(actor, facility)
            null -> authority.canOperateAssetService(actor, facility) ||
                authority.canManageAssetService(actor, facility)
        }
        return asset.takeIf { allowed }
    }

    fun canAccess(actor: UUID, context: AssetPartSource, manager: Boolean?): Boolean {
        val operate = authority.canOperateAssetService(actor, context.facility)
        val manage = authority.canManageAssetService(actor, context.facility)
        return when (manager) {
            true -> manage
            false -> operate
            null -> operate || manage
        }
    }

    fun current(root: PurchaseRequirementDraftRecord,
                last: PurchaseRequirementTransitionRecord?): Boolean {
        if (root.sourceType != "ASSET_MAINTENANCE_OR_REPLACEMENT" ||
            root.workOrderId != null || root.warehouseStorageLocationId != null
        ) return false
        val assetId = root.assetId ?: return false
        val destId = root.assetDeliveryLocationId ?: return false
        val context = resolve(assetId, root.assetIncidentId, root.assetMaintenanceId, destId)
            ?: return false
        return context.asset.organizationId == root.organizationId &&
            context.asset.version == (last?.revised?.workVersion ?: root.assetSourceVersion) &&
            context.delivery.version == root.assetDeliveryLocationVersion &&
            context.issueVersion == root.assetIssueVersion
    }

    fun destination(root: PurchaseRequirementDraftRecord,
                    last: PurchaseRequirementTransitionRecord?): UUID? =
        if (current(root, last)) root.assetDeliveryLocationId else null
}
