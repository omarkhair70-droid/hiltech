package com.hiltech.shared.core.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import com.hiltech.shared.core.design.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SupportDeskUiState(
    val loading: Boolean = false,
    val projectName: String = "",
    val tickets: List<InternalSupportTicketDto> = emptyList(),
    val selectedTicket: InternalSupportTicketDto? = null,
    val internalNotes: List<InternalSupportNoteDto> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Native Compose PM-only service desk. The feature host enforces project
 * authority by receiving data from authenticated server endpoints.
 * This does NOT expose a client portal, an SLA timer or external comments.
 */
@Composable
fun InternalSupportDeskSection(
    state: SupportDeskUiState,
    onRefresh: () -> Unit,
    onCreateTicket: (title: String, description: String, severity: String, priority: String) -> Unit,
    onSelectTicket: (String) -> Unit,
    onOpenCase: (ticketId: String) -> Unit,
    onOpenTriage: (ticketId: String) -> Unit,
    onAddInternalNote: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    val colors = LocalHiltechOperationalColors.current
    var creating by remember(state.projectName) { mutableStateOf(false) }
    var query by remember(state.projectName) { mutableStateOf("") }
    var caseFilter by remember(state.projectName) { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var severity by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("") }
    var note by remember(state.selectedTicket?.ticketId) { mutableStateOf("") }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                HiltechSectionHeading("SERVICE / SUPPORT", hiltechText("مكتب الدعم", "Service desk"), state.projectName.takeIf { it.isNotBlank() })
            }
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text(hiltechText("تحديث", "Refresh")) }
        }
        if (state.loading) Text(hiltechText("جارٍ تحميل سجل الخدمة…", "Loading the current project service record…"))
        state.errorMessage?.let {
            Text("Service unavailable: " + it, color = MaterialTheme.colorScheme.error)
        }

        if (!readOnly) TextButton(onClick = { creating = !creating }) { Text(if (creating) hiltechText("إغلاق", "Close") else hiltechText("+ بلاغ", "+ Ticket")) }
        if (!readOnly && creating) {
        Text(hiltechText("بلاغ داخلي جديد", "New internal ticket"), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text(hiltechText("عنوان المشكلة", "Issue title")) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description, onValueChange = { description = it }, label = { Text(hiltechText("الوصف", "Description")) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Enter the actual configured severity and priority codes separately; " +
                "neither implies a contractual response target.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = severity, onValueChange = { severity = it },
                label = { Text(hiltechText("درجة الخطورة", "Severity code")) }, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = priority, onValueChange = { priority = it },
                label = { Text(hiltechText("الأولوية", "Priority code")) }, modifier = Modifier.weight(1f),
            )
        }
        Button(
            enabled = !state.loading &&
                title.isNotBlank() && description.isNotBlank() &&
                severity.isNotBlank() && priority.isNotBlank(),
            onClick = { onCreateTicket(title, description, severity, priority) },
        ) {
            Text(hiltechText("تسجيل البلاغ الداخلي", "Record internal ticket"))
        }

        }
        Text(hiltechText("البلاغات", "Tickets"), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(hiltechText("ابحث عن بلاغ", "Search tickets")) })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = caseFilter == null, onClick = { caseFilter = null }, label = { Text(hiltechText("الكل", "All")) })
            state.tickets.map { it.internalCaseState }.distinct().forEach { stage ->
                FilterChip(selected = caseFilter == stage, onClick = { caseFilter = stage }, label = { Text(stage) })
            }
        }
        val visible = state.tickets.filter { (caseFilter == null || it.internalCaseState == caseFilter) && (query.isBlank() || it.title.contains(query.trim(), true)) }
        if (visible.isEmpty() && !state.loading) Text(hiltechText("لا توجد بلاغات مطابقة.", "No matching tickets."))
        visible.forEach { ticket ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                Text(ticket.title, style = MaterialTheme.typography.titleMedium)
                HiltechStateTag(ticket.internalCaseState, HiltechStateTone.NEUTRAL)
                Text(
                    "Case: " + ticket.internalCaseState +
                        " · Original report: " + ticket.lifecycleState +
                        " · Coverage: " + ticket.coverageState +
                        " · SLA: " + ticket.slaState,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Severity " + ticket.severityCode + " / Priority " + ticket.priorityCode)
                if (readOnly) {
                    OutlinedButton(onClick = { onSelectTicket(ticket.ticketId) }) {
                        Text(hiltechText("فتح البلاغ", "Review source ticket"))
                    }
                } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSelectTicket(ticket.ticketId) }) {
                        Text(hiltechText("ملاحظات داخلية", "Private notes"))
                    }
                    OutlinedButton(onClick = { onOpenCase(ticket.ticketId) }) {
                        Text(hiltechText("مسار الحالة", "Case lifecycle"))
                    }
                }
                OutlinedButton(onClick = { onOpenTriage(ticket.ticketId) }) {
                    Text(hiltechText("سجل التعيين والتصنيف", "Assignment and classification history"))
                }
                }
            }
        }

        state.selectedTicket?.let { selected ->
            Text("Internal notes · " + selected.title, style = MaterialTheme.typography.titleMedium)
            Text("These notes must never be sent to the client automatically.",
                style = MaterialTheme.typography.bodySmall)
            state.internalNotes.forEach { entry ->
                Text(entry.body)
                Text(entry.createdAt, style = MaterialTheme.typography.bodySmall)
            }
            if (!readOnly) {
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(hiltechText("ملاحظة داخلية", "Internal note")) },
            )
            Button(
                enabled = note.isNotBlank() && !state.loading,
                onClick = { onAddInternalNote(selected.ticketId, note) },
            ) { Text(hiltechText("حفظ الملاحظة", "Save internal note")) }
            }
        }
    }
}
