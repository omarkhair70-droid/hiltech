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

data class MaintenanceWorkChoice(
    val workOrderId: String,
    val title: String,
    val version: Long,
)

data class MaintenanceUiState(
    val loading: Boolean = false,
    val projectName: String = "",
    val workChoices: List<MaintenanceWorkChoice> = emptyList(),
    val visits: List<MaintenanceVisitDto> = emptyList(),
    val selectedVisit: MaintenanceVisitDto? = null,
    val findings: List<MaintenanceFindingDto> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Native cross-platform feature section. Integration must supply authenticated
 * actual WorkOrder choices, current versions and source-approved permissions.
 * No invented recurrence, warranty, scheduled field assignment or auto PO.
 */
@Composable
fun MaintenanceVisitsSection(
    state: MaintenanceUiState,
    onRefresh: () -> Unit,
    onCreateDraft: (workOrderId: String, workOrderVersion: Long, plannedAtIso: String) -> Unit,
    onSelectVisit: (String) -> Unit,
    onRecordFinding: (
        visitId: String, evidenceId: String, typeCode: String,
        severityCode: String, description: String,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedWorkId by remember { mutableStateOf<String?>(null) }
    var proposedAt by remember { mutableStateOf("") }
    var evidenceId by remember(state.selectedVisit?.visitId) { mutableStateOf("") }
    var findingType by remember(state.selectedVisit?.visitId) { mutableStateOf("") }
    var findingSeverity by remember(state.selectedVisit?.visitId) { mutableStateOf("") }
    var findingDescription by remember(state.selectedVisit?.visitId) { mutableStateOf("") }
    val selectedWork = state.workChoices.firstOrNull { it.workOrderId == selectedWorkId }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Maintenance visits", style = MaterialTheme.typography.titleLarge)
        if (state.projectName.isNotBlank()) Text(state.projectName)
        Text(
            "Draft visit proposals linked to real WorkOrders. No confirmed visit, " +
                "contract coverage, parts order or service commitment is created.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
            Text("Refresh work and visits")
        }
        if (state.loading) Text("Loading current project sources…")
        state.errorMessage?.let {
            Text("Cannot load maintenance: " + it, color = MaterialTheme.colorScheme.error)
        }

        Text("Choose existing work", style = MaterialTheme.typography.titleMedium)
        state.workChoices.forEach { choice ->
            OutlinedButton(
                onClick = { selectedWorkId = choice.workOrderId },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    choice.title + " · v" + choice.version +
                        (if (choice.workOrderId == selectedWorkId) " · selected" else ""),
                )
            }
        }
        OutlinedTextField(
            value = proposedAt,
            onValueChange = { proposedAt = it },
            label = { Text("Proposed time (ISO-8601 UTC)") },
            supportingText = { Text("From the actual agreed visit proposal; no automatic frequency") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = selectedWork != null && proposedAt.isNotBlank() && !state.loading,
            onClick = {
                selectedWork?.let { work ->
                    onCreateDraft(work.workOrderId, work.version, proposedAt)
                }
            },
        ) { Text("Save internal visit draft") }

        Text("Visit plans", style = MaterialTheme.typography.titleMedium)
        state.visits.forEach { visit ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("Proposed " + visit.plannedFor, style = MaterialTheme.typography.titleSmall)
                Text(visit.state + " · Coverage " + visit.coverageState)
                Text("Source WorkOrder " + visit.workOrderId.take(8))
                OutlinedButton(onClick = { onSelectVisit(visit.visitId) }) {
                    Text("View work checklist and findings")
                }
            }
        }

        state.selectedVisit?.let { visit ->
            Text("Source work checklist", style = MaterialTheme.typography.titleMedium)
            if (visit.sourceChecklist.isEmpty()) {
                Text("No checklist materialized on this WorkOrder.")
            }
            visit.sourceChecklist.forEach { item ->
                Text(item.label + " · " + item.completionState +
                    (if (item.required) " · required" else ""))
            }
            Text("Evidence-linked field findings", style = MaterialTheme.typography.titleMedium)
            Text(
                "An accepted source WorkOrder and READY Evidence from that exact work are " +
                    "required. Recording a finding does not mark this visit completed.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.findings.forEach { finding ->
                Text(finding.findingTypeCode + " · " + finding.severityCode)
                Text(finding.description)
                Text("Evidence " + finding.evidenceId.take(8),
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = evidenceId, onValueChange = { evidenceId = it },
                label = { Text("Existing READY evidence ID") }, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = findingType, onValueChange = { findingType = it },
                    label = { Text("Finding type") }, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = findingSeverity, onValueChange = { findingSeverity = it },
                    label = { Text("Severity code") }, modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = findingDescription, onValueChange = { findingDescription = it },
                label = { Text("Observed finding") }, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !state.loading && evidenceId.isNotBlank() && findingType.isNotBlank() &&
                    findingSeverity.isNotBlank() && findingDescription.isNotBlank(),
                onClick = {
                    onRecordFinding(
                        visit.visitId, evidenceId,
                        findingType, findingSeverity, findingDescription,
                    )
                },
            ) { Text("Record internal evidence-linked finding") }
        }
    }
}
