package com.hiltech.server.work

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class FieldConfigRefResponse(
    val id: String,
    val code: String,
    val name: String,
    val revision: Int,
)

data class FieldAssignmentResponse(
    val assignmentId: String,
    val targetType: String,
    val targetId: String,
    val targetLabel: String,
    val validFrom: String,
    val validUntil: String?,
    val version: Long,
)

data class FieldProjectResponse(
    val projectId: String,
    val projectCode: String,
    val name: String,
    val lifecycleState: String,
    val baselineVersion: Int,
)

data class FieldSiteResponse(
    val siteId: String,
    val siteCode: String,
    val name: String,
    val projectSiteId: String,
    val projectSiteCode: String?,
    val lifecycleState: String,
    val accessInstructions: String?,
    val timezone: String?,
)

data class FieldAreaResponse(
    val areaId: String,
    val label: String,
    val restricted: Boolean,
)

data class FieldTodayItemResponse(
    val workOrderId: String,
    val workOrderCode: String,
    val title: String,
    val lifecycleState: String,
    val readinessState: String,
    val plannedStart: String?,
    val plannedEnd: String?,
    val priorityCode: String,
    val project: FieldProjectResponse,
    val site: FieldSiteResponse,
    val area: FieldAreaResponse?,
    val assignment: FieldAssignmentResponse,
    val waitingReasonCodes: List<String>,
    val importantBlocker: String?,
    val instructionRevision: Int?,
    val workOrderVersion: Long,
    val bundleAsOf: String,
    val currentlyActionable: Boolean,
)

data class FieldWorkBindingResponse(
    val bindingRevision: Int,
    val workType: FieldConfigRefResponse,
    val assignmentPolicy: FieldConfigRefResponse,
    val readinessPolicy: FieldConfigRefResponse,
    val evidencePolicy: FieldConfigRefResponse,
    val reviewPolicy: FieldConfigRefResponse,
    val trackingPolicy: FieldConfigRefResponse?,
    val checklistTemplate: FieldConfigRefResponse?,
    val instructionTemplate: FieldConfigRefResponse?,
)

data class FieldInstructionResponse(
    val revision: Int,
    val payloadSchemaVersion: Int,
    val structuredPayloadJson: String,
    val summary: String?,
    val sourceTemplate: FieldConfigRefResponse?,
)

data class FieldTaskResponse(
    val taskId: String,
    val taskCode: String?,
    val title: String,
    val description: String?,
    val sortOrder: Int,
    val mandatory: Boolean,
    val estimatedDurationMinutes: Int?,
    val evidenceRequirementKey: String?,
    val version: Long,
)

data class FieldChecklistItemResponse(
    val itemId: String,
    val itemKey: String,
    val label: String,
    val required: Boolean,
    val sortOrder: Int,
    val completionState: String,
    val evidenceRequirementKey: String?,
    val version: Long,
)

data class FieldReadinessRequirementResponse(
    val requirementId: String,
    val family: String,
    val key: String,
    val typeCode: String,
    val label: String?,
    val required: Boolean,
    val satisfactionState: String,
    val evaluationReasonCode: String?,
    val sourceAsOf: String?,
    val evaluatedAt: String?,
    val waived: Boolean,
    val version: Long,
)

data class FieldEvidenceRequirementResponse(
    val requirementKey: String,
    val evidenceTypeCode: String,
    val stage: String,
    val minCount: Int,
    val maxCount: Int?,
    val offlineCaptureAllowedByPolicy: Boolean,
    val allowedContentTypes: List<String>,
)

data class FieldEvidenceMetadataResponse(
    val evidenceId: String,
    val requirementKey: String?,
    val evidenceTypeCode: String,
    val contentType: String,
    val storageState: String,
    val capturedAt: String,
    val version: Long,
)

