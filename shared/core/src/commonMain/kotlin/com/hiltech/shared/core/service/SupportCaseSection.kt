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

data class SupportCaseWorkChoice(
    val id: String,
    val title: String,
    val lifecycleState: String,
)

data class SupportCaseEvidenceChoice(
    val id: String,
    val workOrderId: String,
    val label: String,
)

data class SupportCaseUiState(
    val ticketTitle: String,
    val case: SupportCaseSummaryDto?,
    val history: List<SupportCaseEventDto> = emptyList(),
    val workChoices: List<SupportCaseWorkChoice> = emptyList(),
    val readyEvidence: List<SupportCaseEvidenceChoice> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Native internal manager view; Work/Evidence choices are loaded by source APIs, not invented here. */
@Composable
fun SupportCaseSection(
    state: SupportCaseUiState,
    onRefresh: () -> Unit,
    onRecord: (
        eventType: String, reason: String,
        workOrderId: String?, evidenceId: String?, expectedLatestEventNumber: Int,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    var reason by remember(state.case?.ticketId) { mutableStateOf("") }
    var chosenWork by remember(state.case?.ticketId) { mutableStateOf<String?>(null) }
    var chosenEvidence by remember(state.case?.ticketId) { mutableStateOf<String?>(null) }
    val current = state.case
    val status = current?.internalState
    val evidenceForWork = state.readyEvidence.filter { it.workOrderId == current?.linkedWorkOrderId }
    val chosenReady = evidenceForWork.firstOrNull { it.id == chosenEvidence }
    val linkedWorkState = state.workChoices.firstOrNull {
        it.id == current?.linkedWorkOrderId
    }?.lifecycleState
    val workWasAccepted = linkedWorkState in setOf("ACCEPTED", "CLOSED")
    val permitted = !state.loading && current != null && reason.isNotBlank()

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Internal service case", style = MaterialTheme.typography.titleLarge)
        Text(state.ticketTitle, style = MaterialTheme.typography.titleMedium)
        Text("No customer acceptance, contractual SLA or warranty coverage is implied.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) { Text("Refresh source and history") }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (current == null) {
            Text("Select a real ticket with internal manager permission.")
        } else {
            Text("Internal state: " + current.internalState)
            Text("Coverage " + current.coverageState + " · SLA " + current.slaState)
            Text("Event #" + current.latestEventNumber)
            Text("Linked WorkOrder: " + (current.linkedWorkOrderId?.take(8) ?: "Not linked"))
            OutlinedTextField(
                value = reason, onValueChange = { reason = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("Reason (required for every decision)") },
            )
            if (status == "OPEN") {
                Button(
                    enabled = permitted,
                    onClick = {
                        onRecord("INVESTIGATION_STARTED", reason, null, null, current.latestEventNumber)
                    },
                ) { Text("Start investigation") }
            }
            if (status == "OPEN" || status == "INVESTIGATING") {
                Text("Link an existing project WorkOrder", style = MaterialTheme.typography.titleMedium)
                state.workChoices.forEach { work ->
                    OutlinedButton(
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { chosenWork = work.id },
                    ) {
                        Text(work.title + " · " + work.lifecycleState +
                            if (chosenWork == work.id) " · selected" else "")
                    }
                }
                Button(
                    enabled = permitted && chosenWork != null,
                    onClick = {
                        onRecord("WORK_ORDER_LINKED", reason, chosenWork, null, current.latestEventNumber)
                    },
                ) { Text("Record linked WorkOrder") }
            }
            if (status == "INVESTIGATING") {
                Text("Ready evidence for the linked WorkOrder", style = MaterialTheme.typography.titleMedium)
                if (current.linkedWorkOrderId == null) {
                    Text("Link the real WorkOrder before recording any resolution.")
                }
                evidenceForWork.forEach { evidence ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.loading,
                        onClick = { chosenEvidence = evidence.id },
                    ) { Text(evidence.label + if (chosenEvidence == evidence.id) " · selected" else "") }
                }
                if (!workWasAccepted) {
                    Text("Existing linked WorkOrder must be ACCEPTED or CLOSED before recording internal resolution.",
                        color = MaterialTheme.colorScheme.error)
                }
                Button(
                    enabled = permitted && workWasAccepted &&
                        current.linkedWorkOrderId != null && chosenReady != null,
                    onClick = {
                        onRecord("RESOLUTION_RECORDED", reason, current.linkedWorkOrderId,
                            chosenEvidence, current.latestEventNumber)
                    },
                ) { Text("Record internally resolved with source evidence") }
            }
            if (status == "RESOLVED_INTERNAL") {
                Button(
                    enabled = permitted,
                    onClick = {
                        onRecord("INTERNAL_CLOSE_RECORDED", reason, null, null, current.latestEventNumber)
                    },
                ) { Text("Record internal closure (not client acceptance)") }
            }
            if (status == "RESOLVED_INTERNAL" || status == "CLOSED_INTERNAL") {
                OutlinedButton(
                    enabled = permitted,
                    onClick = {
                        onRecord("REOPENED", reason, null, null, current.latestEventNumber)
                    },
                ) { Text("Reopen for investigation") }
            }
        }
        Text("Immutable internal timeline", style = MaterialTheme.typography.titleMedium)
        state.history.forEach { event ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("#" + event.eventNumber + " · " + event.eventType,
                    style = MaterialTheme.typography.titleSmall)
                Text(event.resultingState + " · " + event.recordedAt,
                    style = MaterialTheme.typography.bodySmall)
                Text(event.reason)
                event.evidenceId?.let {
                    Text("Verified source evidence " + it.take(8),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
