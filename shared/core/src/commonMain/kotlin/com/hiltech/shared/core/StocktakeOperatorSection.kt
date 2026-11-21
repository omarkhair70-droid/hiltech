package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
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
import com.hiltech.shared.core.warehouse.ApplyInventoryAdjustmentDto
import com.hiltech.shared.core.warehouse.CreateStocktakeDto
import com.hiltech.shared.core.warehouse.InventoryAdjustmentRequestDto
import com.hiltech.shared.core.warehouse.InventoryDiscrepancyDto
import com.hiltech.shared.core.warehouse.RecordStockCountDto
import com.hiltech.shared.core.warehouse.RequestInventoryAdjustmentDto
import com.hiltech.shared.core.warehouse.StartStocktakeDto
import com.hiltech.shared.core.warehouse.StocktakeDetailDto
import com.hiltech.shared.core.warehouse.StocktakeSnapshotLineDto
import com.hiltech.shared.core.warehouse.StocktakeVariancesDto
import com.hiltech.shared.core.warehouse.StorageLocationDto
import com.hiltech.shared.core.warehouse.SubmitStocktakeDto
import com.hiltech.shared.core.warehouse.WarehouseApiClient

/**
 * Slice 08 online physical-count surface shared by Windows and Android.
 * It never mutates StockBalance directly. Approved corrections apply only through the canonical Stock ledger command.
 */
