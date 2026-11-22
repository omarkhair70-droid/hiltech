package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Immutable V2 cancellation of a company-issued, never-sent Purchase Order. */
@Composable
fun PurchaseOrderCancellationSection(
    api: PurchaseOrderIssueApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(organizationId) { mutableStateOf(false) }
    var rows by remember(organizationId) {
        mutableStateOf<List<PurchaseOrderLifecycleSummaryDto>>(emptyList())
    }
    var loaded by remember(organizationId) { mutableStateOf(false) }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Purchase order history and cancellation",
            style = MaterialTheme.typography.titleMedium)
        Text("Cancellation only applies before supplier dispatch and never deletes the original PO.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    rows = api.issuedOrders(organizationId, installationId)
                    loaded = true
                } catch (failure: Exception) {
                    loaded = false
                    rows = emptyList()
                    error = failure.message ?: "PO lifecycle access unavailable."
                } finally {
                    busy = false
                }
            }
        }) { Text("Refresh issued and cancelled orders") }
        if (loaded && rows.isEmpty()) Text("No issued purchase orders.")
        rows.forEach { row ->
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.code + " · " + row.effectiveState + " · v" + row.version)
                    Text(row.total + " " + row.currency + " · " + row.supplierDispatchState,
                        style = MaterialTheme.typography.bodySmall)
                    if (row.effectiveState == "ISSUED" &&
                        row.supplierDispatchState == "NOT_SENT" && row.version >= 1L
                    ) {
                        PurchaseOrderCancellationAction(
                            api = api,
                            row = row,
                            installationId = installationId,
                            newOperationId = newOperationId,
                            onCancelled = { receipt ->
                                rows = rows.map { old ->
                                    if (old.id == receipt.purchaseOrderId)
                                        old.copy(effectiveState = "CANCELLED", version = receipt.eventVersion)
                                    else old
                                }
                            },
                        )
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PurchaseOrderCancellationAction(
    api: PurchaseOrderIssueApiClient,
    row: PurchaseOrderLifecycleSummaryDto,
    installationId: String,
    newOperationId: () -> String,
    onCancelled: (CancelPurchaseOrderReceiptDto) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reason by remember(row.id) { mutableStateOf("") }
    var pendingOp by remember(row.id) { mutableStateOf<String?>(null) }
    var busy by remember(row.id) { mutableStateOf(false) }
    var error by remember(row.id) { mutableStateOf<String?>(null) }
    var confirmation by remember(row.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = reason,
            onValueChange = {
                reason = it
                pendingOp = null
                confirmation = false
            },
            label = { Text("Why cancel this unsent order?") },
            minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
        )
        if (!confirmation) {
            OutlinedButton(enabled = !busy && reason.trim().length in 12..1000,
                onClick = { confirmation = true },
            ) { Text("Review cancellation of " + row.code) }
        } else {
            Text("Confirm permanent V2 cancellation. The PO remains in history and will not be sent.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val operation = pendingOp ?: newOperationId().also { pendingOp = it }
                        val receipt = api.cancel(
                            row.id,
                            CancelPurchaseOrderDto(
                                operation, expectedVersion = row.version,
                                reason = reason.trim(),
                            ),
                            installationId,
                        )
                        pendingOp = null
                        onCancelled(receipt)
                    } catch (failure: Exception) {
                        error = failure.message ?: "Cancellation denied: refresh PO state."
                        confirmation = false
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Cancelling…" else "Confirm cancellation") }
            OutlinedButton(enabled = !busy, onClick = { confirmation = false }) {
                Text("Keep the issued order")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
