package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.warehouse.AssetCondition
import com.hiltech.shared.core.warehouse.AssetPassportDto
import com.hiltech.shared.core.warehouse.StockItemDto

@Composable
fun PhysicalAssetOperations(passport: AssetPassportDto, state: AssetInventoryState, dispatch: (AssetAction) -> Unit) {
    val asset = passport.identity
    val custody = passport.custody ?: return
    val currentMovement = state.movements.firstOrNull { it.id == custody.sourceMovementId }
    val type = state.catalog?.types?.firstOrNull { it.id == asset.typeId }
    val locations = state.locations.filter {
        it.lifecycleState == "ACTIVE" && it.warehouseId == asset.warehouseId
    }.map { it.storageLocationId to "${it.code} · ${it.name}" }
    var location by remember(asset.id, asset.version) { mutableStateOf<String?>(null) }
    var condition by remember(asset.id, asset.version) { mutableStateOf(AssetCondition.UNKNOWN) }
    var notes by remember(asset.id, asset.version) { mutableStateOf("") }
    var incident by remember(asset.id, asset.version) { mutableStateOf<String?>(null) }
    var review by remember(asset.id, asset.version, location, condition, notes, incident) { mutableStateOf(false) }
    val returned = currentMovement?.type == "RETURN"
    val inspect = returned && !passport.currentReturnInspected
    val employee = custody.employeeId != null
    val available = state.serviceDetail?.availability?.state == "AVAILABLE"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Physical Asset operations", style = MaterialTheme.typography.titleMedium)
        if (inspect) {
            if (type == null || type.accessoryTemplateId != null) {
                Text("Inspection requires the approved Asset type and accessory checklist. No release recorded.")
                return@Column
            }
            Text("Inspect the actual returned Asset before release.")
            assetPicker("Observed condition", condition.name,
                AssetCondition.entries.filter { it != AssetCondition.UNKNOWN }.map { it.name to it.name }) {
                condition = AssetCondition.valueOf(it)
            }
            if (condition == AssetCondition.DAMAGED || condition == AssetCondition.UNFIT) {
                val incidents = state.serviceDetail?.incidents.orEmpty().filter {
                    it.type == "DAMAGE" && it.state in listOf("OPEN", "UNDER_REVIEW")
                }
                assetPicker("Open damage incident", incident, incidents.map { it.id to it.description }) { incident = it }
                if (incidents.isEmpty()) Text("Record a damage incident in Asset service before inspection.")
            }
            assetField("Inspection notes", notes) { notes = it }
        } else if (employee || available) {
            assetPicker("Destination storage", location, locations.filterNot { it.first == custody.storageLocationId },
                !state.loading) { location = it }
            if (employee) {
                assetPicker("Observed return condition", condition.name, AssetCondition.entries.map { it.name to it.name }) {
                    condition = AssetCondition.valueOf(it)
                }
            }
        } else {
            Text("Asset is not eligible for a storage transfer. Review availability and custody.")
            return@Column
        }
        val damage = condition == AssetCondition.DAMAGED || condition == AssetCondition.UNFIT
        val valid = if (inspect) condition != AssetCondition.UNKNOWN && (!damage || incident != null)
            else location != null && (!employee || condition != AssetCondition.UNKNOWN)
        val label = if (inspect) "Record inspection" else if (employee) "Record physical return" else "Record storage transfer"
        OutlinedButton({
            if (!review) review = true
            else {
                when {
                    inspect -> dispatch(AssetAction.InspectPhysical(asset.id, asset.version, custody.sourceMovementId,
                        condition, notes.optional(), incident.takeIf { damage }))
                    employee -> dispatch(AssetAction.ReturnPhysical(asset.id, asset.version, custody.sourceMovementId, requireNotNull(location), condition))
                    else -> dispatch(AssetAction.TransferPhysical(asset.id, asset.version, custody.sourceMovementId, requireNotNull(location)))
                }
                review = false
            }
        }, enabled = valid && !state.loading) { Text(if (review) label else "Review · $label") }
        if (review) {
            Text("Confirm the actual physical operation for ${asset.assetCode}.")
            OutlinedButton({ review = false }, enabled = !state.loading) { Text("Cancel") }
        }
    }
}

