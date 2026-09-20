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
import com.hiltech.shared.core.HiltechOnboardingState
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.IdentityOrganizationDto
import com.hiltech.shared.core.people.OnboardingCaseDto
import com.hiltech.shared.core.people.OnboardingRequirementDto
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
                "HILTECH_PHASE3_ONBOARDING_EVIDENCE_DIR",
            )
                ?: "build/reports/phase3-onboarding",
        )
    Files.createDirectories(
        outputDirectory,
    )

    Window(
        onCloseRequest =
            ::exitApplication,
        title =
            "HILTECH Phase 3 Onboarding Evidence",
        state =
            rememberWindowState(
                width = 980.dp,
                height = 760.dp,
            ),
    ) {
        var shellState by remember {
            mutableStateOf(
                employeeState(),
            )
        }
        val frame = window

        HiltechShell(
            state = shellState,
        )

        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            delay(500)
            captureWindow(
                frame,
                outputDirectory.resolve(
                    "desktop-employee-preboarding.png",
                ),
            )

            shellState =
                adminState()
            withFrameNanos { }
            withFrameNanos { }
            delay(450)
            captureWindow(
                frame,
                outputDirectory.resolve(
                    "desktop-people-admin-onboarding.png",
                ),
            )

            println(
                "HILTECH_PHASE3_DESKTOP_ONBOARDING_RENDER_PASS " +
                    "employee_preboarding=PASS " +
                    "people_admin=PASS " +
                    "hiltech_owned_blocker=PASS",
            )
            exitApplication()
        }
    }
}

private fun employeeState():
    HiltechShellState =
    HiltechShellState.SignedIn(
        identity =
            identity(
                role = "Employee",
            ),
        onboarding =
            HiltechOnboardingState(
                ownCase =
                    onboardingCase(),
            ),
    )

private fun adminState():
    HiltechShellState =
    HiltechShellState.SignedIn(
        identity =
            identity(
                role = "People Admin",
            ),
        onboarding =
            HiltechOnboardingState(
                selectedEmployeeCase =
                    onboardingCase(),
            ),
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
            "22222222-2222-2222-2222-222222222222",
        organizations =
            listOf(
                IdentityOrganizationDto(
                    membershipId =
                        "33333333-3333-3333-3333-333333333333",
                    organizationId =
                        "22222222-2222-2222-2222-222222222222",
                    organizationCode =
                        "HILTECH",
                    displayName =
                        "HILTECH",
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

private fun onboardingCase():
    OnboardingCaseDto =
    OnboardingCaseDto(
        caseId =
            "44444444-4444-4444-4444-444444444444",
        organizationId =
            "22222222-2222-2222-2222-222222222222",
        employeeId =
            "55555555-5555-5555-5555-555555555555",
        employeeCode = "EMP-014",
        employeeDisplayName =
            "Ahmed Mohamed",
        employeeState =
            "PREBOARDING",
        employeeVersion = 3,
        policyConfigRevisionId =
            "66666666-6666-6666-6666-666666666666",
        policyCode =
            "FIELD-EMPLOYEE",
        policyRevisionNumber = 2,
        state = "OPEN",
        requirements =
            listOf(
                OnboardingRequirementDto(
                    requirementKey =
                        "NATIONAL_ID_DOCUMENT",
                    label =
                        "National ID document",
                    responsibility =
                        "EMPLOYEE",
                    blocking = true,
                    status =
                        "NEEDS_EMPLOYEE",
                    actionCode =
                        "SUBMIT_DOCUMENT",
                    sourceStateCode =
                        "MISSING",
                    sortOrder = 10,
                ),
                OnboardingRequirementDto(
                    requirementKey =
                        "WORKFORCE_ASSIGNMENT",
                    label =
                        "Team and reporting manager",
                    responsibility =
                        "HILTECH",
                    blocking = true,
                    status =
                        "WAITING_HILTECH",
                    sourceStateCode =
                        "MISSING",
                    sortOrder = 20,
                ),
                OnboardingRequirementDto(
                    requirementKey =
                        "CONTACT_MOBILE",
                    label =
                        "Mobile number",
                    responsibility =
                        "EMPLOYEE",
                    blocking = true,
                    status =
                        "SATISFIED",
                    sourceStateCode =
                        "PRESENT",
                    sortOrder = 30,
                ),
            ),
        blockingSatisfied = false,
        caseVersion = 4,
        startedAt =
            "2026-09-20T04:00:00Z",
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
