package com.hiltech.shared.core.warehouse

import kotlinx.serialization.Serializable

@Serializable
data class ReceivingRecordDto(
    val id: String, val organizationId: String, val destinationStorageLocationId: String,
    val sourceType: String, val sourceRef: String? = null, val deliveryDocumentRef: String? = null,
    val state: String, val createdBy: String, val createdAt: String, val updatedAt: String,
    val receivedBy: String? = null, val receivedAt: String? = null, val correlationId: String, val version: Long,
)

@Serializable
data class ReceivingLineDto(
    val id: String, val receivingRecordId: String, val organizationId: String, val candidateClass: String,
    val expectedStockItemId: String? = null, val expectedAssetTypeDefinitionId: String? = null,
    val expectedQuantity: String? = null, val receivedQuantity: String? = null, val unitCode: String? = null,
    val condition: AssetCondition? = null, val serials: List<String> = emptyList(), val sourceLineRef: String? = null,
    val receivedAssetId: String? = null, val receivedBy: String? = null, val receivedAt: String? = null, val version: Long,
)

@Serializable
data class ReceivingDiscrepancyDto(
    val id: String, val receivingRecordId: String, val receivingLineId: String? = null, val organizationId: String,
    val type: String, val description: String, val createdBy: String, val createdAt: String, val version: Long,
)

@Serializable
data class ReceivingDetailDto(
    val record: ReceivingRecordDto, val lines: List<ReceivingLineDto>, val discrepancies: List<ReceivingDiscrepancyDto>,
    val rejectedLineIds: List<String> = emptyList(),
    val deliveryNoteReady: Boolean = false,
)

@Serializable
data class ReceivingDirectoryDto(
    val items: List<ReceivingRecordDto>, val limited: Boolean,
    val nextUpdatedAt: String? = null, val nextId: String? = null,
)

@Serializable
data class CreateReceivingDto(
    val operationId: String, val organizationId: String, val destinationStorageLocationId: String,
    val sourceType: String, val sourceRef: String? = null, val deliveryDocumentRef: String? = null,
)
@Serializable
data class AddReceivingStockLineDto(
    val operationId: String, val recordVersion: Long, val stockItemId: String, val stockItemVersion: Long,
    val candidateClass: String, val expectedQuantity: String? = null, val unitCode: String,
    val sourceLineRef: String? = null,
)
@Serializable
data class ReceiveStockLineDto(
    val operationId: String, val lineId: String, val recordVersion: Long, val lineVersion: Long,
    val stockItemVersion: Long, val quantity: String, val unitCode: String, val condition: AssetCondition,
    val serials: List<String> = emptyList(),
)
@Serializable
data class AddReceivingAssetLineDto(
    val operationId: String, val recordVersion: Long, val assetTypeId: String, val sourceLineRef: String? = null,
)
@Serializable
data class ReceiveAssetLineDto(
    val operationId: String, val lineId: String, val recordVersion: Long, val lineVersion: Long,
    val assetCode: String, val name: String, val manufacturer: String? = null, val model: String? = null,
    val serialNumber: String? = null, val ownershipType: String, val condition: AssetCondition, val tagType: String? = null,
)
@Serializable
data class AddReceivingDiscrepancyDto(
    val operationId: String, val recordVersion: Long, val lineId: String? = null,
    val type: String, val description: String,
)
@Serializable
data class RejectPoReceivingStockLineDto(
    val operationId: String, val lineId: String,
    val recordVersion: Long, val lineVersion: Long,
    val quantity: String, val type: String, val reason: String,
)

@Serializable
data class CompleteReceivingDto(val operationId: String, val recordVersion: Long)

@Serializable
data class ReceivingRecordResultDto(val record: ReceivingRecordDto, val replayed: Boolean)
@Serializable
data class ReceivingLineResultDto(val line: ReceivingLineDto, val replayed: Boolean)
@Serializable
data class ReceivingStockReceiptDto(val line: ReceivingLineDto, val movement: StockMovementDto, val replayed: Boolean)
@Serializable
data class ReceivingAssetReceiptDto(
    val line: ReceivingLineDto, val asset: AssetIdentityDto, val movementId: String,
    val tag: AssetTagDto? = null, val replayed: Boolean,
)
@Serializable
data class ReceivingDiscrepancyResultDto(
    val discrepancy: ReceivingDiscrepancyDto, val record: ReceivingRecordDto, val replayed: Boolean,
)
