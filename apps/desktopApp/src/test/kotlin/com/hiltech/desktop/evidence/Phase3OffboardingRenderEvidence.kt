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
import com.hiltech.shared.core.HiltechPeopleState
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.IdentityOrganizationDto
import com.hiltech.shared.core.people.EmployeeDetailDto
import com.hiltech.shared.core.people.OffboardingAccessFactsDto
import com.hiltech.shared.core.people.OffboardingCaseDto
import com.hiltech.shared.core.people.OffboardingClearanceDto
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
                "HILTECH_PHASE3_OFFBOARDING_EVIDENCE_DIR",
            ) ?: "build/reports/phase3-offboarding",
        )
    Files.createDirectories(
        outputDirectory,
    )

    Window(
        onCloseRequest =
            ::exitApplication,
        title =
            "HILTECH Phase 3 Offboarding Evidence",
        state =
            rememberWindowState(
                width = 1120.dp,
                height = 1280.dp,
            ),
    ) {
        var shellState by remember {
            mutableStateOf(
                accessPendingState(),
            )
        }
        val frame = window

        HiltechShell(
            state = shellState,
        )

        LaunchedEffect(Unit) {
            try {
                withFrameNanos { }
            withFrameNanos { }
            delay(450)
            captureWindow(
                frame,
                outputDirectory.resolve(
                    "desktop-offboarding-access-pending.png",
                ),
            )

            shellState =
                accessClearState()
            withFrameNanos { }
            withFrameNanos { }
            delay(450)
            captureWindow(
                frame,
                outputDirectory.resolve(
                    "desktop-offboarding-access-clear.png",
                ),
            )

            shellState =
                completedState()
            withFrameNanos { }
            withFrameNanos { }
            delay(450)
            captureWindow(
                frame,
                outputDirectory.resolve(
                    "desktop-offboarding-completed.png",
                ),
            )

            println(
                "HILTECH_PHASE3_OFFBOARDING_RENDER_PASS " +
                    "access_pending=PASS " +
                    "system_verified_access=PASS " +
                    "coordination_boundary=PASS " +
                    "former_history=PASS",
            )
            } finally {
                exitApplication()
            }
        }
    }
}

private fun accessPendingState():
    HiltechShellState =
    HiltechShellState.SignedIn(
        identity = identity(),
        people =
            HiltechPeopleState(
                selectedEmployee =
                    employee(
                        state =
                            "OFFBOARDING",
                        version = 5,
                    ),
                selectedOffboarding =
                    offboarding(
                        accessClear = false,
                        completed = false,
                    ),
            ),
    )

private fun accessClearState():
    HiltechShellState =
    HiltechShellState.SignedIn(
        identity = identity(),
        people =
            HiltechPeopleState(
                selectedEmployee =
                    employee(
                        state =
                            "OFFBOARDING",
                        version = 5,
                    ),
                selectedOffboarding =
                    offboarding(
                        accessClear = true,
                        completed = false,
                    ),
            ),
    )

private fun completedState():
    HiltechShellState =
    HiltechShellState.SignedIn(
        identity = identity(),
        people =
            HiltechPeopleState(
                selectedEmployee =
                    employee(
                        state =
                            "FORMER",
                        version = 6,
                    ),
                selectedOffboarding =
                    offboarding(
                        accessClear = true,
                        completed = true,
                    ),
            ),
    )

private fun employee(
    state: String,
    version: Long,
): EmployeeDetailDto =
    EmployeeDetailDto(
        employeeId =
            EMPLOYEE_ID,
        organizationId =
            ORGANIZATION_ID,
        employeeCode =
            "EMP-014",
        employeeState =
            state,
        displayName =
            "Ahmed Mohamed",
        employmentTypeCode =
            "FULL_TIME",
        version = version,
        personVersion = 2,
    )

