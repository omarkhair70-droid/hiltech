package com.hiltech.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.hiltech.shared.core.AssignedWorkSection
import com.hiltech.shared.core.HiltechFieldState
import com.hiltech.shared.core.work.FieldAssignmentDto
import com.hiltech.shared.core.work.FieldBlockerDto
import com.hiltech.shared.core.work.FieldConfigRefDto
import com.hiltech.shared.core.work.FieldEvidenceRequirementDto
import com.hiltech.shared.core.work.FieldExecutionCapabilitiesDto
import com.hiltech.shared.core.work.FieldProjectDto
import com.hiltech.shared.core.work.FieldReadinessRequirementDto
import com.hiltech.shared.core.work.FieldResourceRequirementDto
import com.hiltech.shared.core.work.FieldSiteDto
import com.hiltech.shared.core.work.FieldTodayItemDto
import com.hiltech.shared.core.work.FieldWorkBindingDto
import com.hiltech.shared.core.work.FieldInstructionDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto

class AssignedWorkEvidenceActivity :
    ComponentActivity() {
    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)
        val mode =
            intent.getStringExtra("mode")
                ?: "today"

        setContent {
            MaterialTheme {
                AssignedWorkSection(
                    state =
                        fixtureState(mode),
                    onRefresh = {},
                    onSelectWork = {},
                )
            }
        }
    }
}

private fun fixtureState(
    mode: String,
): HiltechFieldState =
    when (mode) {
        "revoked" ->
            HiltechFieldState(
                loading = false,
                today = emptyList(),
                selectedWorkOrderId = null,
                selectedBundle = null,
            )

        "detail" ->
            HiltechFieldState(
                loading = false,
                today =
                    listOf(
                        todayItem(
                            actionable = false,
                        ),
                    ),
                selectedWorkOrderId =
                    WORK_ORDER_ID,
                selectedBundle =
                    jobBundle(),
            )

        else ->
            HiltechFieldState(
                loading = false,
                today =
                    listOf(
                        todayItem(
                            actionable = true,
                        ),
                    ),
                selectedWorkOrderId = null,
                selectedBundle = null,
            )
    }

