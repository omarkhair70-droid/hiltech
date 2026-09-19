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
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.identity.IdentityDeviceSecurityDto
import com.hiltech.shared.core.identity.IdentityOrganizationDto
import com.hiltech.shared.core.identity.IdentitySecuritySnapshot
import com.hiltech.shared.core.identity.IdentitySessionDto
import kotlinx.coroutines.delay
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window as AwtWindow
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

fun main() = application {
    val outputDirectory = Path.of(
        System.getenv("HILTECH_SHELL_EVIDENCE_DIR")
            ?: "build/reports/phase1-shell",
    )
    Files.createDirectories(outputDirectory)

    Window(
        onCloseRequest = ::exitApplication,
        title = "HILTECH Phase 1 Shell Evidence",
        state = rememberWindowState(
            width = 900.dp,
            height = 760.dp,
        ),
    ) {
        var shellState by remember {
            mutableStateOf<HiltechShellState>(
                HiltechShellState.SignedOut,
            )
        }
        val frame = window

        HiltechShell(
            state = shellState,
        )

        LaunchedEffect(Unit) {
            withFrameNanos { }
            delay(500)
            captureWindow(
                frame,
                outputDirectory.resolve(
                    "desktop-signed-out.png",
                ),
            )

            shellState =
                HiltechShellState.SignedIn(
                    identity =
                        IdentityBootstrapDto(
                            identityId =
                                "11111111-1111-1111-1111-111111111111",
                            identityStatus = "ACTIVE",
                            identityVersion = 3,
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
                                        roleLabel =
                                            "Project Manager",
                                        primary = true,
                                        membershipVersion = 2,
                                    ),
                                ),
                            teams = emptyList(),
                        ),
                    security =
                        IdentitySecuritySnapshot(
                            sessions =
                                listOf(
                                    IdentitySessionDto(
                                        sessionId =
                                            "44444444-4444-4444-4444-444444444444",
                                        deviceId =
                                            "55555555-5555-5555-5555-555555555555",
                                        createdAt =
                                            "2026-09-19T07:00:00Z",
                                        lastSeenAt =
                                            "2026-09-19T08:00:00Z",
                                        expiresAt =
                                            "2026-09-19T19:00:00Z",
                                        authenticationStrength =
                                            "OIDC",
                                        current = true,
                                        version = 4,
                                    ),
                                    IdentitySessionDto(
                                        sessionId =
                                            "66666666-6666-6666-6666-666666666666",
                                        deviceId =
                                            "77777777-7777-7777-7777-777777777777",
                                        createdAt =
                                            "2026-09-18T07:00:00Z",
                                        lastSeenAt =
                                            "2026-09-19T06:30:00Z",
                                        expiresAt =
                                            "2026-09-19T18:30:00Z",
                                        authenticationStrength =
                                            "OIDC",
                                        current = false,
                                        version = 2,
                                    ),
                                ),
                            devices =
                                listOf(
                                    IdentityDeviceSecurityDto(
                                        deviceId =
                                            "55555555-5555-5555-5555-555555555555",
                                        installationId =
                                            "88888888-8888-8888-8888-888888888888",
                                        platform = "WINDOWS",
                                        deviceName =
                                            "HILTECH-PM-01",
                                        appVersion = "0.1.0",
                                        osVersion = "11",
                                        lastSeenAt =
                                            "2026-09-19T08:00:00Z",
                                        current = true,
                                        version = 3,
                                    ),
                                    IdentityDeviceSecurityDto(
                                        deviceId =
                                            "77777777-7777-7777-7777-777777777777",
                                        installationId =
                                            "99999999-9999-9999-9999-999999999999",
                                        platform = "ANDROID",
                                        deviceName =
                                            "HILTECH Field Phone",
                                        appVersion = "0.1.0",
                                        osVersion = "16",
                                        lastSeenAt =
                                            "2026-09-19T06:30:00Z",
                                        current = false,
                                        version = 2,
                                    ),
                                ),
                        ),
                )
            withFrameNanos { }
            withFrameNanos { }
            delay(350)

            captureWindow(
                frame,
                outputDirectory.resolve(
                    "desktop-me-sessions.png",
                ),
            )

            shellState = HiltechShellState.AccessDenied(
                code = "IDENTITY_NOT_PROVISIONED",
                message =
                    "This signed-in identity is not provisioned for HILTECH.",
            )
            withFrameNanos { }
            withFrameNanos { }
            delay(350)

            captureWindow(
                frame,
                outputDirectory.resolve(
                    "desktop-access-denied.png",
                ),
            )

            println(
                "HILTECH_PHASE1_DESKTOP_SHELL_RENDER_PASS " +
                    "signed_out=PASS me_sessions=PASS access_denied=PASS",
            )
            exitApplication()
        }
    }
}

private fun captureWindow(
    window: AwtWindow,
    output: Path,
) {
    val location = window.locationOnScreen
    val bounds = Rectangle(
        location.x,
        location.y,
        window.width,
        window.height,
    )
    require(bounds.width > 0 && bounds.height > 0)

    val image = Robot().createScreenCapture(bounds)
    require(ImageIO.write(image, "png", output.toFile()))
    require(Files.size(output) > 0)
}
