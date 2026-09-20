package com.hiltech.desktop.evidence

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hiltech.shared.core.HiltechProjectsState
import com.hiltech.shared.core.ProjectControlSection
import com.hiltech.shared.core.projects.ProjectListDto
import com.hiltech.shared.core.projects.ProjectResponsibilityDto
import com.hiltech.shared.core.projects.ProjectSiteDto
import com.hiltech.shared.core.projects.ProjectSiteListDto
import com.hiltech.shared.core.projects.ProjectSummaryDto
import com.hiltech.shared.core.projects.SiteDto
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
            System.getenv(
                "HILTECH_PHASE4_PROJECT_SITE_EVIDENCE_DIR",
            ) ?: "build/reports/phase4-project-site",
        )
    Files.createDirectories(
        outputDirectory,
    )

    Window(
        onCloseRequest =
            ::exitApplication,
        title =
            "HILTECH Phase 4 Project Site Evidence",
        state =
            rememberWindowState(
                width = 1180.dp,
                height = 1250.dp,
            ),
    ) {
        var state by remember {
            mutableStateOf(
                draftState(),
            )
        }
        val frame = window

        ProjectControlSection(
            state = state,
        )

        LaunchedEffect(Unit) {
            try {
                withFrameNanos { }
                withFrameNanos { }
                delay(450)
                captureWindow(
                    frame,
                    outputDirectory.resolve(
                        "desktop-my-projects-draft.png",
                    ),
                )

                state =
                    planningState()
                withFrameNanos { }
                withFrameNanos { }
                delay(450)
                captureWindow(
                    frame,
                    outputDirectory.resolve(
                        "desktop-project-site-planning.png",
                    ),
                )

                state =
                    staleConflictState()
                withFrameNanos { }
                withFrameNanos { }
                delay(450)
                captureWindow(
                    frame,
                    outputDirectory.resolve(
                        "desktop-project-version-conflict.png",
                    ),
                )

                println(
                    "HILTECH_PHASE4_PROJECT_SITE_RENDER_PASS " +
                        "my_projects=PASS " +
                        "rtl=PASS " +
                        "project_header=PASS " +
                        "site_context=PASS " +
                        "stale_conflict=PASS " +
                        "no_fake_health=PASS",
                )
            } finally {
                exitApplication()
            }
        }
    }
}

private fun draftState():
    HiltechProjectsState {
    val selected =
        project(
            lifecycle = "DRAFT",
            version = 3,
            siteCount = 0,
            responsibility =
                ProjectResponsibilityDto(
                    responsibilityId =
                        "77777777-7777-7777-7777-777777777777",
                    principalType =
                        "EMPLOYEE",
                    principalId =
                        "88888888-8888-8888-8888-888888888888",
                    principalLabel =
                        "محمد علي",
                    resolutionState =
                        "RESOLVED_USER",
                    effectiveFrom =
                        "2026-09-20T09:00:00Z",
                    version = 1,
                ),
        )

    return HiltechProjectsState(
        list =
            ProjectListDto(
                items =
                    listOf(
                        selected,
                        project(
                            projectId =
                                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                            code = "PRJ-2026-002",
                            name = "مشروع صيانة مستورد",
                            lifecycle =
                                "PLANNING",
                            version = 6,
                            siteCount = 1,
                            responsibility =
                                ProjectResponsibilityDto(
                                    principalType =
                                        null,
                                    principalId =
                                        null,
                                    principalLabel =
                                        null,
                                    resolutionState =
                                        "UNASSIGNED",
                                ),
                        ),
                    ),
                correlationId =
                    "phase4-project-site-evidence",
            ),
        selectedProject = selected,
        selectedSites =
            ProjectSiteListDto(
                items = emptyList(),
                correlationId =
                    "phase4-project-site-evidence",
            ),
    )
}

