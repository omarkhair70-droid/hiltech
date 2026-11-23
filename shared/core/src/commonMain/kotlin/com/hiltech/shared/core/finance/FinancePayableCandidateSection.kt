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
 * Reusable native Compose surface. Integration owner mounts it behind
 * separate default-OFF Windows/Android feature flags; never in a global shell
 * from a concurrent domain branch. No payment, GL posting or bank controls.
 */
@Composable
fun FinancePayableCandidateSection(
    api: FinancePayableApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    allowIntake: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(organizationId) { mutableStateOf(false) }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }
    var loaded by remember(organizationId) { mutableStateOf(false) }
    var handoffs by remember(organizationId) {
        mutableStateOf<List<SupplierHandoffFinanceDto>>(emptyList())
    }
    var candidates by remember(organizationId) {
        mutableStateOf<List<SupplierPayableCandidateDto>>(emptyList())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Finance / Supplier obligations", style = MaterialTheme.typography.titleLarge)
        Text("Accounting review candidates only. No posted liability, approval, payment, or transfer.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    handoffs = api.handoffs(organizationId, installationId)
                    candidates = api.candidates(organizationId, installationId)
                    loaded = true
                    error = null
                } catch (failure: Exception) {
                    loaded = false
                    handoffs = emptyList()
                    candidates = emptyList()
                    error = failure.message ?: "Finance access unavailable."
                } finally {
                    busy = false
                }
            }
        }) { Text("Refresh source-verified invoices") }

        if (loaded && handoffs.isEmpty()) Text("No eligible supplier handoffs.")
        handoffs.forEach { h ->
            Card(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Invoice " + h.invoiceNumber, style = MaterialTheme.typography.titleSmall)
                    Text(h.invoiceAmount + " " + h.currency + " / Supplier " +
                        h.supplierOrganizationId.take(8))
                    val status = when {
                        h.alreadyRecorded -> "Already captured for accounting review"
                        !h.eligible -> "Source changed: must return to Procurement matching"
                        else -> "Verified match, pending accounting intake"
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    if (allowIntake && h.eligible && !h.alreadyRecorded) {
                        var confirm by remember(h.handoffId) { mutableStateOf(false) }
                        var commandId by remember(h.handoffId) { mutableStateOf<String?>(null) }
                        OutlinedButton(enabled = !busy, onClick = {
                            if (!confirm) {
                                confirm = true
                            } else {
                                busy = true
                                scope.launch {
                                    try {
                                        val op = commandId ?: newOperationId().also { commandId = it }
                                        api.record(h.handoffId,
                                            RecordSupplierPayableCandidateDto(op, h.sourceSha256),
                                            installationId)
                                        candidates = api.candidates(organizationId, installationId)
                                        handoffs = api.handoffs(organizationId, installationId)
                                        confirm = false
                                        commandId = null
                                        error = null
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Finance source changed."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        }) { Text(if (confirm) "Confirm accounting review capture" else "Prepare review candidate") }
                        if (confirm) Text("This records evidence only; it does not authorize or pay.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (loaded) {
            Text("Recorded review candidates", style = MaterialTheme.typography.titleMedium)
            if (candidates.isEmpty()) Text("No Finance review candidates recorded.")
            candidates.forEach { p ->
                Text(p.invoiceNumber + " / " + p.amount + " " + p.currency + " / " +
                    p.state + if (p.sourceCurrent) "" else " / SOURCE CHANGED")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
