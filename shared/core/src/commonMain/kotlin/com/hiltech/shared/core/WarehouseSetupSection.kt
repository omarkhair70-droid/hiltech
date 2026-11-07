package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.warehouse.InventoryImportBatchDirectoryDto
import com.hiltech.shared.core.warehouse.InventoryImportBatchDto
import com.hiltech.shared.core.warehouse.StorageLocationListDto
import com.hiltech.shared.core.warehouse.WarehouseAuthorityListDto
import com.hiltech.shared.core.warehouse.WarehouseListDto

/**
 * First human Warehouse Setup surface in the canonical signed-in shell.
 * Data, permissions and import decisions always come from authoritative API.
 */
data class HiltechWarehouseState(
    val loading: Boolean = false,
    val directory: WarehouseListDto? = null,
    val selectedWarehouseId: String? = null,
    val locations: StorageLocationListDto? = null,
    val currentAuthority: WarehouseAuthorityListDto? = null,
    val importDirectory: InventoryImportBatchDirectoryDto? = null,
    val selectedImport: InventoryImportBatchDto? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

@Composable
fun WarehouseSetupSection(
    state: HiltechWarehouseState,
    onRefresh: () -> Unit = {},
    onSelectWarehouse: (String) -> Unit = {},
    onCreateWarehouse: (String, String) -> Unit = { _, _ -> },
    onCreateWarehouseLocation: (String, String) -> Unit = { _, _ -> },
    onOpenImportBatch: (String) -> Unit = {},
    onGrantWarehouseAuthority: (String, String) -> Unit = { _, _ -> },
    onEndWarehouseAuthority: (String, Long) -> Unit = { _, _ -> },
) {
    var warehouseCode by remember { mutableStateOf("") }
    var warehouseName by remember { mutableStateOf("") }
    var storageCode by remember { mutableStateOf("") }
    var storageName by remember { mutableStateOf("") }
    var importBatchId by remember { mutableStateOf("") }
    var principalId by remember { mutableStateOf("") }
    var authorityKey by remember { mutableStateOf("WAREHOUSE_VIEWER") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Warehouse / Storage Setup",
            style = MaterialTheme.typography.titleLarge,
        )
        Text("Physical places, delegated access and reviewed legacy evidence.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh) { Text("Refresh warehouses") }
            if (state.loading) CircularProgressIndicator()
        }
        state.errorMessage?.let {
            Text(
                it + (state.errorCode?.let { code -> " · $code" } ?: ""),
                color = MaterialTheme.colorScheme.error,
            )
        }
        val warehouses = state.directory?.items.orEmpty()
        if (warehouses.isEmpty() && !state.loading) {
            Text("No accessible warehouses yet.")
        }

        warehouses.forEach { warehouse ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val selected = warehouse.warehouseId == state.selectedWarehouseId
                OutlinedButton(
                    onClick = { onSelectWarehouse(warehouse.warehouseId) },
                ) {
                    Text(warehouse.code + " · " + warehouse.name + if (selected) " · Selected" else "")
                }
                Text(if (warehouse.active) "Active" else "Inactive")
            }
        }

        Text("New Warehouse", style = MaterialTheme.typography.titleMedium)
        Text("Requires an existing current Warehouse Manager assignment.")
        OutlinedTextField(
            value = warehouseCode, onValueChange = { warehouseCode = it },
            label = { Text("Warehouse code") },
            singleLine = true,
        )
        OutlinedTextField(
            value = warehouseName, onValueChange = { warehouseName = it },
            label = { Text("Warehouse name") },
            singleLine = true,
        )
        Button(
            enabled = !state.loading && warehouseCode.isNotBlank() && warehouseName.isNotBlank(),
            onClick = {
                onCreateWarehouse(warehouseCode.trim(), warehouseName.trim())
            },
        ) { Text("Create warehouse") }

        state.selectedWarehouseId?.let {
            Text("Storage Locations", style = MaterialTheme.typography.titleMedium)
            state.locations?.items.orEmpty().forEach { location ->
                Text(
                    "· " + location.code + " — " + location.name +
                        " · " + location.kind + " · " + location.lifecycleState,
                )
            }
            Text("Add a location inside the selected Warehouse")
            OutlinedTextField(
                value = storageCode, onValueChange = { storageCode = it },
                label = { Text("Location code") }, singleLine = true,
            )
            OutlinedTextField(
                value = storageName, onValueChange = { storageName = it },
                label = { Text("Location name") }, singleLine = true,
            )
            Button(
                enabled = !state.loading && storageCode.isNotBlank() && storageName.isNotBlank(),
                onClick = { onCreateWarehouseLocation(storageCode.trim(), storageName.trim()) },
            ) { Text("Create storage location") }

            Text("Current Warehouse Authority", style = MaterialTheme.typography.titleMedium)
            val authority = state.currentAuthority
            if (authority == null) {
                Text("Authority directory is not available for this identity.")
            } else {
                authority.items.forEach { binding ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            binding.authorityKey + " · " +
                                binding.principalType + " " + binding.principalId.take(8),
                        )
                        if (binding.authorityKey in setOf("WAREHOUSE_OPERATOR", "WAREHOUSE_VIEWER")) {
                            OutlinedButton(
                                onClick = {
                                    onEndWarehouseAuthority(binding.bindingId, binding.version)
                                },
                            ) { Text("End") }
                        }
                    }
                }
                Text("Delegate Warehouse Viewer or Operator access")
                OutlinedTextField(
                    value = principalId, onValueChange = { principalId = it },
                    label = { Text("Current organization user UUID") }, singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { authorityKey = "WAREHOUSE_VIEWER" },
                    ) { Text(if (authorityKey == "WAREHOUSE_VIEWER") "✓ Viewer" else "Viewer") }
                    OutlinedButton(
                        onClick = { authorityKey = "WAREHOUSE_OPERATOR" },
                    ) { Text(if (authorityKey == "WAREHOUSE_OPERATOR") "✓ Operator" else "Operator") }
                    Button(
                        enabled = principalId.isNotBlank() && !state.loading,
                        onClick = { onGrantWarehouseAuthority(authorityKey, principalId.trim()) },
                    ) { Text("Grant") }
                }
            }
        }

        Text("Legacy Import Review", style = MaterialTheme.typography.titleMedium)
        Text("Rows are evidence only; approval here never changes Stock or Asset balances.")
        state.importDirectory?.items.orEmpty().forEach { batch ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpenImportBatch(batch.batchId) }) {
                    Text(batch.sourceFileName + " · " + batch.state)
                }
            }
        }
        OutlinedTextField(
            value = importBatchId,
            onValueChange = { importBatchId = it },
            label = { Text("Existing import batch UUID") },
            singleLine = true,
        )
        Button(
            enabled = !state.loading && importBatchId.isNotBlank(),
            onClick = { onOpenImportBatch(importBatchId.trim()) },
        ) { Text("Open import batch") }
        state.selectedImport?.let { batch ->
            Text(batch.sourceFileName + " · " + batch.state + " · " + batch.rows.size + " rows")
            batch.rows.forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.sourceSheet + " · row " + row.sourceRowNumber + " · " + row.reviewState)
                    Text(row.normalizedDescription ?: "Description pending review")
                    if (row.issueCodes.isNotEmpty()) {
                        Text("Needs review: " + row.issueCodes.joinToString())
                    }
                }
            }
        }
    }
}
