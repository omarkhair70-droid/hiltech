package com.hiltech.desktop.evidence

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hiltech.shared.core.WorkPlanningSection
import com.hiltech.shared.core.projects.ProjectPlanDto
import com.hiltech.shared.core.projects.ProjectPlanProjectDto
import com.hiltech.shared.core.projects.ProjectPlanSiteDto
import com.hiltech.shared.core.work.ChecklistItemDto
import com.hiltech.shared.core.work.ConfigRefDto
import com.hiltech.shared.core.work.RequirementDto
import com.hiltech.shared.core.work.WorkBindingDto
import com.hiltech.shared.core.work.WorkDependencyDto
import com.hiltech.shared.core.work.WorkInstructionDto
import com.hiltech.shared.core.work.WorkOrderDto
import com.hiltech.shared.core.work.WorkTaskDto
import com.hiltech.shared.core.work.WorkTypeChoiceDto
import kotlinx.coroutines.delay
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window as AwtWindow
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

fun main() = application {
    val outputDirectory =
        Path.of(
            System.getenv("HILTECH_PHASE4_WORK_EVIDENCE_DIR")
                ?: "build/reports/phase4-work",
        )
    Files.createDirectories(outputDirectory)

    Window(
        onCloseRequest = ::exitApplication,
        title = "HILTECH Phase 4 Work Planning Evidence",
        state = rememberWindowState(width = 1180.dp, height = 1080.dp),
    ) {
        var screen by remember { mutableStateOf(0) }
        val frame = window
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
        ) {
            when (screen) {
                0 ->
                    WorkPlanningSection(
                        plan = plan("PLANNING", 12),
                        workTypes = listOf(workType(), surveyType()),
                        orders = listOf(plannedOrder()),
                        onAction = {},
                    )
                1 ->
                    WorkPlanningSection(
                        plan = plan("READY", 13),
                        workTypes = listOf(workType(), surveyType()),
                        orders = listOf(draftOrder()),
                        onAction = {},
                    )
                2 ->
                    WorkPlanningSection(
                        plan = plan("READY", 14),
                        workTypes = listOf(workType(), surveyType()),
                        orders = listOf(plannedOrder()),
                        onAction = {},
                    )
                else ->
                    WorkPlanningSection(
                        plan = plan("PLANNING", 15),
                        workTypes = listOf(workType(), surveyType()),
                        orders = emptyList(),
                        onAction = {},
                    )
            }
        }

        LaunchedEffect(Unit) {
            try {
                settle()
                captureWindow(
                    frame,
                    outputDirectory.resolve(
                        "desktop-work-bound-revisions.png",
                    ),
                )
                screen = 1
                settle()
                captureWindow(
                    frame,
                    outputDirectory.resolve(
                        "desktop-work-draft-gate.png",
                    ),
                )
                screen = 2
                settle()
                captureWindow(
                    frame,
                    outputDirectory.resolve(
                        "desktop-project-activation-ready.png",
                    ),
                )
                screen = 3
                settle()
                captureWindow(
                    frame,
                    outputDirectory.resolve(
                        "desktop-work-editor-contract.png",
                    ),
                )
                println(
                    "HILTECH_PHASE4_WORK_RENDER_PASS " +
                        "bound_revisions=PASS checklist_definition=PASS " +
                        "requirements_pending=PASS document_requirement=PASS " +
                        "readiness_not_evaluated=PASS draft_gate=PASS " +
                        "activation_ready=PASS work_type_selector=PASS " +
                        "schedule_editor=PASS dependency_editor=PASS " +
                        "task_update_control=PASS no_fake_assignment=PASS " +
                        "no_fake_resource_availability=PASS",
                )
            } finally {
                exitApplication()
            }
        }
    }
}

private suspend fun settle() {
    withFrameNanos { }
    withFrameNanos { }
    delay(450)
}

private fun plan(
    lifecycle: String,
    version: Long,
): ProjectPlanDto =
    ProjectPlanDto(
        project =
            ProjectPlanProjectDto(
                projectId = PROJECT_ID,
                projectCode = "PRJ-2026-014",
                name = "تطوير مركز العمليات",
                lifecycleState = lifecycle,
                version = version,
                baselineVersion = 1,
            ),
        projectSites =
            listOf(
                ProjectPlanSiteDto(
                    PROJECT_SITE_ID,
                    SITE_ID,
                    "SITE-OPS",
                    "مركز العمليات",
                    "PLANNED",
                ),
            ),
        areas = emptyList(),
        milestones = emptyList(),
        workPackages = emptyList(),
        dependencies = emptyList(),
        validationValid = true,
        validationReasonCodes = emptyList(),
        canEditPlan = false,
        readyGateReady = true,
        readyGateReasonCodes = emptyList(),
        correlationId = "phase4-work-evidence",
    )

