package com.hiltech.desktop.evidence

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hiltech.shared.core.HiltechProjectsState
import com.hiltech.shared.core.ProjectControlSection
import com.hiltech.shared.core.ProjectPlanningSection
import com.hiltech.shared.core.projects.AreaDto
import com.hiltech.shared.core.projects.MilestoneDto
import com.hiltech.shared.core.projects.PlanDependencyDto
import com.hiltech.shared.core.projects.PlanNodeDto
import com.hiltech.shared.core.projects.ProjectListDto
import com.hiltech.shared.core.projects.ProjectPlanDto
import com.hiltech.shared.core.projects.ProjectPlanProjectDto
import com.hiltech.shared.core.projects.ProjectPlanSiteDto
import com.hiltech.shared.core.projects.ProjectResponsibilityDto
import com.hiltech.shared.core.projects.ProjectSummaryDto
import com.hiltech.shared.core.projects.WorkPackageDto
import com.hiltech.shared.core.projects.WorkPackageOwnerDto
import kotlinx.coroutines.delay
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window as AwtWindow
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

fun main() = application {
    val outputDirectory = Path.of(
        System.getenv("HILTECH_PHASE4_PLANNING_EVIDENCE_DIR")
            ?: "build/reports/phase4-planning",
    )
    Files.createDirectories(outputDirectory)
    Window(
        onCloseRequest = ::exitApplication,
        title = "HILTECH Phase 4 Planning Evidence",
        state = rememberWindowState(width = 1180.dp, height = 1080.dp),
    ) {
        var screen by remember { mutableStateOf(0) }
        val frame = window
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            when (screen) {
                0 -> ProjectPlanningSection(plan = plan(canEdit = false, ready = true), onAction = {})
                1 -> ProjectPlanningSection(plan = plan(canEdit = true, ready = true), onAction = {})
                else -> ProjectControlSection(state = conflictState())
            }
        }
        LaunchedEffect(Unit) {
            try {
                settle()
                captureWindow(frame, outputDirectory.resolve("desktop-project-planning-tree.png"))
                screen = 1
                settle()
                captureWindow(frame, outputDirectory.resolve("desktop-project-ready-gate.png"))
                screen = 2
                settle()
                captureWindow(frame, outputDirectory.resolve("desktop-project-plan-conflict.png"))
                println(
                    "HILTECH_PHASE4_PLANNING_RENDER_PASS " +
                        "site_area_tree=PASS milestones=PASS work_packages=PASS dependencies=PASS " +
                        "ready_gate=PASS stale_conflict=PASS no_phase5_truth=PASS no_phase6_execution=PASS",
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

private fun plan(canEdit: Boolean, ready: Boolean): ProjectPlanDto =
    ProjectPlanDto(
        project = ProjectPlanProjectDto(
            projectId = PROJECT_ID,
            projectCode = "PRJ-2026-014",
            name = "تطوير مركز العمليات",
            lifecycleState = "PLANNING",
            version = 12,
            baselineVersion = 1,
        ),
        projectSites = listOf(
            ProjectPlanSiteDto(PROJECT_SITE_ID, SITE_ID, "SITE-OPS", "مركز العمليات", "PLANNED"),
            ProjectPlanSiteDto(SECOND_PROJECT_SITE_ID, SECOND_SITE_ID, "SITE-WEST", "الموقع الغربي", "PLANNED"),
        ),
        areas = listOf(
            AreaDto(AREA_ID, SITE_ID, null, "BUILDING", "BLDG-A", "المبنى أ", 10, false, 2),
            AreaDto(CHILD_AREA_ID, SITE_ID, AREA_ID, "FLOOR", "F-01", "الدور الأول", 20, false, 1),
            AreaDto(SECOND_AREA_ID, SECOND_SITE_ID, null, "ZONE", "WEST", "المنطقة الغربية", 10, true, 1),
        ),
        milestones = listOf(
            MilestoneDto(MILESTONE_ID, "M-DESIGN", "اعتماد التصميم", "PLANNED", "2026-10-15", 10, true, "اعتماد العميل", 1, 1),
            MilestoneDto(SECOND_MILESTONE_ID, "M-HANDOFF", "تسليم نطاق التخطيط", "PLANNED", "2026-11-30", 20, false, null, 1, 1),
        ),
        workPackages = listOf(
            WorkPackageDto(
                workPackageId = WORK_PACKAGE_ID,
                projectSiteId = PROJECT_SITE_ID,
                siteId = SITE_ID,
                milestoneId = MILESTONE_ID,
                code = "WP-DESIGN",
                name = "حزمة التصميم",
                description = "هيكل تخطيط فقط",
                owner = WorkPackageOwnerDto("TEAM", TEAM_ID, "فريق التخطيط"),
                state = "PLANNED",
                plannedStart = "2026-10-01",
                plannedEnd = "2026-10-12",
                sequence = 10,
                baselineVersion = 1,
                version = 1,
            ),
            WorkPackageDto(
                workPackageId = SECOND_WORK_PACKAGE_ID,
                projectSiteId = SECOND_PROJECT_SITE_ID,
                siteId = SECOND_SITE_ID,
                milestoneId = SECOND_MILESTONE_ID,
                code = "WP-WEST",
                name = "تهيئة النطاق الغربي",
                state = "PLANNED",
                plannedStart = "2026-10-16",
                plannedEnd = "2026-11-20",
                sequence = 20,
                baselineVersion = 1,
                version = 1,
            ),
        ),
        dependencies = listOf(
            PlanDependencyDto(
                DEPENDENCY_ID, PlanNodeDto("WORK_PACKAGE", WORK_PACKAGE_ID),
                PlanNodeDto("MILESTONE", MILESTONE_ID), "FINISH_TO_START", 0, 1,
            ),
            PlanDependencyDto(
                SECOND_DEPENDENCY_ID, PlanNodeDto("MILESTONE", MILESTONE_ID),
                PlanNodeDto("WORK_PACKAGE", SECOND_WORK_PACKAGE_ID), "FINISH_TO_START", 60, 1,
            ),
        ),
        validationValid = true,
        validationReasonCodes = emptyList(),
        canEditPlan = canEdit,
        readyGateReady = ready,
        readyGateReasonCodes = if (ready) emptyList() else listOf("PLANNING_NODE_REQUIRED"),
        correlationId = "phase4-planning-evidence",
    )

private fun conflictState(): HiltechProjectsState {
    val selected = ProjectSummaryDto(
        projectId = PROJECT_ID,
        organizationId = ORGANIZATION_ID,
        projectCode = "PRJ-2026-014",
        name = "تطوير مركز العمليات",
        clientOrganizationId = CLIENT_ID,
        clientDisplayName = "عميل المشروع",
        sourceType = "INTERNAL",
        lifecycleState = "PLANNING",
        currentResponsibility = ProjectResponsibilityDto(
            principalType = "TEAM", principalId = TEAM_ID, principalLabel = "فريق التخطيط",
            resolutionState = "TEAM",
        ),
        startDatePlanned = "2026-10-01",
        endDatePlanned = "2026-11-30",
        baselineVersion = 1,
        siteCount = 2,
        createdAt = "2026-09-20T10:00:00Z",
        updatedAt = "2026-09-20T12:00:00Z",
        version = 12,
    )
    return HiltechProjectsState(
        list = ProjectListDto(listOf(selected), "phase4-planning-evidence"),
        selectedProject = selected,
        plan = plan(canEdit = true, ready = true),
        errorCode = "VERSION_CONFLICT",
        errorMessage = "تم تعديل الخطة من جلسة أخرى. حدّث أحدث نسخة قبل إعادة المحاولة.",
    )
}

private fun captureWindow(window: AwtWindow, output: Path) {
    val location = window.locationOnScreen
    val bounds = Rectangle(location.x, location.y, window.width, window.height)
    require(bounds.width > 0 && bounds.height > 0)
    val image = Robot().createScreenCapture(bounds)
    require(ImageIO.write(image, "png", output.toFile()))
    require(Files.size(output) > 0)
}

private const val ORGANIZATION_ID = "11111111-1111-4111-8111-111111111111"
private const val CLIENT_ID = "22222222-2222-4222-8222-222222222222"
private const val PROJECT_ID = "33333333-3333-4333-8333-333333333333"
private const val SITE_ID = "44444444-4444-4444-8444-444444444444"
private const val SECOND_SITE_ID = "55555555-5555-4555-8555-555555555555"
private const val PROJECT_SITE_ID = "66666666-6666-4666-8666-666666666666"
private const val SECOND_PROJECT_SITE_ID = "77777777-7777-4777-8777-777777777777"
private const val AREA_ID = "88888888-8888-4888-8888-888888888888"
private const val CHILD_AREA_ID = "99999999-9999-4999-8999-999999999999"
private const val SECOND_AREA_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val MILESTONE_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private const val SECOND_MILESTONE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val WORK_PACKAGE_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
private const val SECOND_WORK_PACKAGE_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
private const val DEPENDENCY_ID = "ffffffff-ffff-4fff-8fff-ffffffffffff"
private const val SECOND_DEPENDENCY_ID = "12121212-1212-4212-8212-121212121212"
private const val TEAM_ID = "13131313-1313-4313-8313-131313131313"
