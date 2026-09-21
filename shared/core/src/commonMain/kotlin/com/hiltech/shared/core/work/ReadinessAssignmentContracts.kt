package com.hiltech.shared.core.work

import kotlinx.serialization.Serializable

@Serializable
data class EvaluateReadinessRequestDto(
    val operationId: String,
    val baseVersion: Long,
    val clientOccurredAt: String,
)

@Serializable
data class AssignWorkRequestDto(
    val operationId: String,
    val targetType: String? = null,
    val targetId: String? = null,
    val baseVersion: Long,
    val clientOccurredAt: String,
)

@Serializable
data class ReassignWorkRequestDto(
    val operationId: String,
    val targetType: String,
    val targetId: String,
    val baseVersion: Long,
    val currentAssignmentId: String,
    val baseAssignmentVersion: Long,
    val reason: String? = null,
    val clientOccurredAt: String,
)

@Serializable
data class WaiveReadinessRequirementRequestDto(
    val operationId: String,
    val baseWorkOrderVersion: Long,
    val baseRequirementVersion: Long,
    val reason: String? = null,
    val clientOccurredAt: String,
)

@Serializable
data class EligibilityCheckDto(
    val code: String,
    val satisfied: Boolean,
    val reasonCode: String? = null,
)

@Serializable
data class EligibleTargetDto(
    val targetType: String,
    val targetId: String,
    val displayLabel: String,
    val eligible: Boolean,
    val reasonCodes: List<String> = emptyList(),
    val sourceAsOf: String,
    val sourceFreshness: String,
    val requiredRoleChecks: List<EligibilityCheckDto> = emptyList(),
    val requiredCertificationChecks: List<EligibilityCheckDto> = emptyList(),
    val currentAssignmentConflict: String? = null,
)

@Serializable
data class WorkAssignmentRecordDto(
    val assignmentId: String,
    val workOrderId: String,
    val targetType: String,
    val targetId: String,
    val targetLabel: String,
    val lead: Boolean,
    val state: String,
    val assignedAt: String,
    val assignedBy: String,
    val validFrom: String,
    val validUntil: String? = null,
    val supersedesAssignmentId: String? = null,
    val reason: String? = null,
    val version: Long,
)

@Serializable
data class ReadinessRequirementExecutionDto(
    val requirementId: String,
    val family: String,
    val key: String,
    val typeCode: String,
    val label: String? = null,
    val required: Boolean,
    val satisfactionState: String,
    val evaluationReasonCode: String? = null,
    val sourceAsOf: String? = null,
    val evaluatedAt: String? = null,
    val waiverAllowed: Boolean,
    val waiverReasonRequired: Boolean,
    val waived: Boolean,
    val waiverRef: String? = null,
    val version: Long,
)

@Serializable
data class WorkReadinessBlockerDto(
    val blockerId: String,
    val requirementId: String? = null,
    val blockerTypeCode: String,
    val explanationCode: String? = null,
    val description: String,
    val state: String,
    val version: Long,
)

@Serializable
data class WorkReadinessDto(
    val workOrderId: String,
    val workOrderVersion: Long,
    val lifecycleState: String,
    val readinessState: String,
    val assignmentMode: String,
    val requirements: List<ReadinessRequirementExecutionDto> = emptyList(),
    val blockers: List<WorkReadinessBlockerDto> = emptyList(),
    val eligibleTargets: List<EligibleTargetDto> = emptyList(),
    val currentAssignment: WorkAssignmentRecordDto? = null,
    val assignmentHistory: List<WorkAssignmentRecordDto> = emptyList(),
    val waitingOnReasonCodes: List<String> = emptyList(),
    val evaluatedAt: String? = null,
)

@Serializable
data class WorkReadinessMutationResponseDto(
    val readiness: WorkReadinessDto,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class WorkAssignmentMutationResponseDto(
    val readiness: WorkReadinessDto,
    val assignmentId: String,
    val replayed: Boolean,
    val correlationId: String,
)

@Serializable
data class WorkQueueContextDto(
    val workOrderId: String,
    val workOrderCode: String,
    val title: String,
    val actionCode: String,
    val reasonCodes: List<String> = emptyList(),
    val ownerUserId: String? = null,
)
