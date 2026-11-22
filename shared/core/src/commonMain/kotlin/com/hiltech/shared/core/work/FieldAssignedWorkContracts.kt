package com.hiltech.shared.core.work

import kotlinx.serialization.Serializable

@Serializable
data class FieldConfigRefDto(
    val id: String,
    val code: String,
    val name: String,
    val revision: Int,
)

@Serializable
data class FieldAssignmentDto(
    val assignmentId: String,
    val targetType: String,
    val targetId: String,
    val targetLabel: String,
    val validFrom: String,
    val validUntil: String? = null,
    val version: Long,
)

@Serializable
data class FieldProjectDto(
    val projectId: String,
    val projectCode: String,
    val name: String,
    val lifecycleState: String,
    val baselineVersion: Int,
)

@Serializable
data class FieldSiteDto(
    val siteId: String,
    val siteCode: String,
    val name: String,
    val projectSiteId: String,
    val projectSiteCode: String? = null,
    val lifecycleState: String,
    val accessInstructions: String? = null,
    val timezone: String? = null,
)

@Serializable
data class FieldAreaDto(
    val areaId: String,
    val label: String,
    val restricted: Boolean,
)

@Serializable
data class FieldTodayItemDto(
    val workOrderId: String,
    val workOrderCode: String,
    val title: String,
    val lifecycleState: String,
    val readinessState: String,
    val plannedStart: String? = null,
    val plannedEnd: String? = null,
    val priorityCode: String,
    val project: FieldProjectDto,
    val site: FieldSiteDto,
    val area: FieldAreaDto? = null,
    val assignment: FieldAssignmentDto,
    val waitingReasonCodes: List<String> = emptyList(),
    val importantBlocker: String? = null,
    val instructionRevision: Int? = null,
    val workOrderVersion: Long,
    val bundleAsOf: String,
    val currentlyActionable: Boolean,
)

@Serializable
data class FieldWorkBindingDto(
    val bindingRevision: Int,
    val workType: FieldConfigRefDto,
    val assignmentPolicy: FieldConfigRefDto,
    val readinessPolicy: FieldConfigRefDto,
    val evidencePolicy: FieldConfigRefDto,
    val reviewPolicy: FieldConfigRefDto,
    val trackingPolicy: FieldConfigRefDto? = null,
    val checklistTemplate: FieldConfigRefDto? = null,
    val instructionTemplate: FieldConfigRefDto? = null,
)

@Serializable
data class FieldInstructionDto(
    val revision: Int,
    val payloadSchemaVersion: Int,
    val structuredPayloadJson: String,
    val summary: String? = null,
    val sourceTemplate: FieldConfigRefDto? = null,
)

@Serializable
data class FieldTaskDto(
    val taskId: String,
    val taskCode: String? = null,
    val title: String,
    val description: String? = null,
    val sortOrder: Int,
    val mandatory: Boolean,
    val estimatedDurationMinutes: Int? = null,
    val evidenceRequirementKey: String? = null,
    val version: Long,
)

@Serializable
data class FieldChecklistItemDto(
    val itemId: String,
    val itemKey: String,
    val label: String,
    val required: Boolean,
    val sortOrder: Int,
    val completionState: String,
    val evidenceRequirementKey: String? = null,
    val version: Long,
)

@Serializable
data class FieldReadinessRequirementDto(
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
    val waived: Boolean,
    val version: Long,
)

@Serializable
data class FieldEngineeringMetricDefinitionDto(
    val key: String,
    val label: String,
    val valueType: String,
    val required: Boolean,
    val dimension: String? = null,
    val unitCode: String? = null,
    val precision: Int? = null,
    val minValue: String? = null,
    val maxValue: String? = null,
    val options: List<String> = emptyList(),
    val acceptedValues: List<String> = emptyList(),
)

@Serializable
data class FieldEngineeringTestDefinitionDto(
    val templateId: String,
    val templateCode: String,
    val templateName: String,
    val templateRevision: Int,
    val schemaVersion: Int,
    val label: String,
    val subjectLabel: String,
    val rawEvidenceRequired: Boolean,
    val instrumentAssetRequired: Boolean,
    val calibrationRequired: Boolean,
    val deviceReportedStatusAllowed: Boolean,
    val reviewerRelationshipCode: String? = null,
    val metrics: List<FieldEngineeringMetricDefinitionDto> = emptyList(),
)

