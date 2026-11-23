package com.hiltech.shared.core.executive

import kotlinx.serialization.Serializable

/** A source-owned reference. Navigation/commands are delegated to the owning module. */
@Serializable
data class ExecutiveSourceReferenceDto(
    val module: String,
    val sourceType: String,
    val sourceId: String,
    val sourceVersion: Long? = null,
    val readPath: String,
)

@Serializable
data class ExecutiveDecisionDto(
    val safeTitleCode: String,
    val safeSummary: String? = null,
    val attentionClass: String,
    val updatedAt: String,
    val reference: ExecutiveSourceReferenceDto,
)

@Serializable
data class ExecutiveHealthSignalDto(
    val code: String,
    val severity: String,
    val summary: String,
    val lastObservedAt: String,
    val reference: ExecutiveSourceReferenceDto,
)

@Serializable
data class ExecutiveProjectDto(
    val projectId: String,
    val projectCode: String,
    val name: String,
    val lifecycleState: String,
    val projectVersion: Long,
    val projectUpdatedAt: String,
    val healthState: String,
    val healthAsOf: String? = null,
    val healthPolicyRevision: Int? = null,
    val reasonCodes: List<String> = emptyList(),
    val signals: List<ExecutiveHealthSignalDto> = emptyList(),
    val reference: ExecutiveSourceReferenceDto,
)

@Serializable
data class ExecutiveAssetExceptionDto(
    val warehouseId: String,
    val assetId: String,
    val assetCode: String,
    val name: String? = null,
    val availabilityState: String,
    val calibrationStatus: String,
    val maintenanceStatus: String,
    val reasonCodes: List<String> = emptyList(),
    val evaluatedAt: String,
    val reference: ExecutiveSourceReferenceDto,
)

@Serializable
data class ExecutiveSectionDto<T>(
    val status: String,
    val items: List<T>,
    val source: String,
    val sourceAsOf: String? = null,
    val partial: Boolean,
    val nextCursor: String? = null,
    val unavailableReason: String? = null,
)

@Serializable
data class ExecutivePendingDomainDto(
    val domain: String,
    val status: String,
)

@Serializable
data class ExecutiveHomeDto(
    val viewerIdentityId: String,
    val organizationId: String,
    val asOf: String,
    val correlationId: String,
    val needsYouScope: String,
    val needsYou: ExecutiveSectionDto<ExecutiveDecisionDto>,
    val projects: ExecutiveSectionDto<ExecutiveProjectDto>,
    val assetExceptions: ExecutiveSectionDto<ExecutiveAssetExceptionDto>,
    val pendingDomains: List<ExecutivePendingDomainDto>,
)