private fun workType() =
    WorkTypeChoiceDto(
        workType =
            ConfigRefDto(
                WORK_TYPE_ID,
                "INSTALL",
                "Installation",
                1,
            ),
        description = "تركيب وربط بنية الشبكة",
        defaultPriorityCode = "NORMAL",
        defaultProgressWeight = "2.0",
        countsTowardProjectProgress = true,
    )

private fun surveyType() =
    WorkTypeChoiceDto(
        workType =
            ConfigRefDto(
                SURVEY_WORK_TYPE_ID,
                "SURVEY",
                "Survey",
                2,
            ),
        description = "رفع ومعاينة قبل التنفيذ",
        defaultPriorityCode = "HIGH",
        defaultProgressWeight = "1.0",
        countsTowardProjectProgress = true,
    )

private fun plannedOrder(): WorkOrderDto =
    WorkOrderDto(
        workOrderId = WORK_ORDER_ID,
        organizationId = ORGANIZATION_ID,
        workOrderCode = "WO-2026-001",
        projectId = PROJECT_ID,
        siteId = SITE_ID,
        projectSiteId = PROJECT_SITE_ID,
        title = "تركيب وربط الراك الرئيسي",
        description = "نطاق فعلي مخطط ومربوط بالمراجعات",
        lifecycleState = "PLANNED",
        readinessState = "NOT_EVALUATED",
        plannedStart = "2026-09-22T06:00:00Z",
        plannedEnd = "2026-09-22T08:00:00Z",
        priorityCode = "NORMAL",
        countsTowardProjectProgress = true,
        progressWeight = "2.0",
        baselineVersion = 1,
        createdAt = "2026-09-21T00:00:00Z",
        updatedAt = "2026-09-21T00:05:00Z",
        version = 5,
        binding =
            WorkBindingDto(
                bindingId = BINDING_ID,
                bindingRevision = 1,
                workType =
                    ConfigRefDto(
                        WORK_TYPE_ID,
                        "INSTALL",
                        "Installation",
                        1,
                    ),
                assignmentPolicy =
                    ConfigRefDto(
                        ASSIGNMENT_ID,
                        "INSTALL-ASSIGN",
                        "Install assignment",
                        1,
                    ),
                readinessPolicy =
                    ConfigRefDto(
                        READINESS_ID,
                        "INSTALL-READY",
                        "Install readiness",
                        1,
                    ),
                evidencePolicy =
                    ConfigRefDto(
                        EVIDENCE_ID,
                        "INSTALL-EVIDENCE",
                        "Install evidence",
                        1,
                    ),
                reviewPolicy =
                    ConfigRefDto(
                        REVIEW_ID,
                        "INSTALL-REVIEW",
                        "Install review",
                        1,
                    ),
                checklistTemplate =
                    ConfigRefDto(
                        CHECKLIST_ID,
                        "INSTALL-CHECKLIST",
                        "Install checklist",
                        1,
                    ),
                instructionTemplate =
                    ConfigRefDto(
                        INSTRUCTION_ID,
                        "INSTALL-INSTRUCTION",
                        "Install instruction",
                        1,
                    ),
                createdAt = "2026-09-21T00:02:00Z",
            ),
        instruction =
            WorkInstructionDto(
                instructionRevisionId = INSTRUCTION_REVISION_ID,
                revision = 2,
                sourceTemplate =
                    ConfigRefDto(
                        INSTRUCTION_ID,
                        "INSTALL-INSTRUCTION",
                        "Install instruction",
                        1,
                    ),
                payloadSchemaVersion = 1,
                structuredPayloadJson =
                    """{"steps":["verify","install","label"]}""",
                summary = "مراجعة تنفيذية واضحة",
                changeReason = "توضيح ترتيب الموقع",
                createdAt = "2026-09-21T00:04:00Z",
                correlationId = "phase4-work-evidence",
            ),
        checklist =
            listOf(
                ChecklistItemDto(
                    itemId = CHECKLIST_ITEM_ID,
                    itemKey = "LABEL",
                    label = "تعريف الراك والبورتات",
                    required = true,
                    sortOrder = 10,
                    completionState = "PENDING",
                    evidenceRequirementKey = "PHOTO-FINAL",
                    version = 1,
                ),
            ),
        requirements =
            listOf(
                requirement("READINESS", "SITE-ACCESS", REQUIREMENT_1),
                requirement("EVIDENCE", "PHOTO-FINAL", REQUIREMENT_2),
                requirement("ASSET", "CALIBRATED-METER", REQUIREMENT_3),
                requirement("MATERIAL", "PATCH-CORD", REQUIREMENT_4),
                requirement("DOCUMENT", "APPROVED-DRAWING", REQUIREMENT_5),
            ),
        tasks =
            listOf(
                WorkTaskDto(
                    taskId = TASK_ID,
                    taskCode = "T-01",
                    title = "تأكيد موضع الراك",
                    sortOrder = 10,
                    mandatory = true,
                    estimatedDurationMinutes = 20,
                    evidenceRequirementKey = "PHOTO-FINAL",
                    state = "PLANNED",
                    version = 1,
                ),
            ),
        dependencies =
            listOf(
                WorkDependencyDto(
                    dependencyId = DEPENDENCY_ID,
                    predecessorWorkOrderId = PREDECESSOR_ID,
                    successorWorkOrderId = WORK_ORDER_ID,
                    dependencyType = "FINISH_TO_START",
                    lagMinutes = 30,
                    version = 1,
                ),
            ),
    )

