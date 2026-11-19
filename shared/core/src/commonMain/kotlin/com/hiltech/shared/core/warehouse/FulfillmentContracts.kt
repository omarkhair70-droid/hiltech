package com.hiltech.shared.core.warehouse

import kotlinx.serialization.Serializable

@Serializable
data class IssueReservedStockDto(
    val operationId: String, val workOrderId: String, val requirementId: String, val employeeId: String,
    val reservationId: String, val storageLocationId: String, val workVersion: Long,
    val requirementVersion: Long, val reservationVersion: Long, val balanceVersion: Long,
    val quantity: String, val unitCode: String,
)

@Serializable
data class AdjustIssuedStockDto(
    val operationId: String, val workOrderId: String, val requirementId: String, val employeeId: String,
    val workVersion: Long, val issuedVersion: Long, val quantity: String, val unitCode: String,
    val storageLocationId: String? = null,
)

@Serializable
data class IssuedStockContextDto(
    val stockItemId: String, val employeeId: String, val projectId: String, val siteId: String,
    val workOrderId: String, val requirementId: String, val outstandingQuantity: String, val version: Long,
)
@Serializable
data class StockFulfillmentReceiptDto(
    val movementId: String, val operationId: String, val type: String, val itemId: String,
    val employeeId: String, val workOrderId: String, val requirementId: String, val quantity: String,
    val unitCode: String, val replayed: Boolean,
)

@Serializable
data class CheckoutReservedAssetDto(
    val operationId: String, val assetVersion: Long, val sourceMovementId: String,
    val workOrderId: String, val workVersion: Long, val reservationId: String,
    val reservationVersion: Long, val employeeId: String, val expectedReturnAt: String? = null,
)
@Serializable
data class ReturnPhysicalAssetDto(
    val operationId: String, val assetVersion: Long, val sourceMovementId: String,
    val storageLocationId: String, val condition: AssetCondition,
)
@Serializable
data class TransferPhysicalAssetDto(
    val operationId: String, val assetVersion: Long, val sourceMovementId: String,
    val storageLocationId: String,
)
@Serializable
data class InspectPhysicalReturnDto(
    val operationId: String, val assetVersion: Long, val condition: AssetCondition,
    val notes: String? = null, val damageIncidentId: String? = null,
)
@Serializable
data class PhysicalAssetReceiptDto(val recordId: String, val assetId: String, val kind: String, val replayed: Boolean)

@Serializable
data class FulfillmentEmployeeChoiceDto(val id: String, val code: String, val name: String)

@Serializable
data class IssuedStockDirectoryEntryDto(
    val context: IssuedStockContextDto, val employeeCode: String, val employeeName: String,
    val workCode: String, val workTitle: String, val workVersion: Long, val unitCode: String,
)
@Serializable
data class IssuedStockDirectoryDto(val items: List<IssuedStockDirectoryEntryDto>, val limited: Boolean)

@Serializable
data class StockFulfillmentHistoryRowDto(
    val movementId: String, val operationId: String, val type: String, val quantity: String, val unitCode: String,
    val employeeCode: String, val employeeName: String, val workOrderId: String, val requirementId: String,
    val fromLocationCode: String? = null, val toLocationCode: String? = null, val occurredAt: String,
)

@Serializable
data class StockFulfillmentHistoryDto(val items: List<StockFulfillmentHistoryRowDto>, val limited: Boolean)

@Serializable
data class AssetDueReturnRowDto(
    val assetId: String, val assetCode: String, val assetName: String? = null, val employeeCode: String,
    val employeeName: String, val workOrderId: String? = null, val sourceMovementId: String,
    val expectedReturnAt: String, val overdue: Boolean,
)

@Serializable
data class AssetDueReturnQueueDto(val items: List<AssetDueReturnRowDto>, val limited: Boolean, val asOf: String)
