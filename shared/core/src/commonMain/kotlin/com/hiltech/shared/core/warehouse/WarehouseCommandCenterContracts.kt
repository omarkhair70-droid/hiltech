package com.hiltech.shared.core.warehouse

import kotlinx.serialization.Serializable

@Serializable
data class WarehouseInventoryOverviewDto(
    val balanceLineCount: Int,
    val stockItemCount: Int,
    val zeroOnHandLineCount: Int,
    val zeroAvailableLineCount: Int,
    val lowStockPolicyConfigured: Boolean,
    val lowStockPolicyReason: String? = null,
)

@Serializable
data class WarehouseCommandCenterItemDto(
    val sourceType: String,
    val sourceId: String,
    val label: String,
    val state: String,
    val reasonCodes: List<String>,
    val occurredAt: String? = null,
    val dueAt: String? = null,
    val projectId: String? = null,
    val siteId: String? = null,
    val workOrderId: String? = null,
    val drillType: String? = null,
    val drillId: String? = null,
)

@Serializable
data class WarehouseCommandCenterQueueDto(
    val items: List<WarehouseCommandCenterItemDto>,
    val limited: Boolean,
)

@Serializable
data class WarehouseProjectResourceReadinessItemDto(
    val workOrderId: String,
    val workOrderCode: String,
    val title: String,
    val projectId: String,
    val siteId: String,
    val requirementId: String,
    val requirementKey: String,
    val requirementFamily: String,
    val resourceKind: String,
    val state: String,
    val reasonCode: String,
    val factSourceId: String? = null,
    val factSourceVersion: Long? = null,
    val sourceAsOf: String,
    val candidateCount: Int,
)

@Serializable
data class WarehouseProjectResourceReadinessQueueDto(
    val visible: Boolean,
    val items: List<WarehouseProjectResourceReadinessItemDto>,
    val limited: Boolean,
)

@Serializable
data class WarehouseCommandCenterDto(
    val warehouseId: String,
    val organizationId: String,
    val warehouseCode: String,
    val warehouseName: String,
    val asOf: String,
    val inventory: WarehouseInventoryOverviewDto,
    val dueReturns: WarehouseCommandCenterQueueDto,
    val activeReservations: WarehouseCommandCenterQueueDto,
    val assetExceptions: WarehouseCommandCenterQueueDto,
    val receivingQueue: WarehouseCommandCenterQueueDto,
    val stocktakeQueue: WarehouseCommandCenterQueueDto,
    val importExceptions: WarehouseCommandCenterQueueDto,
    val recentMovements: WarehouseCommandCenterQueueDto,
    val projectResourceReadiness: WarehouseProjectResourceReadinessQueueDto =
        WarehouseProjectResourceReadinessQueueDto(false, emptyList(), false),
)
