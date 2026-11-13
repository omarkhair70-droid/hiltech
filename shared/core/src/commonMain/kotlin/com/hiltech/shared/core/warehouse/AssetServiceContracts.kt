package com.hiltech.shared.core.warehouse

import kotlinx.serialization.Serializable

@Serializable
data class AssetAvailabilityReasonDto(
    val state: String, val code: String, val sourceType: String, val sourceId: String? = null,
    val sourceVersion: Long? = null, val dueAt: String? = null,
)

@Serializable
data class AssetAvailabilityDto(
    val state: String, val assetVersion: Long, val evaluatedAt: String, val reasons: List<AssetAvailabilityReasonDto>,
    val calibrationStatus: String, val calibrationDueAt: String? = null, val certificateOnFile: Boolean,
    val maintenanceStatus: String, val maintenanceDueAt: String? = null,
)

@Serializable
data class SafeAssetAvailabilityDto(
    val state: String, val reasonCodes: List<String>, val calibrationStatus: String, val calibrationDueAt: String? = null,
    val certificateOnFile: Boolean, val incidentTypes: List<String> = emptyList(),
)

@Serializable
data class AssetServiceQueueEntryDto(val id: String, val assetCode: String, val name: String? = null, val availability: AssetAvailabilityDto)

@Serializable
data class AssetIncidentDto(
    val id: String, val type: String, val state: String, val description: String, val reportedBy: String, val reportedAt: String,
    val projectId: String? = null, val siteId: String? = null, val workOrderId: String? = null, val evidenceOnFile: Boolean,
    val investigationOwnerId: String? = null, val resolution: String? = null, val resolvedAt: String? = null, val version: Long,
)

@Serializable
data class AssetCalibrationDto(
    val id: String, val type: String, val result: String, val calibratedAt: String, val validUntil: String? = null,
    val providerRef: String? = null, val certificateOnFile: Boolean, val notes: String? = null, val recordedAt: String,
)

@Serializable
data class AssetMaintenanceDto(
    val id: String, val type: String, val state: String, val description: String, val providerRef: String? = null,
    val sentAt: String, val completedAt: String? = null, val completionNotes: String? = null, val version: Long,
)

@Serializable
data class AssetServiceCapabilitiesDto(val canReport: Boolean, val canSend: Boolean, val canManageService: Boolean)

@Serializable
data class AssetServiceDetailDto(
    val availability: AssetAvailabilityDto, val incidents: List<AssetIncidentDto>, val calibrations: List<AssetCalibrationDto>,
    val maintenance: List<AssetMaintenanceDto>, val capabilities: AssetServiceCapabilitiesDto,
)

@Serializable
data class ReportAssetIncidentDto(val operationId: String, val baseVersion: Long, val type: String, val description: String, val evidenceId: String? = null)

@Serializable
data class ChangeAssetIncidentDto(
    val operationId: String, val baseVersion: Long, val incidentVersion: Long, val state: String,
    val resolution: String? = null, val investigationOwnerId: String? = null,
)

@Serializable
data class RecordAssetCalibrationDto(
    val operationId: String, val baseVersion: Long, val type: String, val result: String, val calibratedAt: String,
    val validUntil: String? = null, val providerRef: String? = null, val certificateEvidenceId: String? = null, val notes: String? = null,
)

@Serializable
data class SendAssetMaintenanceDto(val operationId: String, val baseVersion: Long, val type: String, val description: String, val providerRef: String? = null)

@Serializable
data class CompleteAssetMaintenanceDto(
    val operationId: String, val baseVersion: Long, val serviceId: String, val serviceVersion: Long, val notes: String,
)