@Composable
fun IssuedStockOperations(item: StockItemDto, warehouse: String, state: StockInventoryState, dispatch: (StockAction) -> Unit) {
    var search by remember(item.id, warehouse) { mutableStateOf(state.issuedSearch) }
    var selected by remember(item.id, warehouse) { mutableStateOf<String?>(null) }
    val directory = state.issued.takeIf { state.issuedWarehouseId == warehouse }
    val entries = directory?.items.orEmpty()
    fun key(row: com.hiltech.shared.core.warehouse.IssuedStockDirectoryEntryDto) =
        "${row.context.requirementId}:${row.context.employeeId}"
    val entry = entries.firstOrNull { key(it) == selected }
    var amount by remember(item.id, warehouse, selected, entry?.context?.version) { mutableStateOf("") }
    var kind by remember(item.id, warehouse, selected) { mutableStateOf("CONSUME") }
    var location by remember(item.id, warehouse, selected) { mutableStateOf<String?>(null) }
    var review by remember(item.id, warehouse, selected, entry?.context?.version, amount, kind, location) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Outstanding issued Stock", style = MaterialTheme.typography.titleMedium)
        assetField("Find by employee code or name", search) { search = it }
        OutlinedButton({ dispatch(StockAction.LoadIssued(item.id, search.trim())) }, enabled = !state.loading) {
            Text("Load issued custody")
        }
        if (directory == null) return@Column
        if (directory.limited) Text("Refine your employee search to see additional results.")
        if (entries.isEmpty()) Text("No authorized outstanding custody matches.")
        assetPicker("Issued custody", selected, entries.map { key(it) to "${it.employeeCode} · ${it.employeeName} · ${it.workCode}" },
            !state.loading) { selected = it }
        val current = entry ?: return@Column
        Text("${current.workCode} · ${current.workTitle}")
        Text("Outstanding · ${current.context.outstandingQuantity} ${current.unitCode}")
        assetPicker("Operation", kind, listOf("CONSUME" to "Record consumption", "RETURN" to "Return unused Stock")) { kind = it }
        if (kind == "RETURN") {
            assetPicker("Return to storage", location, state.locations.filter {
                it.warehouseId == warehouse && it.lifecycleState == "ACTIVE" &&
                    (it.projectId == null || it.projectId == current.context.projectId) &&
                    (it.siteId == null || it.siteId == current.context.siteId)
            }.map { it.storageLocationId to "${it.code} · ${it.name}" }) { location = it }
        }
        assetField("Quantity · ${current.unitCode}", amount) { amount = it }
        val valid = amount.trim().matches(Regex("[0-9]{1,14}(\\.[0-9]{1,6})?")) &&
            (kind != "RETURN" || location != null)
        OutlinedButton({
            if (!review) review = true else {
                dispatch(StockAction.AdjustIssued(current, amount.trim(), location.takeIf { kind == "RETURN" }))
                review = false
            }
        }, enabled = valid && !state.loading) { Text(if (review) "Record $kind" else "Review operation") }
        if (review) {
            Text(if (kind == "RETURN") "Confirm actual unused Stock received in storage." else "Confirm actual consumption. Warehouse on-hand will not increase.")
            OutlinedButton({ review = false }, enabled = !state.loading) { Text("Cancel") }
        }
    }
}

