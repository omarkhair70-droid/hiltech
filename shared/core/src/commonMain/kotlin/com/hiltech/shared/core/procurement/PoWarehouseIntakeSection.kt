package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.warehouse.AddReceivingStockLineDto
import com.hiltech.shared.core.warehouse.AssetCondition
import com.hiltech.shared.core.warehouse.CompleteReceivingDto
import com.hiltech.shared.core.warehouse.ReceiveStockLineDto
import com.hiltech.shared.core.warehouse.ReceivingDetailDto
import com.hiltech.shared.core.warehouse.ReceivingLineDto
import com.hiltech.shared.core.warehouse.RejectPoReceivingStockLineDto
import com.hiltech.shared.core.warehouse.StockItemDto
import com.hiltech.shared.core.warehouse.WarehouseApiClient
import kotlinx.coroutines.launch

/** Native PO intake; every physical movement still uses canonical Warehouse. */
@Composable
fun PoWarehouseIntakeSection(
    api: PoWarehouseIntakeApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    warehouseApi: WarehouseApiClient? = null,
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
        Text("Authorized, externally dispatched orders only. Accepted stock, rejected deliveries and discrepancies remain in canonical Warehouse.",
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
        }) { Text(if (loading) "Loading…" else "Refresh authorized PO arrivals") }
        if (loaded && items.isEmpty()) Text("No eligible POs awaiting receiving.")
        items.forEach { po ->
            PoWarehouseStartAction(po, api, warehouseApi, installationId, newOperationId)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PoWarehouseStartAction(
    po: PoWarehouseIntakeDto,
    api: PoWarehouseIntakeApiClient,
    warehouseApi: WarehouseApiClient?,
    installationId: String,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(po.purchaseOrderId) { mutableStateOf(false) }
    var error by remember(po.purchaseOrderId) { mutableStateOf<String?>(null) }
    var startedId by remember(po.purchaseOrderId) { mutableStateOf<String?>(null) }
    var pendingOp by remember(po.purchaseOrderId) { mutableStateOf<String?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
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
                }) { Text(if (busy) "Opening…" else "Start source-bound receiving") }
            }
            startedId?.let { recordId ->
                Text("Canonical Receiving #" + recordId.take(8), style = MaterialTheme.typography.bodySmall)
                if (warehouseApi != null) {
                    PoWarehouseInspectionPanel(po, recordId, warehouseApi, installationId, newOperationId)
                } else Text("Open the Warehouse workspace for line inspection.")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** Re-read authoritative item, versions and rejected line IDs on every action. */
@Composable
private fun PoWarehouseInspectionPanel(
    po: PoWarehouseIntakeDto,
    recordId: String,
    api: WarehouseApiClient,
    installationId: String,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(recordId) { mutableStateOf(false) }
    var error by remember(recordId) { mutableStateOf<String?>(null) }
    var detail by remember(recordId) { mutableStateOf<ReceivingDetailDto?>(null) }
    var stock by remember(recordId) { mutableStateOf<StockItemDto?>(null) }
    var addOp by remember(recordId) { mutableStateOf<String?>(null) }
    var finishOp by remember(recordId) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    val item = api.stockItem(po.stockItemId, installationId)
                    if (!item.active || item.organizationId.isBlank()) {
                        error = "Stock item is no longer active."
                    } else {
                        stock = item
                        detail = api.receivingDetail(recordId, installationId)
                    }
                } catch (failure: Exception) {
                    error = failure.message ?: "Receiving record is not accessible."
                } finally {
                    busy = false
                }
            }
        }) { Text(if (busy) "Refreshing…" else "Refresh inspection and line versions") }
        val current = detail
        val item = stock
        if (current != null && item != null) {
            Text("Receiving state: " + current.record.state + " · revision " +
                current.record.version, style = MaterialTheme.typography.bodySmall)
            if (current.record.state in setOf("DRAFT", "IN_PROGRESS") && current.lines.isEmpty()) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val op = addOp ?: newOperationId().also { addOp = it }
                            api.addReceivingStockLine(
                                recordId, AddReceivingStockLineDto(
                                    op, current.record.version, item.id, item.version,
                                    item.classification, po.remainingQuantity,
                                    item.baseUnitCode, po.poCode,
                                ), installationId,
                            )
                            addOp = null
                            detail = api.receivingDetail(recordId, installationId)
                        } catch (failure: Exception) {
                            error = failure.message ?: "Stock item or PO quantity changed."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Add expected PO stock line") }
            }
            current.lines.forEach { line ->
                val rejected = line.id in current.rejectedLineIds
                when {
                    line.receivedAt != null -> Text("Accepted stock: " + line.receivedQuantity +
                        " " + (line.unitCode ?: "") + " · canonical movement recorded")
                    rejected -> Text("Rejected goods: discrepancy recorded; no stock movement")
                    current.record.state in setOf("DRAFT", "IN_PROGRESS") ->
                        PoWarehouseLineInspectionAction(
                            current, line, item, api, recordId,
                            installationId, newOperationId, onUpdated = {
                                detail = it
                            },
                        )
                }
            }
            val resolved = current.lines.isNotEmpty() && current.lines.all {
                it.receivedAt != null || it.id in current.rejectedLineIds
            }
            if (resolved && current.record.state == "IN_PROGRESS") {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val op = finishOp ?: newOperationId().also { finishOp = it }
                            api.completeReceiving(
                                recordId, CompleteReceivingDto(op, current.record.version),
                                installationId,
                            )
                            finishOp = null
                            detail = api.receivingDetail(recordId, installationId)
                        } catch (failure: Exception) {
                            error = failure.message ?: "Cannot finish until every line is resolved."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Finish audited Receiving record") }
            }
            if (current.record.state in setOf("RECEIVED", "RECEIVED_WITH_DISCREPANCY")) {
                Text("Receiving closed. Check Warehouse movement/discrepancy history.")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PoWarehouseLineInspectionAction(
    detail: ReceivingDetailDto,
    line: ReceivingLineDto,
    item: StockItemDto,
    api: WarehouseApiClient,
    recordId: String,
    installationId: String,
    newOperationId: () -> String,
    onUpdated: (ReceivingDetailDto) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(line.id) { mutableStateOf(false) }
    var error by remember(line.id) { mutableStateOf<String?>(null) }
    var quantity by remember(line.id) { mutableStateOf(line.expectedQuantity ?: "1") }
    var serials by remember(line.id) { mutableStateOf("") }
    var condition by remember(line.id) { mutableStateOf(AssetCondition.GOOD) }
    var rejection by remember(line.id) { mutableStateOf(false) }
    var rejectionType by remember(line.id) { mutableStateOf("DAMAGED_ON_RECEIPT") }
    var menu by remember(line.id) { mutableStateOf(false) }
    var reason by remember(line.id) { mutableStateOf("") }
    var pendingOp by remember(line.id) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Stock " + item.itemCode + " · expected " +
            (line.expectedQuantity ?: "not stated"), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = quantity, onValueChange = {
                quantity = it; pendingOp = null
            }, label = { Text(if (rejection) "Rejected quantity" else "Accepted quantity") },
        )
        if (!rejection) {
            OutlinedButton(enabled = !busy, onClick = {
                condition = if (condition == AssetCondition.GOOD) AssetCondition.FAIR else AssetCondition.GOOD
                pendingOp = null
            }) { Text("Condition: " + condition.name + " (tap for GOOD/FAIR)") }
            if (item.classification == "STOCK_SERIALIZED") {
                OutlinedTextField(
                    value = serials, onValueChange = {
                        serials = it; pendingOp = null
                    }, label = { Text("One serial per item, comma separated") },
                )
            }
        } else {
            OutlinedButton(onClick = { menu = true }) { Text("Rejection: " + rejectionType) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf("DAMAGED_ON_RECEIPT", "WRONG_ITEM", "SERIAL_MISMATCH",
                    "UNEXPECTED_ITEM", "DOCUMENT_MISMATCH").forEach { type ->
                    DropdownMenuItem(text = { Text(type) }, onClick = {
                        rejectionType = type
                        pendingOp = null
                        menu = false
                    })
                }
            }
            OutlinedTextField(
                value = reason, onValueChange = {
                    reason = it; pendingOp = null
                }, label = { Text("Physical rejection reason (at least 12 characters)") },
            )
        }
        OutlinedButton(enabled = !busy, onClick = {
            rejection = !rejection
            pendingOp = null
        }) { Text(if (rejection) "Return to accepted stock" else "Reject this delivery (no inventory posting)") }
        OutlinedButton(enabled = !busy && quantity.isNotBlank() &&
            (!rejection || reason.trim().length >= 12), onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    val op = pendingOp ?: newOperationId().also { pendingOp = it }
                    if (rejection) {
                        api.rejectPoReceivingStockLine(
                            recordId, RejectPoReceivingStockLineDto(
                                op, line.id, detail.record.version, line.version,
                                quantity, rejectionType, reason,
                            ), installationId,
                        )
                    } else {
                        api.receiveStockLine(
                            recordId, ReceiveStockLineDto(
                                op, line.id, detail.record.version, line.version,
                                item.version, quantity, item.baseUnitCode,
                                condition, if (item.classification == "STOCK_SERIALIZED") {
                                    serials.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                } else emptyList(),
                            ), installationId,
                        )
                    }
                    pendingOp = null
                    onUpdated(api.receivingDetail(recordId, installationId))
                } catch (failure: Exception) {
                    error = failure.message ?: "Inspection denied. Reload current source."
                } finally {
                    busy = false
                }
            }
        }) { Text(if (busy) "Recording…" else if (rejection) "Record rejected delivery" else "Post accepted goods to Warehouse") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