@Composable
fun StocktakeOperatorSection(
    api: WarehouseApiClient,
    warehouse: String,
    organizationId: String,
    installationId: String,
    operationId: (List<Any?>) -> String,
    busy: Boolean,
    runTask: (suspend () -> Unit) -> Unit,
    onReceipt: (String) -> Unit,
    requestedStocktakeId: String? = null,
    requestedOpenGeneration: Long = 0L,
) {
    var locations by remember(warehouse) { mutableStateOf<List<StorageLocationDto>?>(null) }
    var rootId by remember(warehouse) { mutableStateOf<String?>(null) }
    var openId by remember(warehouse) { mutableStateOf("") }
    var detail by remember(warehouse) { mutableStateOf<StocktakeDetailDto?>(null) }
    var variances by remember(warehouse) { mutableStateOf<StocktakeVariancesDto?>(null) }
    var commandGeneration by remember(warehouse) { mutableStateOf(0L) }

    fun op(vararg fields: Any?): String =
        operationId(listOf("stocktake", warehouse, commandGeneration) + fields)

    suspend fun refresh(id: String) {
        detail = api.stocktake(id, installationId)
        variances = if (detail?.session?.state in setOf("SUBMITTED", "RECONCILING", "CLOSED")) {
            api.stocktakeVariances(id, installationId)
        } else {
            null
        }
    }

    LaunchedEffect(warehouse, requestedStocktakeId, requestedOpenGeneration) {
        requestedStocktakeId?.let { requested ->
            runTask {
                openId = requested
                refresh(requested)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("الجرد / Stocktake / physical count", style = MaterialTheme.typography.titleMedium)
        Text("جرد فعلي أونلاين · تاريخ إعادة العد محفوظ · العد لا يعدّل الرصيد مباشرة / Online source-of-truth count.")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    runTask {
                        locations = api.warehouseStorageLocations(warehouse, installationId).items
                            .filter { it.lifecycleState == "ACTIVE" }
                        if (rootId !in locations.orEmpty().map { it.storageLocationId }) rootId = null
                    }
                },
                enabled = !busy,
            ) { Text("تحميل مواقع الجرد / Load count locations") }
            detail?.let {
                OutlinedButton(onClick = { runTask { refresh(it.session.id) } }, enabled = !busy) {
                    Text("تحديث الجرد / Refresh stocktake")
                }
            }
        }

        locations?.let { available ->
            Text("نطاق الجرد / Count scope root")
            if (available.isEmpty()) Text("لا يوجد موقع جرد فعّال / No active count location is available.")
            available.forEach { location ->
                OutlinedButton(
                    onClick = { rootId = location.storageLocationId },
                    enabled = !busy,
                ) {
                    Text(
                        (if (rootId == location.storageLocationId) "✓ " else "") +
                            location.code + " · " + location.name,
                    )
                }
            }
            Button(
                onClick = {
                    val root = requireNotNull(rootId)
                    runTask {
                        val created = api.createStocktake(
                            CreateStocktakeDto(
                                operationId = op("create", root),
                                organizationId = organizationId,
                                warehouseId = warehouse,
                                rootStorageLocationId = root,
                            ),
                            installationId,
                        )
                        commandGeneration += 1
                        openId = created.session.id
                        refresh(created.session.id)
                        onReceipt("STOCKTAKE CREATED · " + created.session.id)
                    }
                },
                enabled = !busy && rootId != null,
            ) { Text("إنشاء نطاق الجرد / Create count scope") }
        }

        OutlinedTextField(
            value = openId,
            onValueChange = { openId = it },
            label = { Text("رقم الجرد الحالي / Existing Stocktake UUID") },
            singleLine = true,
            enabled = !busy,
        )
        OutlinedButton(
            onClick = { runTask { refresh(openId.trim()) } },
            enabled = !busy && openId.isNotBlank(),
        ) { Text("فتح الجرد / Open stocktake") }

        detail?.let { current ->
            key(current.session.id, current.session.version, current.countRecords.size, current.adjustmentRequests.size) {
                StocktakeHumanProofSection(
                    detail = current,
                    variances = variances,
                    busy = busy,
                    onStart = {
                        runTask {
                            api.startStocktake(
                                current.session.id,
                                StartStocktakeDto(op("start", current.session.id, current.session.version), current.session.version),
                                installationId,
                            )
                            commandGeneration += 1
                            refresh(current.session.id)
                            onReceipt("STOCKTAKE STARTED · " + current.session.id)
                        }
                    },
                    onCount = { line, quantity, scanSource, notes ->
                        runTask {
                            api.recordStockCount(
                                current.session.id,
                                RecordStockCountDto(
                                    operationId = op(
                                        "count", current.session.id, current.session.version, line.id,
                                        quantity, scanSource, notes,
                                    ),
                                    sessionVersion = current.session.version,
                                    snapshotLineId = line.id,
                                    countedQuantity = quantity,
                                    unitCode = line.unitCode,
                                    scanSource = scanSource,
                                    notes = notes,
                                ),
                                installationId,
                            )
                            commandGeneration += 1
                            refresh(current.session.id)
                            onReceipt("COUNT RECORDED · " + line.itemCode + " · " + line.locationCode)
                        }
                    },
                    onSubmit = {
                        runTask {
                            api.submitStocktake(
                                current.session.id,
                                SubmitStocktakeDto(op("submit", current.session.id, current.session.version), current.session.version),
                                installationId,
                            )
                            commandGeneration += 1
                            refresh(current.session.id)
                            onReceipt("STOCKTAKE SUBMITTED · " + current.session.id)
                        }
                    },
                    onRequestAdjustment = { discrepancy, reason ->
                        runTask {
                            val response = api.requestInventoryAdjustment(
                                discrepancy.id,
                                RequestInventoryAdjustmentDto(
                                    op("request-adjustment", discrepancy.id, discrepancy.version, reason),
                                    discrepancy.version,
                                    reason,
                                ),
                                installationId,
                            )
                            commandGeneration += 1
                            refresh(current.session.id)
                            onReceipt("ADJUSTMENT REQUESTED · " + response.request.id)
                        }
                    },
                    onApplyAdjustment = { adjustment ->
                        runTask {
                            val response = api.applyInventoryAdjustment(
                                adjustment.id,
                                ApplyInventoryAdjustmentDto(
                                    op("apply-adjustment", adjustment.id, adjustment.version),
                                    adjustment.version,
                                ),
                                installationId,
                            )
                            commandGeneration += 1
                            refresh(current.session.id)
                            onReceipt("ADJUSTMENT APPLIED · " + response.movement.id)
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun StocktakeHumanProofSection(
    detail: StocktakeDetailDto,
    variances: StocktakeVariancesDto? = null,
    busy: Boolean = false,
    onStart: () -> Unit = {},
    onCount: (StocktakeSnapshotLineDto, String, String?, String?) -> Unit = { _, _, _, _ -> },
    onSubmit: () -> Unit = {},
    onRequestAdjustment: (InventoryDiscrepancyDto, String) -> Unit = { _, _ -> },
    onApplyAdjustment: (InventoryAdjustmentRequestDto) -> Unit = {},
) {
    val session = detail.session
    val requests = (detail.adjustmentRequests + variances?.adjustmentRequests.orEmpty()).distinctBy { it.id }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Stocktake " + session.id.take(8) + " · " + session.state, style = MaterialTheme.typography.titleSmall)
        Text("Version " + session.version + (session.snapshotAt?.let { " · snapshot $it" } ?: ""))

        if (session.state == "DRAFT") {
            Text("Starting captures immutable expected quantities and versions for the selected location subtree.")
            Button(onClick = onStart, enabled = !busy) { Text("Start authoritative snapshot") }
        }

        if (detail.snapshotLines.isEmpty() && session.state != "DRAFT") {
            Text("No snapshot lines are available.")
        }

        detail.snapshotLines.forEach { line ->
            val history = detail.countRecords.filter { it.snapshotLineId == line.id }
            val latest = history.lastOrNull()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(line.itemCode + " · " + line.locationCode, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Expected " + line.expectedOnHand + " " + line.unitCode +
                        " · balance v" + line.balanceVersion + " · item v" + line.itemVersion,
                )
                if (history.isEmpty()) {
                    Text("No physical count recorded yet.")
                } else {
                    Text("Count history · " + history.size + " record" + if (history.size == 1) "" else "s")
                    history.forEachIndexed { index, count ->
                        Text(
                            "#" + (index + 1) + " · " + count.countedQuantity + " " + count.unitCode +
                                " · " + count.countedAt +
                                (count.scanSource?.let { " · source $it" } ?: "") +
                                (count.supersedesCountRecordId?.let { " · recount" } ?: ""),
                        )
                    }
                }

                if (session.state == "ACTIVE") {
                    var quantity by remember(line.id, latest?.id) {
                        mutableStateOf(latest?.countedQuantity.orEmpty())
                    }
                    var scanSource by remember(line.id, latest?.id) { mutableStateOf("") }
                    var notes by remember(line.id, latest?.id) { mutableStateOf("") }
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("Counted quantity · " + line.unitCode) },
                        singleLine = true,
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = scanSource,
                        onValueChange = { scanSource = it },
                        label = { Text("Scan / source reference (optional)") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Count note (optional)") },
                        enabled = !busy,
                    )
                    Button(
                        onClick = {
                            onCount(
                                line,
                                quantity.trim(),
                                scanSource.trim().ifEmpty { null },
                                notes.trim().ifEmpty { null },
                            )
                        },
                        enabled = !busy && quantity.isNotBlank(),
                    ) { Text(if (latest == null) "Record count" else "Record recount") }
                }
            }
        }

        if (session.state == "ACTIVE") {
            val countedIds = detail.countRecords.map { it.snapshotLineId }.toSet()
            Button(
                onClick = onSubmit,
                enabled = !busy && detail.snapshotLines.isNotEmpty() &&
                    detail.snapshotLines.all { it.id in countedIds },
            ) { Text("Submit physical count") }
        }

        val discrepancies = variances?.discrepancies ?: detail.discrepancies
        if (discrepancies.isNotEmpty()) {
            Text("فرق الجرد / Variance / discrepancy", style = MaterialTheme.typography.titleMedium)
        }
        discrepancies.forEach { discrepancy ->
            val request = requests.firstOrNull { it.discrepancyId == discrepancy.id }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Expected " + discrepancy.expectedQuantity +
                        " · counted " + discrepancy.countedQuantity +
                        " · variance " + discrepancy.varianceQuantity,
                )
                Text(
                    "Snapshot balance v" + discrepancy.snapshotBalanceVersion +
                        " · current balance v" + discrepancy.currentBalanceVersion +
                        " · " + discrepancy.state,
                )
                if (discrepancy.sourceChangedSinceSnapshot) {
                    Text(
                        "Inventory changed after this snapshot. Reconcile current movements before requesting adjustment.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (request != null) {
                    Text("Adjustment requested · " + request.deltaQuantity + " " + request.unitCode)
                    Text("Reason · " + request.reason)
                    Text(
                        if (request.approvalRequestId == null) {
                            "Approval · no approval reference recorded"
                        } else {
                            "Approval · " + (request.approvalStatus ?: "status unavailable") +
                                " · " + request.approvalRequestId.take(8)
                        },
                    )
                    if (request.appliedMovementId != null) {
                        Text("Applied movement · " + request.appliedMovementId.take(8))
                        request.appliedBy?.let { Text("Applied by · " + it.take(8)) }
                        Text("Stocktake reconciliation · " + session.state)
                    } else if (request.approvalStatus == "APPROVED") {
                        Button(
                            onClick = { onApplyAdjustment(request) },
                            enabled = !busy,
                        ) { Text("تطبيق التسوية المعتمدة / Apply approved adjustment") }
                    } else if (request.approvalStatus == "PENDING") {
                        Text("Awaiting inventory adjustment approval.")
                    } else if (request.approvalStatus != null) {
                        Text("Adjustment cannot apply while approval is " + request.approvalStatus + ".")
                    }
                } else if (!discrepancy.sourceChangedSinceSnapshot &&
                    discrepancy.state in setOf("OPEN", "INVESTIGATING")
                ) {
                    var reason by remember(discrepancy.id, discrepancy.version) { mutableStateOf("") }
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Adjustment reason") },
                        enabled = !busy,
                    )
                    Button(
                        onClick = { onRequestAdjustment(discrepancy, reason.trim()) },
                        enabled = !busy && reason.isNotBlank(),
                    ) { Text("طلب تسوية / Request adjustment") }
                    Text("Canonical approval is required before Stock ledger apply.")
                }
            }
        }
    }
}