@Composable
fun StockFulfillmentHistorySection(item: StockItemDto, warehouse: String, state: StockInventoryState, dispatch: (StockAction) -> Unit) {
    var search by remember(item.id, warehouse) { mutableStateOf("") }
    val history = state.fulfillmentHistory.takeIf { state.historyWarehouseId == warehouse && state.historyItemId == item.id }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Physical Stock movement history", style = MaterialTheme.typography.titleMedium)
        Text("Recorded issue, return and consumption, including closed custody.")
        assetField("Filter history by employee code or name", search) { search = it }
        OutlinedButton({ dispatch(StockAction.LoadFulfillmentHistory(item.id, search.trim())) }, enabled = !state.loading) {
            Text("Load physical movement history")
        }
        history?.let { page ->
            Text("Employee filter · ${state.historySearch.ifEmpty { "All authorized" }}")
            if (page.limited) Text("Showing the latest 100 authorized matches. Refine the employee filter.")
            if (page.items.isEmpty()) Text("No authorized physical movements match.")
            page.items.forEach { movement ->
                Text("${movement.type} · ${movement.quantity} ${movement.unitCode}", style = MaterialTheme.typography.titleSmall)
                Text("${movement.employeeCode} · ${movement.employeeName}")
                Text(movement.occurredAt)
                val route = listOfNotNull(movement.fromLocationCode, movement.toLocationCode).joinToString(" → ")
                if (route.isNotEmpty()) Text(route)
                Text("Work · ${movement.workOrderId}")
                Text("Movement receipt · ${movement.movementId}")
            }
        }
    }
}

/** Shared explicit physical transfer review; server owns authorization and conservation. */
@Composable
fun StockStorageTransfer(item: StockItemDto, state: StockInventoryState, warehouse: String? = null,
    dispatch: (StockAction) -> Unit) {
    val active = state.locations.filter { it.lifecycleState == "ACTIVE" && (warehouse == null || it.warehouseId == warehouse) }
    val fromChoices = active.filter { location ->
        state.balances.any { it.storageLocationId == location.storageLocationId && it.onHandQuantity.any { digit -> digit in '1'..'9' } }
    }
    var from by remember(item.id, warehouse) { mutableStateOf<String?>(null) }
    var to by remember(item.id, warehouse, from) { mutableStateOf<String?>(null) }
    var quantity by remember(item.id, warehouse, from, to) { mutableStateOf("") }
    val source = state.balances.firstOrNull { it.storageLocationId == from }
    val destination = state.balances.firstOrNull { it.storageLocationId == to }
    var review by remember(item.id, item.version, warehouse, from, to, quantity, source?.version, destination?.version) {
        mutableStateOf(false)
    }
    val enabled = item.active && !state.loading
    val amount = quantity.trim()
    val valid = enabled && from != to && fromChoices.any { it.storageLocationId == from } &&
        active.any { it.storageLocationId == to } &&
        amount.matches(Regex("[0-9]{1,14}(\\.[0-9]{1,6})?")) && amount.any { it in '1'..'9' } &&
        (!item.serialized || amount.substringAfter('.', "").all { it == '0' })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Storage transfer", style = MaterialTheme.typography.titleMedium)
        if (fromChoices.isEmpty()) Text("No visible positive source balance.")
        assetPicker("Transfer from", from, fromChoices.map { it.storageLocationId to "${it.code} · ${it.name}" }, enabled) { from = it }
        source?.let { Text("Source on hand · ${it.onHandQuantity} ${item.baseUnitCode}") }
        assetPicker("Transfer to", to, active.filterNot { it.storageLocationId == from }.map {
            it.storageLocationId to "${it.code} · ${it.name}"
        }, enabled) { to = it }
        assetField("Transfer quantity (${item.baseUnitCode})", quantity) { quantity = it }
        if (review && valid) {
            val sourceCode = active.first { it.storageLocationId == from }.code
            val destinationCode = active.first { it.storageLocationId == to }.code
            Text("Confirm actual transfer · $amount ${item.baseUnitCode} · $sourceCode → $destinationCode")
            OutlinedButton({
                dispatch(StockAction.Transfer(item, requireNotNull(from), requireNotNull(to), amount))
                review = false
            }, enabled = enabled) { Text("Record stock transfer") }
            OutlinedButton({ review = false }, enabled = enabled) { Text("Cancel transfer") }
        } else {
            OutlinedButton({ review = true }, enabled = valid) { Text("Review stock transfer") }
        }
    }
}
