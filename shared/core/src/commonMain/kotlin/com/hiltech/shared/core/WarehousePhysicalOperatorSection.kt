package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.warehouse.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Online Warehouse operator entry, distinct from Phase 6 field/offline execution.
 * The shared server commands own authorization, versions, ledger and idempotency.
 * No administrative setup or unrestricted custody transfer is exposed here.
 */
@Composable
fun WarehousePhysicalOperatorSection(
    api: WarehouseApiClient,
    organizationId: String,
    installationId: String,
    operationId: (List<Any?>) -> String,
) {
    val scope = rememberCoroutineScope()
    var warehouses by remember(organizationId) { mutableStateOf<List<WarehouseDto>?>(null) }
    var warehouseId by remember(organizationId) { mutableStateOf<String?>(null) }
    var busy by remember(organizationId) { mutableStateOf(false) }
    var message by remember(organizationId) { mutableStateOf<String?>(null) }

    fun runTask(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val code = (failure as? HiltechApiException)?.code
                message = if (code == null) "Request not confirmed. Refresh or retry the same operation."
                    else "Request not confirmed · $code. Refresh or retry the same operation."
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Warehouse physical operations", style = MaterialTheme.typography.titleLarge)
        Text("Online only · record actual handoff, return and consumption.")
        OutlinedButton({
            runTask {
                warehouses = null
                warehouseId = null
                warehouses = api.warehouses(organizationId, installationId).items.filter { it.active }
            }
        }, enabled = !busy) { Text(if (busy) "Working…" else "Load accessible warehouses") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val options = warehouses ?: return@Column
        if (options.isEmpty()) Text("No accessible active warehouses.")
        OperatorChoice("Warehouse", warehouseId, options.map { it.warehouseId to "${it.code} · ${it.name}" }, !busy) {
            warehouseId = it
        }
        val facility = warehouseId ?: return@Column
        key(facility) {
            PhysicalOperatorWorkspace(api, facility, organizationId, installationId, operationId, busy, ::runTask)
        }
    }
}

@Composable
private fun PhysicalOperatorWorkspace(
    api: WarehouseApiClient,
    warehouse: String,
    organizationId: String,
    installationId: String,
    operationId: (List<Any?>) -> String,
    busy: Boolean,
    runTask: (suspend () -> Unit) -> Unit,
) {
    var queue by remember { mutableStateOf<WarehouseReservationQueueDto?>(null) }
    var assetSearch by remember { mutableStateOf("") }
    var assetState by remember { mutableStateOf(AssetInventoryState()) }
    var stockState by remember { mutableStateOf(StockInventoryState()) }
    var stockSearch by remember { mutableStateOf("") }
    var receipt by remember { mutableStateOf<String?>(null) }

    fun op(vararg fields: Any?): String = operationId(listOf("warehouse-mobile", warehouse) + fields)
    val fulfillment = remember(api, warehouse, installationId) {
        val retries = ExactHandoffRetry()
        object : ReservedFulfillmentUiPort {
            override suspend fun recipients(warehouse: String, search: String) =
                api.fulfillmentRecipients(warehouse, search, installationId)

            override suspend fun fulfill(
                reservation: ReservationDto, work: WarehouseWorkRequestDto,
                requirement: RequirementCandidatesDto, employee: String, expectedReturn: String?,
            ): String {
                val intent = listOf(reservation.kind, reservation.id, reservation.version, work.workOrderId,
                    work.workVersion, requirement.demand.requirementId, requirement.demand.requirementVersion, employee, expectedReturn)
                return retries.execute(reservation.id, intent) {
                if (reservation.kind == "ASSET") {
                    val passport = api.assetPassport(reservation.resourceId, installationId)
                    val custody = requireNotNull(passport.custody)
                    val operation = op("checkout", reservation.id, reservation.version, work.workVersion,
                        passport.identity.version, custody.sourceMovementId, employee, expectedReturn)
                    val request = CheckoutReservedAssetDto(operation, passport.identity.version, custody.sourceMovementId,
                        work.workOrderId, work.workVersion, reservation.id, reservation.version, employee, expectedReturn)
                    val send: suspend () -> String = { api.checkoutReservedAsset(reservation.resourceId, request, installationId).recordId }
                    send
                } else {
                require(reservation.kind == "STOCK")
                val location = requireNotNull(reservation.storageLocationId)
                val balance = api.stockBalances(reservation.resourceId, installationId)
                    .singleOrNull { it.storageLocationId == location } ?: error("Stock capacity is unavailable.")
                val quantity = requireNotNull(reservation.quantity)
                val unit = requireNotNull(reservation.unitCode)
                val operation = op("issue", reservation.id, reservation.version, work.workVersion,
                    requirement.demand.requirementVersion, balance.version, employee, quantity, unit, location)
                val request = IssueReservedStockDto(operation, work.workOrderId, reservation.requirementId, employee,
                    reservation.id, location, work.workVersion, requirement.demand.requirementVersion,
                    reservation.version, balance.version, quantity, unit)
                val send: suspend () -> String = { api.issueReservedStock(reservation.resourceId, request, installationId).movementId }
                send
                }
                }
            }
        }
    }

    suspend fun loadAsset(id: String) {
        val passport = api.assetPassport(id, installationId)
        require(passport.identity.warehouseId == warehouse)
        val moves = api.assetMovements(id, installationId)
        val details = api.assetServiceDetail(id, installationId)
        val locations = api.warehouseStorageLocations(warehouse, installationId).items
        assetState = assetState.copy(selected = passport, movements = moves, serviceDetail = details, locations = locations)
    }

    suspend fun loadStock(id: String) {
        val item = api.stockItem(id, installationId)
        val locations = api.warehouseStorageLocations(warehouse, installationId).items
        val balances = api.stockBalances(id, installationId)
        stockState = stockState.copy(selected = item, locations = locations, balances = balances,
            issued = null, issuedSearch = "", issuedWarehouseId = null)
    }

    fun assetAction(action: AssetAction) = runTask {
        val selected = requireNotNull(assetState.selected)
        val id = selected.identity.id
        require(selected.canOperatePhysical && selected.identity.warehouseId == warehouse)
        val response = when (action) {
            is AssetAction.ReturnPhysical -> {
                require(action.id == id)
                api.returnPhysicalAsset(id, ReturnPhysicalAssetDto(
                    op("return-asset", id, action.version, action.movement, action.location, action.condition.name),
                    action.version, action.movement, action.location, action.condition), installationId)
            }
            is AssetAction.TransferPhysical -> {
                require(action.id == id)
                api.transferPhysicalAsset(id, TransferPhysicalAssetDto(
                    op("transfer-asset", id, action.version, action.movement, action.location),
                    action.version, action.movement, action.location), installationId)
            }
            is AssetAction.InspectPhysical -> {
                require(action.id == id)
                api.inspectPhysicalReturn(id, action.movement, InspectPhysicalReturnDto(
                    op("inspect-asset", id, action.version, action.movement, action.condition.name, action.notes, action.damageIncident),
                    action.version, action.condition, action.notes, action.damageIncident), installationId)
            }
            else -> error("Unsupported physical operation.")
        }
        receipt = "${response.kind} · ${response.recordId}"
        assetState = assetState.copy(selected = null, movements = emptyList(), serviceDetail = null)
        loadAsset(id)
    }

    fun stockAction(action: StockAction) = runTask {
        val stock = requireNotNull(stockState.selected)
        when (action) {
            is StockAction.LoadIssued -> {
                require(stock.id == action.item)
                stockState = stockState.copy(issued = null, issuedWarehouseId = null)
                val entries = api.issuedStockDirectory(stock.id, warehouse, action.search, installationId)
                stockState = stockState.copy(issued = entries, issuedSearch = action.search, issuedWarehouseId = warehouse)
            }
            is StockAction.AdjustIssued -> {
                val entry = action.entry
                val context = entry.context
                require(stock.id == context.stockItemId && stockState.issuedWarehouseId == warehouse)
                require(stockState.issued?.items?.contains(entry) == true)
                val operation = op("adjust-stock", stock.id, context.workOrderId, context.requirementId, context.employeeId,
                    entry.workVersion, context.version, action.quantity, entry.unitCode, action.returnLocation)
                val request = AdjustIssuedStockDto(operation, context.workOrderId, context.requirementId, context.employeeId,
                    entry.workVersion, context.version, action.quantity, entry.unitCode, action.returnLocation)
                val response = if (action.returnLocation == null) api.consumeIssuedStock(stock.id, request, installationId)
                    else api.returnUnusedStock(stock.id, request, installationId)
                receipt = "${response.type} · ${response.movementId}"
                val search = stockState.issuedSearch
                stockState = stockState.copy(selected = null, issued = null, issuedWarehouseId = null, balances = emptyList())
                loadStock(stock.id)
                val entries = api.issuedStockDirectory(stock.id, warehouse, search, installationId)
                stockState = stockState.copy(issued = entries, issuedSearch = search, issuedWarehouseId = warehouse)
            }
            else -> error("Unsupported issued stock operation.")
        }
    }

    receipt?.let { Text("Last confirmed server receipt · $it") }
    Text("Reserved handoff", style = MaterialTheme.typography.titleMedium)
    OutlinedButton({ runTask { queue = null; queue = api.reservationQueue(warehouse, installationId) } }, enabled = !busy) {
        Text("Load reserved handoffs")
    }
    queue?.let { data ->
        if (data.canReserve) ReservedFulfillmentSection(warehouse, data, fulfillment, busy,
            run = { action -> runTask { action() } },
            onRecorded = { recorded ->
                receipt = "HANDOFF · $recorded"
                queue = null
                // Previous detail snapshots may no longer describe current custody.
                assetState = assetState.copy(selected = null, movements = emptyList(), serviceDetail = null)
                stockState = stockState.copy(selected = null, issued = null, issuedWarehouseId = null, balances = emptyList())
                queue = api.reservationQueue(warehouse, installationId)
            },
        ) else Text("Physical handoff is not available for this identity.")
    }

    Text("Asset return / inspection / storage transfer", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(assetSearch, { assetSearch = it }, label = { Text("Asset code, serial, name or tag") }, singleLine = true, enabled = !busy)
    OutlinedButton({
        runTask {
            assetState = AssetInventoryState(search = assetSearch.trim())
            val catalog = api.assetCatalog(warehouse, installationId)
            val items = api.assets(warehouse, assetSearch.trim(), null, installationId)
            assetState = assetState.copy(catalog = catalog, items = items,
                nextAfterCode = items.lastOrNull()?.assetCode?.takeIf { items.size == 100 })
        }
    }, enabled = !busy) { Text("Search assets") }
    OperatorChoice("Asset", assetState.selected?.identity?.id,
        assetState.items.map { it.id to listOfNotNull(it.assetCode, it.name).joinToString(" · ") }, !busy) { id ->
        runTask {
            assetState = assetState.copy(selected = null, movements = emptyList(), serviceDetail = null)
            loadAsset(id)
        }
    }
    assetState.nextAfterCode?.let { cursor ->
        OutlinedButton({
            runTask {
                val page = api.assets(warehouse, assetState.search, cursor, installationId)
                assetState = assetState.copy(items = (assetState.items + page).distinctBy { it.id },
                    nextAfterCode = page.lastOrNull()?.assetCode?.takeIf { page.size == 100 })
            }
        }, enabled = !busy) { Text("Load more assets") }
    }
    assetState.selected?.let { passport ->
        Text("${passport.identity.assetCode} · ${passport.identity.condition}")
        passport.custody?.let { custody ->
            Text("Custody · ${custody.employeeCode ?: custody.locationCode ?: "Unavailable"}")
            custody.expectedReturnAt?.let { Text("Expected return · $it") }
        }
        if (passport.canOperatePhysical) PhysicalAssetOperations(passport, assetState.copy(loading = busy), ::assetAction)
        else Text("Physical operation not authorized.")
    }

    Text("Issued stock return / consumption", style = MaterialTheme.typography.titleMedium)
    OutlinedButton({
        runTask {
            stockState = StockInventoryState()
            val page = api.stockItems(organizationId, installationId)
            stockState = stockState.copy(items = page,
                nextAfterCode = page.lastOrNull()?.itemCode?.takeIf { page.size == 100 })
        }
    }, enabled = !busy) { Text("Load stock catalog") }
    OutlinedTextField(stockSearch, { stockSearch = it }, label = { Text("Filter loaded stock by code or name") }, singleLine = true, enabled = !busy)
    val filtered = stockState.items.filter { it.itemCode.contains(stockSearch, true) || it.name.contains(stockSearch, true) }
    OperatorChoice("Stock item", stockState.selected?.id, filtered.map { it.id to "${it.itemCode} · ${it.name}" }, !busy) { id ->
        runTask {
            stockState = stockState.copy(selected = null, issued = null, issuedWarehouseId = null, balances = emptyList())
            loadStock(id)
        }
    }
    stockState.nextAfterCode?.let { cursor ->
        OutlinedButton({
            runTask {
                val page = api.stockItems(organizationId, installationId, cursor)
                stockState = stockState.copy(items = (stockState.items + page).distinctBy { it.id },
                    nextAfterCode = page.lastOrNull()?.itemCode?.takeIf { page.size == 100 })
            }
        }, enabled = !busy) { Text("Load more stock") }
    }
    stockState.selected?.let { item ->
        Text("${item.itemCode} · ${item.name}")
        IssuedStockOperations(item, warehouse, stockState.copy(loading = busy), ::stockAction)
    }
}

@Composable
private fun OperatorChoice(label: String, selected: String?, options: List<Pair<String, String>>, enabled: Boolean, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (options.isEmpty()) return
    Column {
        OutlinedButton({ open = true }, enabled = enabled) {
            Text("$label · ${options.firstOrNull { it.first == selected }?.second ?: "Select"}")
        }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; select(id) })
            }
        }
    }
}
