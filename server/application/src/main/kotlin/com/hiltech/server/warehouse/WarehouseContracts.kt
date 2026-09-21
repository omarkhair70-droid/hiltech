package com.hiltech.server.warehouse

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class StorageLocationKind {
    MAIN_WAREHOUSE,
    WAREHOUSE,
    PROJECT_STORAGE,
    SITE_STORAGE,
    OTHER_TYPED,
}

enum class StorageLocationLifecycle {
    ACTIVE,
    RETIRED,
}

enum class InventoryPrincipalType {
    USER,
    TEAM,
}

enum class WarehouseAuthorityKey {
    WAREHOUSE_MANAGER,
    WAREHOUSE_OPERATOR,
    WAREHOUSE_VIEWER,
    ASSET_MASTER_MANAGER,
    INVENTORY_ADJUSTMENT_APPROVER,
    INVENTORY_VALUE_VIEWER,
}

enum class StorageLocationAuthorityKey {
    LOCATION_RESPONSIBLE,
    LOCATION_OPERATOR,
    LOCATION_VIEWER,
}

enum class InventoryImportSourceType {
    LEGACY_EXCEL,
    CSV,
    MANUAL_IMPORT,
}

enum class InventoryImportBatchState {
    UPLOADED,
    PARSED,
    IN_REVIEW,
    REVIEWED,
    CANCELLED,
}

enum class InventoryCandidateClass {
    UNCLASSIFIED,
    STOCK_QUANTITY,
    STOCK_SERIALIZED,
    COMPANY_ASSET,
}

enum class InventoryImportReviewState {
    PENDING,
    NEEDS_REVIEW,
    APPROVED,
    REJECTED,
}

enum class InventoryImportIssueCode {
    UNKNOWN_UOM,
    PART_NUMBER_MISSING,
    PART_NUMBER_PLACEHOLDER,
    DUPLICATE_PART_NUMBER,
    DESCRIPTION_CONFLICT,
    BRAND_NORMALIZATION_REQUIRED,
    CATEGORY_UNMAPPED,
    SERIAL_REQUIRED_BUT_MISSING,
    QUANTITY_AMBIGUOUS,
    LEGACY_DATE_SEMANTICS_AMBIGUOUS,
    ITEM_CLASSIFICATION_REQUIRED,
    OPENING_LOCATION_REQUIRED,
}

data class WarehouseSnapshot(
    val warehouseId: UUID,
    val organizationId: UUID,
    val code: String,
    val name: String,
    val facilityRef: String?,
    val active: Boolean,
    val version: Long,
)

data class StorageLocationSnapshot(
    val storageLocationId: UUID,
    val organizationId: UUID,
    val code: String,
    val name: String,
    val kind: StorageLocationKind,
    val parentStorageLocationId: UUID?,
    val warehouseId: UUID?,
    val projectId: UUID?,
    val siteId: UUID?,
    val temporary: Boolean,
    val lifecycleState: StorageLocationLifecycle,
    val activeFrom: Instant?,
    val activeUntil: Instant?,
    val restrictedAccess: Boolean,
    val addressOrLocationRef: String?,
    val notes: String?,
    val version: Long,
)

data class WarehouseAuthorityBindingSnapshot(
    val bindingId: UUID,
    val organizationId: UUID,
    val warehouseId: UUID,
    val authorityKey: WarehouseAuthorityKey,
    val principalType: InventoryPrincipalType,
    val principalId: UUID,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val current: Boolean,
    val version: Long,
)

data class StorageAuthorityBindingSnapshot(
    val bindingId: UUID,
    val organizationId: UUID,
    val storageLocationId: UUID,
    val authorityKey: StorageLocationAuthorityKey,
    val principalType: InventoryPrincipalType,
    val principalId: UUID,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val current: Boolean,
    val version: Long,
)

