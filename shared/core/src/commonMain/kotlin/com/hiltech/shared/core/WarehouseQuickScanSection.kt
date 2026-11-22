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
)

/**
 * The operator's existing read-only lookup content, retained while switching
 * between Warehouse Today and code lookup. One session per warehouse/org.
 * No authoritative cache: the UI only retains last explicitly retrieved results.
 */
class WarehouseQuickScanSession {
    var results by mutableStateOf(WarehouseQuickScanState())
    var tag by mutableStateOf("")
    var assetSearch by mutableStateOf("")
    var stockSearch by mutableStateOf("")
}

@Composable
fun WarehouseQuickScanSection(
    api: WarehouseApiClient,
    warehouseId: String,
    organizationId: String,
    installationId: String,
    busy: Boolean,
    runTask: (suspend () -> Unit) -> Unit,
    session: WarehouseQuickScanSession? = null,
) {
    val visibleSession = session ?: remember(warehouseId, organizationId) { WarehouseQuickScanSession() }
    WarehouseQuickScanControls(
        state = visibleSession.results,
        session = visibleSession,
        busy = busy,
        onAssetTagLookup = { code ->
            runTask {
                visibleSession.results = visibleSession.results.copy(
                    assetTagResult = api.lookupAssetTag(code, installationId),
                )
            }
        },
        onAssetSearch = { query ->
            runTask {
                visibleSession.results = visibleSession.results.copy(
                    assetSearchResults = api.assets(warehouseId, query, null, installationId).take(20),
                )
            }
        },
        onStockSearch = { query ->
            runTask {
                visibleSession.results = visibleSession.results.copy(
                    stockSearchResults = api.searchStockItems(organizationId, query, installationId).take(20),
                )
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
    session: WarehouseQuickScanSession? = null,
) {
    var localTag by remember { mutableStateOf("") }
    var localAssetSearch by remember { mutableStateOf("") }
    var localStockSearch by remember { mutableStateOf("") }
    val tag = session?.tag ?: localTag
    val assetSearch = session?.assetSearch ?: localAssetSearch
    val stockSearch = session?.stockSearch ?: localStockSearch
    val validTag = Regex("[A-Za-z0-9_-]{1,128}").matches(tag.trim())

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("البحث السريع / Quick Scan", style = MaterialTheme.typography.titleLarge)
        Text("AssetTag يعيد الأصل canonical نفسه؛ Stock يعتمد على item code أو part number فقط.")

        OutlinedTextField(
            value = tag,
            onValueChange = { value ->
                if (session != null) session.tag = value.take(128) else localTag = value.take(128)
            },
            label = { Text("AssetTag / كود الأصل") },
            singleLine = true,
            enabled = !busy,
        )
        Button(
            onClick = { onAssetTagLookup(tag.trim()) },
            enabled = !busy && validTag,
        ) { Text("حل AssetTag / Resolve AssetTag") }

        state.assetTagResult?.let { result ->
            Text(result.assetCode + (result.name?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleMedium)
            Text("Asset canonical · " + (result.assetId ?: "unavailable"))
            Text(result.typeName + " · " + result.condition.name)
            result.locationCode?.let { Text("Location · $it") }
            result.employeeCode?.let { Text("Custodian · $it") }
        }

        OutlinedTextField(
            value = assetSearch,
            onValueChange = { value ->
                if (session != null) session.assetSearch = value.take(160)
                else localAssetSearch = value.take(160)
            },
            label = { Text("Asset search / بحث الأصل") },
            singleLine = true,
            enabled = !busy,
        )
        Button(
            onClick = { onAssetSearch(assetSearch.trim()) },
            enabled = !busy && assetSearch.isNotBlank(),
        ) { Text("بحث Asset / Search Asset") }
        state.assetSearchResults.forEach { asset ->
            Text("Asset result · " + asset.assetCode + " · " + asset.id)
        }
        val scannedAssetId = state.assetTagResult?.assetId
        if (scannedAssetId != null && state.assetSearchResults.any { it.id == scannedAssetId }) {
            Text("Scan/search canonical match · PASS")
        }

        OutlinedTextField(
            value = stockSearch,
            onValueChange = { value ->
                if (session != null) session.stockSearch = value.take(160)
                else localStockSearch = value.take(160)
            },
            label = { Text("Stock code / part number") },
            singleLine = true,
            enabled = !busy,
        )
        Button(
            onClick = { onStockSearch(stockSearch.trim()) },
            enabled = !busy && stockSearch.isNotBlank(),
        ) { Text("بحث Stock / Search Stock") }
        state.stockSearchResults.forEach { item ->
            Text("Stock canonical · " + item.id + " · " + item.itemCode)
            item.partNumber?.let { Text("Part number · $it") }
        }
        Text("Supplier barcode mapping · unavailable — no authoritative mapping configured.")
    }
}
