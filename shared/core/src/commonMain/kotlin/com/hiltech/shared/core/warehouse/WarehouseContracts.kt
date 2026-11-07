package com.hiltech.shared.core.warehouse

import kotlinx.serialization.Serializable

/**
 * Phase 5 / Slice 01: warehouse identity, storage locations, authority and
 * non-authoritative import review. Strings preserve server enum/date formats.
 * Stock, Asset and custody commands deliberately do not exist in this slice.
 */
@Serializable
data class WarehouseDto(
    val warehouseId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val active: Boolean,
    val version: Long,
)

@Serializable
data class WarehouseListDto(
    val items: List<WarehouseDto>,
    val correlationId: String,
)

@Serializable
data class WarehouseCreateRequestDto(
    val operationId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val facilityRef: String? = null,
    val clientOccurredAt: String,
)

@Serializable
data class WarehouseCreateResponseDto(
    val warehouseId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val active: Boolean,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class StorageLocationDto(
    val storageLocationId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val kind: String,
    val parentStorageLocationId: String? = null,
    val warehouseId: String? = null,
    val projectId: String? = null,
    val siteId: String? = null,
    val temporary: Boolean,
    val lifecycleState: String,
    val version: Long,
)

@Serializable
data class StorageLocationListDto(
    val items: List<StorageLocationDto>,
    val correlationId: String,
)

@Serializable
data class StorageLocationCreateRequestDto(
    val operationId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val kind: String,
    val parentStorageLocationId: String? = null,
    val projectId: String? = null,
    val siteId: String? = null,
    val temporary: Boolean = false,
    val activeFrom: String? = null,
    val restrictedAccess: Boolean = false,
    val addressOrLocationRef: String? = null,
    val notes: String? = null,
    val clientOccurredAt: String,
)

@Serializable
data class StorageLocationCreateResponseDto(
    val storageLocationId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val kind: String,
    val warehouseId: String? = null,
    val projectId: String? = null,
    val siteId: String? = null,
    val temporary: Boolean,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class InventoryImportBatchSummaryDto(
    val batchId: String,
    val organizationId: String,
    val sourceFileName: String,
    val sourceSha256: String,
    val sourceType: String,
    val cutoverDate: String? = null,
    val state: String,
    val version: Long,
)

@Serializable
data class InventoryImportBatchDirectoryDto(
    val items: List<InventoryImportBatchSummaryDto>,
    val correlationId: String,
)

@Serializable
data class InventoryImportBatchDto(
    val batchId: String,
    val organizationId: String,
    val sourceFileName: String,
    val sourceSha256: String,
    val sourceType: String,
    val cutoverDate: String? = null,
    val state: String,
    val version: Long,
    val rows: List<InventoryImportRowDto>,
    val correlationId: String,
)

@Serializable
data class InventoryImportRowDto(
    val rowId: String,
    val batchId: String,
    val sourceSheet: String,
    val sourceRowNumber: Int,
    val candidateClass: String,
    val normalizedBrand: String? = null,
    val normalizedPartNumber: String? = null,
    val normalizedDescription: String? = null,
    val candidateUom: String? = null,
    val candidateQuantity: String? = null,
    val issueCodes: List<String>,
    val reviewState: String,
    val version: Long,
    val sourcePreview: Map<String, String> = emptyMap(),
)

@Serializable
data class InventoryImportBatchCreateRequestDto(
    val operationId: String,
    val organizationId: String,
    val sourceFileName: String,
    val sourceSha256: String,
    val sourceType: String,
    val cutoverDate: String? = null,
    val clientOccurredAt: String,
)

@Serializable
data class InventoryImportBatchCreateResponseDto(
    val batchId: String,
    val organizationId: String,
    val state: String,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class InventoryImportRowCreateRequestDto(
    val operationId: String,
    val sourceSheet: String,
    val sourceRowNumber: Int,
    val rawPayloadJson: String,
    val candidateClass: String = "UNCLASSIFIED",
    val normalizedBrand: String? = null,
    val normalizedPartNumber: String? = null,
    val normalizedDescription: String? = null,
    val candidateUom: String? = null,
    val candidateQuantity: String? = null,
    val candidateValue: String? = null,
    val candidateCurrencyCode: String? = null,
    val sourceIssueCodes: List<String> = emptyList(),
    val clientOccurredAt: String,
)

@Serializable
data class InventoryImportRowReviewRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val candidateClass: String,
    val normalizedBrand: String? = null,
    val normalizedPartNumber: String? = null,
    val normalizedDescription: String? = null,
    val candidateUom: String? = null,
    val candidateQuantity: String? = null,
    val candidateValue: String? = null,
    val candidateCurrencyCode: String? = null,
    val issueCodes: List<String> = emptyList(),
    val reviewState: String,
    val reviewNotes: String? = null,
    val clientOccurredAt: String,
)

@Serializable
data class InventoryImportRowCommandResponseDto(
    val rowId: String,
    val batchId: String,
    val reviewState: String,
    val issueCodes: List<String>,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class WarehouseAuthorityCurrentDto(
    val bindingId: String,
    val warehouseId: String,
    val authorityKey: String,
    val principalType: String,
    val principalId: String,
    val version: Long,
)

@Serializable
data class WarehouseAuthorityListDto(
    val items: List<WarehouseAuthorityCurrentDto>,
    val correlationId: String,
)

@Serializable
data class StorageAuthorityCurrentDto(
    val bindingId: String,
    val storageLocationId: String,
    val authorityKey: String,
    val principalType: String,
    val principalId: String,
    val version: Long,
)

@Serializable
data class StorageAuthorityListDto(
    val items: List<StorageAuthorityCurrentDto>,
    val correlationId: String,
)

@Serializable
data class WarehouseAuthorityGrantRequestDto(
    val operationId: String,
    val authorityKey: String,
    val principalType: String,
    val principalId: String,
    val effectiveFrom: String,
    val clientOccurredAt: String,
)

@Serializable
data class StorageAuthorityGrantRequestDto(
    val operationId: String,
    val authorityKey: String,
    val principalType: String,
    val principalId: String,
    val effectiveFrom: String,
    val clientOccurredAt: String,
)

@Serializable
data class AuthorityEndRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val clientOccurredAt: String,
)

@Serializable
data class WarehouseAuthorityCommandResponseDto(
    val bindingId: String,
    val warehouseId: String,
    val authorityKey: String,
    val principalType: String,
    val principalId: String,
    val current: Boolean,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class StorageAuthorityCommandResponseDto(
    val bindingId: String,
    val storageLocationId: String,
    val authorityKey: String,
    val principalType: String,
    val principalId: String,
    val current: Boolean,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)
