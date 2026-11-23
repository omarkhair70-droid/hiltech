package com.hiltech.shared.core.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class WarrantyCoverageReadinessUiState(
    val ticketTitle: String,
    val assessment: WarrantyCoverageReadinessDto? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Standalone manager-only view; native shell wiring belongs to UX integration. */
@Composable
fun WarrantyCoverageReadinessSection(
    state: WarrantyCoverageReadinessUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Warranty review readiness", style = MaterialTheme.typography.titleLarge)
        Text(state.ticketTitle, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
            Text("Recheck contract evidence")
        }
        if (state.loading) Text("Reading company contract and ticket sources…")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.assessment?.let { result ->
            Text("Coverage: " + result.coverageDecisionState)
            Text("Approval: " + result.approvalState)
            Text("Contract source: " + result.commercialSourceState)
            result.contractId?.let { Text("Contract: " + it.take(8)) }
            Text("Written warranty terms: " +
                if (result.writtenWarrantyTermsPresent) "Present, not yet approved" else "Not available")
            Text("Covered equipment/site: " + result.coveredAssetOrSiteState)
            Text("Requirements before a coverage decision:",
                style = MaterialTheme.typography.titleSmall)
            result.blockerCodes.forEach { Text("• " + it.replace('_', ' ')) }
            Text(
                "This is an internal evidence review, not a warranty approval. " +
                    "No client response, contractual SLA, paid service or entitlement is authorized.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