data class FieldResourceRequirementResponse(
    val requirementId: String,
    val family: String,
    val key: String,
    val typeCode: String,
    val required: Boolean,
    val state: String,
    val integrationState: String,
    val reasonCode: String?,
    val version: Long,
)

data class FieldDocumentRefResponse(
    val documentId: String,
    val code: String,
    val title: String,
    val revision: String?,
    val sourceType: String,
)

data class FieldBlockerResponse(
    val blockerId: String,
    val requirementId: String?,
    val blockerTypeCode: String,
    val explanationCode: String?,
    val description: String,
    val state: String,
    val version: Long,
)

data class FieldExecutionCapabilitiesResponse(
    val startOffline: Boolean,
    val blockOffline: Boolean,
    val resumeOffline: Boolean,
    val evidenceCaptureOffline: Boolean,
    val submitOffline: Boolean,
    val authoritativeOfflineQueue: Boolean,
)

data class TechnicianJobBundleResponse(
    val workOrderId: String,
    val workOrderCode: String,
    val title: String,
    val description: String?,
    val lifecycleState: String,
    val readinessState: String,
    val workOrderVersion: Long,
    val project: FieldProjectResponse,
    val site: FieldSiteResponse,
    val area: FieldAreaResponse?,
    val assignment: FieldAssignmentResponse,
    val binding: FieldWorkBindingResponse?,
    val instruction: FieldInstructionResponse?,
    val tasks: List<FieldTaskResponse>,
    val checklist: List<FieldChecklistItemResponse>,
    val readinessRequirements: List<FieldReadinessRequirementResponse>,
    val evidenceRequirements: List<FieldEvidenceRequirementResponse>,
    val existingEvidence: List<FieldEvidenceMetadataResponse>,
    val resourceRequirements: List<FieldResourceRequirementResponse>,
    val safeDocumentRefs: List<FieldDocumentRefResponse>,
    val blockers: List<FieldBlockerResponse>,
    val waitingReasonCodes: List<String>,
    val executionCapabilities: FieldExecutionCapabilitiesResponse,
    val asOf: String,
)

@RestController
@RequestMapping("/v1/field")
class FieldAssignedWorkController(
    private val service: FieldAssignedWorkService,
) {
    @GetMapping("/today")
    fun today(
        request: HttpServletRequest,
    ): List<FieldTodayItemResponse> {
        val actor =
            request.actorIdentity()
        return service.today(actor).map {
            it.response()
        }
    }
}

@RestController
@RequestMapping("/v1/work-orders")
class FieldJobBundleController(
    private val service: FieldAssignedWorkService,
) {
    @GetMapping("/{workOrderId}/job-bundle")
    fun jobBundle(
        request: HttpServletRequest,
        @PathVariable workOrderId: String,
    ): TechnicianJobBundleResponse =
        service.jobBundle(
            request.actorIdentity(),
            workOrderId.uuid(),
        ).response()
}

private fun FieldTodayItem.response() =
    FieldTodayItemResponse(
        workOrderId =
            workOrderId.toString(),
        workOrderCode =
            workOrderCode,
        title = title,
        lifecycleState =
            lifecycleState.name,
        readinessState =
            readinessState.name,
        plannedStart =
            plannedStart?.toString(),
        plannedEnd =
            plannedEnd?.toString(),
        priorityCode =
            priorityCode,
        project = project.response(),
        site = site.response(),
        area = area?.response(),
        assignment =
            assignment.response(),
        waitingReasonCodes =
            waitingReasonCodes,
        importantBlocker =
            importantBlocker,
        instructionRevision =
            instructionRevision,
        workOrderVersion =
            workOrderVersion,
        bundleAsOf =
            bundleAsOf.toString(),
        currentlyActionable =
            currentlyActionable,
    )

