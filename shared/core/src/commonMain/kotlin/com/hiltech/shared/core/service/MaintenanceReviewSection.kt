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

data class MaintenanceReviewUiState(
    val visitTitle: String,
    val readiness: MaintenanceReviewReadinessDto? = null,
    val evidenceChoices: List<MaintenanceReadyEvidenceDto> = emptyList(),
    val history: List<MaintenanceReviewEventDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Internal PM technical verification of an existing WorkOrder's visit.
 * Never exposes client sign-off, coverage, billing, SLA, or warranty actions.
 */
@Composable
fun MaintenanceTechnicalReviewSection(
    state: MaintenanceReviewUiState,
    onRefresh: () -> Unit,
    onRecord: (
        eventType: String, evidenceId: String?, reason: String,
        expectedLatestEventNumber: Int, expectedSourceWorkVersion: Long,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    val readiness = state.readiness
    var reason by remember(readiness?.visitId) { mutableStateOf("") }
    var selectedEvidence by remember(readiness?.visitId) { mutableStateOf<String?>(null) }
    val currentEvidence = state.evidenceChoices.any { it.evidenceId == selectedEvidence }
    val canWrite = !state.loading && reason.isNotBlank() && readiness != null

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Internal maintenance technical review", style = MaterialTheme.typography.titleLarge)
        Text(state.visitTitle)
        Text(
            "An internal technical finding or completion record is not the client's " +
                "handover acceptance, a contractual service visit, or warranty approval.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
            Text("Refresh canonical WorkOrder and visit proof")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        readiness?.let { current ->
            Text("Review: " + current.reviewState, style = MaterialTheme.typography.titleMedium)
            Text("Source WorkOrder: " + current.currentWorkState +
                " · version " + current.currentWorkVersion)
            Text("Required checklist: " + current.requiredChecklistTotal +
                " · pending " + current.requiredChecklistPending)
            Text("READY visit Evidence: " + current.readyVisitEvidenceCount)
            if (current.missingReasons.isNotEmpty()) {
                current.missingReasons.forEach {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
            OutlinedTextField(
                value = reason, onValueChange = { reason = it },
                label = { Text("Manager review reason (mandatory)") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (current.reviewState == "NOT_STARTED" ||
                current.reviewState == "RECHECK_REQUIRED") {
                Button(
                    enabled = canWrite,
                    onClick = {
                        onRecord(
                            "REVIEW_STARTED", null, reason,
                            current.latestEventNumber, current.currentWorkVersion,
                        )
                    },
                ) { Text("Start internal technical review") }
            }
            if (current.reviewState == "REVIEWING") {
                Text("Choose source READY Evidence from this visit:",
                    style = MaterialTheme.typography.titleMedium)
                if (state.evidenceChoices.isEmpty())
                    Text("No current READY source Evidence available for completion.")
                state.evidenceChoices.forEach { item ->
                    OutlinedButton(
                        onClick = { selectedEvidence = item.evidenceId },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(item.evidenceTypeCode + " · " + item.capturedAt +
                            (if (selectedEvidence == item.evidenceId) " · selected" else ""))
                    }
                }
                Button(
                    enabled = canWrite && current.canConfirmInternalTechnicalCompletion &&
                        currentEvidence,
                    onClick = {
                        onRecord(
                            "INTERNAL_TECHNICAL_COMPLETION", selectedEvidence,
                            reason, current.latestEventNumber, current.currentWorkVersion,
                        )
                    },
                ) { Text("Record technical completion (internal only)") }
            }
            if (current.reviewState == "COMPLETED_INTERNAL") {
                Text("Technical completion is recorded internally; unresolved findings " +
                    "and contractual client acceptance are separate.")
                OutlinedButton(
                    enabled = canWrite,
                    onClick = {
                        onRecord(
                            "RECHECK_REQUESTED", null, reason,
                            current.latestEventNumber, current.currentWorkVersion,
                        )
                    },
                ) { Text("Request new technical check") }
            }
        }
        Text("Immutable review history", style = MaterialTheme.typography.titleMedium)
        state.history.forEach { item ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("#" + item.eventNumber + " · " + item.resultingState)
                Text(item.reason)
                Text(item.recordedAt, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
