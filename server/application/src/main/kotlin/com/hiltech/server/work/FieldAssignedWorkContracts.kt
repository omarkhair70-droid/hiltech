package com.hiltech.server.work

import java.time.Instant
import java.util.UUID

data class FieldExecutionCapabilities(
    val startOffline: Boolean = false,
    val blockOffline: Boolean = false,
    val resumeOffline: Boolean = false,
    val evidenceCaptureOffline: Boolean = false,
    val submitOffline: Boolean = false,
    val authoritativeOfflineQueue: Boolean = false,
)

data class FieldAssignmentContext(
    val assignmentId: UUID,
    val targetType: AssignmentTargetType,
    val targetId: UUID,
    val targetLabel: String,
    val validFrom: Instant,
    val validUntil: Instant?,
    val version: Long,
)

data class FieldProjectContext(
    val projectId: UUID,
    val projectCode: String,
    val name: String,
    val lifecycleState: String,
    val baselineVersion: Int,
)

data class FieldSiteContext(
    val siteId: UUID,
    val siteCode: String,
    val name: String,
    val projectSiteId: UUID,
    val projectSiteCode: String?,
    val lifecycleState: String,
    val accessInstructions: String?,
    val timezone: String?,
)

data class FieldAreaContext(
    val areaId: UUID,
    val label: String,
    val restricted: Boolean,
)

data class FieldEvidenceRequirement(
    val requirementKey: String,
    val evidenceTypeCode: String,
    val stage: String,
    val minCount: Int,
    val maxCount: Int?,
    val offlineCaptureAllowedByPolicy: Boolean,
    val allowedContentTypes: List<String>,
)

data class FieldEvidenceMetadata(
    val evidenceId: UUID,
    val requirementKey: String?,
    val evidenceTypeCode: String,
    val contentType: String,
    val storageState: String,
    val capturedAt: Instant,
    val version: Long,
)

data class FieldResourceRequirement(
    val requirementId: UUID,
    val family: String,
    val key: String,
    val typeCode: String,
    val required: Boolean,
    val state: String,
    val integrationState: String,
    val reasonCode: String?,
    val version: Long,
)

data class FieldSafeDocumentRef(
    val documentId: UUID,
    val code: String,
    val title: String,
    val revision: String?,
    val sourceType: String,
)

data class FieldTodayItem(
    val workOrderId: UUID,
    val workOrderCode: String,
    val title: String,
    val lifecycleState: WorkOrderLifecycle,
    val readinessState: WorkReadiness,
    val plannedStart: Instant?,
    val plannedEnd: Instant?,
    val priorityCode: String,
    val project: FieldProjectContext,
    val site: FieldSiteContext,
    val area: FieldAreaContext?,
    val assignment: FieldAssignmentContext,
    val waitingReasonCodes: List<String>,
    val importantBlocker: String?,
    val instructionRevision: Int?,
    val workOrderVersion: Long,
    val bundleAsOf: Instant,
    val currentlyActionable: Boolean,
)

data class TechnicianJobBundle(
    val workOrderId: UUID,
    val workOrderCode: String,
    val title: String,
    val description: String?,
    val lifecycleState: WorkOrderLifecycle,
    val readinessState: WorkReadiness,
    val workOrderVersion: Long,
    val project: FieldProjectContext,
    val site: FieldSiteContext,
    val area: FieldAreaContext?,
    val assignment: FieldAssignmentContext,
    val binding: WorkPolicyBindingSnapshot?,
    val instruction: WorkInstructionSnapshot?,
    val tasks: List<WorkTaskSnapshot>,
    val checklist: List<WorkChecklistItemSnapshot>,
    val readinessRequirements: List<ReadinessRequirementExecutionSnapshot>,
    val evidenceRequirements: List<FieldEvidenceRequirement>,
    val existingEvidence: List<FieldEvidenceMetadata>,
    val resourceRequirements: List<FieldResourceRequirement>,
    val safeDocumentRefs: List<FieldSafeDocumentRef>,
    val blockers: List<WorkReadinessBlockerSnapshot>,
    val waitingReasonCodes: List<String>,
    val executionCapabilities: FieldExecutionCapabilities =
        FieldExecutionCapabilities(),
    val asOf: Instant,
)
