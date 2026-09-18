package com.hiltech.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.hiltech.shared.core.HiltechShell

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "HILTECH",
    ) {
        HiltechShell()
    }
}
