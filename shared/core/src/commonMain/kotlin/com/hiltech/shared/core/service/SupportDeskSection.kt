package com.hiltech.shared.core.service

import androidx.compose.foundation.layout.Arrangement
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
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var severity by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("") }
    var note by remember(state.selectedTicket?.ticketId) { mutableStateOf("") }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Internal service desk", style = MaterialTheme.typography.titleLarge)
        if (state.projectName.isNotBlank()) Text(state.projectName)
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
            Text("Refresh tickets")
        }
        if (state.loading) Text("Loading the current project service record…")
        state.errorMessage?.let {
            Text("Service unavailable: " + it, color = MaterialTheme.colorScheme.error)
        }

        Text("New internal ticket", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("Issue title") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description, onValueChange = { description = it }, label = { Text("Description") },
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
                label = { Text("Severity code") }, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = priority, onValueChange = { priority = it },
                label = { Text("Priority code") }, modifier = Modifier.weight(1f),
            )
        }
        Button(
            enabled = !state.loading &&
                title.isNotBlank() && description.isNotBlank() &&
                severity.isNotBlank() && priority.isNotBlank(),
            onClick = { onCreateTicket(title, description, severity, priority) },
        ) {
            Text("Record internal ticket")
        }

        Text("Recorded tickets", style = MaterialTheme.typography.titleMedium)
        state.tickets.forEach { ticket ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(ticket.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    ticket.lifecycleState + " · Coverage: " + ticket.coverageState +
                        " · SLA: " + ticket.slaState,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Severity " + ticket.severityCode + " / Priority " + ticket.priorityCode)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSelectTicket(ticket.ticketId) }) {
                        Text("Private notes")
                    }
                    OutlinedButton(onClick = { onOpenCase(ticket.ticketId) }) {
                        Text("Case lifecycle")
                    }
                }
                OutlinedButton(onClick = { onOpenTriage(ticket.ticketId) }) {
                    Text("Assignment and classification history")
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
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Internal note") },
            )
            Button(
                enabled = note.isNotBlank() && !state.loading,
                onClick = { onAddInternalNote(selected.ticketId, note) },
            ) { Text("Save internal note") }
        }
    }
}
