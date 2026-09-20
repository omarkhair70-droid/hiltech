package com.hiltech.shared.core.projects

import kotlinx.serialization.Serializable

@Serializable
data class ProjectPrincipalDto(
    val principalType: String,
    val principalId: String,
)

@Serializable
data class CreateProjectRequestDto(
    val operationId: String,
    val organizationId: String,
    val sourceType: String,
    val sourceExternalReference: String? = null,
    val explicitProjectCode: String? = null,
    val name: String,
    val clientOrganizationId: String,
    val initialResponsibility: ProjectPrincipalDto? = null,
    val startDatePlanned: String? = null,
    val endDatePlanned: String? = null,
    val clientOccurredAt: String,
)

@Serializable
data class UpdateProjectDetailsRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val name: String,
    val startDatePlanned: String? = null,
    val endDatePlanned: String? = null,
    val clientOccurredAt: String,
)

@Serializable
data class ChangeProjectManagerRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val principal: ProjectPrincipalDto,
    val reason: String? = null,
    val clientOccurredAt: String,
)

@Serializable
data class ProjectTransitionRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val clientOccurredAt: String,
)

@Serializable
data class CreateSiteRequestDto(
    val operationId: String,
    val organizationId: String,
    val clientOrganizationId: String,
    val siteCode: String,
    val name: String,
    val addressText: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    val clientOccurredAt: String,
)

@Serializable
data class UpdateSiteRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val name: String,
    val addressText: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    val status: String,
    val clientOccurredAt: String,
)

@Serializable
data class CreateProjectSiteSiteRequestDto(
    val siteCode: String,
    val name: String,
    val addressText: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
)

@Serializable
data class AttachProjectSiteRequestDto(
    val operationId: String,
    val baseProjectVersion: Long,
    val siteId: String? = null,
    val createSite: CreateProjectSiteSiteRequestDto? = null,
    val projectSiteCode: String? = null,
    val accessInstructions: String? = null,
    val projectSpecificNotes: String? = null,
    val clientOccurredAt: String,
)

@Serializable
data class ProjectResponsibilityDto(
    val responsibilityId: String? = null,
    val principalType: String? = null,
    val principalId: String? = null,
    val principalLabel: String? = null,
    val resolutionState: String,
    val effectiveFrom: String? = null,
    val version: Long? = null,
)

@Serializable
data class ProjectSummaryDto(
    val projectId: String,
    val organizationId: String,
    val projectCode: String,
    val name: String,
    val clientOrganizationId: String,
    val clientDisplayName: String,
    val sourceType: String,
    val sourceExternalReference: String? = null,
    val lifecycleState: String,
    val currentResponsibility: ProjectResponsibilityDto,
    val startDatePlanned: String? = null,
    val endDatePlanned: String? = null,
    val baselineVersion: Int,
    val siteCount: Int,
    val createdAt: String,
    val updatedAt: String,
    val version: Long,
)

@Serializable
data class ProjectListDto(
    val items: List<ProjectSummaryDto>,
    val correlationId: String,
)

@Serializable
data class ProjectCommandResponseDto(
    val project: ProjectSummaryDto,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class SiteDto(
    val siteId: String,
    val organizationId: String,
    val clientOrganizationId: String,
    val clientDisplayName: String,
    val siteCode: String,
    val name: String,
    val addressText: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val version: Long,
)

@Serializable
data class SiteCommandResponseDto(
    val site: SiteDto,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class ProjectSiteDto(
    val projectSiteId: String,
    val organizationId: String,
    val projectId: String,
    val site: SiteDto,
    val projectSiteCode: String? = null,
    val lifecycleState: String,
    val accessInstructions: String? = null,
    val projectSpecificNotes: String? = null,
    val activeFrom: String? = null,
    val activeUntil: String? = null,
    val version: Long,
)

@Serializable
data class ProjectSiteListDto(
    val items: List<ProjectSiteDto>,
    val correlationId: String,
)

@Serializable
data class ProjectSiteCommandResponseDto(
    val project: ProjectSummaryDto,
    val projectSite: ProjectSiteDto,
    val replayed: Boolean,
    val correlationId: String,
)
