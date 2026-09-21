package com.hiltech.server.work

import com.hiltech.server.projects.ProjectLifecycleState
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ReviewMode {
    ANY_ONE,
    ALL,
    SEQUENTIAL,
    QUORUM,
}

enum class ReviewSelectorType {
    RELATIONSHIP,
    ROLE,
    TEAM,
    SPECIFIC_USER,
    SUBJECT_MANAGER_CHAIN,
    CLIENT_RELATIONSHIP,
}

enum class WorkReviewDecisionType {
    ACCEPT,
    REWORK,
    REJECT,
    TECHNICAL_HOLD,
}

enum class ProjectHealthState {
    UNKNOWN,
    HEALTHY,
    ATTENTION,
    CRITICAL,
    ON_HOLD,
}

enum class ProjectHealthSignalCode {
    OVERDUE_WORK,
    BLOCKED_WORK,
    REWORK_BACKLOG,
    READINESS_FAILURE,
    MILESTONE_DELAY,
    CLIENT_ACTION_REQUIRED,
}

enum class ProjectHealthSeverity {
    ATTENTION,
    CRITICAL,
}

data class ReviewStepExecutionSnapshot(
    val stepId: UUID,
    val stepKey: String,
    val sequence: Int,
    val selectorType: ReviewSelectorType,
    val selectorValue: String?,
    val quorumCount: Int?,
    val reauthRequired: Boolean,
    val reasonRequiredOnRework: Boolean,
    val reasonRequiredOnReject: Boolean,
    val evidenceVisibilityMode: String,
    val escalationPolicyRef: UUID?,
)

data class ReviewPolicyExecutionSnapshot(
    val configId: UUID,
    val revision: Int,
    val code: String,
    val name: String,
    val mode: ReviewMode,
    val bindExactSubmittedVersion: Boolean,
    val clientAcceptanceSeparate: Boolean,
    val allowDelegation: Boolean,
    val steps: List<ReviewStepExecutionSnapshot>,
)

data class ReviewDecisionSnapshot(
    val decisionId: UUID,
    val workOrderId: UUID,
    val submittedWorkVersion: Long,
    val reviewPolicyId: UUID,
    val reviewPolicyRevision: Int,
    val reviewStepKey: String?,
    val reviewerUserId: UUID,
    val decision: WorkReviewDecisionType,
    val reason: String?,
    val decidedAt: Instant,
    val delegationId: UUID?,
    val reviewerSourceType: String?,
    val reviewerSourceRef: String?,
    val sequence: Int?,
)

data class ReviewerEligibilitySnapshot(
    val eligible: Boolean,
    val reviewStepKey: String?,
    val sourceType: String?,
    val sourceRef: String?,
    val reasonCodes: List<String>,
)

data class ReviewWorkItemSnapshot(
    val workOrderId: UUID,
    val workOrderCode: String,
    val projectId: UUID,
    val projectCode: String,
    val projectName: String,
    val siteId: UUID,
    val title: String,
    val submittedAt: Instant?,
    val submittedVersion: Long?,
    val currentVersion: Long,
    val reviewPolicy: ReviewPolicyExecutionSnapshot,
    val decisions: List<ReviewDecisionSnapshot>,
    val reviewer: ReviewerEligibilitySnapshot,
    val beforeAcceptEvidenceSatisfied: Boolean,
    val evidenceReasonCodes: List<String>,
)

data class ReviewMutationResult(
    val workOrder: WorkOrderSnapshot,
    val decision: ReviewDecisionSnapshot,
    val replayed: Boolean,
)

data class ProjectProgressSnapshot(
    val projectId: UUID,
    val projectVersion: Long,
    val baselineVersion: Int,
    val acceptedWeight: BigDecimal,
    val totalWeight: BigDecimal,
    val progressPercent: BigDecimal?,
    val includedWorkCount: Int,
    val acceptedWorkCount: Int,
    val asOf: Instant,
)

data class ProjectHealthSignalSnapshot(
    val signalId: UUID,
    val projectId: UUID,
    val baselineVersion: Int,
    val signalCode: ProjectHealthSignalCode,
    val severity: ProjectHealthSeverity,
    val sourceType: String,
    val sourceId: UUID,
    val firstObservedAt: Instant,
    val lastObservedAt: Instant,
    val summary: String,
    val current: Boolean,
    val sourceVersion: Long?,
)

