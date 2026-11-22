package com.hiltech.server.procurement

import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.warehouse.StorageLocationLifecycle
import com.hiltech.server.warehouse.WarehousePersistencePort
import com.hiltech.server.work.WorkOrderLifecycle
import com.hiltech.server.work.WorkPersistencePort
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * One typed source-truth contract for S01/S02/S03.
 * Buyers/issuers must never infer a fake WorkOrder for internal replenishment.
 * Exact source revision, company, canonical StockItem and unit are rechecked
 * whenever an RFQ or purchasing approval evaluates currentness.
 */
@Component
class ProcurementDemandSourceVerifier(
    private val work: WorkPersistencePort,
    private val warehouse: WarehousePersistencePort,
    private val stock: StockLedgerPersistence,
) {
    fun isCurrent(
        root: PurchaseRequirementDraftRecord,
        last: PurchaseRequirementTransitionRecord?,
    ): Boolean {
        val item = stock.item(root.stockItemId) ?: return false
        if (!item.active || item.organizationId != root.organizationId ||
            item.version != (last?.revised?.itemVersion ?: root.stockItemVersion) ||
            item.baseUnitCode != (last?.revised?.unitCode ?: root.unitCode)
        ) return false
        return when (root.sourceType) {
            "WORK_ORDER" -> {
                val workId = root.workOrderId ?: return false
                val sourceVersion = last?.revised?.workVersion ?: root.sourceWorkVersion
                    ?: return false
                val order = work.workOrder(workId) ?: return false
                order.organizationId == root.organizationId &&
                    order.version == sourceVersion &&
                    order.lifecycleState in setOf(
                        WorkOrderLifecycle.PLANNED, WorkOrderLifecycle.ASSIGNED,
                        WorkOrderLifecycle.IN_PROGRESS, WorkOrderLifecycle.BLOCKED,
                    )
            }
            "WAREHOUSE_REPLENISHMENT" -> {
                val locationId = root.warehouseStorageLocationId ?: return false
                val sourceVersion = last?.revised?.workVersion ?: root.warehouseSourceVersion
                    ?: return false
                val location = warehouse.storageLocation(locationId) ?: return false
                val facilityId = location.warehouseId ?: return false
                val facility = warehouse.warehouse(facilityId) ?: return false
                val at = java.time.Instant.now()
                location.organizationId == root.organizationId &&
                    location.version == sourceVersion &&
                    location.lifecycleState == StorageLocationLifecycle.ACTIVE &&
                    (location.activeFrom == null || location.activeFrom <= at) &&
                    (location.activeUntil == null || location.activeUntil > at) &&
                    facility.organizationId == root.organizationId && facility.active &&
                    location.projectId == null && location.siteId == null
            }
            else -> false
        }
    }

    /** Source-bound warehouse PO cannot silently be delivered to another project or site. */
    fun warehouseDestination(root: PurchaseRequirementDraftRecord): UUID? =
        if (root.sourceType == "WAREHOUSE_REPLENISHMENT" &&
            isCurrent(root, null)
        ) root.warehouseStorageLocationId else null
}
