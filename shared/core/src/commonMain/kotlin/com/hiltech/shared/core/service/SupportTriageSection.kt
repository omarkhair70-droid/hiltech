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

data class InternalServiceEmployeeChoice(val employeeId: String, val displayName: String)

data class SupportTriageUiState(
    val ticketTitle: String,
    val selectedTicketId: String,
    val events: List<InternalSupportTriageEventDto> = emptyList(),
    val eligibleEmployees: List<InternalServiceEmployeeChoice> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * A native section for manager-authorized internal classification and desk owner.
 * Host supplies real current ACTIVE employee choices from People; it also creates
 * operation IDs and connects auth/navigation. No client or SLA actions.
 */
@Composable
fun SupportTriageSection(
    state: SupportTriageUiState,
    onRefresh: () -> Unit,
    onRecord: (
        eventType: String, employeeId: String?, severityCode: String?,
        priorityCode: String?, reason: String, expectedLatestEventNumber: Int,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedEmployee by remember(state.selectedTicketId) { mutableStateOf<String?>(null) }
    var reason by remember(state.selectedTicketId) { mutableStateOf("") }
    var severity by remember(state.selectedTicketId) { mutableStateOf("") }
    var priority by remember(state.selectedTicketId) { mutableStateOf("") }
    val expected = state.events.maxOfOrNull { it.eventNumber } ?: 0
    val currentOwner = state.events.lastOrNull {
        it.eventType == "OWNER_ASSIGNED" || it.eventType == "OWNER_RELEASED"
    }?.ownerEmployeeId

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Internal support triage", style = MaterialTheme.typography.titleLarge)
        Text(state.ticketTitle, style = MaterialTheme.typography.titleMedium)
        Text(
            "Internal management only. This assigns a desk owner, not a field task, " +
                "and does not set a warranty, contract entitlement, SLA or client message.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) { Text("Refresh events") }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Latest triage event: " + expected)
        state.events.forEach { event ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("#" + event.eventNumber + " · " + event.eventType,
                    style = MaterialTheme.typography.titleSmall)
                event.ownerEmployeeId?.let { Text("Employee " + it.take(8)) }
                if (event.proposedSeverityCode != null) {
                    Text("Internal severity " + event.proposedSeverityCode +
                        " · priority " + (event.proposedPriorityCode ?: "—"))
                }
                Text(event.reason)
                Text(event.createdAt, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Desk owner", style = MaterialTheme.typography.titleMedium)
        Text(
            "Current recorded owner: " + (currentOwner?.take(8) ?: "No owner"),
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.eligibleEmployees.isEmpty()) {
            Text("No eligible employee choices supplied by People. Assignment is unavailable.")
        }
        state.eligibleEmployees.forEach { employee ->
            OutlinedButton(
                onClick = { selectedEmployee = employee.employeeId },
                enabled = !state.loading, modifier = Modifier.fillMaxWidth(),
            ) {
                Text(employee.displayName +
                    (if (employee.employeeId == selectedEmployee) " · selected" else ""))
            }
        }
        OutlinedTextField(
            value = reason, onValueChange = { reason = it },
            label = { Text("Internal reason (required)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = selectedEmployee != null && reason.isNotBlank() && !state.loading,
                onClick = {
                    onRecord("OWNER_ASSIGNED", selectedEmployee, null, null, reason, expected)
                },
            ) { Text("Assign desk owner") }
            OutlinedButton(
                enabled = currentOwner != null && reason.isNotBlank() && !state.loading,
                onClick = {
                    onRecord("OWNER_RELEASED", null, null, null, reason, expected)
                },
            ) { Text("Release owner") }
        }

        Text("Internal classification", style = MaterialTheme.typography.titleMedium)
        Text(
            "These are evidence-backed internal codes; they do not reset contractual response clocks.",
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
            enabled = reason.isNotBlank() && severity.isNotBlank() &&
                priority.isNotBlank() && !state.loading,
            onClick = {
                onRecord("CLASSIFICATION_RECORDED", null, severity, priority, reason, expected)
            },
        ) { Text("Record internal classification") }
    }
}