data class ProjectHealthSnapshot(
    val projectId: UUID,
    val projectVersion: Long,
    val baselineVersion: Int,
    val state: ProjectHealthState,
    val healthPolicyId: UUID?,
    val healthPolicyRevision: Int?,
    val signals: List<ProjectHealthSignalSnapshot>,
    val reasonCodes: List<String>,
    val asOf: Instant,
)

data class ProjectHealthPolicyExecutionSnapshot(
    val configId: UUID,
    val revision: Int,
    val aggregationPrecedence: List<String>,
    val signalRulesJson: String,
)

data class CommandCenterMilestoneSnapshot(
    val milestoneId: UUID,
    val code: String,
    val name: String,
    val plannedDate: LocalDate?,
    val state: String,
)

data class CommandCenterWorkReference(
    val workOrderId: UUID,
    val workOrderCode: String,
    val title: String,
    val lifecycleState: WorkOrderLifecycle,
    val readinessState: WorkReadiness,
    val actionCode: String,
    val reasonCodes: List<String>,
    val projectSiteId: UUID,
    val workPackageId: UUID?,
)

data class ProjectCommandCenterSnapshot(
    val projectId: UUID,
    val projectCode: String,
    val projectName: String,
    val lifecycleState: ProjectLifecycleState,
    val projectManagerLabel: String?,
    val baselineVersion: Int,
    val progress: ProjectProgressSnapshot,
    val health: ProjectHealthSnapshot,
    val nextMilestone: CommandCenterMilestoneSnapshot?,
    val waitingOn: List<CommandCenterWorkReference>,
    val submittedReviewItems: List<ReviewWorkItemSnapshot>,
    val reworkItems: List<CommandCenterWorkReference>,
    val projectSiteIds: List<UUID>,
    val workPackageIds: List<UUID>,
)

data class AcceptWorkCommand(
    val operationId: UUID,
    val workOrderId: UUID,
    val baseVersion: Long,
    val reason: String?,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class RequestReworkCommand(
    val operationId: UUID,
    val workOrderId: UUID,
    val baseVersion: Long,
    val reason: String?,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class PutProjectOnHoldCommand(
    val operationId: UUID,
    val projectId: UUID,
    val baseVersion: Long,
    val reason: String,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class ResumeProjectCommand(
    val operationId: UUID,
    val projectId: UUID,
    val baseVersion: Long,
    val resolution: String,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class ProjectHoldMutationResult(
    val projectId: UUID,
    val lifecycleState: ProjectLifecycleState,
    val projectVersion: Long,
    val health: ProjectHealthSnapshot,
    val replayed: Boolean,
)

sealed interface Slice05DomainEvent {
    val eventId: UUID
    val sourceId: UUID
    val sourceVersion: Long
    val actorUserId: UUID
    val occurredAt: Instant
    val correlationId: String
}

data class WorkAccepted(
    override val eventId: UUID = UUID.randomUUID(),
    val workOrderId: UUID,
    val projectId: UUID,
    val decisionId: UUID,
    override val sourceVersion: Long,
    override val actorUserId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : Slice05DomainEvent {
    override val sourceId: UUID = workOrderId
}

data class WorkReworkRequested(
    override val eventId: UUID = UUID.randomUUID(),
    val workOrderId: UUID,
    val projectId: UUID,
    val decisionId: UUID,
    override val sourceVersion: Long,
    override val actorUserId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : Slice05DomainEvent {
    override val sourceId: UUID = workOrderId
}

data class ProjectHealthChanged(
    override val eventId: UUID = UUID.randomUUID(),
    val projectId: UUID,
    val previousState: ProjectHealthState,
    val newState: ProjectHealthState,
    override val sourceVersion: Long,
    override val actorUserId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : Slice05DomainEvent {
    override val sourceId: UUID = projectId
}

data class ProjectHoldChanged(
    override val eventId: UUID = UUID.randomUUID(),
    val projectId: UUID,
    val fromState: ProjectLifecycleState,
    val toState: ProjectLifecycleState,
    override val sourceVersion: Long,
    override val actorUserId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : Slice05DomainEvent {
    override val sourceId: UUID = projectId
}