private fun TechnicianJobBundle.response() =
    TechnicianJobBundleResponse(
        workOrderId =
            workOrderId.toString(),
        workOrderCode =
            workOrderCode,
        title = title,
        description =
            description,
        lifecycleState =
            lifecycleState.name,
        readinessState =
            readinessState.name,
        workOrderVersion =
            workOrderVersion,
        project = project.response(),
        site = site.response(),
        area = area?.response(),
        assignment =
            assignment.response(),
        binding =
            binding?.let {
                FieldWorkBindingResponse(
                    bindingRevision =
                        it.bindingRevision,
                    workType =
                        it.workType.response(),
                    assignmentPolicy =
                        it.assignmentPolicy.response(),
                    readinessPolicy =
                        it.readinessPolicy.response(),
                    evidencePolicy =
                        it.evidencePolicy.response(),
                    reviewPolicy =
                        it.reviewPolicy.response(),
                    trackingPolicy =
                        it.trackingPolicy
                            ?.response(),
                    checklistTemplate =
                        it.checklistTemplate
                            ?.response(),
                    instructionTemplate =
                        it.instructionTemplate
                            ?.response(),
                )
            },
        instruction =
            instruction?.let {
                FieldInstructionResponse(
                    revision = it.revision,
                    payloadSchemaVersion =
                        it.payloadSchemaVersion,
                    structuredPayloadJson =
                        it.structuredPayloadJson,
                    summary = it.summary,
                    sourceTemplate =
                        it.sourceTemplate
                            ?.response(),
                )
            },
        tasks =
            tasks.map {
                FieldTaskResponse(
                    taskId =
                        it.taskId.toString(),
                    taskCode =
                        it.taskCode,
                    title = it.title,
                    description =
                        it.description,
                    sortOrder =
                        it.sortOrder,
                    mandatory =
                        it.mandatory,
                    estimatedDurationMinutes =
                        it.estimatedDurationMinutes,
                    evidenceRequirementKey =
                        it.evidenceRequirementKey,
                    version = it.version,
                )
            },
        checklist =
            checklist.map {
                FieldChecklistItemResponse(
                    itemId =
                        it.itemId.toString(),
                    itemKey =
                        it.itemKey,
                    label = it.label,
                    required =
                        it.required,
                    sortOrder =
                        it.sortOrder,
                    completionState =
                        it.completionState,
                    evidenceRequirementKey =
                        it.evidenceRequirementKey,
                    version = it.version,
                )
            },
        readinessRequirements =
            readinessRequirements.map {
                FieldReadinessRequirementResponse(
                    requirementId =
                        it.requirementId
                            .toString(),
                    family = it.family,
                    key = it.key,
                    typeCode =
                        it.typeCode,
                    label = it.label,
                    required =
                        it.required,
                    satisfactionState =
                        it.satisfactionState,
                    evaluationReasonCode =
                        it.evaluationReasonCode,
                    sourceAsOf =
                        it.sourceAsOf
                            ?.toString(),
                    evaluatedAt =
                        it.evaluatedAt
                            ?.toString(),
                    waived = it.waived,
                    version = it.version,
                )
            },
        evidenceRequirements =
            evidenceRequirements.map {
                FieldEvidenceRequirementResponse(
                    requirementKey =
                        it.requirementKey,
                    evidenceTypeCode =
                        it.evidenceTypeCode,
                    stage = it.stage,
                    minCount =
                        it.minCount,
                    maxCount =
                        it.maxCount,
                    offlineCaptureAllowedByPolicy =
                        it.offlineCaptureAllowedByPolicy,
                    allowedContentTypes =
                        it.allowedContentTypes,
                )
            },
        existingEvidence =
            existingEvidence.map {
                FieldEvidenceMetadataResponse(
                    evidenceId =
                        it.evidenceId
                            .toString(),
                    requirementKey =
                        it.requirementKey,
                    evidenceTypeCode =
                        it.evidenceTypeCode,
                    contentType =
                        it.contentType,
                    storageState =
                        it.storageState,
                    capturedAt =
                        it.capturedAt
                            .toString(),
                    version = it.version,
                )
            },
        resourceRequirements =
            resourceRequirements.map {
                FieldResourceRequirementResponse(
                    requirementId =
                        it.requirementId
                            .toString(),
                    family = it.family,
                    key = it.key,
                    typeCode =
                        it.typeCode,
                    required =
                        it.required,
                    state = it.state,
                    integrationState =
                        it.integrationState,
                    reasonCode =
                        it.reasonCode,
                    version = it.version,
                )
            },
        safeDocumentRefs =
            safeDocumentRefs.map {
                FieldDocumentRefResponse(
                    documentId =
                        it.documentId
                            .toString(),
                    code = it.code,
                    title = it.title,
                    revision = it.revision,
                    sourceType =
                        it.sourceType,
                )
            },
        blockers =
            blockers.map {
                FieldBlockerResponse(
                    blockerId =
                        it.blockerId
                            .toString(),
                    requirementId =
                        it.requirementId
                            ?.toString(),
                    blockerTypeCode =
                        it.blockerTypeCode,
                    explanationCode =
                        it.explanationCode,
                    description =
                        it.description,
                    state = it.state,
                    version = it.version,
                )
            },
        waitingReasonCodes =
            waitingReasonCodes,
        executionCapabilities =
            FieldExecutionCapabilitiesResponse(
                startOffline =
                    executionCapabilities
                        .startOffline,
                blockOffline =
                    executionCapabilities
                        .blockOffline,
                resumeOffline =
                    executionCapabilities
                        .resumeOffline,
                evidenceCaptureOffline =
                    executionCapabilities
                        .evidenceCaptureOffline,
                submitOffline =
                    executionCapabilities
                        .submitOffline,
                authoritativeOfflineQueue =
                    executionCapabilities
                        .authoritativeOfflineQueue,
            ),
        asOf = asOf.toString(),
    )

