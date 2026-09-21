package com.hiltech.shared.core.work

import kotlinx.serialization.Serializable

@Serializable
data class ReviewStepDto(
    val stepKey: String,
    val sequence: Int,
    val selectorType: String,
    val selectorValue: String? = null,
    val quorumCount: Int? = null,
    val reauthRequired: Boolean,
    val reasonRequiredOnRework: Boolean,
    val reasonRequiredOnReject: Boolean,
    val evidenceVisibilityMode: String,
)

@Serializable
data class ReviewPolicyDto(
    val configId: String,
    val revision: Int,
    val code: String,
    val name: String,
    val mode: String,
    val bindExactSubmittedVersion: Boolean,
    val clientAcceptanceSeparate: Boolean,
    val allowDelegation: Boolean,
    val steps: List<ReviewStepDto> = emptyList(),
)

@Serializable
data class ReviewDecisionDto(
    val decisionId: String,
    val submittedWorkVersion: Long,
    val reviewStepKey: String? = null,
    val reviewerUserId: String,
    val decision: String,
    val reason: String? = null,
    val decidedAt: String,
    val reviewerSourceType: String? = null,
    val reviewerSourceRef: String? = null,
)

@Serializable
data class ReviewerEligibilityDto(
    val eligible: Boolean,
    val reviewStepKey: String? = null,
    val sourceType: String? = null,
    val sourceRef: String? = null,
    val reasonCodes: List<String> = emptyList(),
)

@Serializable
data class ReviewWorkItemDto(
    val workOrderId: String,
    val workOrderCode: String,
    val projectId: String,
    val projectCode: String,
    val projectName: String,
    val siteId: String,
    val title: String,
    val submittedAt: String? = null,
    val submittedVersion: Long? = null,
    val currentVersion: Long,
    val reviewPolicy: ReviewPolicyDto,
    val decisions: List<ReviewDecisionDto> = emptyList(),
    val reviewer: ReviewerEligibilityDto,
    val beforeAcceptEvidenceSatisfied: Boolean,
    val evidenceReasonCodes: List<String> = emptyList(),
)

@Serializable
data class ReviewDecisionRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val reason: String? = null,
    val clientOccurredAt: String,
)

@Serializable
data class ReviewMutationResponseDto(
    val workOrderId: String,
    val lifecycleState: String,
    val version: Long,
    val decision: ReviewDecisionDto,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class ProjectProgressDto(
    val projectId: String,
    val projectVersion: Long,
    val baselineVersion: Int,
    val acceptedWeight: String,
    val totalWeight: String,
    val progressPercent: String? = null,
    val includedWorkCount: Int,
    val acceptedWorkCount: Int,
    val asOf: String,
)

@Serializable
data class ProjectHealthSignalDto(
    val signalId: String,
    val signalCode: String,
    val severity: String,
    val sourceType: String,
    val sourceId: String,
    val firstObservedAt: String,
    val lastObservedAt: String,
    val summary: String,
    val sourceVersion: Long? = null,
)

@Serializable
data class ProjectHealthDto(
    val projectId: String,
    val projectVersion: Long,
    val baselineVersion: Int,
    val state: String,
    val healthPolicyId: String? = null,
    val healthPolicyRevision: Int? = null,
    val signals: List<ProjectHealthSignalDto> = emptyList(),
    val reasonCodes: List<String> = emptyList(),
    val asOf: String,
)

@Serializable
data class CommandCenterMilestoneDto(
    val milestoneId: String,
    val code: String,
    val name: String,
    val plannedDate: String? = null,
    val state: String,
)

@Serializable
data class CommandCenterWorkDto(
    val workOrderId: String,
    val workOrderCode: String,
    val title: String,
    val lifecycleState: String,
    val readinessState: String,
    val actionCode: String,
    val reasonCodes: List<String> = emptyList(),
    val projectSiteId: String,
    val workPackageId: String? = null,
)

@Serializable
data class ProjectCommandCenterDto(
    val projectId: String,
    val projectCode: String,
    val projectName: String,
    val lifecycleState: String,
    val projectManagerLabel: String? = null,
    val baselineVersion: Int,
    val progress: ProjectProgressDto,
    val health: ProjectHealthDto,
    val nextMilestone: CommandCenterMilestoneDto? = null,
    val waitingOn: List<CommandCenterWorkDto> = emptyList(),
    val submittedReviewItems: List<ReviewWorkItemDto> = emptyList(),
    val reworkItems: List<CommandCenterWorkDto> = emptyList(),
    val projectSiteIds: List<String> = emptyList(),
    val workPackageIds: List<String> = emptyList(),
)

@Serializable
data class ProjectHoldRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val reason: String,
    val clientOccurredAt: String,
)

@Serializable
data class ProjectResumeRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val resolution: String,
    val clientOccurredAt: String,
)

@Serializable
data class ProjectHoldMutationResponseDto(
    val projectId: String,
    val lifecycleState: String,
    val projectVersion: Long,
    val health: ProjectHealthDto,
    val replayed: Boolean,
    val correlationId: String,
)
