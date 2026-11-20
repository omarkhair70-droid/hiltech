package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.warehouse.AddReceivingAssetLineDto
import com.hiltech.shared.core.warehouse.AddReceivingDiscrepancyDto
import com.hiltech.shared.core.warehouse.AddReceivingStockLineDto
import com.hiltech.shared.core.warehouse.AssetCatalogDto
import com.hiltech.shared.core.warehouse.AssetCondition
import com.hiltech.shared.core.warehouse.AssetTypeDto
import com.hiltech.shared.core.warehouse.CompleteReceivingDto
import com.hiltech.shared.core.warehouse.CreateReceivingDto
import com.hiltech.shared.core.warehouse.ReceiveAssetLineDto
import com.hiltech.shared.core.warehouse.ReceiveStockLineDto
import com.hiltech.shared.core.warehouse.ReceivingDetailDto
import com.hiltech.shared.core.warehouse.ReceivingDirectoryDto
import com.hiltech.shared.core.warehouse.ReceivingLineDto
import com.hiltech.shared.core.warehouse.StockItemDto
import com.hiltech.shared.core.warehouse.StorageLocationDto
import com.hiltech.shared.core.warehouse.WarehouseApiClient

private val receivingSourceTypes = listOf(
    "PO" to "Purchase order reference",
    "TRANSFER" to "Transfer reference",
    "RETURN" to "Return reference",
    "INITIALIZATION" to "Initialization reference",
    "OTHER" to "Other source",
)

private val receivingDiscrepancyTypes = listOf(
    "SHORT_QTY" to "Short quantity",
    "OVER_QTY" to "Over quantity",
    "WRONG_ITEM" to "Wrong item",
    "DAMAGED_ON_RECEIPT" to "Damaged on receipt",
    "SERIAL_MISMATCH" to "Serial mismatch",
    "UNEXPECTED_ITEM" to "Unexpected item",
    "DOCUMENT_MISMATCH" to "Document mismatch",
)

