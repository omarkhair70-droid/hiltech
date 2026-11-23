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
    val canDraftVisit: Boolean = false,
    val canLinkSupportFollowups: Boolean = false,
    val workChoices: List<MaintenanceWorkChoice> = emptyList(),
    val visits: List<MaintenanceVisitDto> = emptyList(),
    val selectedVisit: MaintenanceVisitDto? = null,
    val findings: List<MaintenanceFindingDto> = emptyList(),
    val supportTicketChoices: List<InternalSupportTicketDto> = emptyList(),
    val findingTicketLinks: List<MaintenanceTicketFollowupDto> = emptyList(),
    val sourceStatus: MaintenanceSourceStatusDto? = null,
    val readyEvidence: List<MaintenanceReadyEvidenceDto> = emptyList(),
    val errorMessage: String? = null,
)

/** Do not expose support triage/ticket linking in a technician-only view. */
object MaintenanceNativePermissions {
    fun mayOfferFollowup(managerAuthorized: Boolean, alreadyLinked: Boolean) =
        managerAuthorized && !alreadyLinked
}

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
    onLinkFindingTicket: (
        findingId: String, ticketId: String, reason: String,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedWorkId by remember(state.projectName) { mutableStateOf<String?>(null) }
    var proposedAt by remember { mutableStateOf("") }
    var evidenceId by remember(state.selectedVisit?.visitId) { mutableStateOf("") }
    var findingType by remember(state.selectedVisit?.visitId) { mutableStateOf("") }
    var findingSeverity by remember(state.selectedVisit?.visitId) { mutableStateOf("") }
    var findingDescription by remember(state.selectedVisit?.visitId) { mutableStateOf("") }
    var selectedFindingId by remember(state.selectedVisit?.visitId) { mutableStateOf<String?>(null) }
    var selectedSupportTicketId by remember(state.selectedVisit?.visitId) { mutableStateOf<String?>(null) }
    var followupReason by remember(state.selectedVisit?.visitId) { mutableStateOf("") }
    val selectedWork = state.workChoices.firstOrNull { it.workOrderId == selectedWorkId }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Maintenance visits", style = MaterialTheme.typography.titleLarge)
        if (state.projectName.isNotBlank()) Text(state.projectName)
        Text(
            "Existing WorkOrders own field execution. A maintenance visit stays a proposal " +
                "until approved; no contract coverage, parts order, or external commitment is created.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
            Text("Refresh work and visits")
        }
        if (state.loading) Text("Loading current project sources…")
        state.errorMessage?.let {
            Text("Cannot load maintenance: " + it, color = MaterialTheme.colorScheme.error)
        }

        if (state.canDraftVisit) {
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
        }

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
                "Findings can be recorded during real field execution using READY Evidence " +
                    "from the exact WorkOrder. The visit is not automatically marked complete.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.findings.forEach { finding ->
                Text(finding.findingTypeCode + " · " + finding.severityCode)
                Text(finding.description)
                Text("Evidence " + finding.evidenceId.take(8),
                    style = MaterialTheme.typography.bodySmall)
                val linked = state.findingTicketLinks.firstOrNull {
                    it.findingId == finding.findingId && it.visitId == visit.visitId
                }
                if (linked != null) {
                    Text("Follow-up: existing support ticket " + linked.ticketId.take(8),
                        style = MaterialTheme.typography.bodySmall)
                } else if (MaintenanceNativePermissions.mayOfferFollowup(
                    state.canLinkSupportFollowups, alreadyLinked = false,
                )) {
                    OutlinedButton(
                        enabled = !state.loading,
                        onClick = {
                            selectedFindingId = finding.findingId
                            selectedSupportTicketId = null
                        },
                    ) { Text("Link existing support ticket") }
                }
            }
            val toLink = state.findings.firstOrNull { it.findingId == selectedFindingId }
            if (toLink != null && MaintenanceNativePermissions.mayOfferFollowup(
                    state.canLinkSupportFollowups,
                    state.findingTicketLinks.any { it.findingId == toLink.findingId },
                )) {
                Text("Follow-up for " + toLink.findingTypeCode,
                    style = MaterialTheme.typography.titleMedium)
                Text("Choose a real open support ticket in this same project. " +
                    "The link does not create a new ticket, work order, PO or invoice.",
                    style = MaterialTheme.typography.bodySmall)
                val available = state.supportTicketChoices.filter {
                    it.internalCaseState in setOf("OPEN", "INVESTIGATING")
                }
                if (available.isEmpty()) {
                    Text("No open support ticket supplied. Open one in the Service Desk first.")
                }
                available.forEach { ticket ->
                    OutlinedButton(
                        enabled = !state.loading,
                        onClick = { selectedSupportTicketId = ticket.ticketId },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(ticket.title +
                            (if (selectedSupportTicketId == ticket.ticketId) " · selected" else ""))
                    }
                }
                OutlinedTextField(
                    value = followupReason, onValueChange = { followupReason = it },
                    label = { Text("Why this finding needs follow-up") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !state.loading && followupReason.isNotBlank() &&
                        selectedSupportTicketId != null,
                    onClick = {
                        selectedSupportTicketId?.let {
                            onLinkFindingTicket(toLink.findingId, it, followupReason)
                        }
                    },
                ) { Text("Record internal support follow-up") }
            }
            state.sourceStatus?.takeIf { it.visitId == visit.visitId }?.let { status ->
                Text("Canonical WorkOrder: " + status.workOrderLifecycleState +
                    " · v" + status.currentWorkVersion)
                Text("Required checklist: " +
                    (status.requiredChecklistTotal - status.requiredChecklistPending) +
                    "/" + status.requiredChecklistTotal +
                    " · READY Evidence " + status.readyWorkEvidenceCount)
                if (!status.canRecordInternalFinding) {
                    Text("Finding unavailable: the visit has not started, source work is not in an execution state, or your work permission is missing.",
                        color = MaterialTheme.colorScheme.error)
                }
                Text("Visit completion not confirmed · Coverage " + status.contractCoverageState,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Select existing READY Work Evidence")
            if (state.readyEvidence.isEmpty()) {
                Text("No READY Evidence choices returned for this WorkOrder.")
            }
            state.readyEvidence.filter { it.workOrderId == visit.workOrderId }.forEach { evidence ->
                OutlinedButton(
                    enabled = !state.loading,
                    onClick = { evidenceId = evidence.evidenceId },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(evidence.evidenceTypeCode + " · " + evidence.capturedAt +
                        if (evidenceId == evidence.evidenceId) " · selected" else "")
                }
            }
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
                enabled = !state.loading && state.sourceStatus?.visitId == visit.visitId &&
                    state.sourceStatus?.canRecordInternalFinding == true &&
                    state.readyEvidence.any { it.evidenceId == evidenceId && it.workOrderId == visit.workOrderId } &&
                    findingType.isNotBlank() && findingSeverity.isNotBlank() && findingDescription.isNotBlank(),
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