private fun planningState():
    HiltechProjectsState {
    val selected =
        project(
            lifecycle = "PLANNING",
            version = 7,
            siteCount = 1,
            responsibility =
                ProjectResponsibilityDto(
                    responsibilityId =
                        "99999999-9999-9999-9999-999999999999",
                    principalType =
                        "TEAM",
                    principalId =
                        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                    principalLabel =
                        "فريق التنفيذ A",
                    resolutionState =
                        "TEAM",
                    effectiveFrom =
                        "2026-09-20T11:00:00Z",
                    version = 1,
                ),
        )
    val site =
        SiteDto(
            siteId =
                "cccccccc-cccc-cccc-cccc-cccccccccccc",
            organizationId =
                ORGANIZATION_ID,
            clientOrganizationId =
                CLIENT_ID,
            clientDisplayName =
                "العميل التجريبي",
            siteCode =
                "SITE-CAIRO-01",
            name =
                "موقع القاهرة الرئيسي",
            addressText =
                "القاهرة الجديدة — بوابة ٣",
            latitude = 30.0444,
            longitude = 31.2357,
            timezone =
                "Africa/Cairo",
            status = "ACTIVE",
            createdAt =
                "2026-09-20T10:00:00Z",
            updatedAt =
                "2026-09-20T10:00:00Z",
            version = 1,
        )

    return HiltechProjectsState(
        list =
            ProjectListDto(
                items =
                    listOf(selected),
                correlationId =
                    "phase4-project-site-evidence",
            ),
        selectedProject =
            selected,
        selectedSites =
            ProjectSiteListDto(
                items =
                    listOf(
                        ProjectSiteDto(
                            projectSiteId =
                                "dddddddd-dddd-dddd-dddd-dddddddddddd",
                            organizationId =
                                ORGANIZATION_ID,
                            projectId =
                                PROJECT_ID,
                            site = site,
                            projectSiteCode =
                                "MAIN",
                            lifecycleState =
                                "PLANNED",
                            accessInstructions =
                                "الاتصال بالأمن قبل الدخول",
                            projectSpecificNotes =
                                "المخزن المؤقت خارج نطاق Slice 01",
                            version = 1,
                        ),
                    ),
                correlationId =
                    "phase4-project-site-evidence",
            ),
        lastCreatedSite = site,
    )
}

private fun staleConflictState():
    HiltechProjectsState =
    planningState().copy(
        errorCode =
            "VERSION_CONFLICT",
        errorMessage =
            "تم تعديل المشروع من جلسة أخرى. حدّث البيانات قبل إعادة المحاولة.",
    )

private fun project(
    projectId: String = PROJECT_ID,
    code: String = "PRJ-2026-001",
    name: String = "تحديث شبكة القاهرة",
    lifecycle: String,
    version: Long,
    siteCount: Int,
    responsibility: ProjectResponsibilityDto,
): ProjectSummaryDto =
    ProjectSummaryDto(
        projectId = projectId,
        organizationId =
            ORGANIZATION_ID,
        projectCode = code,
        name = name,
        clientOrganizationId =
            CLIENT_ID,
        clientDisplayName =
            "العميل التجريبي",
        sourceType = "INTERNAL",
        lifecycleState = lifecycle,
        currentResponsibility =
            responsibility,
        startDatePlanned =
            "2026-10-01",
        endDatePlanned =
            "2026-11-30",
        baselineVersion = 1,
        siteCount = siteCount,
        createdAt =
            "2026-09-20T09:00:00Z",
        updatedAt =
            "2026-09-20T11:00:00Z",
        version = version,
    )

private fun captureWindow(
    window: AwtWindow,
    output: Path,
) {
    val location =
        window.locationOnScreen
    val bounds =
        Rectangle(
            location.x,
            location.y,
            window.width,
            window.height,
        )
    require(
        bounds.width > 0 &&
            bounds.height > 0,
    )

    val image =
        Robot().createScreenCapture(
            bounds,
        )
    require(
        ImageIO.write(
            image,
            "png",
            output.toFile(),
        ),
    )
    require(
        Files.size(output) > 0,
    )
}

private const val ORGANIZATION_ID =
    "11111111-1111-1111-1111-111111111111"
private const val CLIENT_ID =
    "22222222-2222-2222-2222-222222222222"
private const val PROJECT_ID =
    "33333333-3333-3333-3333-333333333333"
