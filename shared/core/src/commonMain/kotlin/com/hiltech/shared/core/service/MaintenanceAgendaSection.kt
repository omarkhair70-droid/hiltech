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

data class MaintenanceAgendaUiState(
    val projectId: String,
    val projectName: String,
    val agenda: MaintenanceAgendaDto? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Read-only manager's planning agenda, never a contract schedule or field
 * dispatch surface. All items reference actual proposed maintenance visits.
 */
@Composable
fun MaintenancePlanningAgendaSection(
    state: MaintenanceAgendaUiState,
    onQuery: (fromUtc: String, toUtc: String) -> Unit,
    onOpenVisit: (visitId: String) -> Unit,
    modifier: Modifier = Modifier,
    canOpenVisit: Boolean = true,
) {
    var from by remember(state.projectId) { mutableStateOf(state.agenda?.from ?: "") }
    var to by remember(state.projectId) { mutableStateOf(state.agenda?.to ?: "") }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Maintenance planning agenda", style = MaterialTheme.typography.titleLarge)
        Text(state.projectName)
        Text(
            "These are existing internally proposed visits. A reached visit window is " +
                "not evidence that work occurred, a contractual overdue state, or an approved recurrence.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = from, onValueChange = { from = it },
                label = { Text("From UTC (inclusive)") },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = to, onValueChange = { to = it },
                label = { Text("To UTC (exclusive)") },
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            enabled = !state.loading && from.isNotBlank() && to.isNotBlank(),
            onClick = { onQuery(from, to) },
        ) { Text("Load actual visit proposals") }
        if (state.loading) Text("Reading source visits and current WorkOrders…")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.agenda?.let { agenda ->
            Text("Project proposals · " + agenda.items.size +
                (if (agenda.truncated) " (only first 200 — narrow the date range)" else ""))
            if (agenda.items.isEmpty()) Text("No proposed visits in this time window.")
            agenda.items.forEach { entry ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(entry.workOrderTitle, style = MaterialTheme.typography.titleMedium)
                    Text("Proposed: " + entry.plannedFor +
                        " · " + entry.visitWindowState)
                    Text("Work: " + entry.sourceWorkState +
                        " · version " + entry.sourceWorkVersion)
                    Text("Technical review: " + entry.reviewState)
                    Text("Contract schedule: not approved · Coverage: unverified",
                        style = MaterialTheme.typography.bodySmall)
                    if (canOpenVisit) {
                        OutlinedButton(onClick = { onOpenVisit(entry.visitId) }) {
                            Text("Open source visit and technical review")
                        }
                    }
                }
            }
        }
    }
}
