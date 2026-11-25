package com.hiltech.shared.core.design

import android.content.res.Configuration
import android.os.LocaleList
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
internal actual fun HiltechPlatformLanguageProvider(language: HiltechLanguage, content: @Composable () -> Unit) {
    val parent = LocalContext.current
    val configuration = LocalConfiguration.current
    val localized = remember(parent, configuration, language) {
        val locale = Locale.forLanguageTag(if (language == HiltechLanguage.Arabic) "ar-EG" else "en-GB")
        val copy = Configuration(configuration).apply { setLocales(LocaleList(locale)); setLayoutDirection(locale) }
        ContextThemeWrapper(parent, parent.theme).apply { applyOverrideConfiguration(copy) } to copy
    }
    // A themed wrapper retains the activity as its base. No activity recreation,
    // process-wide Android locale change, or loss of an in-progress form is needed.
    CompositionLocalProvider(LocalContext provides localized.first, LocalConfiguration provides localized.second, content = content)
}
