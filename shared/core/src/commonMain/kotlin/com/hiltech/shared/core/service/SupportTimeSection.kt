package com.hiltech.shared.core.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

data class SupportTimeUiState(
    val ticketTitle: String,
    val observation: SupportTimeObservationDto? = null,
    val history: List<SupportTimeEventDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Manager's immutable historical time observations, NOT contracted SLA. */
@Composable
fun SupportInternalTimeSection(
    state: SupportTimeUiState,
    onRefresh: () -> Unit,
    onRecord: (eventType: String, reason: String, expectedEventNumber: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = state.observation
    var reason by remember(source?.ticketId) { mutableStateOf("") }
    val canWrite = !state.loading && reason.isNotBlank() &&
        source != null && source.caseState in setOf("OPEN", "INVESTIGATING")

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Internal service timing", style = MaterialTheme.typography.titleLarge)
        Text(state.ticketTitle, style = MaterialTheme.typography.titleMedium)
        Text(
            "These are measured internal observations, not a promised client response, " +
                "an approved SLA pause, a coverage decision or a breach.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
            Text("Refresh ticket and original events")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loading) Text("Reading original support history…")
        source?.let { timing ->
            Text("Case: " + timing.caseState)
            Text("Opened: " + timing.openedAt)
            Text("Observed wall time: " + timing.elapsedWallSeconds + " seconds")
            timing.firstInternalAttentionAt?.let {
                Text("First internal attention: " + it)
                Text("Observed time to first internal attention: " +
                    timing.firstInternalAttentionElapsedSeconds + " seconds")
            } ?: Text("First internal attention has not been recorded.")
            Text("Noted internal hold time: " + timing.notedInternalHoldSeconds +
                " seconds (NOT deducted from contractual SLA)")
            if (timing.internalHoldActive) {
                Text("Internal hold active since: " +
                    (timing.internalHoldStartedAt ?: "unknown"),
                    color = MaterialTheme.colorScheme.error)
            }
            Text("Contractual SLA: " + timing.contractualSlaState)
            Text("Contractual hold-pause approval: " + timing.holdSlaPauseApprovalState)
            OutlinedTextField(
                value = reason, onValueChange = { reason = it },
                label = { Text("Reason / source observation (required)") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (canWrite && timing.firstInternalAttentionAt == null) {
                Button(onClick = {
                    onRecord("INTERNAL_ATTENTION_RECORDED", reason, timing.latestEventNumber)
                }) { Text("Record first internal attention") }
            }
            if (canWrite && !timing.internalHoldActive) {
                Button(onClick = {
                    onRecord("INTERNAL_HOLD_STARTED", reason, timing.latestEventNumber)
                }) { Text("Note internal hold (not SLA pause)") }
            }
            if (canWrite && timing.internalHoldActive) {
                Button(onClick = {
                    onRecord("INTERNAL_HOLD_ENDED", reason, timing.latestEventNumber)
                }) { Text("End internal hold") }
            }
        }
        Text("Immutable internal time history", style = MaterialTheme.typography.titleMedium)
        state.history.forEach { event ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("#" + event.eventNumber + " · " + event.eventType)
                Text(event.reason)
                Text(event.recordedAt, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
