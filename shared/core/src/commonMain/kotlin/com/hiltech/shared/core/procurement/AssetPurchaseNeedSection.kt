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
import com.hiltech.shared.core.warehouse.AssetIdentityDto
import com.hiltech.shared.core.warehouse.AssetServiceDetailDto
import com.hiltech.shared.core.warehouse.StockItemDto
import com.hiltech.shared.core.warehouse.StorageLocationDto
import com.hiltech.shared.core.warehouse.WarehouseDto
import com.hiltech.shared.core.warehouse.WarehouseApiClient
import kotlinx.coroutines.launch

private data class AssetPurchaseIssue(
    val kind: String, val id: String, val version: Long, val label: String,
)

/**
 * Source-first asset spare/replacement stock procurement, not a generic
 * "buy something" form and not an auto-created incident or WorkOrder.
 */
@Composable
fun AssetPurchaseNeedSection(
    api: AssetPurchaseNeedApiClient,
    warehouseApi: WarehouseApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(organizationId) { mutableStateOf(false) }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }
    var warehouses by remember(organizationId) { mutableStateOf<List<WarehouseDto>>(emptyList()) }
    var selectedWarehouse by remember(organizationId) { mutableStateOf<WarehouseDto?>(null) }
    var assets by remember(organizationId) { mutableStateOf<List<AssetIdentityDto>>(emptyList()) }
    var selectedAsset by remember(organizationId) { mutableStateOf<AssetIdentityDto?>(null) }
    var destinations by remember(organizationId) { mutableStateOf<List<StorageLocationDto>>(emptyList()) }
    var selectedDestination by remember(organizationId) { mutableStateOf<StorageLocationDto?>(null) }
    var issues by remember(organizationId) { mutableStateOf<List<AssetPurchaseIssue>>(emptyList()) }
    var selectedIssue by remember(organizationId) { mutableStateOf<AssetPurchaseIssue?>(null) }
    var stockItems by remember(organizationId) { mutableStateOf<List<StockItemDto>>(emptyList()) }
    var selectedItem by remember(organizationId) { mutableStateOf<StockItemDto?>(null) }
    var existing by remember(organizationId) { mutableStateOf<List<AssetPurchaseNeedDto>>(emptyList()) }
    var assetSearch by remember(organizationId) { mutableStateOf("") }
    var stockSearch by remember(organizationId) { mutableStateOf("") }
    var quantity by remember(organizationId) { mutableStateOf("") }
    var justification by remember(organizationId) { mutableStateOf("") }
    var createOp by remember(organizationId) { mutableStateOf<String?>(null) }
    var chooseWarehouse by remember(organizationId) { mutableStateOf(false) }
    var chooseAsset by remember(organizationId) { mutableStateOf(false) }
    var chooseIssue by remember(organizationId) { mutableStateOf(false) }
    var chooseDestination by remember(organizationId) { mutableStateOf(false) }
    var chooseItem by remember(organizationId) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Equipment parts & replacement stock", style = MaterialTheme.typography.titleLarge)
        Text("Start from a real unresolved equipment incident or ongoing maintenance. The stock request needs an independent technical check before purchasing. External repair services are not stock lines.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    warehouses = warehouseApi.warehouses(organizationId, installationId)
                        .items.filter { it.active && it.organizationId == organizationId }
                } catch (failure: Exception) {
                    error = failure.message ?: "No authorized Warehouses are available."
                } finally { busy = false }
            }
        }) { Text("Load real equipment Warehouses") }

        OutlinedButton(enabled = !busy && warehouses.isNotEmpty(),
            onClick = { chooseWarehouse = true }) {
            Text(selectedWarehouse?.let { it.code + " · " + it.name } ?: "Select equipment Warehouse")
        }
        DropdownMenu(expanded = chooseWarehouse, onDismissRequest = { chooseWarehouse = false }) {
            warehouses.forEach { w ->
                DropdownMenuItem(text = { Text(w.code + " · " + w.name) }, onClick = {
                    selectedWarehouse = w
                    chooseWarehouse = false
                    selectedAsset = null
                    selectedIssue = null
                    selectedDestination = null
                    assets = emptyList()
                    issues = emptyList()
                    existing = emptyList()
                    createOp = null
                    busy = true
                    scope.launch {
                        try {
                            destinations = warehouseApi.warehouseStorageLocations(
                                w.warehouseId, installationId,
                            ).items.filter { it.organizationId == organizationId &&
                                it.warehouseId == w.warehouseId &&
                                it.kind in setOf("MAIN_WAREHOUSE", "WAREHOUSE") &&
                                it.projectId == null && it.siteId == null &&
                                it.lifecycleState == "ACTIVE"
                            }
                            assets = warehouseApi.assets(
                                w.warehouseId, "", null, installationId,
                            ).filter { it.organizationId == organizationId &&
                                it.lifecycle == "ACTIVE"
                            }
                            stockItems = warehouseApi.stockItems(organizationId, installationId)
                                .filter { it.active && it.organizationId == organizationId }
                        } catch (failure: Exception) {
                            error = failure.message ?: "Cannot load this Warehouse's equipment."
                        } finally { busy = false }
                    }
                })
            }
        }

        if (selectedWarehouse != null) {
            OutlinedTextField(value = assetSearch, onValueChange = {
                assetSearch = it
            }, label = { Text("Search equipment name, asset code or serial") })
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        assets = warehouseApi.assets(
                            selectedWarehouse!!.warehouseId, assetSearch, null, installationId,
                        ).filter { it.organizationId == organizationId &&
                            it.lifecycle == "ACTIVE"
                        }
                    } catch (failure: Exception) {
                        error = failure.message ?: "Equipment search unavailable."
                    } finally { busy = false }
                }
            }) { Text("Search equipment") }
            OutlinedButton(enabled = !busy && assets.isNotEmpty(),
                onClick = { chooseAsset = true }) {
                Text(selectedAsset?.let {
                    it.assetCode + " · " + (it.name ?: it.typeName)
                } ?: "Choose actual equipment")
            }
            DropdownMenu(expanded = chooseAsset, onDismissRequest = { chooseAsset = false }) {
                assets.forEach { asset ->
                    DropdownMenuItem(text = {
                        Text(asset.assetCode + " · " + (asset.name ?: asset.typeName))
                    }, onClick = {
                        selectedAsset = asset
                        chooseAsset = false
                        selectedIssue = null
                        issues = emptyList()
                        existing = emptyList()
                        createOp = null
                        busy = true
                        scope.launch {
                            try {
                                val history: AssetServiceDetailDto =
                                    warehouseApi.assetServiceDetail(asset.id, installationId)
                                issues = history.incidents.filter {
                                    it.state in setOf("OPEN","UNDER_REVIEW") &&
                                        it.type in setOf("DAMAGE","MISSING","LOST")
                                }.map { issue ->
                                    AssetPurchaseIssue(
                                        "INCIDENT", issue.id, issue.version,
                                        issue.type + " · " + issue.description.take(65),
                                    )
                                } + history.maintenance.filter {
                                    it.state == "IN_PROGRESS"
                                }.map { maintenance ->
                                    AssetPurchaseIssue(
                                        "MAINTENANCE", maintenance.id, maintenance.version,
                                        maintenance.type + " · " + maintenance.description.take(65),
                                    )
                                }
                                existing = api.list(asset.id, installationId)
                            } catch (failure: Exception) {
                                error = failure.message ?:
                                    "Equipment service evidence cannot be read with this role."
                            } finally { busy = false }
                        }
                    })
                }
            }
        }

        val asset = selectedAsset
        if (asset != null) {
            if (issues.isEmpty()) Text(
                "No open incident or active maintenance is available. Record the equipment issue first.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(enabled = !busy && issues.isNotEmpty(), onClick = {
                chooseIssue = true
            }) { Text(selectedIssue?.label ?: "Select unresolved equipment issue") }
            DropdownMenu(expanded = chooseIssue, onDismissRequest = { chooseIssue = false }) {
                issues.forEach { issue ->
                    DropdownMenuItem(text = { Text(issue.label) }, onClick = {
                        selectedIssue = issue
                        chooseIssue = false
                        createOp = null
                    })
                }
            }
            OutlinedButton(enabled = !busy && destinations.isNotEmpty(), onClick = {
                chooseDestination = true
            }) {
                Text(selectedDestination?.let { it.code + " · " + it.name } ?:
                    "Delivery to asset's company Warehouse")
            }
            DropdownMenu(expanded = chooseDestination,
                onDismissRequest = { chooseDestination = false }) {
                destinations.forEach { location ->
                    DropdownMenuItem(text = {
                        Text(location.code + " · " + location.name)
                    }, onClick = {
                        selectedDestination = location
                        chooseDestination = false
                        createOp = null
                    })
                }
            }
            OutlinedTextField(value = stockSearch, onValueChange = {
                stockSearch = it
            }, label = { Text("Search required spare part / replacement stock item") })
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        stockItems = warehouseApi.searchStockItems(
                            organizationId, stockSearch, installationId,
                        ).filter { it.organizationId == organizationId && it.active }
                    } catch (failure: Exception) {
                        error = failure.message ?: "Stock catalog is unavailable."
                    } finally { busy = false }
                }
            }) { Text("Find canonical stock item") }
            OutlinedButton(enabled = !busy && stockItems.isNotEmpty(),
                onClick = { chooseItem = true }) {
                Text(selectedItem?.let { it.itemCode + " · " + it.name } ?: "Choose stock item")
            }
            DropdownMenu(expanded = chooseItem, onDismissRequest = { chooseItem = false }) {
                stockItems.forEach { item ->
                    DropdownMenuItem(text = {
                        Text(item.itemCode + " · " + item.name + " " + item.baseUnitCode)
                    }, onClick = {
                        selectedItem = item
                        chooseItem = false
                        createOp = null
                    })
                }
            }
            OutlinedTextField(value = quantity, onValueChange = {
                quantity = it; createOp = null
            }, label = { Text("Required quantity in stock unit") })
            selectedItem?.let {
                Text("Unit " + it.baseUnitCode + " · item version " + it.version,
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(value = justification, onValueChange = {
                justification = it; createOp = null
            }, label = { Text("Why does this exact issue need this part?") })
            val sourceIssue = selectedIssue
            val destination = selectedDestination
            val stockItem = selectedItem
            if (sourceIssue != null && destination != null && stockItem != null) {
                OutlinedButton(enabled = !busy &&
                    quantity.toDoubleOrNull()?.let { it > 0 } == true &&
                    justification.trim().length in 12..500, onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val op = createOp ?: newOperationId().also { createOp = it }
                            api.create(asset.id, CreateAssetPurchaseNeedDto(
                                operationId = op, assetVersion = asset.version,
                                incidentId = sourceIssue.id.takeIf { sourceIssue.kind == "INCIDENT" },
                                maintenanceId = sourceIssue.id.takeIf { sourceIssue.kind == "MAINTENANCE" },
                                issueVersion = sourceIssue.version,
                                deliveryStorageLocationId = destination.storageLocationId,
                                deliveryLocationVersion = destination.version,
                                stockItemId = stockItem.id, stockItemVersion = stockItem.version,
                                quantity = quantity, unitCode = stockItem.baseUnitCode,
                                justification = justification.trim(),
                            ), installationId)
                            createOp = null
                            quantity = ""
                            justification = ""
                            existing = api.list(asset.id, installationId)
                        } catch (failure: Exception) {
                            error = failure.message ?: "Equipment issue or stock has changed. Reload."
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "Saving…" else "Create equipment-linked stock request") }
            }
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try { existing = api.list(asset.id, installationId) }
                    catch (failure: Exception) {
                        error = failure.message ?: "Unable to refresh equipment requests."
                    } finally { busy = false }
                }
            }) { Text("Refresh this asset's purchase needs") }
            existing.forEach { need ->
                AssetNeedCard(
                    need, api, warehouseApi, installationId, newOperationId,
                ) { updated ->
                    existing = existing.map { if (it.id == updated.id) updated else it }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun AssetNeedCard(
    need: AssetPurchaseNeedDto,
    api: AssetPurchaseNeedApiClient,
    warehouseApi: WarehouseApiClient,
    installationId: String,
    newOperationId: () -> String,
    onChanged: (AssetPurchaseNeedDto) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(need.id) { mutableStateOf(false) }
    var error by remember(need.id) { mutableStateOf<String?>(null) }
    var pendingOp by remember(need.id) { mutableStateOf<String?>(null) }
    var pendingAction by remember(need.id) { mutableStateOf<String?>(null) }
    var editQty by remember(need.id) { mutableStateOf(need.requestedQuantity) }
    var editWhy by remember(need.id) { mutableStateOf(need.justification) }
    var reviewReason by remember(need.id) { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Need #" + need.id.take(8) + " · " + need.state,
                style = MaterialTheme.typography.titleMedium)
            Text(need.assetCode + " · " + need.issueType + " #" + need.issueId.take(8))
            Text("Stock " + need.requestedQuantity + " " + need.unitCode +
                " · request version " + need.version,
                style = MaterialTheme.typography.bodySmall)
            Text(if (need.sourceCurrent) "Equipment source current"
                else "Equipment issue or stock changed: review blocked",
                style = MaterialTheme.typography.bodySmall)
            Text("Stock advisory: " + need.assessmentCode +
                (need.visibleFreeQuantity?.let { " · visible free $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall)
            Text(need.justification)
            need.lastReviewReason?.let {
                Text("Manager requests clarification: " + it)
            }

            fun act(name: String, action: suspend (String) -> AssetPurchaseNeedDto) {
                val op = if (pendingAction == name) {
                    pendingOp ?: newOperationId().also { pendingOp = it }
                } else newOperationId().also { pendingAction = name; pendingOp = it }
                busy = true
                error = null
                scope.launch {
                    try {
                        val updated = action(op)
                        pendingOp = null
                        pendingAction = null
                        editQty = updated.requestedQuantity
                        editWhy = updated.justification
                        onChanged(updated)
                    } catch (failure: Exception) {
                        error = failure.message ?: "Command denied or stale. Refresh source."
                    } finally { busy = false }
                }
            }

            if (need.canSubmit) OutlinedButton(enabled = !busy, onClick = {
                act("submit") { op ->
                    api.submit(need.id, SubmitPurchaseRequirementDraftDto(
                        op, need.version,
                    ), installationId).need
                }
            }) { Text("Submit part need to equipment manager") }

            if (need.canRevise) {
                OutlinedTextField(value = editQty, onValueChange = {
                    editQty = it; pendingOp = null
                }, label = { Text("Revised stock quantity") })
                OutlinedTextField(value = editWhy, onValueChange = {
                    editWhy = it; pendingOp = null
                }, label = { Text("Revised reason") })
                OutlinedButton(enabled = !busy &&
                    editQty.toDoubleOrNull()?.let { it > 0 } == true &&
                    editWhy.trim().length in 12..500, onClick = {
                    act("revise") { op ->
                        val passport = warehouseApi.assetPassport(need.assetId, installationId)
                        val item = warehouseApi.stockItem(need.stockItemId, installationId)
                        api.revise(need.id, ReviseAssetPurchaseNeedDto(
                            op, need.version, passport.identity.version, item.version,
                            editQty, item.baseUnitCode, editWhy.trim(),
                        ), installationId).need
                    }
                }) { Text("Revise with current source versions") }
            }
            if (need.canWithdraw) OutlinedButton(enabled = !busy, onClick = {
                act("withdraw") { op ->
                    api.withdraw(need.id, WithdrawPurchaseRequirementDraftDto(
                        op, need.version,
                    ), installationId).need
                }
            }) { Text("Withdraw equipment purchase need") }
            if (need.canTechnicalReview) {
                OutlinedTextField(value = reviewReason, onValueChange = {
                    reviewReason = it; pendingOp = null
                }, label = { Text("Technical review explanation") })
                if (need.state == "SUBMITTED") {
                    OutlinedButton(enabled = !busy && need.sourceCurrent, onClick = {
                        act("technical-ready") { op ->
                            api.review(need.id, ReviewPurchaseRequirementDto(
                                op, need.version, "READY_FOR_SOURCING",
                            ), installationId).need
                        }
                    }) { Text("Equipment part need ready for independent buyer") }
                }
                OutlinedButton(enabled = !busy && reviewReason.trim().isNotEmpty(),
                    onClick = {
                    act("needs-info") { op ->
                        api.review(need.id, ReviewPurchaseRequirementDto(
                            op, need.version, "NEEDS_INFO", reviewReason.trim(),
                        ), installationId).need
                    }
                }) { Text("Request more information") }
                OutlinedButton(enabled = !busy && reviewReason.trim().isNotEmpty(),
                    onClick = {
                    act("reject") { op ->
                        api.review(need.id, ReviewPurchaseRequirementDto(
                            op, need.version, "REJECTED", reviewReason.trim(),
                        ), installationId).need
                    }
                }) { Text("Reject the technical need") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
