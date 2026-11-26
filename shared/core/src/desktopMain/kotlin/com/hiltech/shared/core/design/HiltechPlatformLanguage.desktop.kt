package com.hiltech.shared.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.util.Locale

@Composable
internal actual fun HiltechPlatformLanguageProvider(language: HiltechLanguage, content: @Composable () -> Unit) {
    val locale = remember(language) { Locale.forLanguageTag(if (language == HiltechLanguage.Arabic) "ar-EG" else "en-GB") }
    // The pinned desktop Material library reads Locale.current from the JVM.
    // Set it before composing its children; changing language keeps their form state.
    if (Locale.getDefault() != locale) Locale.setDefault(locale)
    content()
}
