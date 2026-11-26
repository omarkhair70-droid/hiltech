package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Finance-only, online, read-only native projection of genuine awarded
 * Commercial sources. No invoice creation, booking, due dates or collection.
 * Mount only behind an authenticated Finance route.
 */
@Composable
fun FinanceClientAwardSourceSection(
    organizationId: String,
    installationId: String,
    sources: FinanceClientAwardSourceApiClient,
) {
    val scope = rememberCoroutineScope()
    var loading by remember(organizationId) { mutableStateOf(false) }
    var report by remember(organizationId) {
        mutableStateOf<FinanceClientAwardSourcePageDto?>(null)
    }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Client awards · Finance source", style = MaterialTheme.typography.titleSmall)
        Text(
            "Signed Sales/Commercial source only. Not issued invoices or booked receivables.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(enabled = !loading, onClick = {
            loading = true
            scope.launch {
                try {
                    val latest = sources.list(organizationId, installationId)
                    check(
                        latest.reportedCompanyReceivableTotal == null &&
                            latest.reportedCompanyRevenueTotal == null &&
                            latest.reportedCustomerCollectionsTotal == null
                    ) { "Source-only projection cannot report cash or receivable totals." }
                    report = latest
                    error = null
                } catch (failure: Exception) {
                    error = failure.message ?: "Finance awarded-project sources unavailable."
                } finally {
                    loading = false
                }
            }
        }) { Text(if (loading) "Loading…" else "Refresh awarded client sources") }

        report?.let { page ->
            if (!page.sourceAvailable) {
                Text(
                    "Commercial award source is not integrated yet; " +
                        "customer balances are UNKNOWN, not zero.",
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                if (page.hasMoreSources) {
                    Text(
                        "Only the first ${page.queryLimit} awarded sources are shown. " +
                            "This is not the company's full customer balance.",
                    )
                }
                if (page.rows.isEmpty()) {
                    Text(
                        "No awarded source records in this accessible source. " +
                            "This does not establish company-wide collections or receivables.",
                    )
                }
                page.rows.forEach { source ->
                    Text(
                        "Project " + source.projectId.take(8) + " · Quote " +
                            source.quoteRevision + " · " + source.sellAmount +
                            " " + source.currency,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Client " + source.clientOrganizationId.take(8) +
                            " · Invoice not issued · Finance review required",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
