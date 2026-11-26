package com.hiltech.shared.core.design

import androidx.compose.runtime.Composable

/** Localized display only. Date-only DTO values remain ISO and do not pass through a device time zone. */
@Composable
fun hiltechDateText(value: String?): String {
    val language = LocalHiltechLanguage.current
    if (value.isNullOrBlank()) return language.text("غير محدد", "Not specified")
    val utcMillis = hiltechDateToUtcMillis(value) ?: return value
    val languageTag = if (language == HiltechLanguage.Arabic) "ar-EG" else "en-GB"
    return hiltechLocalizedDate(utcMillis, languageTag)
}
