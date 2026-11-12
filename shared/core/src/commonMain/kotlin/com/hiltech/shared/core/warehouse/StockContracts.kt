package com.hiltech.shared.core.warehouse

import kotlinx.serialization.Serializable

@Serializable
data class StockItemDto(
    val id: String, val organizationId: String, val itemCode: String, val name: String,
    val description: String? = null, val brand: String? = null, val partNumber: String? = null,
    val categoryDefinitionId: String, val categoryRevision: Int, val baseUnitCode: String,
    val classification: String, val serialized: Boolean, val lotTracked: Boolean, val active: Boolean,
    val sourceImportRowId: String? = null, val version: Long, val openingInitialized: Boolean = false,
    val verifiedZeroOpening: Boolean = false,
)

@Serializable
data class StockUnitDto(val code: String, val name: String)
@Serializable
data class StockCategoryDto(val id: String, val name: String, val revision: Int)
@Serializable
data class StockCatalogDto(val units: List<StockUnitDto>, val categories: List<StockCategoryDto>)
@Serializable
data class StockBalanceDto(
    val storageLocationId: String, val locationCode: String, val onHandQuantity: String, val version: Long,
)
@Serializable
data class StockMovementDto(
    val id: String, val operationId: String, val type: String, val quantity: String, val unitCode: String,
    val fromStorageLocationId: String? = null, val toStorageLocationId: String? = null,
    val sourceImportRowId: String? = null, val occurredAt: String, val recordedBy: String,
)
@Serializable
data class StockMovementReceiptDto(val movement: StockMovementDto, val replayed: Boolean)

@Serializable
data class RegisterStockUnitDto(val operationId: String, val warehouseId: String, val code: String, val name: String)
@Serializable
data class CreateStockItemDto(
    val operationId: String, val warehouseId: String, val itemCode: String, val name: String,
    val description: String? = null, val brand: String? = null, val partNumber: String? = null,
    val categoryDefinitionId: String, val baseUnitCode: String, val classification: String,
    val lotTracked: Boolean = false,
)
@Serializable
data class ApproveStockItemDto(
    val operationId: String, val baseVersion: Long, val itemCode: String, val name: String, val lotTracked: Boolean = false,
)
@Serializable
data class InitializeStockOpeningDto(
    val operationId: String, val baseVersion: Long, val sourceImportRowId: String, val storageLocationId: String,
    val quantity: String, val unitCode: String, val cutoverAt: String,
)
@Serializable
data class TransferStockDto(
    val operationId: String, val baseVersion: Long, val fromStorageLocationId: String, val toStorageLocationId: String,
    val quantity: String, val unitCode: String,
)
