package com.hiltech.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.AssetLookupState
import com.hiltech.shared.core.AssetTagLookupSection
import com.hiltech.shared.core.warehouse.AssetCondition
import com.hiltech.shared.core.warehouse.AssetTagLookupDto

class AssetLookupEvidenceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var state by remember { mutableStateOf(AssetLookupState()) }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp)) {
                        AssetTagLookupSection(state) { code ->
                            state = if (code == "synthetic-current-tag") {
                                AssetLookupState(result = AssetTagLookupDto(
                                    "SYNTH-PASSPORT", "Synthetic calibration instrument", "Synthetic Asset type",
                                    serialNumber = "SYNTH-SERIAL", condition = AssetCondition.GOOD, locationCode = "SYNTH-ASSET-BIN",
                                ))
                            } else AssetLookupState(message = "Asset tag unavailable.")
                        }
                    }
                }
            }
        }
    }
}
