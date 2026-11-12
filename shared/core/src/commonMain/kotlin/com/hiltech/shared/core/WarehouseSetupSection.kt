package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import com.hiltech.shared.core.warehouse.StorageLocationDto
import com.hiltech.shared.core.warehouse.StorageLocationListDto
import com.hiltech.shared.core.warehouse.WarehouseAuthorityListDto
import com.hiltech.shared.core.warehouse.WarehouseListDto

/** 
 * Safe reviewer edit intent. Never carries source raw JSON or monetary values.
 * The UI does not expose APPROVED until source comparison is independently verified.
 */
data class WarehouseImportReviewDraft(
    val batchId: String,
    val rowId: String,
    val baseVersion: Long,
    val candidateClass: String,
    val normalizedBrand: String?,
    val normalizedPartNumber: String?,
    val normalizedDescription: String?,
    val candidateUom: String?,
    val candidateQuantity: String?,
    val issueCodes: List<String>,
    val reviewState: String,
    val reviewNotes: String?,
)

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
    val stock: StockInventoryState? = null,
    val assets: AssetInventoryState? = null,
)

@Composable
fun WarehouseSetupSection(
    state: HiltechWarehouseState,
    onRefresh: () -> Unit = {},
    onSelectWarehouse: (String) -> Unit = {},
    onCreateWarehouse: (String, String) -> Unit = { _, _ -> },
    onCreateWarehouseLocation: (String, String) -> Unit = { _, _ -> },
    onOpenImportBatch: (String) -> Unit = {},
    onReviewImportRow: (WarehouseImportReviewDraft) -> Unit = {},
    onGrantWarehouseAuthority: (String, String) -> Unit = { _, _ -> },
    onEndWarehouseAuthority: (String, Long) -> Unit = { _, _ -> },
    onStockAction: (StockAction) -> Unit = {},
    onAssetAction: (AssetAction) -> Unit = {},
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
            for ((location, depth) in storageTree(state.locations?.items.orEmpty())) {
                Column(Modifier.padding(start = (depth.coerceAtMost(8) * 16).dp)) {
                    Text(location.code + " · " + location.name + " · " + location.lifecycleState)
                    Text(location.kind + if (location.temporary) " · Temporary" else "")
                    location.projectId?.let { Text("Project $it") }
                    location.siteId?.let { Text("Site $it") }
                }
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

        state.assets?.let { AssetPassportSection(it, state.selectedWarehouseId, onAssetAction) }
        state.stock?.let { StockInventorySection(it, state.selectedWarehouseId, onStockAction) }
        Text("Legacy Import Review", style = MaterialTheme.typography.titleMedium)
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
            var selectedImportRowId by remember(batch.batchId) {
                mutableStateOf(batch.rows.firstOrNull()?.rowId)
            }
            Text(batch.sourceFileName + " · " + batch.state + " · " + batch.rows.size + " rows")
            SelectionContainer {
                Column {
                    Text("Batch " + batch.batchId)
                    Text("Source SHA-256 " + batch.sourceSha256)
                }
            }
            batch.rows.forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.sourceSheet + " · row " + row.sourceRowNumber + " · " + row.reviewState)
                    Text(row.normalizedDescription ?: "Description pending review")
                    if (row.issueCodes.isNotEmpty()) {
                        Text("Needs review: " + row.issueCodes.joinToString())
                    }
                    OutlinedButton(
                        onClick = { selectedImportRowId = row.rowId },
                    ) { Text("Review row") }
                }
            }
            val selectedRow = batch.rows.firstOrNull { it.rowId == selectedImportRowId }
            if (selectedRow != null) {
                var approvedItemCode by remember(selectedRow.rowId) { mutableStateOf("") }
                var approvedItemName by remember(selectedRow.rowId) { mutableStateOf("") }
                var candidateClass by remember(selectedRow.rowId, selectedRow.version) {
                    mutableStateOf(selectedRow.candidateClass)
                }
                var brand by remember(selectedRow.rowId, selectedRow.version) {
                    mutableStateOf(selectedRow.normalizedBrand.orEmpty())
                }
                var partNumber by remember(selectedRow.rowId, selectedRow.version) {
                    mutableStateOf(selectedRow.normalizedPartNumber.orEmpty())
                }
                var description by remember(selectedRow.rowId, selectedRow.version) {
                    mutableStateOf(selectedRow.normalizedDescription.orEmpty())
                }
                var unit by remember(selectedRow.rowId, selectedRow.version) {
                    mutableStateOf(selectedRow.candidateUom.orEmpty())
                }
                var quantity by remember(selectedRow.rowId, selectedRow.version) {
                    mutableStateOf(selectedRow.candidateQuantity.orEmpty())
                }
                var notes by remember(selectedRow.rowId, selectedRow.version) {
                    mutableStateOf("")
                }
                var requestedClears by remember(selectedRow.rowId, selectedRow.version) {
                    mutableStateOf(emptySet<String>())
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        "Review source row " + selectedRow.sourceRowNumber +
                            " · version " + selectedRow.version,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("Source cells", style = MaterialTheme.typography.titleSmall)
                    if (selectedRow.sourcePreview.isEmpty()) {
                        Text("No preview is available for this source layout.")
                    }
                    selectedRow.sourcePreview.forEach { (label, value) -> Text("$label · $value") }
                    Text("Approval requires verified source and mapping evidence.")
                    if (state.stock != null && selectedRow.reviewState == "NEEDS_REVIEW" &&
                        selectedRow.candidateClass in setOf("STOCK_QUANTITY", "STOCK_SERIALIZED")
                    ) {
                        OutlinedTextField(approvedItemCode, { approvedItemCode = it }, label = { Text("New Stock item code") }, singleLine = true)
                        OutlinedTextField(approvedItemName, { approvedItemName = it }, label = { Text("New Stock item name") }, singleLine = true)
                        OutlinedButton(onClick = {
                            onStockAction(StockAction.Approve(batch.batchId, selectedRow.rowId, selectedRow.version,
                                approvedItemCode.trim(), approvedItemName.trim()))
                        }, enabled = !state.loading && !state.stock.loading &&
                            approvedItemCode.isNotBlank() && approvedItemName.isNotBlank()) { Text("Approve verified Stock item") }
                    }
                    if (state.assets != null && selectedRow.reviewState == "NEEDS_REVIEW" &&
                        selectedRow.candidateClass == "COMPANY_ASSET"
                    ) {
                        ApproveAssetImportForm(batch.batchId, selectedRow.rowId, selectedRow.version,
                            state.loading || state.assets.loading, onAssetAction)
                    }
                    Text("Recorded source issues: " +
                        selectedRow.issueCodes.joinToString().ifBlank { "None recorded" })
                    val candidateIssues = selectedRow.issueCodes.filter { it in candidateReviewIssues }
                    if (candidateIssues.isNotEmpty()) {
                        Text("Request issue clearance after correcting the candidate.")
                        candidateIssues.forEach { issue ->
                            Row {
                                Checkbox(
                                    checked = issue in requestedClears,
                                    onCheckedChange = { checked ->
                                        requestedClears = if (checked) requestedClears + issue else requestedClears - issue
                                    },
                                )
                                Text(issue)
                            }
                        }
                    }
                    Text("Normalized candidate", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = candidateClass, onValueChange = { candidateClass = it },
                        label = { Text("Candidate class") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = brand, onValueChange = { brand = it },
                        label = { Text("Brand") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = partNumber, onValueChange = { partNumber = it },
                        label = { Text("Part number") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = description, onValueChange = { description = it },
                        label = { Text("Description") },
                    )
                    OutlinedTextField(
                        value = unit, onValueChange = { unit = it },
                        label = { Text("Candidate UOM") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = quantity, onValueChange = { quantity = it },
                        label = { Text("Candidate quantity") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = notes, onValueChange = { notes = it },
                        label = { Text("Reviewer explanation") },
                    )
                    val candidateValid = candidateClass in setOf(
                        "UNCLASSIFIED", "STOCK_QUANTITY", "STOCK_SERIALIZED", "COMPANY_ASSET",
                    )
                    fun draft(state: String) = WarehouseImportReviewDraft(
                        batchId = batch.batchId,
                        rowId = selectedRow.rowId,
                        baseVersion = selectedRow.version,
                        candidateClass = candidateClass,
                        normalizedBrand = brand.trim().ifEmpty { null },
                        normalizedPartNumber = partNumber.trim().ifEmpty { null },
                        normalizedDescription = description.trim().ifEmpty { null },
                        candidateUom = unit.trim().ifEmpty { null },
                        candidateQuantity = quantity.trim().ifEmpty { null },
                        issueCodes = selectedRow.issueCodes.filterNot { it in requestedClears },
                        reviewState = state,
                        reviewNotes = notes.trim().ifEmpty { null },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !state.loading && candidateValid &&
                                (requestedClears.isEmpty() || notes.isNotBlank()),
                            onClick = { onReviewImportRow(draft("NEEDS_REVIEW")) },
                        ) { Text("Save review / needs clarification") }
                        OutlinedButton(
                            enabled = !state.loading && candidateValid && notes.isNotBlank(),
                            onClick = { onReviewImportRow(draft("REJECTED")) },
                        ) { Text("Reject with reason") }
                    }
                }
            }
        }
    }
}

private val candidateReviewIssues = setOf(
    "ITEM_CLASSIFICATION_REQUIRED", "UNKNOWN_UOM", "BRAND_NORMALIZATION_REQUIRED",
    "PART_NUMBER_MISSING", "PART_NUMBER_PLACEHOLDER", "DUPLICATE_PART_NUMBER", "QUANTITY_AMBIGUOUS",
)

private fun storageTree(locations: List<StorageLocationDto>): List<Pair<StorageLocationDto, Int>> {
    val visibleIds = locations.mapTo(mutableSetOf()) { it.storageLocationId }
    val children = locations.groupBy { it.parentStorageLocationId?.takeIf(visibleIds::contains) }
    val pending = ArrayDeque(children[null].orEmpty().map { it to 0 })
    return buildList {
        while (pending.isNotEmpty()) {
            val (location, depth) = pending.removeFirst()
            add(location to depth)
            children[location.storageLocationId].orEmpty().asReversed().forEach {
                pending.addFirst(it to depth + 1)
            }
        }
    }
}
