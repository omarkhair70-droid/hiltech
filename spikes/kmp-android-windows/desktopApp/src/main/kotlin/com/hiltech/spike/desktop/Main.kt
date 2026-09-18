package com.hiltech.spike.desktop

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
import com.hiltech.spike.shared.AdaptiveMode
import com.hiltech.spike.shared.HiltechRtlAdaptiveApp
import kotlinx.coroutines.delay

fun main() = application {
    val width = System.getenv("HILTECH_SPIKE_WIDTH")?.toIntOrNull() ?: 1200
    val height = System.getenv("HILTECH_SPIKE_HEIGHT")?.toIntOrNull() ?: 800
    val rtl = System.getenv("HILTECH_SPIKE_RTL") == "true"
    val autoClose = System.getenv("HILTECH_SPIKE_AUTOCLOSE") == "1"

    Window(
        onCloseRequest = ::exitApplication,
        title = "HILTECH RTL Adaptive Spike",
        state = rememberWindowState(
            width = width.dp,
            height = height.dp,
        ),
    ) {
        var resolved by remember { mutableStateOf<AdaptiveMode?>(null) }

        HiltechRtlAdaptiveApp(
            forceRtl = rtl,
            onLayoutResolved = { resolved = it },
        )

        LaunchedEffect(resolved) {
            val mode = resolved ?: return@LaunchedEffect

            withFrameNanos { }
            delay(180)

            println(
                "HILTECH_RTL_RENDER_PASS width=" + width +
                    " height=" + height +
                    " rtl=" + rtl +
                    " mode=" + mode.name,
            )

            if (autoClose) {
                exitApplication()
            }
        }
    }
}
