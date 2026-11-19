package com.hiltech.shared.core.warehouse

import kotlinx.serialization.Serializable

@Serializable
data class StocktakeSessionDto(
    val id: String,
    val organizationId: String,
    val warehouseId: String,
    val rootStorageLocationId: String? = null,
    val state: String,
    val categoryIds: List<String> = emptyList(),
    val stockItemIds: List<String> = emptyList(),
    val snapshotAt: String? = null,
    val createdBy: String,
    val createdAt: String,
    val correlationId: String,
    val version: Long,
)

@Serializable
data class StocktakeSnapshotLineDto(
    val id: String,
    val sessionId: String,
    val organizationId: String,
    val stockItemId: String,
    val itemCode: String,
    val storageLocationId: String,
    val locationCode: String,
    val expectedOnHand: String,
    val expectedReserved: String,
    val expectedDamaged: String,
    val expectedQuarantine: String,
    val unitCode: String,
    val balanceVersion: Long,
    val itemVersion: Long,
    val snapshotAt: String,
)

@Serializable
data class StockCountRecordDto(
    val id: String,
    val sessionId: String,
    val snapshotLineId: String,
    val organizationId: String,
    val stockItemId: String,
    val storageLocationId: String,
    val countedQuantity: String,
    val unitCode: String,
    val countedBy: String,
    val countedAt: String,
    val scanSource: String? = null,
    val notes: String? = null,
    val supersedesCountRecordId: String? = null,
    val operationId: String,
    val correlationId: String,
)

@Serializable
data class InventoryDiscrepancyDto(
    val id: String,
    val sessionId: String,
    val snapshotLineId: String,
    val latestCountRecordId: String,
    val organizationId: String,
    val stockItemId: String,
    val storageLocationId: String,
    val expectedQuantity: String,
    val countedQuantity: String,
    val varianceQuantity: String,
    val snapshotBalanceVersion: Long,
    val currentBalanceVersion: Long,
    val sourceChangedSinceSnapshot: Boolean,
    val state: String,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String,
    val version: Long,
)

@Serializable
data class InventoryAdjustmentRequestDto(
    val id: String,
    val discrepancyId: String,
    val sessionId: String,
    val organizationId: String,
    val stockItemId: String,
    val storageLocationId: String,
    val deltaQuantity: String,
    val unitCode: String,
    val reason: String,
    val requestedBy: String,
    val requestedAt: String,
    val approvalRequestId: String? = null,
    val approvalStatus: String? = null,
    val appliedMovementId: String? = null,
    val appliedBy: String? = null,
    val appliedAt: String? = null,
    val version: Long,
)

@Serializable
data class CreateStocktakeDto(
    val operationId: String,
    val organizationId: String,
    val warehouseId: String,
    val rootStorageLocationId: String,
    val categoryIds: List<String> = emptyList(),
    val stockItemIds: List<String> = emptyList(),
)
@Serializable
data class StartStocktakeDto(val operationId: String, val sessionVersion: Long)
@Serializable
data class RecordStockCountDto(
    val operationId: String,
    val sessionVersion: Long,
    val snapshotLineId: String,
    val countedQuantity: String,
    val unitCode: String,
    val scanSource: String? = null,
    val notes: String? = null,
)
@Serializable
data class SubmitStocktakeDto(val operationId: String, val sessionVersion: Long)
@Serializable
data class RequestInventoryAdjustmentDto(
    val operationId: String,
    val discrepancyVersion: Long,
    val reason: String,
)

@Serializable
data class StocktakeSessionResultDto(
    val session: StocktakeSessionDto,
    val snapshotLines: List<StocktakeSnapshotLineDto> = emptyList(),
    val replayed: Boolean,
)
@Serializable
data class StockCountResultDto(val count: StockCountRecordDto, val replayed: Boolean)
@Serializable
data class StocktakeSubmitResultDto(
    val session: StocktakeSessionDto,
    val discrepancies: List<InventoryDiscrepancyDto>,
    val replayed: Boolean,
)
@Serializable
data class InventoryAdjustmentRequestResultDto(
    val request: InventoryAdjustmentRequestDto,
    val replayed: Boolean,
)
@Serializable
data class StocktakeDetailDto(
    val session: StocktakeSessionDto,
    val snapshotLines: List<StocktakeSnapshotLineDto>,
    val countRecords: List<StockCountRecordDto>,
    val discrepancies: List<InventoryDiscrepancyDto>,
    val adjustmentRequests: List<InventoryAdjustmentRequestDto>,
)
@Serializable
data class StocktakeVariancesDto(
    val session: StocktakeSessionDto,
    val discrepancies: List<InventoryDiscrepancyDto>,
    val adjustmentRequests: List<InventoryAdjustmentRequestDto>,
)