private fun todayItem(
    actionable: Boolean,
) =
    FieldTodayItemDto(
        workOrderId = WORK_ORDER_ID,
        workOrderCode = "WO-2026-010",
        title = "تركيب راك الموقع",
        lifecycleState = "ASSIGNED",
        readinessState =
            if (actionable) {
                "READY"
            } else {
                "BLOCKED"
            },
        plannedStart =
            "2026-09-21T07:00:00Z",
        plannedEnd =
            "2026-09-21T10:00:00Z",
        priorityCode = "HIGH",
        project = project(),
        site = site(),
        area = null,
        assignment = assignment(),
        waitingReasonCodes =
            if (actionable) {
                emptyList()
            } else {
                listOf(
                    "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                )
            },
        importantBlocker =
            if (actionable) {
                null
            } else {
                "المادة المطلوبة لسه بدون مصدر مخزون موثوق",
            },
        instructionRevision = 2,
        workOrderVersion = 8,
        bundleAsOf =
            "2026-09-21T06:30:00Z",
        currentlyActionable =
            actionable,
    )

private fun jobBundle() =
    TechnicianJobBundleDto(
        workOrderId = WORK_ORDER_ID,
        workOrderCode = "WO-2026-010",
        title = "تركيب راك الموقع",
        description =
            "نفّذ حسب تعليمات الموقع الحالية. الشاشة دي للقراءة فقط.",
        lifecycleState = "ASSIGNED",
        readinessState = "BLOCKED",
        workOrderVersion = 8,
        project = project(),
        site = site(),
        area = null,
        assignment = assignment(),
        binding =
            FieldWorkBindingDto(
                bindingRevision = 3,
                workType =
                    config(
                        "RACK-INSTALL",
                        "Rack installation",
                        4,
                    ),
                assignmentPolicy =
                    config(
                        "FIELD-ASSIGN",
                        "Field assignment",
                        2,
                    ),
                readinessPolicy =
                    config(
                        "FIELD-READY",
                        "Field readiness",
                        3,
                    ),
                evidencePolicy =
                    config(
                        "FIELD-EVIDENCE",
                        "Field evidence",
                        2,
                    ),
                reviewPolicy =
                    config(
                        "TECH-REVIEW",
                        "Technical review",
                        1,
                    ),
                trackingPolicy = null,
                checklistTemplate = null,
                instructionTemplate =
                    config(
                        "RACK-INSTRUCTION",
                        "Rack instruction",
                        2,
                    ),
            ),
        instruction =
            FieldInstructionDto(
                revision = 2,
                payloadSchemaVersion = 1,
                structuredPayloadJson =
                    """{"steps":["Verify rack position","Confirm grounding"]}""",
                summary =
                    "راجع مكان الراك والتأريض قبل أي تنفيذ.",
                sourceTemplate =
                    config(
                        "RACK-INSTRUCTION",
                        "Rack instruction",
                        2,
                    ),
            ),
        tasks = emptyList(),
        checklist = emptyList(),
        readinessRequirements =
            listOf(
                FieldReadinessRequirementDto(
                    requirementId =
                        REQUIREMENT_ID,
                    family = "MATERIAL",
                    key = "FIBER-PATCH-CORD",
                    typeCode = "MATERIAL",
                    label =
                        "Fiber patch cord",
                    required = true,
                    satisfactionState =
                        "BLOCKED",
                    evaluationReasonCode =
                        "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                    waived = false,
                    version = 2,
                ),
            ),
        evidenceRequirements =
            listOf(
                FieldEvidenceRequirementDto(
                    requirementKey =
                        "INSTALLATION-PHOTO",
                    evidenceTypeCode = "PHOTO",
                    stage = "BEFORE_SUBMIT",
                    minCount = 1,
                    maxCount = 4,
                    offlineCaptureAllowedByPolicy =
                        true,
                    allowedContentTypes =
                        listOf(
                            "image/jpeg",
                        ),
                ),
            ),
        existingEvidence = emptyList(),
        resourceRequirements =
            listOf(
                FieldResourceRequirementDto(
                    requirementId =
                        REQUIREMENT_ID,
                    family = "MATERIAL",
                    key = "FIBER-PATCH-CORD",
                    typeCode = "MATERIAL",
                    required = true,
                    state = "BLOCKED",
                    integrationState =
                        "SOURCE_PENDING_PHASE5",
                    reasonCode =
                        "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                    version = 2,
                ),
            ),
        safeDocumentRefs = emptyList(),
        blockers =
            listOf(
                FieldBlockerDto(
                    blockerId =
                        BLOCKER_ID,
                    requirementId =
                        REQUIREMENT_ID,
                    blockerTypeCode =
                        "READINESS_REQUIREMENT",
                    explanationCode =
                        "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
                    description =
                        "المادة المطلوبة لسه بدون مصدر مخزون موثوق",
                    state = "OPEN",
                    version = 1,
                ),
            ),
        waitingReasonCodes =
            listOf(
                "PHASE5_RESOURCE_SOURCE_UNAVAILABLE",
            ),
        executionCapabilities =
            FieldExecutionCapabilitiesDto(
                startOffline = false,
                blockOffline = false,
                resumeOffline = false,
                evidenceCaptureOffline = false,
                submitOffline = false,
                authoritativeOfflineQueue =
                    false,
            ),
        asOf = "2026-09-21T06:30:00Z",
    )

private fun project() =
    FieldProjectDto(
        projectId = PROJECT_ID,
        projectCode = "P-010",
        name = "Beni Suef rollout",
        lifecycleState = "ACTIVE",
        baselineVersion = 2,
    )

private fun site() =
    FieldSiteDto(
        siteId = SITE_ID,
        siteCode = "BS-01",
        name = "موقع بني سويف 01",
        projectSiteId =
            PROJECT_SITE_ID,
        projectSiteCode = "PS-01",
        lifecycleState = "ACTIVE",
        accessInstructions =
            "اتصل بمسؤول الموقع قبل الدخول.",
        timezone = "Africa/Cairo",
    )

private fun assignment() =
    FieldAssignmentDto(
        assignmentId =
            ASSIGNMENT_ID,
        targetType = "USER",
        targetId = USER_ID,
        targetLabel = "الفني الحالي",
        validFrom =
            "2026-09-21T06:00:00Z",
        validUntil = null,
        version = 1,
    )

private fun config(
    code: String,
    name: String,
    revision: Int,
) =
    FieldConfigRefDto(
        id =
            java.util.UUID
                .nameUUIDFromBytes(
                    code.toByteArray(),
                )
                .toString(),
        code = code,
        name = name,
        revision = revision,
    )

private const val WORK_ORDER_ID =
    "11111111-1111-4111-8111-111111111111"
private const val PROJECT_ID =
    "22222222-2222-4222-8222-222222222222"
private const val SITE_ID =
    "33333333-3333-4333-8333-333333333333"
private const val PROJECT_SITE_ID =
    "44444444-4444-4444-8444-444444444444"
private const val ASSIGNMENT_ID =
    "55555555-5555-4555-8555-555555555555"
private const val USER_ID =
    "66666666-6666-4666-8666-666666666666"
private const val REQUIREMENT_ID =
    "77777777-7777-4777-8777-777777777777"
private const val BLOCKER_ID =
    "88888888-8888-4888-8888-888888888888"
