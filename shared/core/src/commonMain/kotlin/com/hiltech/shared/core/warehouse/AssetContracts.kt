package com.hiltech.shared.core.warehouse

import kotlinx.serialization.Serializable

@Serializable
enum class AssetCondition { UNKNOWN, GOOD, FAIR, DAMAGED, UNFIT }

@Serializable
data class AssetTypeDto(
    val id: String, val name: String, val revision: Int, val serialRequired: Boolean, val calibrationRequired: Boolean,
    val highValueOrRestricted: Boolean, val allowedTagTypes: List<String>, val operatorMayTag: Boolean,
    val maintenancePolicyId: String? = null, val accessoryTemplateId: String? = null, val inspectionRequiredOnReturn: Boolean,
)

@Serializable
data class AssetIdentityDto(
    val id: String, val organizationId: String, val warehouseId: String? = null, val assetCode: String,
    val name: String? = null, val manufacturer: String? = null, val model: String? = null, val serialNumber: String? = null,
    val typeId: String, val typeName: String, val typeRevision: Int, val condition: AssetCondition,
    val lifecycle: String, val duplicateSerial: Boolean, val sourceImportRowId: String? = null, val version: Long,
)

@Serializable
data class AssetCustodyDto(
    val storageLocationId: String? = null, val locationCode: String? = null,
    val employeeId: String? = null, val employeeCode: String? = null, val projectId: String? = null,
    val siteId: String? = null, val workOrderId: String? = null, val sourceMovementId: String, val since: String? = null,
)

@Serializable
data class AssetTagDto(
    val id: String, val code: String, val type: String, val active: Boolean, val primary: Boolean,
    val replacesTagId: String? = null, val issuedAt: String, val endedAt: String? = null,
)

@Serializable
data class AssetCatalogDto(val types: List<AssetTypeDto>, val canRegister: Boolean)

@Serializable
data class AssetPassportDto(
    val identity: AssetIdentityDto, val custody: AssetCustodyDto? = null, val tags: List<AssetTagDto>,
    val permittedTagTypes: List<String> = emptyList(), val canInitialize: Boolean = false, val canCorrect: Boolean = false,
)

@Serializable
data class AssetTagLookupDto(
    val assetCode: String, val name: String? = null, val typeName: String, val manufacturer: String? = null,
    val model: String? = null, val serialNumber: String? = null, val condition: AssetCondition,
    val locationCode: String? = null, val employeeCode: String? = null, val custodySince: String? = null,
)

@Serializable
data class AssetMovementDto(
    val id: String, val type: String, val fromLocationId: String? = null, val toLocationId: String? = null,
    val fromEmployeeId: String? = null, val toEmployeeId: String? = null, val projectId: String? = null,
    val siteId: String? = null, val workOrderId: String? = null, val correctedMovementId: String? = null,
    val sourceImportRowId: String? = null, val condition: String? = null, val occurredAt: String, val reason: String? = null,
)

@Serializable
data class AssetRegistrationDto(
    val operationId: String, val warehouseId: String, val assetTypeId: String, val assetCode: String, val name: String,
    val manufacturer: String?, val model: String?, val serialNumber: String?, val ownershipType: String,
    val condition: AssetCondition, val initialLocationId: String?,
)

@Serializable
data class AssetTagCommandDto(val operationId: String, val baseVersion: Long, val tagType: String, val previousTagId: String? = null)

@Serializable
data class OpenAssetCustodyDto(
    val operationId: String, val baseVersion: Long, val condition: AssetCondition, val locationId: String?,
    val employeeId: String?, val workOrderId: String?, val cutoverAt: String?,
)

@Serializable
data class CorrectAssetCustodyDto(
    val operationId: String, val baseVersion: Long, val condition: AssetCondition, val sourceMovementId: String, val reason: String,
    val locationId: String?, val employeeId: String?, val workOrderId: String?,
)

@Serializable
data class ApproveAssetImportDto(
    val operationId: String, val baseVersion: Long, val assetCode: String, val name: String,
    val ownershipType: String, val condition: AssetCondition,
)