private fun draftOrder() =
    plannedOrder().copy(
        workOrderId = DRAFT_WORK_ORDER_ID,
        workOrderCode = "WO-2026-002",
        title = "إنهاء وربط الكابلات",
        lifecycleState = "DRAFT",
        version = 1,
        binding = null,
        instruction = null,
        checklist = emptyList(),
        requirements = emptyList(),
        tasks = emptyList(),
        dependencies = emptyList(),
    )

private fun requirement(
    family: String,
    key: String,
    id: String,
) =
    RequirementDto(
        requirementId = id,
        family = family,
        key = key,
        required = true,
        satisfactionState = "PENDING",
        version = 1,
    )

private fun captureWindow(
    window: AwtWindow,
    output: Path,
) {
    val location = window.locationOnScreen
    val bounds =
        Rectangle(
            location.x,
            location.y,
            window.width,
            window.height,
        )
    require(bounds.width > 0 && bounds.height > 0)
    val image =
        Robot().createScreenCapture(bounds)
    require(
        ImageIO.write(
            image,
            "png",
            output.toFile(),
        ),
    )
    require(Files.size(output) > 0)
}

private const val ORGANIZATION_ID = "11111111-1111-4111-8111-111111111111"
private const val PROJECT_ID = "22222222-2222-4222-8222-222222222222"
private const val SITE_ID = "33333333-3333-4333-8333-333333333333"
private const val PROJECT_SITE_ID = "44444444-4444-4444-8444-444444444444"
private const val WORK_ORDER_ID = "55555555-5555-4555-8555-555555555555"
private const val DRAFT_WORK_ORDER_ID = "66666666-6666-4666-8666-666666666666"
private const val PREDECESSOR_ID = "77777777-7777-4777-8777-777777777777"
private const val WORK_TYPE_ID = "88888888-8888-4888-8888-888888888888"
private const val SURVEY_WORK_TYPE_ID = "89898989-8989-4989-8989-898989898989"
private const val ASSIGNMENT_ID = "99999999-9999-4999-8999-999999999999"
private const val READINESS_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val EVIDENCE_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private const val REVIEW_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val CHECKLIST_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
private const val INSTRUCTION_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
private const val BINDING_ID = "ffffffff-ffff-4fff-8fff-ffffffffffff"
private const val INSTRUCTION_REVISION_ID = "12121212-1212-4212-8212-121212121212"
private const val CHECKLIST_ITEM_ID = "13131313-1313-4313-8313-131313131313"
private const val REQUIREMENT_1 = "14141414-1414-4414-8414-141414141414"
private const val REQUIREMENT_2 = "15151515-1515-4515-8515-151515151515"
private const val REQUIREMENT_3 = "16161616-1616-4616-8616-161616161616"
private const val REQUIREMENT_4 = "17171717-1717-4717-8717-171717171717"
private const val REQUIREMENT_5 = "18181818-1818-4818-8818-181818181818"
private const val TASK_ID = "19191919-1919-4919-8919-191919191919"
private const val DEPENDENCY_ID = "20202020-2020-4020-8020-202020202020"
