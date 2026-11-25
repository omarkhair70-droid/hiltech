@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hiltech.shared.core.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CalendarLocale
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

internal expect fun hiltechCalendarLocale(languageTag: String): CalendarLocale
internal expect fun hiltechLocalizedDate(utcMillis: Long, languageTag: String): String

/** Calendar selection for both clients. The callback preserves the existing ISO-date DTO contract. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiltechDateField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    optional: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = LocalHiltechOperationalColors.current
    val language = LocalHiltechLanguage.current
    val languageTag = if (language == HiltechLanguage.Arabic) "ar-EG" else "en-GB"
    val selectedMillis = hiltechDateToUtcMillis(value)
    val invalid = value.isNotBlank() && selectedMillis == null
    var open by remember { mutableStateOf(false) }
    val title = label + if (optional) language.text(" (اختياري)", " (optional)") else ""
    val displayValue = selectedMillis?.let { hiltechLocalizedDate(it, languageTag) }
        ?: if (invalid) value else language.text("اختر تاريخًا", "Choose a date")
    val invalidMessage = language.text("التاريخ غير صالح. اختر تاريخًا من التقويم.", "This date is invalid. Choose a date from the calendar.")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = colors.secondaryText)
        Surface(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = displayValue
                role = Role.Button
                if (invalid) error(invalidMessage)
            },
            color = colors.surfaceElevated,
            shape = HiltechShape.small,
            border = BorderStroke(1.dp, if (invalid) MaterialTheme.colorScheme.error else colors.divider),
        ) {
            Row(
                Modifier.heightIn(min = 48.dp).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    displayValue,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.primaryText,
                )
                Text(language.text("التقويم", "Calendar"), style = MaterialTheme.typography.labelLarge, color = colors.accent)
            }
        }
        if (invalid) Text(
            invalidMessage,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (open && enabled) {
        val pickerState = remember(languageTag) {
            DatePickerState(
                locale = hiltechCalendarLocale(languageTag),
                initialSelectedDateMillis = selectedMillis,
                yearRange = 1..9999,
            )
        }
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedDateMillis?.let(::hiltechDateFromUtcMillis) != null,
                    onClick = {
                        pickerState.selectedDateMillis?.let(::hiltechDateFromUtcMillis)?.let(onValueChange)
                        open = false
                    },
                ) { Text(language.text("اختيار", "Select")) }
            },
            dismissButton = {
                Row {
                    if (optional) TextButton(onClick = { onValueChange(""); open = false }) {
                        Text(language.text("مسح التاريخ", "Clear date"))
                    }
                    TextButton(onClick = { open = false }) { Text(language.text("إلغاء", "Cancel")) }
                }
            },
        ) {
            DatePicker(
                state = pickerState,
                showModeToggle = false,
                title = { Text(title, Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.labelLarge) },
                headline = {
                    Text(
                        pickerState.selectedDateMillis?.let { hiltechLocalizedDate(it, languageTag) }
                            ?: language.text("اختر تاريخًا", "Choose a date"),
                        Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
            )
        }
    }
}

@Composable
fun HiltechPlannedDateRange(
    start: String,
    end: String,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HiltechDateField(hiltechText("البداية المخططة", "Planned start"), start, onStartChange, enabled = enabled)
        HiltechDateField(hiltechText("النهاية المخططة", "Planned end"), end, onEndChange, enabled = enabled)
        if (hiltechDateRangeProblem(start, end) == HiltechDateRangeProblem.END_BEFORE_START) Text(
            hiltechText("تاريخ النهاية يجب أن يساوي تاريخ البداية أو يأتي بعده.", "The end date must be on or after the start date."),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
