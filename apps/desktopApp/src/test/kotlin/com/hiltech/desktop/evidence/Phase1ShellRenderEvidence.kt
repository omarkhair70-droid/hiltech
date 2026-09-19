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
            width = 760.dp,
            height = 520.dp,
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
                    "signed_out=PASS access_denied=PASS",
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
