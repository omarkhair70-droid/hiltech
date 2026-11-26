package com.hiltech.shared.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechShape
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.LocalHiltechOperationalColors
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
    reservationPort: ReservationUiPort? = null,
    onAssetAction: (AssetAction) -> Unit = {},
    requestedAssetId: String? = null,
    onAssetFocusConsumed: () -> Unit = {},
    readableProjects: Map<String, String> = emptyMap(),
    readableAssignments: Map<String, String> = emptyMap(),
    onOpenProject: (String) -> Unit = {},
    onOpenAssignment: (String) -> Unit = {},
) {
    var warehouseCode by remember { mutableStateOf("") }
    var warehouseName by remember { mutableStateOf("") }
    var storageCode by remember { mutableStateOf("") }
    var storageName by remember { mutableStateOf("") }
    var importBatchId by remember { mutableStateOf("") }
    var principalId by remember { mutableStateOf("") }
    var authorityKey by remember { mutableStateOf("WAREHOUSE_VIEWER") }

    val colors = LocalHiltechOperationalColors.current
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var tab by remember(state.selectedWarehouseId) { mutableStateOf(if (requestedAssetId != null && state.assets != null) WarehouseWorkspaceTab.Assets else WarehouseWorkspaceTab.Overview) }
    androidx.compose.runtime.LaunchedEffect(requestedAssetId, state.selectedWarehouseId, state.assets != null) {
        if (requestedAssetId != null && state.selectedWarehouseId != null && state.assets != null) tab = WarehouseWorkspaceTab.Assets
    }
    var showLocationForm by remember(state.selectedWarehouseId) { mutableStateOf(false) }
    var showAuthorityForm by remember(state.selectedWarehouseId) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                HiltechSectionHeading("WAREHOUSE / INVENTORY", hiltechText("إدارة المخازن", "Warehouse management"),
                    hiltechText("المخزون والمعدات ومواقع التخزين والحجوزات.", "Stock, equipment, storage locations and reservations."))
            }
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text(hiltechText("تحديث", "Refresh")) }
        }
        if (state.loading) CircularProgressIndicator()
        state.errorMessage?.let { error ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                HiltechStateTag(hiltechText("تعذر تحديث البيانات", "Unable to refresh data"), HiltechStateTone.ATTENTION)
                Text(error)
                state.errorCode?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.secondaryText) }
            }
        }
        val warehouses = state.directory?.items.orEmpty()
        val selected = warehouses.firstOrNull { it.warehouseId == state.selectedWarehouseId }
        val directory: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(hiltechText("دليل المخازن", "Warehouse directory"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { creating = !creating }) { Text(if (creating) hiltechText("إغلاق", "Close") else hiltechText("+ مخزن", "+ Warehouse")) }
                }
                if (creating) HiltechPanel(Modifier.fillMaxWidth()) {
                    Text("New Warehouse", style = MaterialTheme.typography.titleMedium)
                    Text("Requires an existing current Warehouse Manager assignment.")
                    OutlinedTextField(
                        value = warehouseCode, onValueChange = { warehouseCode = it },
                        label = { Text(hiltechText("كود المخزن", "Warehouse code")) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = warehouseName, onValueChange = { warehouseName = it },
                        label = { Text(hiltechText("اسم المخزن", "Warehouse name")) },
                        singleLine = true,
                    )
                    Button(
                        enabled = !state.loading && warehouseCode.isNotBlank() && warehouseName.isNotBlank(),
                        onClick = {
                            onCreateWarehouse(warehouseCode.trim(), warehouseName.trim())
                        },
                    ) { Text(hiltechText("إنشاء مخزن", "Create warehouse")) }
                }
                OutlinedTextField(value = query, onValueChange = { query = it },
                    label = { Text(hiltechText("اسم المخزن أو الكود", "Warehouse name or code")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val matching = warehouses.filter { it.name.contains(query.trim(), true) || it.code.contains(query.trim(), true) }
                if (!state.loading && matching.isEmpty()) Text(if (query.isBlank()) hiltechText("لا توجد مخازن متاحة.", "No warehouses available.") else hiltechText("لا توجد نتائج مطابقة.", "No matching results."))
                matching.forEach { warehouse ->
                    Surface(
                        onClick = { onSelectWarehouse(warehouse.warehouseId); creating = false },
                        color = if (warehouse.warehouseId == state.selectedWarehouseId) colors.selected else colors.surface,
                        contentColor = colors.primaryText, shape = HiltechShape.medium, modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(warehouse.code, color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                            Text(warehouse.name, style = MaterialTheme.typography.titleMedium)
                            HiltechStateTag(if (warehouse.active) hiltechText("نشط", "Active") else hiltechText("غير نشط", "Inactive"),
                                if (warehouse.active) HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL)
                        }
                    }
                }
            }
        }
        val detail: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (selected != null) HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(selected.code, style = MaterialTheme.typography.labelMedium, color = colors.secondaryText)
                    Text(selected.name, style = MaterialTheme.typography.headlineSmall)
                    HiltechStateTag(if (selected.active) hiltechText("مخزن نشط", "Active warehouse") else hiltechText("غير نشط", "Inactive"),
                        if (selected.active) HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL)
                }
                val tabs = WarehouseWorkspaceTab.entries.filter {
                    when (it) {
                        WarehouseWorkspaceTab.Stock -> state.stock != null
                        WarehouseWorkspaceTab.Assets -> state.assets != null
                        WarehouseWorkspaceTab.Reservations -> reservationPort != null && state.selectedWarehouseId != null
                        WarehouseWorkspaceTab.Locations, WarehouseWorkspaceTab.Access -> state.selectedWarehouseId != null
                        else -> true
                    }
                }
                val currentTab = tab.takeIf { it in tabs } ?: WarehouseWorkspaceTab.Overview
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabs.forEach { item ->
                        FilterChip(selected = currentTab == item, onClick = { tab = item }, label = { Text(hiltechText(item.title, item.english)) })
                    }
                }
                when (currentTab) {
                    WarehouseWorkspaceTab.Overview -> {
                        HiltechPanel(Modifier.fillMaxWidth()) {
                            Text(hiltechText("مساحات المخزن", "Warehouse workspaces"), style = MaterialTheme.typography.titleLarge)
                            Text(if (selected == null) hiltechText("اختر مخزنًا لاستعراض بياناته.", "Choose a warehouse to view its data.") else hiltechText("انتقل إلى الأصناف أو المعدات أو مواقع التخزين وإدارة المخزن.", "Browse stock, equipment, storage locations and administration."))
                            tabs.filter { it != WarehouseWorkspaceTab.Overview }.forEach { item ->
                                Surface(onClick = { tab = item }, color = colors.surfaceElevated,
                                    shape = HiltechShape.small, modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(hiltechText(item.title, item.english), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        Text(hiltechText("فتح", "Open"), color = colors.accent)
                                    }
                                }
                            }
                        }
                    }
                    WarehouseWorkspaceTab.Stock -> state.stock?.let { StockInventorySection(it, state.selectedWarehouseId, onStockAction) }
                    WarehouseWorkspaceTab.Assets -> state.assets?.let { AssetPassportSection(it, state.selectedWarehouseId, onAssetAction, requestedAssetId, onAssetFocusConsumed, readableProjects, readableAssignments, onOpenProject, onOpenAssignment) }
                    WarehouseWorkspaceTab.Reservations -> if (reservationPort != null) state.selectedWarehouseId?.let { WarehouseReservations(it, reservationPort) }
                    WarehouseWorkspaceTab.Locations -> HiltechPanel(Modifier.fillMaxWidth()) {
                        Text(hiltechText("مواقع التخزين", "Storage locations"), style = MaterialTheme.typography.titleMedium)
                        val locations = state.locations
                        if (locations == null) Text(hiltechText("بيانات المواقع غير متاحة حاليًا.", "Location data is unavailable."))
                        else if (locations.items.isEmpty()) Text(hiltechText("لا توجد مواقع تخزين مسجلة.", "No storage locations registered."))
                        for ((location, depth) in storageTree(locations?.items.orEmpty())) {
                            Column(Modifier.padding(start = (depth.coerceAtMost(6) * 12).dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(location.name, style = MaterialTheme.typography.titleSmall)
                                Text(location.code + " · " + location.kind, color = colors.secondaryText)
                                Text(location.lifecycleState + if (location.temporary) hiltechText(" · مؤقت", " · Temporary") else "")
                            }
                            HorizontalDivider(color = colors.divider)
                        }
                        TextButton(onClick = { showLocationForm = !showLocationForm }) { Text(if (showLocationForm) hiltechText("إغلاق", "Close") else hiltechText("+ موقع تخزين", "+ Storage location")) }
                        if (showLocationForm) {
                            Text(hiltechText("أضف موقع تخزين داخل المخزن المحدد", "Add a location inside the selected Warehouse"))
                            OutlinedTextField(
                                value = storageCode, onValueChange = { storageCode = it },
                                label = { Text(hiltechText("كود الموقع", "Location code")) }, singleLine = true,
                            )
                            OutlinedTextField(
                                value = storageName, onValueChange = { storageName = it },
                                label = { Text(hiltechText("اسم الموقع", "Location name")) }, singleLine = true,
                            )
                            Button(
                                enabled = !state.loading && storageCode.isNotBlank() && storageName.isNotBlank(),
                                onClick = { onCreateWarehouseLocation(storageCode.trim(), storageName.trim()) },
                            ) { Text(hiltechText("إنشاء موقع تخزين", "Create storage location")) }
                        }
                    }
                    WarehouseWorkspaceTab.Access -> HiltechPanel(Modifier.fillMaxWidth()) {
                        Text(hiltechText("صلاحيات المخزن", "Warehouse permissions"), style = MaterialTheme.typography.titleMedium)
                        val authority = state.currentAuthority
                        if (authority == null) Text(hiltechText("لا يتوفر دليل الصلاحيات لهذا الحساب.", "Permission directory is unavailable to this account."))
                        else {
                            authority.items.forEach { binding ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(binding.authorityKey, style = MaterialTheme.typography.titleSmall)
                                        Text(binding.principalType + " · " + binding.principalId.take(8), color = colors.secondaryText)
                                    }
                                    if (binding.authorityKey in setOf("WAREHOUSE_OPERATOR", "WAREHOUSE_VIEWER")) {
                                        TextButton(onClick = { onEndWarehouseAuthority(binding.bindingId, binding.version) }) { Text(hiltechText("إنهاء", "End")) }
                                    }
                                }
                                HorizontalDivider(color = colors.divider)
                            }
                            TextButton(onClick = { showAuthorityForm = !showAuthorityForm }) { Text(if (showAuthorityForm) hiltechText("إغلاق", "Close") else hiltechText("إضافة صلاحية", "Grant access")) }
                            if (showAuthorityForm) {
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
                    }
                    WarehouseWorkspaceTab.Import -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 900.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(.85f)) { directory() }
                    Column(Modifier.weight(1.65f)) { detail() }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    directory()
                    detail()
                }
            }
        }
    }
}

private enum class WarehouseWorkspaceTab(val title: String, val english: String) {
    Overview("نظرة عامة", "Overview"), Stock("الأصناف", "Stock"), Assets("المعدات", "Equipment"), Reservations("الحجوزات", "Reservations"),
    Locations("المواقع", "Locations"), Import("مراجعة الاستيراد", "Import review"), Access("الصلاحيات", "Permissions"),
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
