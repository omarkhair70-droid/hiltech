package com.hiltech.server.work

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class WorkOrderLifecycle { DRAFT, PLANNED, ASSIGNED, IN_PROGRESS, BLOCKED, SUBMITTED_FOR_REVIEW, REWORK_REQUIRED, ACCEPTED, CLOSED, CANCELLED }
enum class WorkReadiness { NOT_EVALUATED, READY, BLOCKED }
enum class WorkDependencyType { FINISH_TO_START }
enum class WorkTaskState { PLANNED, CANCELLED }

data class ConfigRef(val id: UUID, val code: String, val name: String, val revision: Int)

data class WorkPolicyBindingSnapshot(
    val bindingId: UUID,
    val bindingRevision: Int,
    val workType: ConfigRef,
    val assignmentPolicy: ConfigRef,
    val readinessPolicy: ConfigRef,
    val evidencePolicy: ConfigRef,
    val reviewPolicy: ConfigRef,
    val trackingPolicy: ConfigRef?,
    val checklistTemplate: ConfigRef?,
    val instructionTemplate: ConfigRef?,
    val createdAt: Instant,
)

data class WorkInstructionSnapshot(
    val instructionRevisionId: UUID,
    val revision: Int,
    val sourceTemplate: ConfigRef?,
    val payloadSchemaVersion: Int,
    val structuredPayloadJson: String,
    val summary: String?,
    val changeReason: String?,
    val createdAt: Instant,
    val correlationId: String,
)

data class WorkChecklistItemSnapshot(
    val itemId: UUID,
    val itemKey: String,
    val label: String,
    val required: Boolean,
    val sortOrder: Int,
    val completionState: String,
    val evidenceRequirementKey: String?,
    val version: Long,
)

data class WorkRequirementSnapshot(
    val requirementId: UUID,
    val family: String,
    val key: String,
    val sourceConfig: ConfigRef?,
    val required: Boolean,
    val satisfactionState: String,
    val version: Long,
)

data class WorkTaskSnapshot(
    val taskId: UUID,
    val taskCode: String?,
    val title: String,
    val description: String?,
    val sortOrder: Int,
    val mandatory: Boolean,
    val estimatedDurationMinutes: Int?,
    val evidenceRequirementKey: String?,
    val state: WorkTaskState,
    val version: Long,
)

data class WorkDependencySnapshot(
    val dependencyId: UUID,
    val predecessorWorkOrderId: UUID,
    val successorWorkOrderId: UUID,
    val type: WorkDependencyType,
    val lagMinutes: Long,
    val version: Long,
)

data class WorkOrderSnapshot(
    val workOrderId: UUID,
    val organizationId: UUID,
    val workOrderCode: String,
    val projectId: UUID,
    val siteId: UUID,
    val projectSiteId: UUID,
    val areaId: UUID?,
    val workPackageId: UUID?,
    val title: String,
    val description: String?,
    val lifecycleState: WorkOrderLifecycle,
    val readinessState: WorkReadiness,
    val plannedStart: Instant?,
    val plannedEnd: Instant?,
    val priorityCode: String,
    val countsTowardProjectProgress: Boolean,
    val progressWeight: BigDecimal,
    val baselineVersion: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    val binding: WorkPolicyBindingSnapshot?,
    val instruction: WorkInstructionSnapshot?,
    val checklist: List<WorkChecklistItemSnapshot>,
    val requirements: List<WorkRequirementSnapshot>,
    val tasks: List<WorkTaskSnapshot>,
    val dependencies: List<WorkDependencySnapshot>,
)

data class WorkTypeChoice(
    val workType: ConfigRef,
    val description: String?,
    val defaultPriorityCode: String?,
    val defaultProgressWeight: BigDecimal,
    val countsTowardProjectProgress: Boolean,
)

data class WorkOrderMutationResult(val workOrder: WorkOrderSnapshot, val replayed: Boolean)
data class WorkTaskMutationResult(val workOrder: WorkOrderSnapshot, val taskId: UUID, val replayed: Boolean)
data class WorkDependencyMutationResult(val workOrder: WorkOrderSnapshot, val dependencyId: UUID, val replayed: Boolean)
data class ProjectActivationResult(val projectId: UUID, val lifecycleState: String, val version: Long, val replayed: Boolean)

