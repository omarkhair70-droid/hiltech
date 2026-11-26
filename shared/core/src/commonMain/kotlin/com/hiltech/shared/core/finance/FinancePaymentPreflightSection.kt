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

/**
 * Finance Windows/Android native preflight. The integration team alone
 * owns navigation. This screen NEVER contains a payment command.
 */
@Composable
fun FinancePaymentPreflightSection(
    api: FinancePaymentPreflightApiClient,
    organizationId: String,
    installationId: String,
) {
    val scope = rememberCoroutineScope()
    var loading by remember(organizationId) { mutableStateOf(false) }
    var result by remember(organizationId) {
        mutableStateOf<FinancePaymentPreflightReportDto?>(null)
    }
    var pulse by remember(organizationId) {
        mutableStateOf<FinanceCandidatePulseDto?>(null)
    }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Finance / Payment readiness", style = MaterialTheme.typography.titleLarge)
        Text("No supplier payment can be approved, issued, transmitted or reconciled by this screen.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !loading, onClick = {
            loading = true
            scope.launch {
                try {
                    val next = api.readiness(organizationId, installationId)
                    val nextPulse = api.candidatePulse(organizationId, installationId)
                    result = next
                    pulse = nextPulse
                    error = null
                } catch (failure: Exception) {
                    result = null
                    pulse = null
                    error = failure.message ?: "Finance readiness unavailable."
                } finally {
                    loading = false
                }
            }
        }) { Text(if (loading) "Checking…" else "Refresh current payment blockers") }

        pulse?.let { current ->
            Text("Unposted invoice review candidates",
                style = MaterialTheme.typography.titleMedium)
            Text("As of " + current.observedAt,
                style = MaterialTheme.typography.labelSmall)
            current.groupsByCurrency.forEach { group ->
                Text(group.reviewCandidateCount.toString() + " items / " +
                    group.reviewCandidateTotal + " " + group.currency +
                    " under review; NOT booked, approved or paid")
            }
            Text("Bank balance unknown. Reconciliation unavailable.",
                style = MaterialTheme.typography.bodySmall)
        }

        result?.let { report ->
            Text("Supplier invoices awaiting accounting authorization",
                style = MaterialTheme.typography.titleMedium)
            if (report.rows.isEmpty()) Text("No source-bound supplier review candidates.")
            report.rows.forEach { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Invoice " + row.invoiceNumber,
                            style = MaterialTheme.typography.titleSmall)
                        Text(row.amount + " " + row.currency + " / " +
                            row.accountingSourceState)
                        Text(if (row.sourceCurrent) "Source match currently valid" else
                            "Source changed: return to Procurement",
                            style = MaterialTheme.typography.bodySmall)
                        row.blockingReasons.forEach { reason ->
                            Text("Blocked: " + reason,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