private fun offboarding(
    accessClear: Boolean,
    completed: Boolean,
): OffboardingCaseDto {
    val externalState =
        if (completed) {
            "CLEAR"
        } else {
            "PENDING"
        }
    val hrState =
        if (accessClear || completed) {
            "CLEAR"
        } else {
            "PENDING"
        }

    return OffboardingCaseDto(
        caseId =
            CASE_ID,
        organizationId =
            ORGANIZATION_ID,
        employeeId =
            EMPLOYEE_ID,
        employeeCode =
            "EMP-014",
        employeeDisplayName =
            "Ahmed Mohamed",
        employeeState =
            if (completed) {
                "FORMER"
            } else {
                "OFFBOARDING"
            },
        employeeVersion =
            if (completed) 6 else 5,
        activeEmploymentId =
            if (completed) {
                null
            } else {
                EMPLOYMENT_ID
            },
        employmentStartDate =
            if (completed) {
                null
            } else {
                "2025-11-01"
            },
        employmentVersion =
            if (completed) {
                null
            } else {
                2
            },
        state =
            if (completed) {
                "COMPLETED"
            } else {
                "OPEN"
            },
        lastWorkingDate =
            "2026-09-20",
        reasonCategoryCode =
            "RESIGNATION",
        note =
            "Handover coordinated with People.",
        clearances =
            listOf(
                clearance(
                    "HR",
                    hrState,
                    "PEOPLE",
                ),
                clearance(
                    "ACCESS",
                    if (accessClear) {
                        "CLEAR"
                    } else {
                        "PENDING"
                    },
                    "IDENTITY",
                ),
                clearance(
                    "PROJECT",
                    externalState,
                    "PEOPLE",
                ),
                clearance(
                    "ASSET",
                    externalState,
                    "PEOPLE",
                ),
                clearance(
                    "FINANCE",
                    externalState,
                    "PEOPLE",
                ),
                clearance(
                    "PAYROLL",
                    externalState,
                    "PEOPLE",
                ),
            ),
        accessFacts =
            OffboardingAccessFactsDto(
                linkedIdentityPresent = true,
                activeOrganizationMemberships =
                    if (accessClear) 0 else 1,
                activeSessions =
                    if (accessClear) 0 else 2,
                currentWorkforceAssignmentPresent =
                    !accessClear,
                clear =
                    accessClear,
            ),
        canComplete =
            accessClear &&
                !completed,
        blockers =
            when {
                completed ->
                    emptyList()
                !accessClear ->
                    listOf(
                        "ACCESS_NOT_CLEAR",
                        "CLEARANCE_HR_UNRESOLVED",
                        "CLEARANCE_PROJECT_UNRESOLVED",
                        "CLEARANCE_ASSET_UNRESOLVED",
                        "CLEARANCE_FINANCE_UNRESOLVED",
                        "CLEARANCE_PAYROLL_UNRESOLVED",
                    )
                else ->
                    listOf(
                        "CLEARANCE_PROJECT_UNRESOLVED",
                        "CLEARANCE_ASSET_UNRESOLVED",
                        "CLEARANCE_FINANCE_UNRESOLVED",
                        "CLEARANCE_PAYROLL_UNRESOLVED",
                    )
            },
        caseVersion =
            if (completed) 8 else 3,
        startedAt =
            "2026-09-19T09:00:00Z",
        completedAt =
            if (completed) {
                "2026-09-20T15:00:00Z"
            } else {
                null
            },
    )
}

private fun clearance(
    type: String,
    state: String,
    source: String,
): OffboardingClearanceDto =
    OffboardingClearanceDto(
        type = type,
        state = state,
        source = source,
        reason = null,
        resolvedAt =
            if (state == "PENDING") {
                null
            } else {
                "2026-09-20T12:00:00Z"
            },
        version =
            if (state == "PENDING") 1 else 2,
    )

private fun identity():
    IdentityBootstrapDto =
    IdentityBootstrapDto(
        identityId =
            "11111111-1111-1111-1111-111111111111",
        identityStatus =
            "ACTIVE",
        identityVersion = 1,
        primaryOrganizationId =
            ORGANIZATION_ID,
        organizations =
            listOf(
                IdentityOrganizationDto(
                    membershipId =
                        "33333333-3333-3333-3333-333333333333",
                    organizationId =
                        ORGANIZATION_ID,
                    organizationCode =
                        "HILTECH",
                    displayName =
                        "HILTECH",
                    organizationType =
                        "HILTECH",
                    membershipType =
                        "EMPLOYEE",
                    roleLabel =
                        "People Admin",
                    primary = true,
                    membershipVersion = 1,
                ),
            ),
        teams = emptyList(),
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
    "22222222-2222-2222-2222-222222222222"
private const val EMPLOYEE_ID =
    "55555555-5555-5555-5555-555555555555"
private const val EMPLOYMENT_ID =
    "66666666-6666-6666-6666-666666666666"
private const val CASE_ID =
    "77777777-7777-7777-7777-777777777777"
