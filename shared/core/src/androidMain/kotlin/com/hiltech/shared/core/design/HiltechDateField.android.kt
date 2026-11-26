@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hiltech.shared.core.design

import androidx.compose.material3.CalendarLocale
import java.text.DateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

internal actual fun hiltechCalendarLocale(languageTag: String): CalendarLocale = Locale.forLanguageTag(languageTag)

internal actual fun hiltechLocalizedDate(utcMillis: Long, languageTag: String): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.forLanguageTag(languageTag)).apply {
        calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.forLanguageTag(languageTag)).apply {
            gregorianChange = Date(Long.MIN_VALUE)
        }
    }.format(Date(utcMillis))