@Serializable
data class FieldEngineeringTestCaptureDto(
    val operationId: String,
    val assignmentId: String,
    val assignmentVersion: Long,
    val workOrderVersion: Long,
    val evidencePolicyId: String,
    val evidencePolicyRevision: Int,
    val requirementKey: String,
    val schemaTemplateId: String,
    val schemaTemplateRevision: Int,
    val schemaVersion: Int,
    val testSubjectRef: String,
    val values: Map<String, String>,
    val instrumentAssetId: String? = null,
    val rawEvidenceId: String? = null,
    val deviceReportedStatus: String? = null,
    val capturedAt: String,
    val clientOccurredAt: String? = null,
    val supersedesResultId: String? = null,
)

@Serializable
data class FieldEngineeringTestReceiptDto(
    val resultId: String,
    val workOrderId: String,
    val requirementKey: String,
    val testSubjectRef: String,
    val evaluatedStatus: String,
    val deviceReportedStatus: String? = null,
    val reviewerRelationshipCode: String? = null,
    val supersedesResultId: String? = null,
    val replayed: Boolean,
)

@Serializable
data class FieldEvidenceRequirementDto(
    val requirementKey: String,
    val evidenceTypeCode: String,
    val stage: String,
    val minCount: Int,
    val maxCount: Int? = null,
    val offlineCaptureAllowedByPolicy: Boolean,
    val allowedContentTypes: List<String> = emptyList(),
    val structuredTest: FieldEngineeringTestDefinitionDto? = null,
)

@Serializable
data class FieldEvidenceMetadataDto(
    val evidenceId: String,
    val requirementKey: String? = null,
    val evidenceTypeCode: String,
    val contentType: String,
    val storageState: String,
    val capturedAt: String,
    val version: Long,
)

@Serializable
data class FieldResourceRequirementDto(
    val requirementId: String,
    val family: String,
    val key: String,
    val typeCode: String,
    val required: Boolean,
    val state: String,
    val integrationState: String,
    val reasonCode: String? = null,
    val version: Long,
)

@Serializable
data class FieldDocumentRefDto(
    val documentId: String,
    val code: String,
    val title: String,
    val revision: String? = null,
    val sourceType: String,
)

@Serializable
data class FieldBlockerDto(
    val blockerId: String,
    val requirementId: String? = null,
    val blockerTypeCode: String,
    val explanationCode: String? = null,
    val description: String,
    val state: String,
    val version: Long,
)

@Serializable
data class FieldExecutionCapabilitiesDto(
    val startOffline: Boolean,
    val blockOffline: Boolean,
    val resumeOffline: Boolean,
    val evidenceCaptureOffline: Boolean,
    val submitOffline: Boolean,
    val authoritativeOfflineQueue: Boolean,
)

@Serializable
data class TechnicianJobBundleDto(
    val workOrderId: String,
    val workOrderCode: String,
    val title: String,
    val description: String? = null,
    val lifecycleState: String,
    val readinessState: String,
    val workOrderVersion: Long,
    val project: FieldProjectDto,
    val site: FieldSiteDto,
    val area: FieldAreaDto? = null,
    val assignment: FieldAssignmentDto,
    val binding: FieldWorkBindingDto? = null,
    val instruction: FieldInstructionDto? = null,
    val tasks: List<FieldTaskDto> = emptyList(),
    val checklist: List<FieldChecklistItemDto> = emptyList(),
    val readinessRequirements: List<FieldReadinessRequirementDto> = emptyList(),
    val evidenceRequirements: List<FieldEvidenceRequirementDto> = emptyList(),
    val existingEvidence: List<FieldEvidenceMetadataDto> = emptyList(),
    val resourceRequirements: List<FieldResourceRequirementDto> = emptyList(),
    val safeDocumentRefs: List<FieldDocumentRefDto> = emptyList(),
    val blockers: List<FieldBlockerDto> = emptyList(),
    val waitingReasonCodes: List<String> = emptyList(),
    val executionCapabilities: FieldExecutionCapabilitiesDto,
    val asOf: String,
)
