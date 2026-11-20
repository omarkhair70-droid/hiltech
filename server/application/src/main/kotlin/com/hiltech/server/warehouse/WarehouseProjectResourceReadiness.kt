package com.hiltech.server.warehouse

import com.hiltech.server.work.WorkPersistencePort
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

data class WarehouseProjectResourceReadinessItem(
    val workOrderId: UUID,
    val workOrderCode: String,
    val title: String,
    val projectId: UUID,
    val siteId: UUID,
    val requirementId: UUID,
    val requirementKey: String,
    val requirementFamily: String,
    val resourceKind: String,
    val state: String,
    val reasonCode: String,
    val factSourceId: UUID?,
    val factSourceVersion: Long?,
    val sourceAsOf: Instant,
    val candidateCount: Int,
)

data class WarehouseProjectResourceReadinessQueue(
    val visible: Boolean,
    val items: List<WarehouseProjectResourceReadinessItem>,
    val limited: Boolean,
)

@Component
class WarehouseProjectResourceReadinessSource(
    private val records: ReservationPersistence,
    private val source: WarehouseWorkResourceSource,
    private val work: WorkPersistencePort,
    private val warehouses: WarehousePersistencePort,
    private val authorization: WarehouseAuthorizationPort,
) {
    fun queue(
        actorUserId: UUID,
        warehouse: WarehouseSnapshot,
        at: Instant,
    ): WarehouseProjectResourceReadinessQueue {
        if (!authorization.canReserveResources(actorUserId, warehouse)) {
            return WarehouseProjectResourceReadinessQueue(false, emptyList(), false)
        }
        val items = records.openWork(warehouse.organizationId).flatMap { workOrderId ->
            val order = work.workOrder(workOrderId) ?: return@flatMap emptyList()
            records.demands(workOrderId)
                .filter { it.version != null }
                .mapNotNull { demand ->
                    val localCandidates = source.candidates(order, demand, at).filter { candidate ->
                        candidate.locationId?.let(warehouses::storageLocation)?.warehouseId == warehouse.warehouseId
                    }
                    val fact = source.fact(order, demand, at, localCandidates)
                    if (fact.satisfied) return@mapNotNull null
                    WarehouseProjectResourceReadinessItem(
                        workOrderId = order.workOrderId,
                        workOrderCode = order.workOrderCode,
                        title = order.title,
                        projectId = order.projectId,
                        siteId = order.siteId,
                        requirementId = demand.requirementId,
                        requirementKey = demand.key,
                        requirementFamily = demand.family,
                        resourceKind = if (demand.assetTypeId != null) "ASSET" else "STOCK",
                        state = if (fact.reason in setOf("ASSET_AVAILABLE", "STOCK_AVAILABLE")) {
                            "READY_TO_RESERVE"
                        } else {
                            "BLOCKED"
                        },
                        reasonCode = fact.reason,
                        factSourceId = fact.sourceId,
                        factSourceVersion = fact.sourceVersion,
                        sourceAsOf = fact.asOf,
                        candidateCount = localCandidates.size,
                    )
                }
        }.sortedWith(
            compareBy<WarehouseProjectResourceReadinessItem> { if (it.state == "BLOCKED") 0 else 1 }
                .thenBy { it.workOrderCode }
                .thenBy { it.requirementKey }
                .thenBy { it.requirementId },
        )
        return WarehouseProjectResourceReadinessQueue(true, items.take(50), items.size > 50)
    }
}
