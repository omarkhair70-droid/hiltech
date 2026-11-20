package com.hiltech.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.WarehousePhysicalHandoffAuthorityGate
import com.hiltech.shared.core.WarehouseQuickScanControls
import com.hiltech.shared.core.WarehouseQuickScanState
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.warehouse.AssetCondition
import com.hiltech.shared.core.warehouse.AssetTagLookupDto

/** Debug-only human proof using the production shared safe lookup and authority gate. */
class WarehouseClosureEvidenceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val safeState = remember {
                WarehouseQuickScanState(
                    assetTagResult = AssetTagLookupDto(
                        assetCode = "SYNTH-SAFE-ASSET",
                        name = "Synthetic safe asset",
                        typeName = "Synthetic tool",
                        serialNumber = "SYNTH-SERIAL",
                        condition = AssetCondition.GOOD,
                        locationCode = "SYNTH-BIN",
                        assetId = "11111111-2222-4333-8444-555555555555",
                    ),
                )
            }
            HiltechShell(
                state = HiltechShellState.SignedIn(
                    identity = IdentityBootstrapDto(
                        identityId = "99999999-9999-4999-8999-999999999999",
                        identityStatus = "ACTIVE",
                        identityVersion = 1,
                        organizations = emptyList(),
                        teams = emptyList(),
                    ),
                ),
                warehouseOperator = {
                    WarehouseQuickScanControls(
                        state = safeState,
                        busy = true,
                        onAssetTagLookup = {},
                        onAssetSearch = {},
                        onStockSearch = {},
                    )
                    WarehousePhysicalHandoffAuthorityGate(canOperate = false) {
                        error("Revoked authority must not expose physical handoff controls.")
                    }
                },
            )
        }
    }
}
