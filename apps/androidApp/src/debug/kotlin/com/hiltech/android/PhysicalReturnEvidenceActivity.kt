package com.hiltech.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.AssetAction
import com.hiltech.shared.core.AssetInventoryState
import com.hiltech.shared.core.PhysicalAssetOperations
import com.hiltech.shared.core.warehouse.*

/** DEBUG-only UI proof. Synthetic state transitions, not an authoritative API or real equipment. */
class PhysicalReturnEvidenceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val at = "2026-11-18T12:00:00Z"
        val bin = StorageLocationDto("synthetic-return-bin", "synthetic-org", "SYNTH-BIN", "Synthetic return bin",
            "WAREHOUSE", warehouseId = "synthetic-warehouse", temporary = false, lifecycleState = "ACTIVE", version = 1)
        val type = AssetTypeDto("synthetic-type", "Synthetic inspection type", 3, false, false, false,
            emptyList(), false, inspectionRequiredOnReturn = true)
        val identity = AssetIdentityDto("synthetic-return-asset", "synthetic-org", "synthetic-warehouse",
            "SYNTH-RETURN-TOOL", typeId = type.id, typeName = type.name, typeRevision = type.revision,
            condition = AssetCondition.GOOD, lifecycle = "ACTIVE", duplicateSerial = false, version = 4)
        val initial = AssetPassportDto(identity, AssetCustodyDto(employeeId = "synthetic-employee",
            employeeCode = "SYNTH-EMP", sourceMovementId = "synthetic-checkout"), emptyList(),
            canOperatePhysical = true, returnInspectionType = type)
        val returnedMovement = AssetMovementDto("SYNTH-RETURN-RECEIPT", "RETURN",
            fromEmployeeId = "synthetic-employee", toLocationId = bin.storageLocationId, condition = "GOOD", occurredAt = at)
        setContent {
            MaterialTheme {
                var passport by remember { mutableStateOf(initial) }
                var stage by remember { mutableStateOf(0) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Synthetic return and inspection evidence")
                    if (stage >= 1) Text("SYNTH-RETURN-RECEIPT")
                    if (stage == 1) Text("BLOCKED_INSPECTION")
                    if (stage == 2) {
                        Text("SYNTH-INSPECTION-RECEIPT")
                        Text("Inspection complete · AVAILABLE")
                    } else {
                        val state = AssetInventoryState(selected = passport, locations = listOf(bin),
                            movements = if (stage == 1) listOf(returnedMovement) else emptyList())
                        PhysicalAssetOperations(passport, state) { action ->
                            when (action) {
                                is AssetAction.ReturnPhysical -> {
                                    check(stage == 0 && action.id == identity.id && action.version == 4L &&
                                        action.movement == "synthetic-checkout" && action.location == bin.storageLocationId &&
                                        action.condition == AssetCondition.GOOD)
                                    passport = passport.copy(identity = identity.copy(version = 5),
                                        custody = AssetCustodyDto(storageLocationId = bin.storageLocationId, locationCode = bin.code,
                                            sourceMovementId = returnedMovement.id))
                                    stage = 1
                                }
                                is AssetAction.InspectPhysical -> {
                                    check(stage == 1 && action.id == identity.id && action.version == 5L &&
                                        action.movement == returnedMovement.id && action.condition == AssetCondition.GOOD &&
                                        action.damageIncident == null)
                                    passport = passport.copy(identity = identity.copy(version = 6), currentReturnInspected = true)
                                    stage = 2
                                }
                                else -> error("Unexpected synthetic action")
                            }
                        }
                    }
                }
            }
        }
    }
}
