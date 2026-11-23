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

/** Native employee / Finance review only, no file upload or approval button. */
@Composable
fun FinanceExpenseReceiptReadinessSection(
    api: FinanceExpenseReceiptReadinessApiClient,
    organizationId: String,
    installationId: String,
    isFinanceReviewer: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(organizationId, isFinanceReviewer) { mutableStateOf(false) }
    var items by remember(organizationId, isFinanceReviewer) {
        mutableStateOf<List<FinanceExpenseReceiptReadinessDto>>(emptyList())
    }
    var loaded by remember(organizationId, isFinanceReviewer) { mutableStateOf(false) }
    var error by remember(organizationId, isFinanceReviewer) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (isFinanceReviewer) "Finance / Expense receipt review"
            else "My expense receipts", style = MaterialTheme.typography.titleLarge)
        Text("Source evidence only. No reimbursement, finance approval or payment can be released here.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    items = if (isFinanceReviewer)
                        api.forFinanceReviewer(organizationId, installationId)
                    else api.mine(organizationId, installationId)
                    loaded = true
                    error = null
                } catch (failure: Exception) {
                    items = emptyList()
                    loaded = false
                    error = failure.message ?: "Expense review unavailable."
                } finally {
                    busy = false
                }
            }
        }) { Text(if (busy) "Checking receipts…" else "Refresh receipt verification") }

        if (loaded && items.isEmpty()) {
            Text("No expense drafts visible with current access.")
        }
        items.forEach { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.expenseAmount + " " + row.currency,
                        style = MaterialTheme.typography.titleSmall)
                    Text("Expense " + row.expenseDraftId.take(8))
                    Text(if (row.uploadedReceiptReady)
                        "Canonical Evidence receipt present — still not approved"
                    else "Receipt verification is not ready")
                    Text("Verified receipt files: " + row.verifiedReceiptCount)
                    row.blockingReasons.forEach { reason ->
                        Text(reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
