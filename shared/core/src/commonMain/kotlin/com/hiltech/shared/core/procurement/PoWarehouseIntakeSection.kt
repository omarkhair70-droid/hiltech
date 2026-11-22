package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Receivers only see currently authorized active warehouse destinations, no
 * supplier quote prices. Starts real canonical Receiving records, never stock
 * movements until the existing ReceiveLine command is completed separately.
 */
@Composable
fun PoWarehouseIntakeSection(
    api: PoWarehouseIntakeApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var loading by remember(organizationId) { mutableStateOf(false) }
    var loaded by remember(organizationId) { mutableStateOf(false) }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }
    var items by remember(organizationId) {
        mutableStateOf<List<PoWarehouseIntakeDto>>(emptyList())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Supplier PO warehouse intake", style = MaterialTheme.typography.titleLarge)
        Text("Only real issued orders with recorded supplier dispatch. Physical stock is posted in canonical Warehouse receiving.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !loading, onClick = {
            loading = true
            error = null
            scope.launch {
                try {
                    items = api.queue(organizationId, installationId)
                    loaded = true
                } catch (failure: Exception) {
                    loaded = false
                    items = emptyList()
                    error = failure.message ?: "Warehouse PO permission unavailable."
                } finally {
                    loading = false
                }
            }
        }) { Text(if (loading) "Loading…" else "Refresh supplier PO arrivals") }
        if (loaded && items.isEmpty()) Text("No eligible POs awaiting receiving.")
        items.forEach { po ->
            PoWarehouseStartAction(po, api, installationId, newOperationId)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PoWarehouseStartAction(
    po: PoWarehouseIntakeDto,
    api: PoWarehouseIntakeApiClient,
    installationId: String,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(po.purchaseOrderId) { mutableStateOf(false) }
    var error by remember(po.purchaseOrderId) { mutableStateOf<String?>(null) }
    var startedId by remember(po.purchaseOrderId) { mutableStateOf<String?>(null) }
    var pendingOp by remember(po.purchaseOrderId) { mutableStateOf<String?>(null) }
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(po.poCode + " · " + po.remainingQuantity + " " + po.unitCode + " remaining",
                style = MaterialTheme.typography.titleMedium)
            Text("Expected " + po.expectedQuantity + " · posted " +
                po.physicallyReceivedQuantity + " · PO version " + po.effectiveOrderVersion,
                style = MaterialTheme.typography.bodySmall)
            if (startedId == null) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val op = pendingOp ?: newOperationId().also { pendingOp = it }
                            startedId = api.start(po.purchaseOrderId, op, installationId).record.id
                            pendingOp = null
                        } catch (failure: Exception) {
                            error = failure.message ?: "Receiving source or authority changed."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (busy) "Opening…" else "Start canonical receiving record") }
            }
            startedId?.let {
                Text("Receiving record created: " + it.take(8) +
                    ". Continue inspection, line quantities and discrepancies in Warehouse.",
                    style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
