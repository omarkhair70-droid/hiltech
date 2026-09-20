package com.hiltech.server.projects

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ProjectLifecycleState {
    DRAFT,
    KICKOFF,
    PLANNING,
    READY,
    ACTIVE,
    ON_HOLD,
    DELIVERY_REVIEW,
    HANDOVER,
    DELIVERED,
    CLOSED,
}

enum class ProjectSourceType {
    INTERNAL,
    IMPORT,
}

enum class ProjectResponsibilityPrincipalType {
    EMPLOYEE,
    TEAM,
}

enum class ProjectResponsibilityResolutionState {
    RESOLVED_USER,
    TEAM,
    BUSINESS_ONLY_NO_LOGIN,
    UNASSIGNED,
}

enum class SiteStatus {
    ACTIVE,
    INACTIVE,
}

enum class ProjectSiteLifecycleState {
    PLANNED,
    ACTIVE,
    ON_HOLD,
    COMPLETED,
    CLOSED,
}

data class ProjectResponsibilitySnapshot(
    val responsibilityId: UUID,
    val projectId: UUID,
    val organizationId: UUID,
    val responsibilityKey: String,
    val principalType: ProjectResponsibilityPrincipalType,
    val principalId: UUID,
    val principalLabel: String,
    val linkedUserIdentityId: UUID?,
    val resolutionState: ProjectResponsibilityResolutionState,
    val effectiveFrom: Instant,
    val version: Long,
)

data class ProjectSnapshot(
    val projectId: UUID,
    val organizationId: UUID,
    val projectCode: String,
    val name: String,
    val clientOrganizationId: UUID,
    val clientDisplayName: String,
    val sourceType: ProjectSourceType,
    val sourceExternalReference: String?,
    val lifecycleState: ProjectLifecycleState,
    val responsibility: ProjectResponsibilitySnapshot?,
    val startDatePlanned: LocalDate?,
    val endDatePlanned: LocalDate?,
    val baselineVersion: Int,
    val siteCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class SiteSnapshot(
    val siteId: UUID,
    val organizationId: UUID,
    val clientOrganizationId: UUID,
    val clientDisplayName: String,
    val siteCode: String,
    val name: String,
    val addressText: String?,
    val latitude: Double?,
    val longitude: Double?,
    val timezone: String?,
    val status: SiteStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class ProjectSiteSnapshot(
    val projectSiteId: UUID,
    val organizationId: UUID,
    val projectId: UUID,
    val site: SiteSnapshot,
    val projectSiteCode: String?,
    val lifecycleState: ProjectSiteLifecycleState,
    val accessInstructions: String?,
    val projectSpecificNotes: String?,
    val activeFrom: Instant?,
    val activeUntil: Instant?,
    val version: Long,
)

data class ProjectPrincipalInput(
    val principalType: ProjectResponsibilityPrincipalType,
    val principalId: UUID,
)

data class CreateProjectCommand(
    val operationId: UUID,
    val organizationId: UUID,
    val sourceType: ProjectSourceType,
    val sourceExternalReference: String?,
    val explicitProjectCode: String?,
    val name: String,
    val clientOrganizationId: UUID,
    val initialResponsibility: ProjectPrincipalInput?,
    val startDatePlanned: LocalDate?,
    val endDatePlanned: LocalDate?,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class UpdateProjectDetailsCommand(
    val operationId: UUID,
    val projectId: UUID,
    val baseVersion: Long,
    val name: String,
    val startDatePlanned: LocalDate?,
    val endDatePlanned: LocalDate?,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class ChangeProjectManagerCommand(
    val operationId: UUID,
    val projectId: UUID,
    val baseVersion: Long,
    val principal: ProjectPrincipalInput,
    val reason: String?,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class ProjectLifecycleCommand(
    val operationId: UUID,
    val projectId: UUID,
    val baseVersion: Long,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class CreateSiteCommand(
    val operationId: UUID,
    val organizationId: UUID,
    val clientOrganizationId: UUID,
    val siteCode: String,
    val name: String,
    val addressText: String?,
    val latitude: Double?,
    val longitude: Double?,
    val timezone: String?,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class UpdateSiteCommand(
    val operationId: UUID,
    val siteId: UUID,
    val baseVersion: Long,
    val name: String,
    val addressText: String?,
    val latitude: Double?,
    val longitude: Double?,
    val timezone: String?,
    val status: SiteStatus,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class AttachProjectSiteCommand(
    val operationId: UUID,
    val projectId: UUID,
    val baseProjectVersion: Long,
    val siteId: UUID,
    val projectSiteCode: String?,
    val accessInstructions: String?,
    val projectSpecificNotes: String?,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class ProjectCommandResult(
    val project: ProjectSnapshot,
    val replayed: Boolean,
)

data class SiteCommandResult(
    val site: SiteSnapshot,
    val replayed: Boolean,
)

data class ProjectSiteCommandResult(
    val project: ProjectSnapshot,
    val projectSite: ProjectSiteSnapshot,
    val replayed: Boolean,
)

sealed interface ProjectDomainEvent {
    val eventId: UUID
    val sourceId: UUID
    val sourceVersion: Long
    val actorUserId: UUID
    val occurredAt: Instant
    val correlationId: String
}

data class ProjectCreated(
    override val eventId: UUID = UUID.randomUUID(),
    val projectId: UUID,
    val organizationId: UUID,
    override val sourceVersion: Long,
    override val actorUserId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : ProjectDomainEvent {
    override val sourceId: UUID = projectId
}

data class ProjectResponsibilityChanged(
    override val eventId: UUID = UUID.randomUUID(),
    val projectId: UUID,
    val organizationId: UUID,
    val responsibilityId: UUID,
    override val sourceVersion: Long,
    override val actorUserId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : ProjectDomainEvent {
    override val sourceId: UUID = projectId
}

data class ProjectLifecycleChanged(
    override val eventId: UUID = UUID.randomUUID(),
    val projectId: UUID,
    val organizationId: UUID,
    val fromState: ProjectLifecycleState,
    val toState: ProjectLifecycleState,
    override val sourceVersion: Long,
    override val actorUserId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : ProjectDomainEvent {
    override val sourceId: UUID = projectId
}

data class SiteCreated(
    override val eventId: UUID = UUID.randomUUID(),
    val siteId: UUID,
    val organizationId: UUID,
    override val sourceVersion: Long,
    override val actorUserId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : ProjectDomainEvent {
    override val sourceId: UUID = siteId
}

data class SiteUpdated(
    override val eventId: UUID = UUID.randomUUID(),
    val siteId: UUID,
    val organizationId: UUID,
    override val sourceVersion: Long,
    override val actorUserId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : ProjectDomainEvent {
    override val sourceId: UUID = siteId
}

data class ProjectSiteAttached(
    override val eventId: UUID = UUID.randomUUID(),
    val projectSiteId: UUID,
    val projectId: UUID,
    val siteId: UUID,
    val organizationId: UUID,
    override val sourceVersion: Long,
    override val actorUserId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : ProjectDomainEvent {
    override val sourceId: UUID = projectSiteId
}