private fun FieldProjectContext.response() =
    FieldProjectResponse(
        projectId =
            projectId.toString(),
        projectCode = projectCode,
        name = name,
        lifecycleState =
            lifecycleState,
        baselineVersion =
            baselineVersion,
    )

private fun FieldSiteContext.response() =
    FieldSiteResponse(
        siteId = siteId.toString(),
        siteCode = siteCode,
        name = name,
        projectSiteId =
            projectSiteId.toString(),
        projectSiteCode =
            projectSiteCode,
        lifecycleState =
            lifecycleState,
        accessInstructions =
            accessInstructions,
        timezone = timezone,
    )

private fun FieldAreaContext.response() =
    FieldAreaResponse(
        areaId = areaId.toString(),
        label = label,
        restricted = restricted,
    )

private fun FieldAssignmentContext.response() =
    FieldAssignmentResponse(
        assignmentId =
            assignmentId.toString(),
        targetType =
            targetType.name,
        targetId =
            targetId.toString(),
        targetLabel =
            targetLabel,
        validFrom =
            validFrom.toString(),
        validUntil =
            validUntil?.toString(),
        version = version,
    )

private fun ConfigRef.response() =
    FieldConfigRefResponse(
        id = id.toString(),
        code = code,
        name = name,
        revision = revision,
    )

private fun HttpServletRequest.actorIdentity(): UUID {
    val context =
        HiltechRequestContext.current(this)
    return context.identityId
        ?.let {
            runCatching {
                UUID.fromString(it)
            }.getOrNull()
        }
        ?: throw ProductApiException(
            "AUTHENTICATION_REQUIRED",
            "An authenticated identity is required.",
            HttpStatus.UNAUTHORIZED,
        )
}

private fun String.uuid(): UUID =
    runCatching {
        UUID.fromString(this)
    }.getOrElse {
        throw ProductApiException(
            "INVALID_ID",
            "The WorkOrder identifier is invalid.",
            HttpStatus.BAD_REQUEST,
        )
    }
