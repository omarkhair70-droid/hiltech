package com.hiltech.shared.core.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ServiceContractSourceUiState(
    val projectName: String,
    val source: ProjectServiceContractSourceDto? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * HILTECH managers may view approved commercial source pointers only.
 * Signed metadata is NOT the interpreted warranty policy or a service schedule.
 */
@Composable
fun ServiceContractSourceSection(
    state: ServiceContractSourceUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Project service contract source", style = MaterialTheme.typography.titleLarge)
        Text(state.projectName, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
            Text("Refresh signed commercial evidence")
        }
        if (state.loading) Text("Reading the original commercial contract record…")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.source?.let { source ->
            Text("Source verification: " + source.sourceState)
            source.contractId?.let { Text("Contract reference: " + it.take(8)) }
            source.clientPoId?.let { Text("Client purchase order reference: " + it.take(8)) }
            source.sourceEffectiveDate?.let { Text("Contract effective date: " + it) }
            source.sourceEndDate?.let { Text("Contract end date: " + it) }
            Text("Written warranty terms: " + if (source.hasWrittenWarrantyTerms) "Recorded" else "Not recorded")
            Text("Written maintenance terms: " + if (source.hasWrittenMaintenanceTerms) "Recorded" else "Not recorded")
            Text("Warranty entitlement: " + source.warrantyEntitlementState,
                style = MaterialTheme.typography.bodyMedium)
            Text("SLA policy: " + source.slaPolicyState)
            Text("Maintenance schedule: " + source.maintenanceScheduleState)
            Text("Renewal eligibility: " + source.renewalEligibilityState)
            Text(
                "Even a verified signed record does not make warranty active, " +
                    "calculate SLA deadlines, book visits or approve renewals. " +
                    "Those require approved company policy and actual contract interpretation.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
