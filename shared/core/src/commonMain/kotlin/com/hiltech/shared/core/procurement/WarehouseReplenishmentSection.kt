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
import com.hiltech.shared.core.warehouse.StockItemDto
import com.hiltech.shared.core.warehouse.StorageLocationDto
import com.hiltech.shared.core.warehouse.WarehouseApiClient
import kotlinx.coroutines.launch

/**
 * Genuine warehouse-source purchase need: no arbitrary UUID entry and no
 * mock source. Backend guards current roles and stock versions on EVERY write.
 * This is S01 technical intake only; buyer/issuer rights remain distinct.
 */
@Composable
fun WarehouseReplenishmentSection(
    api: WarehouseReplenishmentApiClient,
    warehouseApi: WarehouseApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(organizationId) { mutableStateOf(false) }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }
    var locations by remember(organizationId) {
        mutableStateOf<List<StorageLocationDto>>(emptyList())
    }
    var items by remember(organizationId) { mutableStateOf<List<StockItemDto>>(emptyList()) }
    var selectedLocation by remember(organizationId) { mutableStateOf<StorageLocationDto?>(null) }
    var selectedItem by remember(organizationId) { mutableStateOf<StockItemDto?>(null) }
    var showLocations by remember(organizationId) { mutableStateOf(false) }
    var showItems by remember(organizationId) { mutableStateOf(false) }
    var requests by remember(organizationId) { mutableStateOf<List<WarehouseReplenishmentDto>>(emptyList()) }
    var qty by remember(organizationId) { mutableStateOf("") }
    var reason by remember(organizationId) { mutableStateOf("") }
    var itemSearch by remember(organizationId) { mutableStateOf("") }
    var createOp by remember(organizationId) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Warehouse replenishment", style = MaterialTheme.typography.titleLarge)
        Text("Ask for real stock at a managed Warehouse location. An authorized manager validates the need; purchasing/PO approvals remain independent.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    locations = warehouseApi.storageLocations(organizationId, installationId).items
                        .filter { it.organizationId == organizationId &&
                            it.warehouseId != null && it.projectId == null &&
                            it.siteId == null && it.lifecycleState == "ACTIVE" &&
                            it.kind in setOf("MAIN_WAREHOUSE", "WAREHOUSE")
                        }
                    items = warehouseApi.stockItems(organizationId, installationId).filter {
                        it.organizationId == organizationId && it.active
                    }
                } catch (failure: Exception) {
                    error = failure.message ?: "Source directory is not accessible."
                } finally { busy = false }
            }
        }) { Text(if (busy) "Loading…" else "Load authorized Warehouse sources") }

        OutlinedButton(enabled = !busy && locations.isNotEmpty(),
            onClick = { showLocations = true }) {
            Text(selectedLocation?.let { it.code + " · " + it.name } ?: "Choose a real Warehouse location")
        }
        DropdownMenu(expanded = showLocations, onDismissRequest = { showLocations = false }) {
            locations.forEach { location ->
                DropdownMenuItem(text = { Text(location.code + " · " + location.name) }, onClick = {
                    showLocations = false
                    selectedLocation = location
                    requests = emptyList()
                    createOp = null
                    busy = true
                    scope.launch {
                        try { requests = api.list(location.storageLocationId, installationId) }
                        catch (failure: Exception) {
                            error = failure.message ?: "This Warehouse source is not authorized."
                        } finally { busy = false }
                    }
                })
            }
        }

        if (selectedLocation != null) {
            OutlinedTextField(value = itemSearch, onValueChange = {
                itemSearch = it
            }, label = { Text("Search canonical stock items") })
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        items = warehouseApi.searchStockItems(
                            organizationId, itemSearch, installationId,
                        ).filter { it.active && it.organizationId == organizationId }
                    } catch (failure: Exception) {
                        error = failure.message ?: "Stock item search unavailable."
                    } finally { busy = false }
                }
            }) { Text("Search live stock items") }

            OutlinedButton(enabled = !busy && items.isNotEmpty(), onClick = {
                showItems = true
            }) { Text(selectedItem?.let { it.itemCode + " · " + it.name } ?: "Choose stock item") }
            DropdownMenu(expanded = showItems, onDismissRequest = { showItems = false }) {
                items.forEach { item ->
                    DropdownMenuItem(text = {
                        Text(item.itemCode + " · " + item.name + " · " + item.baseUnitCode)
                    }, onClick = {
                        showItems = false
                        selectedItem = item
                        createOp = null
                    })
                }
            }
            OutlinedTextField(value = qty, onValueChange = {
                qty = it; createOp = null
            }, label = { Text("Quantity (canonical unit)") })
            selectedItem?.let { Text("Unit: " + it.baseUnitCode + " · source version " +
                it.version, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(value = reason, onValueChange = {
                reason = it; createOp = null
            }, label = { Text("Why is the Warehouse replenishment required?") })
            val location = selectedLocation
            val item = selectedItem
            if (location != null && item != null) {
                OutlinedButton(enabled = !busy &&
                    qty.toDoubleOrNull()?.let { it > 0 } == true &&
                    reason.trim().length in 12..500, onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val op = createOp ?: newOperationId().also { createOp = it }
                            api.create(location.storageLocationId,
                                CreateWarehouseReplenishmentDto(
                                    op, location.version, item.id, item.version,
                                    qty, item.baseUnitCode, reason.trim(),
                                ), installationId)
                            createOp = null
                            qty = ""
                            reason = ""
                            requests = api.list(location.storageLocationId, installationId)
                        } catch (failure: Exception) {
                            error = failure.message ?: "The Warehouse source changed. Reload."
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "Saving…" else "Create source-bound stock request") }
            }
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        requests = api.list(location?.storageLocationId ?: "", installationId)
                    } catch (failure: Exception) {
                        error = failure.message ?: "Could not refresh Warehouse requests."
                    } finally { busy = false }
                }
            }) { Text("Refresh current Warehouse requests") }
            requests.forEach { draft ->
                WarehouseReplenishmentRequestCard(
                    draft, api, warehouseApi, installationId, newOperationId,
                    onChanged = { updated ->
                        requests = requests.map { if (it.id == updated.id) updated else it }
                    },
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun WarehouseReplenishmentRequestCard(
    draft: WarehouseReplenishmentDto,
    api: WarehouseReplenishmentApiClient,
    warehouseApi: WarehouseApiClient,
    installationId: String,
    newOperationId: () -> String,
    onChanged: (WarehouseReplenishmentDto) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(draft.id) { mutableStateOf(false) }
    var error by remember(draft.id) { mutableStateOf<String?>(null) }
    var actionOp by remember(draft.id) { mutableStateOf<String?>(null) }
    var actionKey by remember(draft.id) { mutableStateOf<String?>(null) }
    var editQty by remember(draft.id) { mutableStateOf(draft.requestedQuantity) }
    var editReason by remember(draft.id) { mutableStateOf(draft.justification) }
    var reviewReason by remember(draft.id) { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Need #" + draft.id.take(8) + " · " + draft.state,
                style = MaterialTheme.typography.titleMedium)
            Text("Stock quantity " + draft.requestedQuantity + " " + draft.unitCode +
                " · revision " + draft.version +
                if (draft.sourceCurrent) " · source current" else " · source changed",
                style = MaterialTheme.typography.bodySmall)
            Text(draft.justification)
            Text("Stock check: " + draft.assessmentCode +
                (draft.visibleFreeQuantity?.let { " · visible free $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall)
            if (draft.state == "NEEDS_INFO")
                Text("Manager requested clarification: " + (draft.lastReviewReason ?: ""))

            fun act(name: String, run: suspend (String) -> WarehouseReplenishmentDto) {
                busy = true
                error = null
                val op = if (actionKey == name) {
                    actionOp ?: newOperationId().also { actionOp = it }
                } else newOperationId().also { actionOp = it; actionKey = name }
                scope.launch {
                    try {
                        val next = run(op)
                        actionOp = null
                        actionKey = null
                        editQty = next.requestedQuantity
                        editReason = next.justification
                        onChanged(next)
                    } catch (failure: Exception) {
                        error = failure.message ?: "Command denied; reload live Warehouse state."
                    } finally { busy = false }
                }
            }
            if (draft.canSubmit) OutlinedButton(enabled = !busy, onClick = {
                act("submit") { op -> api.submit(
                    draft.id, SubmitPurchaseRequirementDraftDto(op, draft.version),
                    installationId,
                ).requirement }
            }) { Text("Submit need for Warehouse manager review") }

            if (draft.canRevise) {
                OutlinedTextField(value = editQty, onValueChange = {
                    editQty = it; actionOp = null
                }, label = { Text("Revised quantity") })
                OutlinedTextField(value = editReason, onValueChange = {
                    editReason = it; actionOp = null
                }, label = { Text("Revised justification") })
                OutlinedButton(enabled = !busy &&
                    editQty.toDoubleOrNull()?.let { it > 0 } == true &&
                    editReason.trim().length in 12..500, onClick = {
                    act("revise") { op ->
                        val location = warehouseApi.storageLocation(
                            draft.storageLocationId, installationId,
                        )
                        val item = warehouseApi.stockItem(draft.stockItemId, installationId)
                        api.revise(draft.id, ReviseWarehouseReplenishmentDto(
                            op, draft.version, location.version, item.version,
                            editQty, item.baseUnitCode, editReason.trim(),
                        ), installationId).requirement
                    }
                }) { Text("Save immutable new specification") }
            }
            if (draft.canWithdraw) OutlinedButton(enabled = !busy, onClick = {
                act("withdraw") { op -> api.withdraw(
                    draft.id, WithdrawPurchaseRequirementDraftDto(op, draft.version),
                    installationId,
                ).requirement }
            }) { Text("Withdraw internal request") }
            if (draft.canTechnicalReview) {
                OutlinedTextField(value = reviewReason, onValueChange = {
                    reviewReason = it; actionOp = null
                }, label = { Text("Manager review reason / exception") })
                if (draft.state == "SUBMITTED") {
                    OutlinedButton(enabled = !busy && draft.sourceCurrent, onClick = {
                        act("ready") { op -> api.review(
                            draft.id, ReviewPurchaseRequirementDto(
                                op, draft.version, "READY_FOR_SOURCING",
                            ), installationId,
                        ).requirement }
                    }) { Text("Technically ready for buyer (not PO approval)") }
                }
                OutlinedButton(enabled = !busy && reviewReason.trim().isNotBlank(), onClick = {
                    act("info") { op -> api.review(
                        draft.id, ReviewPurchaseRequirementDto(
                            op, draft.version, "NEEDS_INFO", reviewReason.trim(),
                        ), installationId,
                    ).requirement }
                }) { Text("Request more information") }
                OutlinedButton(enabled = !busy && reviewReason.trim().isNotBlank(), onClick = {
                    act("reject") { op -> api.review(
                        draft.id, ReviewPurchaseRequirementDto(
                            op, draft.version, "REJECTED", reviewReason.trim(),
                        ), installationId,
                    ).requirement }
                }) { Text("Reject technical request") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
