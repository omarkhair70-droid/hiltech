package com.hiltech.spike.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.hiltech.spike.shared.HiltechSpikeApp

fun main(args: Array<String>) {
    if (Spike15DesktopOps.handle(args)) {
        return
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "HILTECH Spike",
        ) {
            HiltechSpikeApp()
        }
    }
}