data class InventoryImportBatchSnapshot(
    val batchId: UUID,
    val organizationId: UUID,
    val sourceFileName: String,
    val sourceSha256: String,
    val sourceType: InventoryImportSourceType,
    val cutoverDate: LocalDate?,
    val state: InventoryImportBatchState,
    val createdByUserId: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class InventoryImportRowSnapshot(
    val rowId: UUID,
    val batchId: UUID,
    val organizationId: UUID,
    val sourceSheet: String,
    val sourceRowNumber: Int,
    val rawPayloadJson: String,
    val candidateClass: InventoryCandidateClass,
    val normalizedBrand: String?,
    val normalizedPartNumber: String?,
    val normalizedDescription: String?,
    val candidateUom: String?,
    val candidateQuantity: BigDecimal?,
    val candidateValue: BigDecimal?,
    val candidateCurrencyCode: String?,
    val issueCodes: List<InventoryImportIssueCode>,
    val reviewState: InventoryImportReviewState,
    val reviewNotes: String?,
    val reviewedByUserId: UUID?,
    val reviewedAt: Instant?,
    val approvedTargetType: String?,
    val approvedTargetId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class InventoryImportBatchDetail(
    val batch: InventoryImportBatchSnapshot,
    val rows: List<InventoryImportRowSnapshot>,
)

data class CreateWarehouseCommand(
    val operationId: UUID,
    val organizationId: UUID,
    val code: String,
    val name: String,
    val facilityRef: String?,
    val actorUserId: UUID,
    val clientOccurredAt: Instant,
    val correlationId: String,
)

data class CreateStorageLocationCommand(
    val operationId: UUID,
    val warehouseId: UUID?,
    val organizationId: UUID,
    val code: String,
    val name: String,
    val kind: StorageLocationKind,
    val parentStorageLocationId: UUID?,
    val projectId: UUID?,
    val siteId: UUID?,
    val temporary: Boolean,
    val activeFrom: Instant?,
    val restrictedAccess: Boolean,
    val addressOrLocationRef: String?,
    val notes: String?,
    val actorUserId: UUID,
    val clientOccurredAt: Instant,
    val correlationId: String,
)

data class GrantWarehouseAuthorityCommand(
    val operationId: UUID,
    val warehouseId: UUID,
    val authorityKey: WarehouseAuthorityKey,
    val principalType: InventoryPrincipalType,
    val principalId: UUID,
    val effectiveFrom: Instant,
    val actorUserId: UUID,
    val clientOccurredAt: Instant,
    val correlationId: String,
)

data class EndWarehouseAuthorityCommand(
    val operationId: UUID,
    val warehouseId: UUID,
    val bindingId: UUID,
    val baseVersion: Long,
    val actorUserId: UUID,
    val clientOccurredAt: Instant,
    val correlationId: String,
)

data class GrantStorageAuthorityCommand(
    val operationId: UUID,
    val storageLocationId: UUID,
    val authorityKey: StorageLocationAuthorityKey,
    val principalType: InventoryPrincipalType,
    val principalId: UUID,
    val effectiveFrom: Instant,
    val actorUserId: UUID,
    val clientOccurredAt: Instant,
    val correlationId: String,
)

data class EndStorageAuthorityCommand(
    val operationId: UUID,
    val storageLocationId: UUID,
    val bindingId: UUID,
    val baseVersion: Long,
    val actorUserId: UUID,
    val clientOccurredAt: Instant,
    val correlationId: String,
)

data class CreateInventoryImportBatchCommand(
    val operationId: UUID,
    val organizationId: UUID,
    val sourceFileName: String,
    val sourceSha256: String,
    val sourceType: InventoryImportSourceType,
    val cutoverDate: LocalDate?,
    val actorUserId: UUID,
    val clientOccurredAt: Instant,
    val correlationId: String,
)

data class AddInventoryImportRowCommand(
    val operationId: UUID,
    val batchId: UUID,
    val sourceSheet: String,
    val sourceRowNumber: Int,
    val rawPayloadJson: String,
    val candidateClass: InventoryCandidateClass,
    val normalizedBrand: String?,
    val normalizedPartNumber: String?,
    val normalizedDescription: String?,
    val candidateUom: String?,
    val candidateQuantity: BigDecimal?,
    val candidateValue: BigDecimal?,
    val candidateCurrencyCode: String?,
    val sourceIssueCodes: List<InventoryImportIssueCode>,
    val actorUserId: UUID,
    val clientOccurredAt: Instant,
    val correlationId: String,
)

data class ReviewInventoryImportRowCommand(
    val operationId: UUID,
    val batchId: UUID,
    val rowId: UUID,
    val baseVersion: Long,
    val candidateClass: InventoryCandidateClass,
    val normalizedBrand: String?,
    val normalizedPartNumber: String?,
    val normalizedDescription: String?,
    val candidateUom: String?,
    val candidateQuantity: BigDecimal?,
    val candidateValue: BigDecimal?,
    val candidateCurrencyCode: String?,
    val issueCodes: List<InventoryImportIssueCode>,
    val reviewState: InventoryImportReviewState,
    val reviewNotes: String?,
    val actorUserId: UUID,
    val clientOccurredAt: Instant,
    val correlationId: String,
)

data class WarehouseCommandResult(
    val warehouse: WarehouseSnapshot,
    val replayed: Boolean,
)

data class StorageLocationCommandResult(
    val storageLocation: StorageLocationSnapshot,
    val replayed: Boolean,
)

data class ImportBatchCommandResult(
    val batch: InventoryImportBatchSnapshot,
    val replayed: Boolean,
)

data class ImportRowCommandResult(
    val row: InventoryImportRowSnapshot,
    val replayed: Boolean,
)
