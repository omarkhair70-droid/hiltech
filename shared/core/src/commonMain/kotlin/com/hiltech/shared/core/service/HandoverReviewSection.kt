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

data class HandoverReviewUiState(
    val packageTitle: String,
    val summary: HandoverReviewSummaryDto?,
    val history: List<HandoverReviewEventDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Internal draft review only; no client handover, approval or waiver controls. */
@Composable
fun HandoverInternalReviewSection(
    state: HandoverReviewUiState,
    onRefresh: () -> Unit,
    onRecord: (eventType: String, reason: String, expectedLatestEventNumber: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var reason by remember(state.summary?.packageVersionId) { mutableStateOf("") }
    val item = state.summary
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Internal handover document review", style = MaterialTheme.typography.titleLarge)
        Text(state.packageTitle)
        Text("Checks and findings are internal evidence. They do not approve the package or accept on the client's behalf.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
            Text("Refresh original source and review history")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        item?.let { current ->
            Text("Package v" + current.packageVersionNumber + " · " + current.reviewState,
                style = MaterialTheme.typography.titleMedium)
            Text("Frozen source: " + current.frozenSourceDigest.take(16),
                style = MaterialTheme.typography.bodySmall)
            Text("Current source: " + current.currentSourceDigest.take(16),
                style = MaterialTheme.typography.bodySmall)
            if (!current.sourceStillMatches) {
                Text("Source changed: create a new handover snapshot before making another review decision.",
                    color = MaterialTheme.colorScheme.error)
            }
            if (!current.isLatestPackageVersion) {
                Text("This package is superseded. Open the latest internal draft.",
                    color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = reason, onValueChange = { reason = it },
                label = { Text("Review reason or finding") }, modifier = Modifier.fillMaxWidth(),
            )
            val allowed = !state.loading && current.sourceStillMatches &&
                current.isLatestPackageVersion && reason.isNotBlank()
            if (current.reviewState == "NOT_STARTED") {
                Button(
                    onClick = { onRecord("CHECK_STARTED", reason, current.latestEventNumber) },
                    enabled = allowed,
                ) { Text("Start internal check") }
            }
            if (current.reviewState == "CHECKING" || current.reviewState == "FINDINGS_RECORDED") {
                Button(
                    onClick = { onRecord("FINDING_RECORDED", reason, current.latestEventNumber) },
                    enabled = allowed,
                ) { Text("Record review finding") }
            }
            if (current.reviewState == "FINDINGS_RECORDED") {
                OutlinedButton(
                    onClick = { onRecord("RECHECK_REQUESTED", reason, current.latestEventNumber) },
                    enabled = allowed,
                ) { Text("Request new draft and recheck") }
            }
        }
        Text("Review history", style = MaterialTheme.typography.titleMedium)
        state.history.forEach { event ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("#" + event.eventNumber + " · " + event.eventType)
                Text(event.reason)
                Text(event.createdAt, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
