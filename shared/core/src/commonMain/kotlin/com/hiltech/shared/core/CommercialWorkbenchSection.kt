package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp

/** Commercial state is obtained only through the authenticated, role-filtered API. */
data class CommercialOpportunityTile(
    val id: String,
    val title: String,
    val state: String,
    val source: String,
    val clientOrganizationId: String,
    val deadline: String? = null,
)
data class CommercialWorkbenchState(
    val loading: Boolean = false,
    val enabled: Boolean = false,
    val canWrite: Boolean = false,
    val canRelease: Boolean = false,
    val opportunities: List<CommercialOpportunityTile> = emptyList(),
    val selectedOpportunityId: String? = null,
    val selectedQuoteId: String? = null,
    val errorCode: String? = null,
)

/**
 * Native Compose surface shared by the Windows workbench and bounded Android visits.
 * Integration owner mounts the feature under an explicit default-OFF runtime gate;
 * never place a general employee route into the global shell without a role check.
 */
@Composable
fun CommercialWorkbenchSection(
    state: CommercialWorkbenchState,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onCreate: (title: String, clientOrganizationId: String, source: String) -> Unit,
    onPlanVisit: (opportunityId: String, purpose: String, scheduledAt: String) -> Unit,
    onRequestQuoteApproval: (quoteId: String) -> Unit,
    onReleaseApprovedQuote: (quoteId: String) -> Unit,
) {
    if (!state.enabled) {
        Text("Commercial operations are not enabled.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    var title by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var visitPurpose by remember { mutableStateOf("") }
    var visitAt by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sales / Tenders", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
        }
        if (state.loading) Text("Loading commercial records…")
        state.errorCode?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.canWrite) {
            Text("New opportunity", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Opportunity title") },
            )
            OutlinedTextField(
                value = clientId, onValueChange = { clientId = it },
                label = { Text("Existing client organization ID") },
            )
            Button(
                enabled = title.isNotBlank() && clientId.isNotBlank(),
                onClick = { onCreate(title.trim(), clientId.trim(), "DIRECT") },
            ) { Text("Create opportunity") }
        }
        state.opportunities.forEach { opportunity ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    Text(opportunity.title, style = MaterialTheme.typography.titleSmall)
                    Text(opportunity.state + " · " + opportunity.source)
                    opportunity.deadline?.let { Text("Decision: " + it) }
                }
                OutlinedButton(onClick = { onSelect(opportunity.id) }) { Text("Open") }
            }
        }
        if (state.canWrite && state.selectedOpportunityId != null) {
            Text("Discovery / site visit", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = visitPurpose, onValueChange = { visitPurpose = it },
                label = { Text("Purpose") },
            )
            OutlinedTextField(
                value = visitAt, onValueChange = { visitAt = it },
                label = { Text("Scheduled UTC (ISO-8601)") },
            )
            Button(
                enabled = visitPurpose.isNotBlank() && visitAt.isNotBlank(),
                onClick = {
                    onPlanVisit(state.selectedOpportunityId, visitPurpose.trim(), visitAt.trim())
                },
            ) { Text("Record site visit") }
        }
        if (state.canWrite && state.selectedQuoteId != null) {
            OutlinedButton(onClick = { onRequestQuoteApproval(state.selectedQuoteId) }) {
                Text("Request independent quote approval")
            }
        }
        if (state.canRelease && state.selectedQuoteId != null) {
            Button(onClick = { onReleaseApprovedQuote(state.selectedQuoteId) }) {
                Text("Release approved quote")
            }
        }
        Text(
            "Internal costing and supplier quotations are never shown in the customer quote preview.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
