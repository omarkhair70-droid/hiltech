package com.hiltech.shared.core.design

import androidx.compose.runtime.Composable

/** Keep platform component strings and accessibility labels in the selected application language. */
@Composable
internal expect fun HiltechPlatformLanguageProvider(language: HiltechLanguage, content: @Composable () -> Unit)
