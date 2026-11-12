package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.warehouse.StockBalanceDto
import com.hiltech.shared.core.warehouse.StockCatalogDto
import com.hiltech.shared.core.warehouse.StockItemDto
import com.hiltech.shared.core.warehouse.StockMovementDto
import com.hiltech.shared.core.warehouse.StorageLocationDto

data class StockInventoryState(
    val catalog: StockCatalogDto? = null,
    val items: List<StockItemDto> = emptyList(),
    val nextAfterCode: String? = null,
    val locations: List<StorageLocationDto> = emptyList(),
    val selected: StockItemDto? = null,
    val balances: List<StockBalanceDto> = emptyList(),
    val movements: List<StockMovementDto> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)

sealed interface StockAction {
    data object Refresh : StockAction
    data class More(val afterCode: String) : StockAction
    data class Select(val id: String) : StockAction
    data class RegisterUnit(val code: String, val name: String) : StockAction
    data class Create(
        val code: String, val name: String, val description: String?, val brand: String?, val partNumber: String?,
        val categoryId: String, val unit: String, val classification: String, val lotTracked: Boolean,
    ) : StockAction
    data class Approve(val batchId: String, val rowId: String, val version: Long, val code: String, val name: String) : StockAction
    data class Opening(val item: StockItemDto, val location: String, val quantity: String, val cutoverAt: String) : StockAction
    data class Transfer(val item: StockItemDto, val from: String, val to: String, val quantity: String) : StockAction
}

@Composable
fun StockInventorySection(state: StockInventoryState, selectedWarehouseId: String?, dispatch: (StockAction) -> Unit) {
    var showSetup by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Stock Inventory", style = MaterialTheme.typography.titleLarge)
        OutlinedButton({ dispatch(StockAction.Refresh) }, enabled = !state.loading) {
            Text(if (state.loading) "Loading stock" else "Refresh stock")
        }
        state.message?.let { Text(it) }
        val catalog = state.catalog ?: return@Column
        if (selectedWarehouseId != null) {
            OutlinedButton({ showSetup = !showSetup }, enabled = !state.loading) {
                Text(if (showSetup) "Close Stock setup" else "Set up Stock and units")
            }
            if (showSetup) StockMasterForm(catalog, state.loading, dispatch)
        }
        if (state.items.isEmpty()) Text("No Stock items")
        stockPicker("Stock item", state.selected?.id, state.items.map { it.id to "${it.itemCode} · ${it.name}" },
            !state.loading) { dispatch(StockAction.Select(it)) }
        state.nextAfterCode?.let { cursor ->
            OutlinedButton({ dispatch(StockAction.More(cursor)) }, enabled = !state.loading) { Text("Load more items") }
        }
        state.selected?.let { StockItemDetail(it, state, dispatch) }
    }
}

@Composable
private fun StockMasterForm(catalog: StockCatalogDto, loading: Boolean, dispatch: (StockAction) -> Unit) {
    var unitCode by remember { mutableStateOf("") }
    var unitName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var part by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var unit by remember { mutableStateOf<String?>(null) }
    var classification by remember { mutableStateOf("STOCK_QUANTITY") }
    var lotTracked by remember { mutableStateOf(false) }
    Text("Canonical unit", style = MaterialTheme.typography.titleMedium)
    stockField("Unit code", unitCode) { unitCode = it }
    stockField("Unit name", unitName) { unitName = it }
    Button({ dispatch(StockAction.RegisterUnit(unitCode.trim(), unitName.trim())) },
        enabled = !loading && unitCode.isNotBlank() && unitName.isNotBlank()) { Text("Register unit") }
    Text("Stock master", style = MaterialTheme.typography.titleMedium)
    stockField("Item code", code) { code = it }
    stockField("Name", name) { name = it }
    stockField("Description", description) { description = it }
    stockField("Brand", brand) { brand = it }
    stockField("Part number", part) { part = it }
    stockPicker("Category", category, catalog.categories.map { it.id to "${it.name} · revision ${it.revision}" }, !loading) { category = it }
    stockPicker("Unit", unit, catalog.units.map { it.code to "${it.code} · ${it.name}" }, !loading) { unit = it }
    stockPicker("Classification", classification, listOf("STOCK_QUANTITY" to "Quantity Stock", "STOCK_SERIALIZED" to "Serialized Stock"),
        !loading) { classification = it }
    Row { Checkbox(lotTracked, { lotTracked = it }); Text("Lot tracked") }
    Button(onClick = {
        dispatch(StockAction.Create(code.trim(), name.trim(), description.trim().ifEmpty { null },
            brand.trim().ifEmpty { null }, part.trim().ifEmpty { null },
            requireNotNull(category), requireNotNull(unit), classification, lotTracked))
    }, enabled = !loading && code.isNotBlank() && name.isNotBlank() && category != null && unit != null) {
        Text("Create Stock item")
    }
}

