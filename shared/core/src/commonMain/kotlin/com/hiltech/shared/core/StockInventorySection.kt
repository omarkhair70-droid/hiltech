package com.hiltech.shared.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.*
import com.hiltech.shared.core.warehouse.StockBalanceDto
import com.hiltech.shared.core.warehouse.IssuedStockDirectoryDto
import com.hiltech.shared.core.warehouse.IssuedStockDirectoryEntryDto
import com.hiltech.shared.core.warehouse.StockCatalogDto
import com.hiltech.shared.core.warehouse.StockFulfillmentHistoryDto
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
    val issued: IssuedStockDirectoryDto? = null,
    val issuedSearch: String = "",
    val issuedWarehouseId: String? = null,
    val fulfillmentHistory: StockFulfillmentHistoryDto? = null,
    val historyWarehouseId: String? = null,
    val historyItemId: String? = null,
    val historySearch: String = "",
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
    data class LoadIssued(val item: String, val search: String) : StockAction
    data class LoadFulfillmentHistory(val item: String, val search: String) : StockAction
    data class MoreFulfillmentHistory(
        val item: String, val search: String, val afterRecordedAt: String, val afterMovementId: String,
    ) : StockAction
    data class AdjustIssued(val entry: IssuedStockDirectoryEntryDto, val quantity: String, val returnLocation: String?) : StockAction
}

@Composable
fun StockInventorySection(state: StockInventoryState, selectedWarehouseId: String?, dispatch: (StockAction) -> Unit) {
    val colors = LocalHiltechOperationalColors.current
    var query by remember(selectedWarehouseId) { mutableStateOf("") }
    var showSetup by remember(selectedWarehouseId) { mutableStateOf(false) }
    var detailMode by remember(state.selected?.id) { mutableStateOf(state.selected != null) }
    var tab by remember(state.selected?.id) { mutableStateOf(StockDetailTab.Balance) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(hiltechText("دليل الأصناف", "Stock catalogue"), style = MaterialTheme.typography.titleLarge)
                Text(hiltechText("الأرصدة والحركة والتسليم الفعلي", "Balances, movements and actual fulfillment"), color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { dispatch(StockAction.Refresh) }, enabled = !state.loading) {
                Text(if (state.loading) hiltechText("جارٍ التحميل", "Loading") else hiltechText("تحديث", "Refresh"))
            }
        }
        state.message?.let { Text(it) }
        val catalog = state.catalog ?: return@Column
        val directory: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selectedWarehouseId != null) {
                    TextButton(onClick = { showSetup = !showSetup }, enabled = !state.loading) {
                        Text(if (showSetup) hiltechText("إغلاق إعداد الأصناف", "Close stock setup") else hiltechText("+ صنف أو وحدة قياس", "+ Item or unit"))
                    }
                    if (showSetup) HiltechPanel(Modifier.fillMaxWidth()) { StockMasterForm(catalog, state.loading, dispatch) }
                }
                OutlinedTextField(query, { query = it }, label = { Text(hiltechText("ابحث في الأصناف المعروضة", "Search visible items")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                val matches = state.items.filter { item ->
                    query.isBlank() || listOfNotNull(item.itemCode, item.name, item.brand, item.partNumber)
                        .any { it.contains(query.trim(), ignoreCase = true) }
                }
                Text("المعروض: " + matches.size, style = MaterialTheme.typography.labelMedium, color = colors.secondaryText)
                if (matches.isEmpty()) Text(if (query.isBlank()) hiltechText("لا توجد أصناف معروضة.", "No visible items.") else hiltechText("لا توجد نتائج في الصفحة المحمّلة.", "No results in the loaded page."))
                matches.forEach { item ->
                    Surface(onClick = { detailMode = true; dispatch(StockAction.Select(item.id)) }, enabled = !state.loading,
                        color = if (item.id == state.selected?.id) colors.selected else colors.surface,
                        contentColor = colors.primaryText, shape = HiltechShape.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.itemCode, color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(item.brand, item.partNumber, item.baseUnitCode).joinToString(" · "),
                                color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                            HiltechStateTag(if (item.active) hiltechText("نشط", "Active") else hiltechText("غير نشط", "Inactive"),
                                if (item.active) HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL)
                        }
                    }
                }
                state.nextAfterCode?.let { cursor ->
                    OutlinedButton({ dispatch(StockAction.More(cursor)) }, enabled = !state.loading) { Text(hiltechText("تحميل أصناف إضافية", "Load more items")) }
                }
            }
        }
        val detail: @Composable () -> Unit = {
            val item = state.selected
            if (item == null) {
                HiltechPanel(Modifier.fillMaxWidth()) { Text(hiltechText("اختر صنفًا لعرض رصيده وحركته.", "Choose an item to view its balance and movements.")) }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HiltechPanel(Modifier.fillMaxWidth()) {
                        Text(item.itemCode, style = MaterialTheme.typography.labelMedium, color = colors.secondaryText)
                        Text(item.name, style = MaterialTheme.typography.headlineSmall)
                        Text(listOfNotNull(item.brand, item.partNumber, item.baseUnitCode).joinToString(" · "))
                    }
                    val tabs = StockDetailTab.entries.filter { selectedWarehouseId != null || it == StockDetailTab.Balance }
                    val current = tab.takeIf { it in tabs } ?: StockDetailTab.Balance
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tabs.forEach { choice -> FilterChip(selected = current == choice, onClick = { tab = choice },
                            label = { Text(hiltechText(choice.title, choice.english)) }) }
                    }
                    when (current) {
                        StockDetailTab.Balance -> HiltechPanel(Modifier.fillMaxWidth()) { StockItemDetail(item, state, dispatch) }
                        StockDetailTab.Issued -> selectedWarehouseId?.let { IssuedStockOperations(item, it, state, dispatch) }
                        StockDetailTab.History -> selectedWarehouseId?.let { StockFulfillmentHistorySection(item, it, state, dispatch) }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 860.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(.9f)) { directory() }
                Column(Modifier.weight(1.5f)) { detail() }
            } else if (detailMode && state.selected != null) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { detailMode = false }) { Text(hiltechText("العودة إلى الأصناف", "Back to stock")) }
                detail()
            } else directory()
        }
    }
}

private enum class StockDetailTab(val title: String, val english: String) {
    Balance("الرصيد والحركة", "Balance & movements"), Issued("العهد المصروفة", "Issued stock"), History("سجل التسليم", "Fulfillment history"),
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
    StockStorageTransfer(item, state, dispatch = dispatch)
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
