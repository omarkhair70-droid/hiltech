package com.hiltech.android

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.WarehouseQuickScanControls
import com.hiltech.shared.core.WarehouseQuickScanState
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.warehouse.AssetCondition
import com.hiltech.shared.core.warehouse.AssetIdentityDto
import com.hiltech.shared.core.warehouse.AssetTagLookupDto
import com.hiltech.shared.core.warehouse.StockItemDto

class WarehouseQuickScanEvidenceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actor = "99999999-9999-4999-8999-999999999999"
        val organization = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val warehouse = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val assetId = "11111111-2222-4333-8444-555555555555"
        val stockId = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        val status = TextView(this).apply {
            text = "SYNTH-QUICK-SCAN-READY"
            textSize = 14f
        }
        val content = ComposeView(this).apply {
            setContent {
                var state by remember { mutableStateOf(WarehouseQuickScanState()) }
                HiltechShell(
                    state = HiltechShellState.SignedIn(
                        identity = IdentityBootstrapDto(
                            identityId = actor,
                            identityStatus = "ACTIVE",
                            identityVersion = 1,
                            organizations = emptyList(),
                            teams = emptyList(),
                        ),
                    ),
                    warehouseOperator = {
                        WarehouseQuickScanControls(
                            state = state,
                            busy = false,
                            onAssetTagLookup = { code ->
                                check(code == "synthetic-current-tag")
                                state = state.copy(
                                    assetTagResult = AssetTagLookupDto(
                                        assetCode = "SYNTH-PASSPORT",
                                        name = "Synthetic calibration instrument",
                                        typeName = "Synthetic Asset type",
                                        serialNumber = "SYNTH-SERIAL",
                                        condition = AssetCondition.GOOD,
                                        locationCode = "SYNTH-ASSET-BIN",
                                        assetId = assetId,
                                    ),
                                )
                                status.text = "SYNTH-QUICK-SCAN-TAG-RECEIPT"
                            },
                            onAssetSearch = { query ->
                                check(query == "SYNTH-PASSPORT")
                                state = state.copy(
                                    assetSearchResults = listOf(
                                        AssetIdentityDto(
                                            id = assetId,
                                            organizationId = organization,
                                            warehouseId = warehouse,
                                            assetCode = "SYNTH-PASSPORT",
                                            name = "Synthetic calibration instrument",
                                            serialNumber = "SYNTH-SERIAL",
                                            typeId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                                            typeName = "Synthetic Asset type",
                                            typeRevision = 1,
                                            condition = AssetCondition.GOOD,
                                            lifecycle = "ACTIVE",
                                            duplicateSerial = false,
                                            version = 4,
                                        ),
                                    ),
                                )
                                status.text = "SYNTH-QUICK-SCAN-ASSET-SEARCH-RECEIPT"
                            },
                            onStockSearch = { query ->
                                check(query == "SYNTH-PART-01")
                                state = state.copy(
                                    stockSearchResults = listOf(
                                        StockItemDto(
                                            id = stockId,
                                            organizationId = organization,
                                            itemCode = "SYNTH-STOCK-01",
                                            name = "Synthetic cable",
                                            partNumber = "SYNTH-PART-01",
                                            categoryDefinitionId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                                            categoryRevision = 1,
                                            baseUnitCode = "EA",
                                            classification = "STOCK_QUANTITY",
                                            serialized = false,
                                            lotTracked = false,
                                            active = true,
                                            version = 2,
                                        ),
                                    ),
                                )
                                status.text = "SYNTH-QUICK-SCAN-STOCK-SEARCH-RECEIPT"
                            },
                        )
                    },
                )
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(status, LinearLayout.LayoutParams(-1, -2))
                addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            },
        )
    }
}
