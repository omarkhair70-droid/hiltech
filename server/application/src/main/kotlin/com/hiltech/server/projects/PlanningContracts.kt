package com.hiltech.server.projects

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class MilestoneState {
    PLANNED,
    ACHIEVED,
    CANCELLED,
}

enum class WorkPackageState {
    PLANNED,
    ACTIVE,
    COMPLETED,
    CANCELLED,
}

enum class PlanNodeType {
    MILESTONE,
    WORK_PACKAGE,
}

enum class PlanDependencyType {
    FINISH_TO_START,
}

data class AreaSnapshot(
    val areaId: UUID,
    val organizationId: UUID,
    val siteId: UUID,
    val parentAreaId: UUID?,
    val typeCode: String,
    val code: String,
    val name: String,
    val sequence: Int?,
    val restrictedAccess: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class MilestoneSnapshot(
    val milestoneId: UUID,
    val organizationId: UUID,
    val projectId: UUID,
    val code: String,
    val name: String,
    val state: MilestoneState,
    val plannedDate: LocalDate?,
    val actualDate: LocalDate?,
    val sequence: Int?,
    val clientVisible: Boolean,
    val acceptanceRequirement: String?,
    val baselineVersion: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class WorkPackageOwnerSnapshot(
    val principalType: ProjectResponsibilityPrincipalType,
    val principalId: UUID,
    val principalLabel: String,
)

data class WorkPackageSnapshot(
    val workPackageId: UUID,
    val organizationId: UUID,
    val projectId: UUID,
    val projectSiteId: UUID?,
    val siteId: UUID?,
    val milestoneId: UUID?,
    val code: String,
    val name: String,
    val description: String?,
    val owner: WorkPackageOwnerSnapshot?,
    val state: WorkPackageState,
    val plannedStart: LocalDate?,
    val plannedEnd: LocalDate?,
    val sequence: Int?,
    val baselineVersion: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class PlanNodeRef(
    val type: PlanNodeType,
    val id: UUID,
)

data class PlanDependencySnapshot(
    val dependencyId: UUID,
    val organizationId: UUID,
    val projectId: UUID,
    val predecessor: PlanNodeRef,
    val successor: PlanNodeRef,
    val dependencyType: PlanDependencyType,
    val lagMinutes: Long,
    val createdAt: Instant,
    val version: Long,
)

data class PlanValidationSummary(
    val valid: Boolean,
    val reasonCodes: List<String>,
)

data class ProjectReadyGate(
    val ready: Boolean,
    val reasonCodes: List<String>,
)

data class ProjectPlanSnapshot(
    val project: ProjectSnapshot,
    val projectSites: List<ProjectSiteSnapshot>,
    val areas: List<AreaSnapshot>,
    val milestones: List<MilestoneSnapshot>,
    val workPackages: List<WorkPackageSnapshot>,
    val dependencies: List<PlanDependencySnapshot>,
    val validation: PlanValidationSummary,
    val canEditPlan: Boolean,
    val readyGate: ProjectReadyGate,
)

data class CreateAreaCommand(
    val operationId: UUID,
    val projectId: UUID,
    val projectSiteId: UUID,
    val parentAreaId: UUID?,
    val typeCode: String,
    val code: String,
    val name: String,
    val sequence: Int?,
    val restrictedAccess: Boolean,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class UpdateAreaCommand(
    val operationId: UUID,
    val areaId: UUID,
    val projectId: UUID,
    val parentAreaId: UUID?,
    val typeCode: String,
    val code: String,
    val name: String,
    val sequence: Int?,
    val restrictedAccess: Boolean,
    val baseProjectVersion: Long,
    val baseObjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class CreateMilestoneCommand(
    val operationId: UUID,
    val projectId: UUID,
    val code: String,
    val name: String,
    val plannedDate: LocalDate?,
    val sequence: Int?,
    val clientVisible: Boolean,
    val acceptanceRequirement: String?,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class UpdateMilestoneCommand(
    val operationId: UUID,
    val milestoneId: UUID,
    val code: String,
    val name: String,
    val plannedDate: LocalDate?,
    val sequence: Int?,
    val clientVisible: Boolean,
    val acceptanceRequirement: String?,
    val baseProjectVersion: Long,
    val baseObjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class CreateWorkPackageCommand(
    val operationId: UUID,
    val projectId: UUID,
    val projectSiteId: UUID?,
    val siteId: UUID?,
    val milestoneId: UUID?,
    val code: String,
    val name: String,
    val description: String?,
    val owner: ProjectPrincipalInput?,
    val plannedStart: LocalDate?,
    val plannedEnd: LocalDate?,
    val sequence: Int?,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class UpdateWorkPackageCommand(
    val operationId: UUID,
    val workPackageId: UUID,
    val projectSiteId: UUID?,
    val siteId: UUID?,
    val milestoneId: UUID?,
    val code: String,
    val name: String,
    val description: String?,
    val owner: ProjectPrincipalInput?,
    val plannedStart: LocalDate?,
    val plannedEnd: LocalDate?,
    val sequence: Int?,
    val baseProjectVersion: Long,
    val baseObjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class AddPlanDependencyCommand(
    val operationId: UUID,
    val projectId: UUID,
    val predecessor: PlanNodeRef,
    val successor: PlanNodeRef,
    val dependencyType: PlanDependencyType,
    val lagMinutes: Long,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class RemovePlanDependencyCommand(
    val operationId: UUID,
    val projectId: UUID,
    val dependencyId: UUID,
    val baseProjectVersion: Long,
    val baseObjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class MarkProjectReadyCommand(
    val operationId: UUID,
    val projectId: UUID,
    val baseProjectVersion: Long,
    val expectedBaselineVersion: Int,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class PlanningMutationResult(
    val plan: ProjectPlanSnapshot,
    val targetType: String,
    val targetId: UUID,
    val replayed: Boolean,
)

data class ProjectPlanChanged(
    override val eventId: UUID = UUID.randomUUID(),
    val projectId: UUID,
    val organizationId: UUID,
    val changeType: String,
    val changedTargetType: String,
    val changedTargetId: UUID,
    override val sourceVersion: Long,
    override val actorUserId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : ProjectDomainEvent {
    override val sourceId: UUID = projectId
}
