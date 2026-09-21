package com.hiltech.server.work

import java.time.Instant
import java.util.UUID

enum class AssignmentMode {
    AUTO,
    SUGGEST_CONFIRM,
    MANUAL_ELIGIBLE,
}

enum class AssignmentTargetType {
    USER,
    TEAM,
    CREW,
    SUBCONTRACTOR_ORGANIZATION,
}

data class AssignmentPolicyExecutionSnapshot(
    val configId: UUID,
    val revision: Int,
    val mode: AssignmentMode,
    val allowedTargetTypes: Set<AssignmentTargetType>,
    val requiredRoleCodes: Set<String>,
    val requiredCertificationCodes: Set<String>,
    val allowPreboardingEmployee: Boolean,
    val allowCrossTeamAssignment: Boolean,
    val allowExternalSubcontractor: Boolean,
    val reassignmentRequiresReason: Boolean,
)

data class ReadinessPolicyExecutionSnapshot(
    val configId: UUID,
    val revision: Int,
    val allRequiredMustBeSatisfied: Boolean,
)

data class EligibilityCheck(
    val code: String,
    val satisfied: Boolean,
    val reasonCode: String? = null,
)

data class EligibleTargetResult(
    val targetType: AssignmentTargetType,
    val targetId: UUID,
    val displayLabel: String,
    val eligible: Boolean,
    val reasonCodes: List<String>,
    val sourceAsOf: Instant,
    val sourceFreshness: String = "CURRENT",
    val requiredRoleChecks: List<EligibilityCheck> = emptyList(),
    val requiredCertificationChecks: List<EligibilityCheck> = emptyList(),
    val currentAssignmentConflict: String? = null,
)

data class WorkAssignmentRecord(
    val assignmentId: UUID,
    val workOrderId: UUID,
    val targetType: AssignmentTargetType,
    val targetId: UUID,
    val targetLabel: String,
    val lead: Boolean,
    val state: String,
    val assignedAt: Instant,
    val assignedBy: UUID,
    val validFrom: Instant,
    val validUntil: Instant?,
    val supersedesAssignmentId: UUID?,
    val reason: String?,
    val version: Long,
)

data class ReadinessRequirementExecutionSnapshot(
    val requirementId: UUID,
    val family: String,
    val key: String,
    val typeCode: String,
    val label: String?,
    val required: Boolean,
    val satisfactionState: String,
    val evaluationReasonCode: String?,
    val sourceAsOf: Instant?,
    val evaluatedAt: Instant?,
    val waiverAllowed: Boolean,
    val waiverReasonRequired: Boolean,
    val waived: Boolean,
    val waiverRef: UUID?,
    val version: Long,
)

data class WorkReadinessBlockerSnapshot(
    val blockerId: UUID,
    val requirementId: UUID?,
    val blockerTypeCode: String,
    val explanationCode: String?,
    val description: String,
    val state: String,
    val version: Long,
)

data class WorkReadinessSnapshot(
    val workOrderId: UUID,
    val workOrderVersion: Long,
    val lifecycleState: WorkOrderLifecycle,
    val readinessState: WorkReadiness,
    val assignmentMode: AssignmentMode,
    val requirements: List<ReadinessRequirementExecutionSnapshot>,
    val blockers: List<WorkReadinessBlockerSnapshot>,
    val eligibleTargets: List<EligibleTargetResult>,
    val currentAssignment: WorkAssignmentRecord?,
    val assignmentHistory: List<WorkAssignmentRecord>,
    val waitingOnReasonCodes: List<String>,
    val evaluatedAt: Instant?,
)

data class WorkQueueContextItem(
    val workOrderId: UUID,
    val workOrderCode: String,
    val title: String,
    val actionCode: String,
    val reasonCodes: List<String>,
    val ownerUserId: UUID?,
)

data class EvaluateReadinessCommand(
    val operationId: UUID,
    val workOrderId: UUID,
    val baseVersion: Long,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class AssignWorkCommand(
    val operationId: UUID,
    val workOrderId: UUID,
    val targetType: AssignmentTargetType?,
    val targetId: UUID?,
    val baseVersion: Long,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class ReassignWorkCommand(
    val operationId: UUID,
    val workOrderId: UUID,
    val targetType: AssignmentTargetType,
    val targetId: UUID,
    val baseVersion: Long,
    val currentAssignmentId: UUID,
    val baseAssignmentVersion: Long,
    val reason: String?,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class WaiveReadinessRequirementCommand(
    val operationId: UUID,
    val workOrderId: UUID,
    val requirementId: UUID,
    val baseWorkOrderVersion: Long,
    val baseRequirementVersion: Long,
    val reason: String?,
    val clientOccurredAt: Instant,
    val actorUserId: UUID,
    val correlationId: String,
)

data class WorkReadinessMutationResult(
    val readiness: WorkReadinessSnapshot,
    val replayed: Boolean,
)

data class WorkAssignmentMutationResult(
    val readiness: WorkReadinessSnapshot,
    val assignmentId: UUID,
    val replayed: Boolean,
)

data class WorkReadinessChanged(
    val eventId: UUID = UUID.randomUUID(),
    val workOrderId: UUID,
    val organizationId: UUID,
    val projectId: UUID,
    val previousState: WorkReadiness,
    val newState: WorkReadiness,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

data class WorkAssignmentChanged(
    val eventId: UUID = UUID.randomUUID(),
    val workOrderId: UUID,
    val organizationId: UUID,
    val projectId: UUID,
    val previousAssignmentId: UUID?,
    val assignmentId: UUID,
    val targetType: AssignmentTargetType,
    val targetId: UUID,
    val sourceVersion: Long,
    val actorUserId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)
