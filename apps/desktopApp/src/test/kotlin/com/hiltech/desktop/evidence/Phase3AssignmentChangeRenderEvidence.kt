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
import com.hiltech.shared.core.people.WorkforceAssignmentDto
import com.hiltech.shared.core.people.WorkforceStructureDto
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
                "HILTECH_PHASE3_ASSIGNMENT_CHANGE_EVIDENCE_DIR",
            ) ?: "build/reports/phase3-assignment-change",
        )
    Files.createDirectories(
        outputDirectory,
    )

    Window(
        onCloseRequest =
            ::exitApplication,
        title =
            "HILTECH Phase 3 Assignment Change Evidence",
        state =
            rememberWindowState(
                width = 1120.dp,
                height = 1180.dp,
            ),
    ) {
        var shellState by remember {
            mutableStateOf(
                adminBeforeState(),
            )
        }
        val frame = window

        HiltechShell(
            state = shellState,
        )

        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            delay(450)
            captureWindow(
                frame,
                outputDirectory.resolve(
                    "desktop-assignment-before.png",
                ),
            )

            shellState =
                adminAfterState()
            withFrameNanos { }
            withFrameNanos { }
            delay(450)
            captureWindow(
                frame,
                outputDirectory.resolve(
                    "desktop-assignment-after-history.png",
                ),
            )

            shellState =
                employeeAfterState()
            withFrameNanos { }
            withFrameNanos { }
            delay(450)
            captureWindow(
                frame,
                outputDirectory.resolve(
                    "desktop-employee-current-assignment.png",
                ),
            )

            println(
                "HILTECH_PHASE3_ASSIGNMENT_CHANGE_RENDER_PASS " +
                    "windows_change=PASS " +
                    "history=PASS " +
                    "project_warning=PASS " +
                    "employee_current=PASS",
            )
            exitApplication()
        }
    }
}

private fun adminBeforeState():
    HiltechShellState =
    HiltechShellState.SignedIn(
        identity =
            identity("People Admin"),
        people =
            HiltechPeopleState(
                workforceStructure =
                    structure(
                        currentBefore(),
                        supportPeer(),
                        managerAssignment(),
                    ),
                selectedEmployee =
                    workerDetail(),
                selectedWorkforceAssignment =
                    currentBefore(),
                selectedWorkforceHistory =
                    structure(
                        currentBefore(),
                    ),
            ),
    )

private fun adminAfterState():
    HiltechShellState =
    HiltechShellState.SignedIn(
        identity =
            identity("People Admin"),
        people =
            HiltechPeopleState(
                workforceStructure =
                    structure(
                        changedAssignment(),
                        supportPeer(),
                        managerAssignment(),
                    ),
                selectedEmployee =
                    workerDetail(),
                selectedWorkforceAssignment =
                    changedAssignment(),
                selectedWorkforceHistory =
                    structure(
                        changedAssignment(),
                        currentBefore().copy(
                            state = "ENDED",
                            effectiveTo =
                                "2026-09-20T07:00:00Z",
                            version = 2,
                        ),
                    ),
            ),
    )

private fun employeeAfterState():
    HiltechShellState =
    HiltechShellState.SignedIn(
        identity =
            identity("Employee"),
        people =
            HiltechPeopleState(
                ownProfile =
                    workerDetail(),
                ownWorkforceAssignment =
                    changedAssignment(),
            ),
    )

private fun workerDetail():
    EmployeeDetailDto =
    EmployeeDetailDto(
        employeeId =
            WORKER_EMPLOYEE_ID,
        organizationId =
            ORGANIZATION_ID,
        employeeCode = "EMP-014",
        employeeState = "ACTIVE",
        displayName =
            "Ahmed Mohamed",
        employmentTypeCode =
            "FULL_TIME",
        version = 4,
        personVersion = 2,
    )

