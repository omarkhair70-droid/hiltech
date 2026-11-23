package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.warehouse.AssetIdentityDto
import com.hiltech.shared.core.warehouse.AssetTagLookupDto
import com.hiltech.shared.core.warehouse.StockItemDto
import com.hiltech.shared.core.warehouse.WarehouseApiClient

data class WarehouseQuickScanState(
    val assetTagResult: AssetTagLookupDto? = null,
    val assetSearchResults: List<AssetIdentityDto> = emptyList(),
    val stockSearchResults: List<StockItemDto> = emptyList(),
    val resolvedTagQuery: String? = null,
    val assetSearchResultQuery: String? = null,
    val stockSearchResultQuery: String? = null,
)

/** Source results only describe the exact submitted query, never edited text. */
fun warehouseScanResultMatchesQuery(currentInput: String, submittedQuery: String?): Boolean =
    submittedQuery != null && submittedQuery.isNotBlank() && currentInput.trim() == submittedQuery

@Composable
fun WarehouseQuickScanSection(
    api: WarehouseApiClient,
    warehouseId: String,
    organizationId: String,
    installationId: String,
    busy: Boolean,
    runTask: (suspend () -> Unit) -> Unit,
) {
    var state by remember(warehouseId, organizationId, installationId) {
        mutableStateOf(WarehouseQuickScanState())
    }
    WarehouseQuickScanControls(
        state = state,
        busy = busy,
        onTagInputChanged = {
            state = state.copy(assetTagResult = null, resolvedTagQuery = null)
        },
        onAssetInputChanged = {
            state = state.copy(assetSearchResults = emptyList(), assetSearchResultQuery = null)
        },
        onStockInputChanged = {
            state = state.copy(stockSearchResults = emptyList(), stockSearchResultQuery = null)
        },
        onAssetTagLookup = { code ->
            // Never leave a previous asset's identity on screen if this lookup fails.
            state = state.copy(assetTagResult = null, resolvedTagQuery = null)
            runTask {
                val result = api.lookupAssetTag(code, installationId)
                state = state.copy(assetTagResult = result, resolvedTagQuery = code)
            }
        },
        onAssetSearch = { query ->
            state = state.copy(assetSearchResults = emptyList(), assetSearchResultQuery = null)
            runTask {
                val results = api.assets(warehouseId, query, null, installationId).take(20)
                state = state.copy(assetSearchResults = results, assetSearchResultQuery = query)
            }
        },
        onStockSearch = { query ->
            state = state.copy(stockSearchResults = emptyList(), stockSearchResultQuery = null)
            runTask {
                val results = api.searchStockItems(organizationId, query, installationId).take(20)
                state = state.copy(stockSearchResults = results, stockSearchResultQuery = query)
            }
        },
    )
}

@Composable
fun WarehouseQuickScanControls(
    state: WarehouseQuickScanState,
    busy: Boolean,
    onAssetTagLookup: (String) -> Unit,
    onAssetSearch: (String) -> Unit,
    onStockSearch: (String) -> Unit,
    onTagInputChanged: () -> Unit = {},
    onAssetInputChanged: () -> Unit = {},
    onStockInputChanged: () -> Unit = {},
) {
    var tag by remember { mutableStateOf("") }
    var assetSearch by remember { mutableStateOf("") }
    var stockSearch by remember { mutableStateOf("") }
    val validTag = Regex("[A-Za-z0-9_-]{1,128}").matches(tag.trim())

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("البحث السريع / Quick Scan", style = MaterialTheme.typography.titleLarge)
        Text("AssetTag يعيد الأصل canonical نفسه؛ Stock يعتمد على item code أو part number فقط.")

        OutlinedTextField(
            value = tag,
            onValueChange = {
                val next = it.take(128)
                if (next != tag) {
                    tag = next
                    onTagInputChanged()
                }
            },
            label = { Text("AssetTag / كود الأصل") },
            singleLine = true,
            enabled = !busy,
        )
        Button(
            onClick = { onAssetTagLookup(tag.trim()) },
            enabled = !busy && validTag,
        ) { Text("حل AssetTag / Resolve AssetTag") }

        val visibleTagResult = state.assetTagResult?.takeIf {
            warehouseScanResultMatchesQuery(tag, state.resolvedTagQuery)
        }
        visibleTagResult?.let { result ->
            Text(result.assetCode + (result.name?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleMedium)
            Text("Asset canonical · " + (result.assetId ?: "unavailable"))
            Text(result.typeName + " · " + result.condition.name)
            result.locationCode?.let { Text("Location · $it") }
            result.employeeCode?.let { Text("Custodian · $it") }
        }

        OutlinedTextField(
            value = assetSearch,
            onValueChange = {
                val next = it.take(160)
                if (next != assetSearch) {
                    assetSearch = next
                    onAssetInputChanged()
                }
            },
            label = { Text("Asset search / بحث الأصل") },
            singleLine = true,
            enabled = !busy,
        )
        Button(
            onClick = { onAssetSearch(assetSearch.trim()) },
            enabled = !busy && assetSearch.isNotBlank(),
        ) { Text("بحث Asset / Search Asset") }
        val visibleAssetSearchResults = state.assetSearchResults.takeIf {
            warehouseScanResultMatchesQuery(assetSearch, state.assetSearchResultQuery)
        }.orEmpty()
        visibleAssetSearchResults.forEach { asset ->
            Text("Asset result · " + asset.assetCode + " · " + asset.id)
        }
        val scannedAssetId = visibleTagResult?.assetId
        if (scannedAssetId != null && visibleAssetSearchResults.any { it.id == scannedAssetId }) {
            Text("Scan/search canonical match · PASS")
        }

        OutlinedTextField(
            value = stockSearch,
            onValueChange = {
                val next = it.take(160)
                if (next != stockSearch) {
                    stockSearch = next
                    onStockInputChanged()
                }
            },
            label = { Text("Stock code / part number") },
            singleLine = true,
            enabled = !busy,
        )
        Button(
            onClick = { onStockSearch(stockSearch.trim()) },
            enabled = !busy && stockSearch.isNotBlank(),
        ) { Text("بحث Stock / Search Stock") }
        val visibleStockSearchResults = state.stockSearchResults.takeIf {
            warehouseScanResultMatchesQuery(stockSearch, state.stockSearchResultQuery)
        }.orEmpty()
        visibleStockSearchResults.forEach { item ->
            Text("Stock canonical · " + item.id + " · " + item.itemCode)
            item.partNumber?.let { Text("Part number · $it") }
        }
        Text("Supplier barcode mapping · unavailable — no authoritative mapping configured.")
    }
}