@Composable
private fun StockItemDetail(item: StockItemDto, state: StockInventoryState, dispatch: (StockAction) -> Unit) {
    var from by remember(item.id) { mutableStateOf<String?>(null) }
    var to by remember(item.id) { mutableStateOf<String?>(null) }
    var quantity by remember(item.id) { mutableStateOf("") }
    var openingLocation by remember(item.id) { mutableStateOf<String?>(null) }
    var openingQuantity by remember(item.id) { mutableStateOf("") }
    var cutover by remember(item.id) { mutableStateOf("") }
    val locations = state.locations.associate { it.storageLocationId to it.code }
    val active = state.locations.filter { it.lifecycleState == "ACTIVE" }.map { it.storageLocationId to "${it.code} · ${it.name}" }
    val canMove = !state.loading && item.active
    Text(item.name, style = MaterialTheme.typography.titleMedium)
    Text(listOfNotNull(item.brand, item.partNumber, item.baseUnitCode).joinToString(" · "))
    item.description?.let { Text(it) }
    item.sourceImportRowId?.let { Text("Import row $it") }
    Text(when {
        item.openingInitialized -> "Opening posted"
        item.verifiedZeroOpening -> "Verified zero opening quantity"
        item.sourceImportRowId != null -> "Opening pending"
        else -> "Stock master created without opening evidence"
    })
    Text("On hand by location", style = MaterialTheme.typography.titleMedium)
    if (state.balances.isEmpty()) Text("No visible balances")
    state.balances.forEach { Text("${it.locationCode} · ${it.onHandQuantity} ${item.baseUnitCode}") }
    Text("Movement history", style = MaterialTheme.typography.titleMedium)
    if (state.movements.isEmpty()) Text("No visible movements")
    state.movements.forEach { movement ->
        val route = listOfNotNull(locations[movement.fromStorageLocationId], locations[movement.toStorageLocationId])
            .joinToString(" → ")
        Text("${movement.occurredAt} · ${movement.type} · ${movement.quantity} ${movement.unitCode} · $route")
    }
    if (item.sourceImportRowId != null && !item.openingInitialized && !item.verifiedZeroOpening) {
        stockPicker("Verified opening location", openingLocation, active, canMove) { openingLocation = it }
        stockField("Opening quantity (${item.baseUnitCode})", openingQuantity) { openingQuantity = it }
        stockField("Cutover timestamp (ISO-8601)", cutover) { cutover = it }
        Button(onClick = { dispatch(StockAction.Opening(item, requireNotNull(openingLocation), openingQuantity.trim(), cutover.trim())) },
            enabled = canMove && openingLocation != null && openingQuantity.isNotBlank() && cutover.isNotBlank()) {
            Text("Initialize opening balance")
        }
    }
    stockPicker("Transfer from", from, active, canMove) { from = it }
    stockPicker("Transfer to", to, active.filterNot { it.first == from }, canMove) { to = it }
    stockField("Transfer quantity (${item.baseUnitCode})", quantity) { quantity = it }
    Button(onClick = { dispatch(StockAction.Transfer(item, requireNotNull(from), requireNotNull(to), quantity.trim())) },
        enabled = canMove && from != null && to != null && from != to && quantity.isNotBlank()) { Text("Transfer stock") }
}

@Composable
private fun stockPicker(label: String, selected: String?, items: List<Pair<String, String>>, enabled: Boolean, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton({ expanded = true }, enabled = enabled && items.isNotEmpty()) {
            Text(items.firstOrNull { it.first == selected }?.second ?: label)
        }
        DropdownMenu(expanded, { expanded = false }) {
            items.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; select(id) })
            }
        }
    }
}

@Composable
private fun stockField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true)
}
