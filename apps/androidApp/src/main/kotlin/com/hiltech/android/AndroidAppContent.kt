package com.hiltech.android

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.hiltech.shared.core.design.HiltechOperationalTheme
import com.hiltech.shared.core.design.LocalHiltechOperationalColors

/** Paint the system-bar area with the current theme while protecting app content. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun ComponentActivity.setHiltechAndroidContent(content: @Composable () -> Unit) {
    setContent {
        HiltechOperationalTheme {
            Box(
                Modifier.fillMaxSize()
                    .background(LocalHiltechOperationalColors.current.canvas)
                    // Consume the larger inset on each edge, including an open IME.
                    // Descendant NavigationBars must not apply these insets twice.
                    .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                    .semantics { testTagsAsResourceId = true },
            ) {
                Box(
                    Modifier.fillMaxSize().testTag("hiltech_android_safe_content"),
                ) {
                    content()
                }
            }
        }
    }
}
