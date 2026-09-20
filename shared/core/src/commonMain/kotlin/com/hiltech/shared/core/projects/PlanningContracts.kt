package com.hiltech.shared.core.projects

import kotlinx.serialization.Serializable

@Serializable
data class CreateAreaRequestDto(
    val operationId: String,
    val projectSiteId: String,
    val parentAreaId: String? = null,
    val typeCode: String,
    val code: String,
    val name: String,
    val sequence: Int? = null,
    val restrictedAccess: Boolean = false,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

@Serializable
data class UpdateAreaRequestDto(
    val operationId: String,
    val projectId: String,
    val parentAreaId: String? = null,
    val typeCode: String,
    val code: String,
    val name: String,
    val sequence: Int? = null,
    val restrictedAccess: Boolean = false,
    val baseProjectVersion: Long,
    val baseObjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

@Serializable
data class CreateMilestoneRequestDto(
    val operationId: String,
    val code: String,
    val name: String,
    val plannedDate: String? = null,
    val sequence: Int? = null,
    val clientVisible: Boolean = false,
    val acceptanceRequirement: String? = null,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

@Serializable
data class UpdateMilestoneRequestDto(
    val operationId: String,
    val code: String,
    val name: String,
    val plannedDate: String? = null,
    val sequence: Int? = null,
    val clientVisible: Boolean = false,
    val acceptanceRequirement: String? = null,
    val baseProjectVersion: Long,
    val baseObjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

@Serializable
data class PlanningOwnerDto(
    val principalType: String,
    val principalId: String,
)

@Serializable
data class CreateWorkPackageRequestDto(
    val operationId: String,
    val projectSiteId: String? = null,
    val siteId: String? = null,
    val milestoneId: String? = null,
    val code: String,
    val name: String,
    val description: String? = null,
    val owner: PlanningOwnerDto? = null,
    val plannedStart: String? = null,
    val plannedEnd: String? = null,
    val sequence: Int? = null,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

@Serializable
data class UpdateWorkPackageRequestDto(
    val operationId: String,
    val projectSiteId: String? = null,
    val siteId: String? = null,
    val milestoneId: String? = null,
    val code: String,
    val name: String,
    val description: String? = null,
    val owner: PlanningOwnerDto? = null,
    val plannedStart: String? = null,
    val plannedEnd: String? = null,
    val sequence: Int? = null,
    val baseProjectVersion: Long,
    val baseObjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

@Serializable
data class PlanNodeDto(
    val type: String,
    val id: String,
)

@Serializable
data class AddPlanDependencyRequestDto(
    val operationId: String,
    val predecessor: PlanNodeDto,
    val successor: PlanNodeDto,
    val dependencyType: String = "FINISH_TO_START",
    val lagMinutes: Long = 0,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

@Serializable
data class RemovePlanDependencyRequestDto(
    val operationId: String,
    val baseProjectVersion: Long,
    val baseObjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

@Serializable
data class MarkProjectReadyRequestDto(
    val operationId: String,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: String,
)

@Serializable
data class ProjectPlanProjectDto(
    val projectId: String,
    val projectCode: String,
    val name: String,
    val lifecycleState: String,
    val version: Long,
    val baselineVersion: Int,
)

@Serializable
data class ProjectPlanSiteDto(
    val projectSiteId: String,
    val siteId: String,
    val siteCode: String,
    val name: String,
    val lifecycleState: String,
)

@Serializable
data class AreaDto(
    val areaId: String,
    val siteId: String,
    val parentAreaId: String? = null,
    val typeCode: String,
    val code: String,
    val name: String,
    val sequence: Int? = null,
    val restrictedAccess: Boolean,
    val version: Long,
)

@Serializable
data class MilestoneDto(
    val milestoneId: String,
    val code: String,
    val name: String,
    val state: String,
    val plannedDate: String? = null,
    val sequence: Int? = null,
    val clientVisible: Boolean,
    val acceptanceRequirement: String? = null,
    val baselineVersion: Int,
    val version: Long,
)

@Serializable
data class WorkPackageOwnerDto(
    val principalType: String,
    val principalId: String,
    val principalLabel: String,
)

@Serializable
data class WorkPackageDto(
    val workPackageId: String,
    val projectSiteId: String? = null,
    val siteId: String? = null,
    val milestoneId: String? = null,
    val code: String,
    val name: String,
    val description: String? = null,
    val owner: WorkPackageOwnerDto? = null,
    val state: String,
    val plannedStart: String? = null,
    val plannedEnd: String? = null,
    val sequence: Int? = null,
    val baselineVersion: Int,
    val version: Long,
)

@Serializable
data class PlanDependencyDto(
    val dependencyId: String,
    val predecessor: PlanNodeDto,
    val successor: PlanNodeDto,
    val dependencyType: String,
    val lagMinutes: Long,
    val version: Long,
)

@Serializable
data class ProjectPlanDto(
    val project: ProjectPlanProjectDto,
    val projectSites: List<ProjectPlanSiteDto>,
    val areas: List<AreaDto>,
    val milestones: List<MilestoneDto>,
    val workPackages: List<WorkPackageDto>,
    val dependencies: List<PlanDependencyDto>,
    val validationValid: Boolean,
    val validationReasonCodes: List<String>,
    val canEditPlan: Boolean,
    val readyGateReady: Boolean,
    val readyGateReasonCodes: List<String>,
    val correlationId: String,
)

@Serializable
data class PlanningMutationResponseDto(
    val plan: ProjectPlanDto,
    val targetType: String,
    val targetId: String,
    val replayed: Boolean,
    val correlationId: String,
)
