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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class HandoverUiState(
    val loading: Boolean = false,
    val projectName: String = "",
    val readiness: HandoverReadinessDto? = null,
    val latestDraft: HandoverPackageVersionDto? = null,
    val errorMessage: String? = null,
)

/**
 * Role-aware data is loaded by the authenticated feature host.
 * Shared Compose implementation renders natively on Windows and Android.
 * No unsigned/unauthorized client acceptance button is offered.
 */
@Composable
fun HandoverSection(
    state: HandoverUiState,
    onRefresh: () -> Unit,
    onCreateInternalDraft: () -> Unit,
    onOpenInternalReview: (packageVersionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Project handover", style = MaterialTheme.typography.titleLarge)
        if (state.projectName.isNotBlank()) Text(state.projectName)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
                Text("Refresh source documents")
            }
            Button(
                onClick = onCreateInternalDraft,
                enabled = !state.loading && state.readiness != null,
            ) {
                Text("Snapshot internal draft")
            }
        }
        if (state.loading) Text("Checking the current project, work and documents…")
        state.errorMessage?.let {
            Text("Unable to load handover: " + it, color = MaterialTheme.colorScheme.error)
        }

        state.readiness?.let { readiness ->
            Text("Source work orders: " + readiness.workOrderCount)
            Text("Approved current document revisions: " + readiness.documentCount)
            Text("Source digest: " + readiness.sourceDigest.take(16) + "…",
                style = MaterialTheme.typography.bodySmall)
            if (readiness.missingReasons.isEmpty()) {
                Text("No current source omissions detected")
            } else {
                Text("Handover gates", style = MaterialTheme.typography.titleMedium)
                readiness.missingReasons.forEach { code ->
                    Text("• " + code, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "Internal review, external sharing, acceptance and warranty activation " +
                    "remain unavailable until approved handover policy, access rules and contract sources exist.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        state.latestDraft?.let { draft ->
            OutlinedButton(
                enabled = !state.loading,
                onClick = { onOpenInternalReview(draft.packageVersionId) },
            ) { Text("Open source-linked internal review") }
            Text("Internal package v" + draft.versionNumber + " · " + draft.state,
                style = MaterialTheme.typography.titleMedium)
            Text("Captured: " + draft.createdAt)
            Text("Frozen source references: " + draft.documents.size)
            draft.documents.forEach { ref ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("Work " + ref.workOrderId.take(8) + " · document " + ref.documentId.take(8))
                    Text("Revision " + ref.revisionId.take(8) + " · SHA-256 " + ref.sha256.take(12) + "…",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
