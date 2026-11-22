package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Finance Windows review queue; always read-only. Android optional, not mounted. */
@Composable
fun FinanceEmployeeReviewSection(
    api: FinanceEmployeeReviewApiClient,
    organizationId: String,
    installationId: String,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(organizationId) { mutableStateOf(false) }
    var loaded by remember(organizationId) { mutableStateOf(false) }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }
    var drafts by remember(organizationId) {
        mutableStateOf<List<FinanceEmployeeReviewDto>>(emptyList())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Employee finance draft review", style = MaterialTheme.typography.titleLarge)
        Text("Read-only triage. Company approval rules, receipts and funding are not yet configured.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    drafts = api.drafts(organizationId, installationId)
                    error = null
                    loaded = true
                } catch (failure: Exception) {
                    drafts = emptyList()
                    loaded = false
                    error = failure.message ?: "Finance review is unavailable."
                } finally {
                    busy = false
                }
            }
        }) { Text("Refresh permitted drafts") }
        if (loaded && drafts.isEmpty()) Text("No employee finance drafts.")
        drafts.forEach { draft ->
            Card(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(draft.kind + " / " + draft.employeeCode,
                        style = MaterialTheme.typography.titleSmall)
                    Text(draft.amount + " " + draft.currency + " / " + draft.purpose)
                    Text(draft.state + " — not submitted, approved, issued or paid",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
