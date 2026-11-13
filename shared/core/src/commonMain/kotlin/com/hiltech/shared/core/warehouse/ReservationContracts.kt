package com.hiltech.shared.core.warehouse

import kotlinx.serialization.Serializable

@Serializable
data class WorkResourceDemandDto(val requirementId: String, val requirementVersion: Long, val key: String, val family: String,
    val type: String, val assetTypeId: String? = null, val stockItemId: String? = null, val quantity: String? = null,
    val unitCode: String? = null, val version: Long? = null)

@Serializable
data class ResourceCandidateDto(val kind: String, val id: String, val code: String, val locationId: String? = null,
    val sourceVersion: Long, val asOf: String, val availableQuantity: String? = null, val unitCode: String? = null,
    val reasonCodes: List<String>)

@Serializable
data class RequirementCandidatesDto(val demand: WorkResourceDemandDto, val reason: String, val candidates: List<ResourceCandidateDto>)

@Serializable
data class WorkResourceCandidatesDto(val workOrderId: String, val workVersion: Long, val canRequest: Boolean,
    val requirements: List<RequirementCandidatesDto>)

@Serializable
data class ReservationDto(val kind: String, val id: String, val resourceId: String, val workOrderId: String,
    val requirementId: String, val projectId: String, val siteId: String, val storageLocationId: String? = null,
    val quantity: String? = null, val unitCode: String? = null, val state: String, val version: Long,
    val expiresAt: String, val requestedBy: String, val endReason: String? = null)

@Serializable
data class WarehouseWorkRequestDto(val workOrderId: String, val code: String, val title: String, val workVersion: Long,
    val requirements: List<RequirementCandidatesDto>)

@Serializable
data class WarehouseReservationQueueDto(val canReserve: Boolean, val requests: List<WarehouseWorkRequestDto>,
    val reservations: List<ReservationDto>)

@Serializable
data class RequestWorkResourceDto(val operationId: String, val workVersion: Long, val requirementVersion: Long,
    val assetTypeId: String? = null, val stockItemId: String? = null, val quantity: String? = null, val unitCode: String? = null)

@Serializable
data class ReserveAssetDto(val operationId: String, val workOrderId: String, val requirementId: String,
    val workVersion: Long, val requirementVersion: Long, val resourceVersion: Long, val expiresAt: String)

@Serializable
data class ReserveStockDto(val operationId: String, val workOrderId: String, val requirementId: String, val workVersion: Long,
    val requirementVersion: Long, val resourceVersion: Long, val storageLocationId: String, val quantity: String,
    val unitCode: String, val expiresAt: String)

@Serializable
data class ReleaseReservationDto(val operationId: String, val baseVersion: Long, val reason: String)