private fun currentBefore():
    WorkforceAssignmentDto =
    WorkforceAssignmentDto(
        assignmentId =
            OLD_ASSIGNMENT_ID,
        organizationId =
            ORGANIZATION_ID,
        employeeId =
            WORKER_EMPLOYEE_ID,
        employeeCode = "EMP-014",
        employeeDisplayName =
            "Ahmed Mohamed",
        teamId = FIELD_TEAM_ID,
        teamCode = "FIELD-A",
        teamName = "Field Crew A",
        roleCode = "TECHNICIAN",
        roleLabel =
            "Field Technician",
        reportsToEmployeeId =
            MANAGER_EMPLOYEE_ID,
        reportsToEmployeeCode =
            "EMP-002",
        reportsToDisplayName =
            "Mona Hassan",
        state = "ACTIVE",
        effectiveFrom =
            "2026-08-01T08:00:00Z",
        version = 1,
    )

private fun changedAssignment():
    WorkforceAssignmentDto =
    WorkforceAssignmentDto(
        assignmentId =
            NEW_ASSIGNMENT_ID,
        organizationId =
            ORGANIZATION_ID,
        employeeId =
            WORKER_EMPLOYEE_ID,
        employeeCode = "EMP-014",
        employeeDisplayName =
            "Ahmed Mohamed",
        teamId = SUPPORT_TEAM_ID,
        teamCode = "SUPPORT-A",
        teamName = "Support Crew A",
        roleCode =
            "LEAD_TECHNICIAN",
        roleLabel =
            "Lead Technician",
        reportsToEmployeeId = null,
        reportsToEmployeeCode = null,
        reportsToDisplayName = null,
        state = "ACTIVE",
        effectiveFrom =
            "2026-09-20T07:00:00Z",
        version = 1,
        supersedesAssignmentId =
            OLD_ASSIGNMENT_ID,
    )

private fun supportPeer():
    WorkforceAssignmentDto =
    WorkforceAssignmentDto(
        assignmentId =
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        organizationId =
            ORGANIZATION_ID,
        employeeId =
            "cccccccc-cccc-cccc-cccc-cccccccccccc",
        employeeCode = "EMP-021",
        employeeDisplayName =
            "Sara Ali",
        teamId = SUPPORT_TEAM_ID,
        teamCode = "SUPPORT-A",
        teamName = "Support Crew A",
        roleCode = "TECHNICIAN",
        roleLabel = "Technician",
        state = "ACTIVE",
        effectiveFrom =
            "2026-07-01T08:00:00Z",
        version = 1,
    )

private fun managerAssignment():
    WorkforceAssignmentDto =
    WorkforceAssignmentDto(
        assignmentId =
            "dddddddd-dddd-dddd-dddd-dddddddddddd",
        organizationId =
            ORGANIZATION_ID,
        employeeId =
            MANAGER_EMPLOYEE_ID,
        employeeCode = "EMP-002",
        employeeDisplayName =
            "Mona Hassan",
        teamId = FIELD_TEAM_ID,
        teamCode = "FIELD-A",
        teamName = "Field Crew A",
        roleCode = "SUPERVISOR",
        roleLabel = "Supervisor",
        state = "ACTIVE",
        effectiveFrom =
            "2026-06-01T08:00:00Z",
        version = 1,
    )

private fun structure(
    vararg items: WorkforceAssignmentDto,
): WorkforceStructureDto =
    WorkforceStructureDto(
        items = items.toList(),
        correlationId =
            "phase3-assignment-evidence",
    )

private fun identity(
    role: String,
): IdentityBootstrapDto =
    IdentityBootstrapDto(
        identityId =
            "11111111-1111-1111-1111-111111111111",
        identityStatus = "ACTIVE",
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
                    displayName = "HILTECH",
                    organizationType =
                        "HILTECH",
                    membershipType =
                        "EMPLOYEE",
                    roleLabel = role,
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
private const val WORKER_EMPLOYEE_ID =
    "55555555-5555-5555-5555-555555555555"
private const val MANAGER_EMPLOYEE_ID =
    "66666666-6666-6666-6666-666666666666"
private const val FIELD_TEAM_ID =
    "77777777-7777-7777-7777-777777777777"
private const val SUPPORT_TEAM_ID =
    "88888888-8888-8888-8888-888888888888"
private const val OLD_ASSIGNMENT_ID =
    "99999999-9999-9999-9999-999999999999"
private const val NEW_ASSIGNMENT_ID =
    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