data class CreateWorkOrderCommand(
    val operationId: UUID, val projectId: UUID, val siteId: UUID, val projectSiteId: UUID,
    val areaId: UUID?, val workPackageId: UUID?, val explicitCode: String?, val workTypeCode: String,
    val workTypeRevision: Int?, val title: String, val description: String?, val plannedStart: Instant?,
    val plannedEnd: Instant?, val priorityCode: String?, val baseProjectVersion: Long,
    val expectedBaselineVersion: Int, val clientOccurredAt: Instant, val actorUserId: UUID, val correlationId: String,
)

data class UpdateWorkOrderDetailsCommand(
    val operationId: UUID, val workOrderId: UUID, val areaId: UUID?, val workPackageId: UUID?, val title: String,
    val description: String?, val plannedStart: Instant?, val plannedEnd: Instant?, val priorityCode: String,
    val baseVersion: Long, val clientOccurredAt: Instant, val actorUserId: UUID, val correlationId: String,
)

data class PlanWorkCommand(
    val operationId: UUID, val workOrderId: UUID, val workTypeCode: String, val workTypeRevision: Int?,
    val payloadSchemaVersion: Int, val structuredInstructionJson: String?, val instructionSummary: String?,
    val baseVersion: Long, val expectedBaselineVersion: Int, val clientOccurredAt: Instant,
    val actorUserId: UUID, val correlationId: String,
)

data class ReviseWorkInstructionCommand(
    val operationId: UUID, val workOrderId: UUID, val payloadSchemaVersion: Int,
    val structuredInstructionJson: String, val summary: String?, val changeReason: String,
    val baseVersion: Long, val clientOccurredAt: Instant, val actorUserId: UUID, val correlationId: String,
)

data class CreateWorkTaskCommand(
    val operationId: UUID, val workOrderId: UUID, val taskCode: String?, val title: String, val description: String?,
    val sortOrder: Int, val mandatory: Boolean, val estimatedDurationMinutes: Int?,
    val evidenceRequirementKey: String?, val baseVersion: Long, val clientOccurredAt: Instant,
    val actorUserId: UUID, val correlationId: String,
)

data class UpdateWorkTaskCommand(
    val operationId: UUID, val taskId: UUID, val taskCode: String?, val title: String, val description: String?,
    val sortOrder: Int, val mandatory: Boolean, val estimatedDurationMinutes: Int?,
    val evidenceRequirementKey: String?, val state: WorkTaskState, val baseTaskVersion: Long,
    val baseWorkOrderVersion: Long, val clientOccurredAt: Instant, val actorUserId: UUID, val correlationId: String,
)

data class AddWorkDependencyCommand(
    val operationId: UUID, val workOrderId: UUID, val predecessorWorkOrderId: UUID,
    val type: WorkDependencyType, val lagMinutes: Long, val baseVersion: Long,
    val clientOccurredAt: Instant, val actorUserId: UUID, val correlationId: String,
)

data class RemoveWorkDependencyCommand(
    val operationId: UUID, val workOrderId: UUID, val dependencyId: UUID, val baseVersion: Long,
    val baseDependencyVersion: Long, val clientOccurredAt: Instant, val actorUserId: UUID, val correlationId: String,
)

data class ActivateProjectCommand(
    val operationId: UUID, val projectId: UUID, val baseVersion: Long, val expectedBaselineVersion: Int,
    val clientOccurredAt: Instant, val actorUserId: UUID, val correlationId: String,
)

data class WorkOrderChanged(
    val eventId: UUID = UUID.randomUUID(), val workOrderId: UUID, val organizationId: UUID,
    val projectId: UUID, val siteId: UUID, val changeType: String, val sourceVersion: Long,
    val actorUserId: UUID, val occurredAt: Instant, val correlationId: String,
)

data class ProjectActivated(
    val eventId: UUID = UUID.randomUUID(), val projectId: UUID, val organizationId: UUID,
    val sourceVersion: Long, val actorUserId: UUID, val occurredAt: Instant, val correlationId: String,
)