@Composable
internal fun ReceivingOperatorSection(
    api: WarehouseApiClient,
    warehouse: String,
    organizationId: String,
    installationId: String,
    operationId: (List<Any?>) -> String,
    busy: Boolean,
    runTask: (suspend () -> Unit) -> Unit,
    onReceipt: (String) -> Unit,
    requestedRecordId: String? = null,
    requestedOpenGeneration: Long = 0L,
) {
    var directory by remember(warehouse) { mutableStateOf<ReceivingDirectoryDto?>(null) }
    var detail by remember(warehouse) { mutableStateOf<ReceivingDetailDto?>(null) }
    var locations by remember(warehouse) { mutableStateOf<List<StorageLocationDto>>(emptyList()) }
    var stockItems by remember(warehouse) { mutableStateOf<List<StockItemDto>>(emptyList()) }
    var stockCursor by remember(warehouse) { mutableStateOf<String?>(null) }
    var assetCatalog by remember(warehouse) { mutableStateOf<AssetCatalogDto?>(null) }
    var createOpen by remember(warehouse) { mutableStateOf(false) }
    var receivingSearch by remember(warehouse) { mutableStateOf("") }

    suspend fun loadDirectory() {
        directory = api.receivingDirectory(warehouse, installationId)
    }

    suspend fun loadSupport() {
        locations = api.warehouseStorageLocations(warehouse, installationId).items
            .filter { it.lifecycleState == "ACTIVE" && it.warehouseId == warehouse }
        val stock = api.stockItems(organizationId, installationId)
        stockItems = stock
        stockCursor = stock.lastOrNull()?.itemCode?.takeIf { stock.size == 100 }
        assetCatalog = api.assetCatalog(warehouse, installationId)
    }

    suspend fun loadDetail(id: String) {
        detail = api.receivingDetail(id, installationId)
    }

    suspend fun refreshAfter(recordId: String, receipt: String) {
        onReceipt(receipt)
        loadDetail(recordId)
        loadDirectory()
    }

    fun op(vararg fields: Any?): String =
        operationId(listOf("receiving-ui", warehouse) + fields)

    LaunchedEffect(warehouse, requestedRecordId, requestedOpenGeneration) {
        requestedRecordId?.let { requested ->
            runTask {
                loadDirectory()
                loadSupport()
                loadDetail(requested)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Receiving / discrepancies", style = MaterialTheme.typography.titleMedium)
        Text("Online physical receiving · server versions, authorization and classification remain authoritative.")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                runTask {
                    detail = null
                    directory = null
                    loadDirectory()
                    loadSupport()
                }
            }, enabled = !busy) { Text("Load Receiving") }
            OutlinedButton({ createOpen = !createOpen }, enabled = !busy && locations.isNotEmpty()) {
                Text(if (createOpen) "Close new Receiving" else "New Receiving")
            }
        }

        if (createOpen) {
            ReceivingCreateForm(locations, busy) { destination, sourceType, sourceRef, documentRef ->
                runTask {
                    val operation = op("create", destination, sourceType, sourceRef, documentRef)
                    val response = api.createReceiving(
                        CreateReceivingDto(operation, organizationId, destination, sourceType, sourceRef, documentRef),
                        installationId,
                    )
                    createOpen = false
                    refreshAfter(response.record.id, "RECEIVING · ${response.record.id}")
                }
            }
        }

        val currentDirectory = directory
        if (currentDirectory != null) {
            ReceivingDirectorySection(
                directory = currentDirectory,
                selectedId = detail?.record?.id,
                busy = busy,
                search = receivingSearch,
                changeSearch = { receivingSearch = it },
                open = { id -> runTask { loadDetail(id) } },
                more = if (currentDirectory.limited &&
                    currentDirectory.nextUpdatedAt != null && currentDirectory.nextId != null) {
                    {
                        runTask {
                            val page = api.receivingDirectory(
                                warehouse, installationId,
                                requireNotNull(currentDirectory.nextUpdatedAt), requireNotNull(currentDirectory.nextId),
                            )
                            directory = page.copy(
                                items = (currentDirectory.items + page.items).distinctBy { it.id },
                            )
                        }
                    }
                } else null,
            )
        }

        detail?.let { loaded ->
            key(loaded.record.id, loaded.record.version) {
                ReceivingDetail(
                    detail = loaded,
                    stockItems = stockItems,
                    assetCatalog = assetCatalog,
                    busy = busy,
                    loadMoreStock = stockCursor?.let { cursor ->
                        {
                            runTask {
                                val page = api.stockItems(organizationId, installationId, cursor)
                                stockItems = (stockItems + page).distinctBy { it.id }
                                stockCursor = page.lastOrNull()?.itemCode?.takeIf { page.size == 100 }
                            }
                        }
                    },
                    addStock = { item, expected, sourceLine ->
                        runTask {
                            val operation = op(
                                "add-stock-line", loaded.record.id, loaded.record.version,
                                item.id, item.version, item.classification, expected, sourceLine,
                            )
                            val response = api.addReceivingStockLine(
                                loaded.record.id,
                                AddReceivingStockLineDto(
                                    operation, loaded.record.version, item.id, item.version,
                                    item.classification, expected, item.baseUnitCode, sourceLine,
                                ),
                                installationId,
                            )
                            refreshAfter(loaded.record.id, "RECEIVING LINE · ${response.line.id}")
                        }
                    },
                    addAsset = { type, sourceLine ->
                        runTask {
                            val operation = op(
                                "add-asset-line", loaded.record.id, loaded.record.version, type.id, sourceLine,
                            )
                            val response = api.addReceivingAssetLine(
                                loaded.record.id,
                                AddReceivingAssetLineDto(operation, loaded.record.version, type.id, sourceLine),
                                installationId,
                            )
                            refreshAfter(loaded.record.id, "RECEIVING ASSET LINE · ${response.line.id}")
                        }
                    },
                    receiveStock = { line, quantity, condition, serials ->
                        runTask {
                            val itemId = requireNotNull(line.expectedStockItemId)
                            val current = api.stockItem(itemId, installationId)
                            require(current.classification == line.candidateClass)
                            val operation = op(
                                "receive-stock-line", loaded.record.id, loaded.record.version,
                                line.id, line.version, current.id, current.version, quantity,
                                current.baseUnitCode, condition.name, serials,
                            )
                            val response = api.receiveStockLine(
                                loaded.record.id,
                                ReceiveStockLineDto(
                                    operation, line.id, loaded.record.version, line.version, current.version,
                                    quantity, current.baseUnitCode, condition, serials,
                                ),
                                installationId,
                            )
                            refreshAfter(loaded.record.id, "RECEIVE · ${response.movement.id}")
                        }
                    },
                    receiveAsset = { line, form ->
                        runTask {
                            val operation = op(
                                "receive-asset-line", loaded.record.id, loaded.record.version,
                                line.id, line.version, form.code, form.name, form.manufacturer, form.model,
                                form.serial, form.ownership, form.condition.name, form.tagType,
                            )
                            val response = api.receiveAssetLine(
                                loaded.record.id,
                                ReceiveAssetLineDto(
                                    operation, line.id, loaded.record.version, line.version,
                                    form.code, form.name, form.manufacturer, form.model, form.serial,
                                    form.ownership, form.condition, form.tagType,
                                ),
                                installationId,
                            )
                            refreshAfter(
                                loaded.record.id,
                                "RECEIVED ASSET · ${response.asset.assetCode} · ${response.movementId}",
                            )
                        }
                    },
                    discrepancy = { lineId, type, description ->
                        runTask {
                            val operation = op(
                                "receiving-discrepancy", loaded.record.id, loaded.record.version,
                                lineId, type, description,
                            )
                            val response = api.addReceivingDiscrepancy(
                                loaded.record.id,
                                AddReceivingDiscrepancyDto(
                                    operation, loaded.record.version, lineId, type, description,
                                ),
                                installationId,
                            )
                            refreshAfter(
                                loaded.record.id,
                                "DISCREPANCY · ${response.discrepancy.id}",
                            )
                        }
                    },
                    complete = {
                        runTask {
                            val operation = op("complete-receiving", loaded.record.id, loaded.record.version)
                            val response = api.completeReceiving(
                                loaded.record.id,
                                CompleteReceivingDto(operation, loaded.record.version),
                                installationId,
                            )
                            refreshAfter(
                                loaded.record.id,
                                "RECEIVING ${response.record.state} · ${response.record.id}",
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ReceivingCreateForm(
    locations: List<StorageLocationDto>,
    busy: Boolean,
    create: (String, String, String?, String?) -> Unit,
) {
    var destination by remember { mutableStateOf<String?>(null) }
    var sourceType by remember { mutableStateOf("OTHER") }
    var sourceRef by remember { mutableStateOf("") }
    var documentRef by remember { mutableStateOf("") }

    Text("New Receiving", style = MaterialTheme.typography.titleSmall)
    assetPicker(
        "Destination storage",
        destination,
        locations.map { it.storageLocationId to "${it.code} · ${it.name}" },
        !busy,
    ) { destination = it }
    assetPicker("Source type", sourceType, receivingSourceTypes, !busy) { sourceType = it }
    assetField("Source reference (optional)", sourceRef) { sourceRef = it }
    assetField("Delivery document reference (optional)", documentRef) { documentRef = it }
    Button(
        onClick = {
            create(
                requireNotNull(destination),
                sourceType,
                sourceRef.optional(),
                documentRef.optional(),
            )
        },
        enabled = !busy && destination != null,
    ) { Text("Create Receiving record") }
}

private val receivingRecordId = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

@Composable
fun ReceivingDirectorySection(
    directory: ReceivingDirectoryDto,
    selectedId: String?,
    busy: Boolean,
    search: String,
    changeSearch: (String) -> Unit,
    open: (String) -> Unit,
    more: (() -> Unit)? = null,
) {
    if (directory.items.isEmpty()) Text("No Receiving records are visible in this Warehouse.")
    assetField("Receiving search / scan", search, changeSearch)
    val needle = search.trim()
    val visible = if (needle.isEmpty()) directory.items else directory.items.filter { item ->
        listOfNotNull(item.id, item.sourceType, item.sourceRef, item.deliveryDocumentRef, item.state)
            .any { it.contains(needle, ignoreCase = true) }
    }
    if (needle.isNotEmpty() && visible.isEmpty()) Text("No loaded Receiving record matches this search.")
    assetPicker(
        "Receiving",
        selectedId,
        visible.map { it.id to listOfNotNull(it.sourceType, it.sourceRef, it.state).joinToString(" · ") },
        !busy,
    ) { open(it) }
    if (receivingRecordId.matches(needle)) {
        OutlinedButton({ open(needle) }, enabled = !busy) { Text("Open scanned Receiving receipt") }
    }
    more?.let { OutlinedButton(it, enabled = !busy) { Text("Load older Receiving records") } }
}

@Composable
fun ReceivingHumanProofSection(
    detail: ReceivingDetailDto,
    stockItems: List<StockItemDto>,
    assetCatalog: AssetCatalogDto?,
    busy: Boolean = false,
    receiveStock: (ReceivingLineDto, String, AssetCondition, List<String>) -> Unit,
    receiveAsset: (
        ReceivingLineDto, String, String, String?, String?, String?, String, AssetCondition, String?
    ) -> Unit,
    discrepancy: (String?, String, String) -> Unit,
    complete: () -> Unit,
) {
    ReceivingDetail(
        detail = detail,
        stockItems = stockItems,
        assetCatalog = assetCatalog,
        busy = busy,
        showAddLines = false,
        loadMoreStock = null,
        addStock = { _, _, _ -> Unit },
        addAsset = { _, _ -> Unit },
        receiveStock = receiveStock,
        receiveAsset = { line, form ->
            receiveAsset(
                line, form.code, form.name, form.manufacturer, form.model, form.serial,
                form.ownership, form.condition, form.tagType,
            )
        },
        discrepancy = discrepancy,
        complete = complete,
    )
}

@Composable
private fun ReceivingDetail(
    detail: ReceivingDetailDto,
    stockItems: List<StockItemDto>,
    assetCatalog: AssetCatalogDto?,
    busy: Boolean,
    showAddLines: Boolean = true,
    loadMoreStock: (() -> Unit)?,
    addStock: (StockItemDto, String?, String?) -> Unit,
    addAsset: (AssetTypeDto, String?) -> Unit,
    receiveStock: (ReceivingLineDto, String, AssetCondition, List<String>) -> Unit,
    receiveAsset: (ReceivingLineDto, ReceivingAssetForm) -> Unit,
    discrepancy: (String?, String, String) -> Unit,
    complete: () -> Unit,
) {
    val record = detail.record
    val editable = record.state == "DRAFT" || record.state == "IN_PROGRESS"
    val receivable = record.state == "IN_PROGRESS"
    val completeReady = receivable && detail.lines.isNotEmpty() && detail.lines.all { it.receivedAt != null }

    HorizontalDivider()
    Text(
        listOfNotNull(record.sourceType, record.sourceRef, record.state).joinToString(" · "),
        style = MaterialTheme.typography.titleMedium,
    )
    record.deliveryDocumentRef?.let { Text("Document · $it") }
    Text("Receiving receipt · ${record.id}")
    Text("Version · ${record.version}")

    if (editable && showAddLines) {
        ReceivingAddLineForms(stockItems, assetCatalog, busy, loadMoreStock, addStock, addAsset)
    }

    if (detail.lines.isEmpty()) Text("No physical Receiving lines yet.")
    detail.lines.forEach { line ->
        key(line.id, line.version) {
            ReceivingLineCard(
                line = line,
                stockItems = stockItems,
                assetCatalog = assetCatalog,
                busy = busy,
                canReceive = receivable,
                receiveStock = receiveStock,
                receiveAsset = receiveAsset,
            )
        }
    }

    if (editable) {
        ReceivingDiscrepancyForm(detail, busy, discrepancy)
    }

    if (detail.discrepancies.isNotEmpty()) {
        Text("Recorded discrepancies", style = MaterialTheme.typography.titleSmall)
        detail.discrepancies.forEach {
            Text("${it.type.replace('_', ' ')} · ${it.description}")
            it.receivingLineId?.let { line -> Text("Line · $line") }
            Text("Discrepancy receipt · ${it.id}")
        }
    }

    Button(complete, enabled = !busy && completeReady) {
        Text(if (completeReady) "Complete Receiving" else "Receive every line before completion")
    }
}

@Composable
private fun ReceivingAddLineForms(
    stockItems: List<StockItemDto>,
    assetCatalog: AssetCatalogDto?,
    busy: Boolean,
    loadMoreStock: (() -> Unit)?,
    addStock: (StockItemDto, String?, String?) -> Unit,
    addAsset: (AssetTypeDto, String?) -> Unit,
) {
    var stockId by remember { mutableStateOf<String?>(null) }
    var expected by remember { mutableStateOf("") }
    var stockSourceLine by remember { mutableStateOf("") }
    val stock = stockItems.firstOrNull { it.id == stockId }

    Text("Add Stock line", style = MaterialTheme.typography.titleSmall)
    assetPicker(
        "Stock item",
        stockId,
        stockItems.filter { it.active }.map { it.id to "${it.itemCode} · ${it.name} · ${it.classification}" },
        !busy,
    ) { stockId = it }
    stock?.let { Text("Canonical unit · ${it.baseUnitCode} · ${it.classification}") }
    assetField("Expected quantity (optional)", expected) { expected = it }
    assetField("Source line reference (optional)", stockSourceLine) { stockSourceLine = it }
    Button(
        onClick = { addStock(requireNotNull(stock), expected.optional(), stockSourceLine.optional()) },
        enabled = !busy && stock != null,
    ) { Text("Add Stock line") }
    loadMoreStock?.let {
        OutlinedButton(it, enabled = !busy) { Text("Load more Stock items") }
    }

    val types = assetCatalog?.types.orEmpty()
    var typeId by remember { mutableStateOf<String?>(null) }
    var assetSourceLine by remember { mutableStateOf("") }
    val type = types.firstOrNull { it.id == typeId }

    Text("Add Asset line", style = MaterialTheme.typography.titleSmall)
    assetPicker(
        "Explicit Asset type",
        typeId,
        types.map { it.id to "${it.name} · revision ${it.revision}" },
        !busy,
    ) { typeId = it }
    type?.let {
        Text(buildString {
            append(if (it.serialRequired) "Serial required" else "Serial optional")
            if (it.highValueOrRestricted) append(" · High-value/restricted")
        })
    }
    assetField("Source line reference (optional)", assetSourceLine) { assetSourceLine = it }
    Button(
        onClick = { addAsset(requireNotNull(type), assetSourceLine.optional()) },
        enabled = !busy && type != null,
    ) { Text("Add explicit Asset line") }
}

@Composable
private fun ReceivingLineCard(
    line: ReceivingLineDto,
    stockItems: List<StockItemDto>,
    assetCatalog: AssetCatalogDto?,
    busy: Boolean,
    canReceive: Boolean,
    receiveStock: (ReceivingLineDto, String, AssetCondition, List<String>) -> Unit,
    receiveAsset: (ReceivingLineDto, ReceivingAssetForm) -> Unit,
) {
    HorizontalDivider()
    val stock = line.expectedStockItemId?.let { id -> stockItems.firstOrNull { it.id == id } }
    val type = line.expectedAssetTypeDefinitionId?.let { id -> assetCatalog?.types?.firstOrNull { it.id == id } }
    Text(
        when (line.candidateClass) {
            "COMPANY_ASSET" -> "Asset · ${type?.name ?: line.expectedAssetTypeDefinitionId}"
            else -> "Stock · ${stock?.itemCode ?: line.expectedStockItemId} · ${line.candidateClass}"
        },
        style = MaterialTheme.typography.titleSmall,
    )
    line.expectedQuantity?.let { Text("Expected · $it ${line.unitCode.orEmpty()}") }
    line.sourceLineRef?.let { Text("Source line · $it") }

    if (line.receivedAt != null) {
        Text("Received · ${line.receivedQuantity} ${line.unitCode.orEmpty()} · ${line.condition}")
        line.serials.takeIf { it.isNotEmpty() }?.let { Text("Serial evidence · ${it.joinToString()}") }
        line.receivedAssetId?.let { Text("Asset · $it") }
        Text("Line receipt · ${line.id}")
        return
    }

    if (!canReceive) {
        Text("Start Receiving before physical receipt.")
        return
    }

    if (line.candidateClass == "COMPANY_ASSET") {
        ReceivingAssetLineForm(line, type, busy, receiveAsset)
    } else {
        ReceivingStockLineForm(line, busy, receiveStock)
    }
}

@Composable
private fun ReceivingStockLineForm(
    line: ReceivingLineDto,
    busy: Boolean,
    receive: (ReceivingLineDto, String, AssetCondition, List<String>) -> Unit,
) {
    var quantity by remember(line.id, line.version) { mutableStateOf(line.expectedQuantity.orEmpty()) }
    var condition by remember(line.id, line.version) { mutableStateOf(AssetCondition.UNKNOWN) }
    var serialText by remember(line.id, line.version) { mutableStateOf("") }
    val serialized = line.candidateClass == "STOCK_SERIALIZED"

    assetField("Received quantity ${line.unitCode.orEmpty()}", quantity) { quantity = it }
    ReceivingConditionPicker(condition, !busy) { condition = it }
    if (serialized) {
        OutlinedTextField(
            serialText,
            { serialText = it },
            label = { Text("Serials · comma or new line separated") },
            enabled = !busy,
        )
    }
    val serials = serialText.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
    Button(
        onClick = { receive(line, quantity.trim(), condition, serials) },
        enabled = !busy && quantity.isNotBlank() && condition != AssetCondition.UNKNOWN &&
            (!serialized || serials.isNotEmpty()),
    ) { Text("Record Stock receipt") }
}

private data class ReceivingAssetForm(
    val code: String,
    val name: String,
    val manufacturer: String?,
    val model: String?,
    val serial: String?,
    val ownership: String,
    val condition: AssetCondition,
    val tagType: String?,
)

@Composable
private fun ReceivingAssetLineForm(
    line: ReceivingLineDto,
    type: AssetTypeDto?,
    busy: Boolean,
    receive: (ReceivingLineDto, ReceivingAssetForm) -> Unit,
) {
    var code by remember(line.id, line.version) { mutableStateOf("") }
    var name by remember(line.id, line.version) { mutableStateOf("") }
    var manufacturer by remember(line.id, line.version) { mutableStateOf("") }
    var model by remember(line.id, line.version) { mutableStateOf("") }
    var serial by remember(line.id, line.version) { mutableStateOf("") }
    var ownership by remember(line.id, line.version) { mutableStateOf("") }
    var condition by remember(line.id, line.version) { mutableStateOf(AssetCondition.UNKNOWN) }
    var tagType by remember(line.id, line.version) { mutableStateOf<String?>(null) }

    if (type == null) {
        Text("The explicit Asset type is not currently available. Reload before receiving.")
        return
    }
    assetField("Asset code", code) { code = it }
    assetField("Asset name", name) { name = it }
    assetField("Manufacturer (optional)", manufacturer) { manufacturer = it }
    assetField("Model (optional)", model) { model = it }
    assetField(if (type.serialRequired) "Serial number required" else "Serial number (optional)", serial) { serial = it }
    assetField("Ownership type code", ownership) { ownership = it }
    ReceivingConditionPicker(condition, !busy) { condition = it }

    if (type.allowedTagTypes.isNotEmpty()) {
        assetPicker(
            "Initial tag (optional)",
            tagType,
            listOf("" to "No initial tag") + type.allowedTagTypes.map { it to it },
            !busy,
        ) { tagType = it.ifBlank { null } }
    }

    Button(
        onClick = {
            receive(
                line,
                ReceivingAssetForm(
                    code.trim(), name.trim(), manufacturer.optional(), model.optional(), serial.optional(),
                    ownership.trim(), condition, tagType,
                ),
            )
        },
        enabled = !busy && code.isNotBlank() && name.isNotBlank() && ownership.isNotBlank() &&
            condition != AssetCondition.UNKNOWN && (!type.serialRequired || serial.isNotBlank()),
    ) { Text("Record Asset receipt") }
}

@Composable
private fun ReceivingDiscrepancyForm(
    detail: ReceivingDetailDto,
    busy: Boolean,
    record: (String?, String, String) -> Unit,
) {
    var lineId by remember(detail.record.id, detail.record.version) { mutableStateOf<String?>(null) }
    var type by remember(detail.record.id, detail.record.version) { mutableStateOf("SHORT_QTY") }
    var description by remember(detail.record.id, detail.record.version) { mutableStateOf("") }

    Text("Record discrepancy", style = MaterialTheme.typography.titleSmall)
    assetPicker(
        "Scope",
        lineId ?: "",
        listOf("" to "Whole Receiving record") + detail.lines.map { it.id to "Line · ${it.id}" },
        !busy,
    ) { lineId = it.ifBlank { null } }
    assetPicker("Discrepancy type", type, receivingDiscrepancyTypes, !busy) { type = it }
    OutlinedTextField(
        description,
        { description = it.take(2000) },
        label = { Text("Physical discrepancy description") },
        enabled = !busy,
    )
    Button(
        onClick = { record(lineId, type, description.trim()) },
        enabled = !busy && description.isNotBlank(),
    ) { Text("Record discrepancy") }
}

@Composable
private fun ReceivingConditionPicker(
    condition: AssetCondition,
    enabled: Boolean,
    select: (AssetCondition) -> Unit,
) {
    assetPicker(
        "Condition",
        condition.name,
        AssetCondition.entries.map { it.name to it.name.lowercase().replace('_', ' ') },
        enabled,
    ) { select(AssetCondition.valueOf(it)) }
}