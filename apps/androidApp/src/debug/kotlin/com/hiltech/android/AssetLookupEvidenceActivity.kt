package com.hiltech.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hiltech.shared.core.AssetLookupState
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.warehouse.AssetCondition
import com.hiltech.shared.core.warehouse.AssetTagLookupDto

class AssetLookupEvidenceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var state by remember { mutableStateOf(AssetLookupState()) }
            HiltechShell(
                state = HiltechShellState.SignedIn(
                    identity = IdentityBootstrapDto(
                        identityId = "99999999-9999-4999-8999-999999999999",
                        identityStatus = "ACTIVE",
                        identityVersion = 1,
                        primaryOrganizationId = null,
                        organizations = emptyList(),
                        teams = emptyList(),
                    ),
                    assetLookup = state,
                ),
                onLookupAssetTag = { code ->
                    state = if (code == "synthetic-current-tag") {
                        AssetLookupState(result = AssetTagLookupDto(
                            "SYNTH-PASSPORT", "Synthetic calibration instrument", "Synthetic Asset type",
                            serialNumber = "SYNTH-SERIAL", condition = AssetCondition.GOOD, locationCode = "SYNTH-ASSET-BIN",
                        ))
                    } else AssetLookupState(message = "Asset tag unavailable.")
                },
            )
        }
    }
}
