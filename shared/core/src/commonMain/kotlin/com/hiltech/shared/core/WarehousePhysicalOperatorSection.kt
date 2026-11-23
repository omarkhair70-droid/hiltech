package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechOperationalTheme
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
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

    WarehouseArabicFirstFrame {
        HiltechOperationalTheme {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HiltechSectionHeading(
            index = "WAREHOUSE / PHYSICAL",
            title = "عمليات المخزن",
            subtitle = "أونلاين فقط · التسليم والمرتجع والاستهلاك الفعلي بإذن السيرفر.",
        )
        OutlinedButton({
            runTask {
                warehouses = null
                warehouseId = null
                warehouses = api.warehouses(organizationId, installationId).items.filter { it.active }
            }
        }, enabled = !busy) { Text(if (busy) "جاري التنفيذ… / Working…" else "تحميل المخازن المتاحة / Load accessible warehouses") }
        message?.let { HiltechStateTag(it, HiltechStateTone.BLOCKED) }
        val options = warehouses ?: return@Column
        if (options.isEmpty()) {
            HiltechStateTag("لا توجد مخازن فعالة متاحة لهذه الهوية.", HiltechStateTone.ATTENTION)
        }
        OperatorChoice("المخزن / Warehouse", warehouseId, options.map { it.warehouseId to "${it.code} · ${it.name}" }, !busy) {
            warehouseId = it
        }
        val facility = warehouseId ?: return@Column
        key(facility) {
            PhysicalOperatorWorkspace(api, facility, organizationId, installationId, operationId, busy, ::runTask)
        }
        }
        }
    }
}

@Composable
fun WarehouseArabicFirstFrame(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        content()
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
    var commandCenter by remember { mutableStateOf<WarehouseCommandCenterDto?>(null) }
    var queue by remember { mutableStateOf<WarehouseReservationQueueDto?>(null) }
    var assetSearch by remember { mutableStateOf("") }
    var assetState by remember { mutableStateOf(AssetInventoryState()) }
    var stockState by remember { mutableStateOf(StockInventoryState()) }
    var stockSearch by remember { mutableStateOf("") }
    var receipt by remember { mutableStateOf<String?>(null) }
    var refreshWarning by remember { mutableStateOf<String?>(null) }
    var storageLocationDrill by remember(warehouse) { mutableStateOf<List<StorageLocationDto>?>(null) }
    var receivingDrillId by remember(warehouse) { mutableStateOf<String?>(null) }
    var receivingDrillGeneration by remember(warehouse) { mutableStateOf(0L) }
    var stocktakeDrillId by remember(warehouse) { mutableStateOf<String?>(null) }
    var stocktakeDrillGeneration by remember(warehouse) { mutableStateOf(0L) }
    var workResourceDrillContext by remember(warehouse) { mutableStateOf<String?>(null) }

    suspend fun afterConfirmedReceipt(refresh: suspend () -> Unit) {
        refreshWarning = null
        try {
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            refreshWarning = "Operation recorded. Current data refresh failed; reload before another physical operation."
        }
    }

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
        val visible = locations.map { it.storageLocationId }.toSet()
        val balances = api.stockBalances(id, installationId).filter { it.storageLocationId in visible }
        val movements = api.stockMovements(id, installationId).filter {
            listOfNotNull(it.fromStorageLocationId, it.toStorageLocationId).let { endpoints ->
                endpoints.isNotEmpty() && endpoints.all { endpoint -> endpoint in visible }
            }
        }
        stockState = stockState.copy(selected = item, locations = locations, balances = balances, movements = movements,
            issued = null, issuedSearch = "", issuedWarehouseId = null,
            fulfillmentHistory = null, historyWarehouseId = null, historyItemId = null, historySearch = "")
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
        assetState = assetState.copy(selected = null, movements = emptyList(), serviceDetail = null, dueReturns = null)
        afterConfirmedReceipt { loadAsset(id) }
    }

    fun stockAction(action: StockAction) = runTask {
        val stock = requireNotNull(stockState.selected)
        when (action) {
            is StockAction.Transfer -> {
                require(stock == action.item && stock.active && action.from != action.to)
                val allowed = stockState.locations.filter { it.warehouseId == warehouse && it.lifecycleState == "ACTIVE" }
                require(allowed.any { it.storageLocationId == action.from } && allowed.any { it.storageLocationId == action.to })
                val source = stockState.balances.single { it.storageLocationId == action.from }
                val destination = stockState.balances.singleOrNull { it.storageLocationId == action.to }
                val operation = op("transfer-stock", stock.id, stock.version, source.version, destination?.version,
                    action.from, action.to, action.quantity, stock.baseUnitCode)
                val response = api.transferStock(stock.id, TransferStockDto(operation, stock.version,
                    action.from, action.to, action.quantity, stock.baseUnitCode), installationId)
                receipt = "${response.movement.type} · ${response.movement.id}"
                stockState = stockState.copy(selected = null, balances = emptyList(), movements = emptyList(),
                    issued = null, issuedWarehouseId = null, fulfillmentHistory = null)
                afterConfirmedReceipt { loadStock(stock.id) }
            }
            is StockAction.LoadFulfillmentHistory -> {
                require(stock.id == action.item)
                stockState = stockState.copy(fulfillmentHistory = null, historyWarehouseId = null, historyItemId = null)
                val history = api.stockFulfillmentHistory(stock.id, warehouse, action.search, installationId)
                stockState = stockState.copy(fulfillmentHistory = history, historyWarehouseId = warehouse,
                    historyItemId = stock.id, historySearch = action.search)
            }
            is StockAction.MoreFulfillmentHistory -> {
                require(stock.id == action.item && stockState.historyWarehouseId == warehouse &&
                    stockState.historyItemId == stock.id && stockState.historySearch == action.search)
                val page = api.stockFulfillmentHistory(stock.id, warehouse, action.search, installationId,
                    action.afterRecordedAt, action.afterMovementId)
                val previous = stockState.fulfillmentHistory?.items.orEmpty()
                stockState = stockState.copy(fulfillmentHistory =
                    page.copy(items = (previous + page.items).distinctBy { it.movementId }))
            }
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
                afterConfirmedReceipt {
                    loadStock(stock.id)
                    val entries = api.issuedStockDirectory(stock.id, warehouse, search, installationId)
                    stockState = stockState.copy(issued = entries, issuedSearch = search, issuedWarehouseId = warehouse)
                }
            }
            else -> error("Unsupported issued stock operation.")
        }
    }

    WarehouseQuickScanSection(
        api = api,
        warehouseId = warehouse,
        organizationId = organizationId,
        installationId = installationId,
        busy = busy,
        runTask = runTask,
    )

    Text("الرئيسية / Warehouse Home", style = MaterialTheme.typography.titleMedium)
    Text("البحث السريع · إصدار / Checkout اليوم · المرتجعات المستحقة · الاستلام · الجرد · الاستثناءات")
    OutlinedButton(
        onClick = {
            runTask {
                commandCenter = null
                commandCenter = api.warehouseCommandCenter(warehouse, installationId)
            }
        },
        enabled = !busy,
    ) { Text("تحميل مركز العمليات / Load command center") }
    commandCenter?.let { snapshot ->
        WarehouseCommandCenterSection(
            snapshot = snapshot,
            onOpenStorageLocations = {
                runTask {
                    storageLocationDrill = api.warehouseStorageLocations(warehouse, installationId).items
                        .filter { it.warehouseId == warehouse }
                }
            },
            onDrill = { drillType, drillId ->
                when (drillType) {
                    "ASSET" -> runTask {
                        assetState = assetState.copy(selected = null, movements = emptyList(), serviceDetail = null)
                        loadAsset(drillId)
                    }
                    "STOCK_ITEM" -> runTask {
                        stockState = stockState.copy(
                            selected = null, issued = null, issuedWarehouseId = null, balances = emptyList(),
                        )
                        loadStock(drillId)
                    }
                    "RECEIVING" -> {
                        receivingDrillId = drillId
                        receivingDrillGeneration += 1
                    }
                    "STOCKTAKE" -> {
                        stocktakeDrillId = drillId
                        stocktakeDrillGeneration += 1
                    }
                }
            },
            onOpenWorkResource = { workOrderId, requirementId ->
                workResourceDrillContext = "Work/$workOrderId · requirement/$requirementId"
                runTask {
                    queue = null
                    queue = api.reservationQueue(warehouse, installationId)
                }
            },
        )
    }
    storageLocationDrill?.let { locations ->
        Text("مواقع التخزين / StorageLocation drill", style = MaterialTheme.typography.titleMedium)
        if (locations.isEmpty()) Text("لا توجد مواقع تخزين مرئية / No visible storage locations.")
        locations.forEach { location ->
            Text(location.code + " · " + location.name + " · " + location.lifecycleState)
        }
    }
    workResourceDrillContext?.let { Text("مورد أمر العمل / Resource drill · $it") }

    receipt?.let {
        HiltechStateTag("سجّل السيرفر العملية · $it", HiltechStateTone.CURRENT)
    }
    refreshWarning?.let {
        HiltechStateTag("الإيصال مؤكد، لكن عرض البيانات لم يتحدث: $it", HiltechStateTone.ATTENTION)
    }
    ReceivingOperatorSection(
        api = api,
        warehouse = warehouse,
        organizationId = organizationId,
        installationId = installationId,
        operationId = operationId,
        busy = busy,
        runTask = runTask,
        onReceipt = { receipt = it },
        requestedRecordId = receivingDrillId,
        requestedOpenGeneration = receivingDrillGeneration,
    )
    StocktakeOperatorSection(
        api = api,
        warehouse = warehouse,
        organizationId = organizationId,
        installationId = installationId,
        operationId = operationId,
        busy = busy,
        runTask = runTask,
        onReceipt = { receipt = it },
        requestedStocktakeId = stocktakeDrillId,
        requestedOpenGeneration = stocktakeDrillGeneration,
    )
    Text("إصدار / Checkout اليوم · Reserved handoff", style = MaterialTheme.typography.titleMedium)
    OutlinedButton({ runTask { queue = null; queue = api.reservationQueue(warehouse, installationId) } }, enabled = !busy) {
        Text("تحميل إصدار / Checkout اليوم · Load reserved handoffs")
    }
    queue?.let { data ->
        WarehousePhysicalHandoffAuthorityGate(data.canReserve) {
            ReservedFulfillmentSection(warehouse, data, fulfillment, busy,
                run = { action -> runTask { action() } },
                onRecorded = { recorded ->
                    receipt = "HANDOFF · $recorded"
                    queue = null
                    // Previous detail snapshots may no longer describe current custody.
                    assetState = assetState.copy(selected = null, movements = emptyList(), serviceDetail = null, dueReturns = null)
                    stockState = stockState.copy(selected = null, issued = null, issuedWarehouseId = null, balances = emptyList())
                    afterConfirmedReceipt { queue = api.reservationQueue(warehouse, installationId) }
                },
            )
        }
    }

    Text("المرتجعات المستحقة / Returns Due · Asset return / inspection / storage transfer", style = MaterialTheme.typography.titleMedium)
    AssetDueReturnsSection(assetState.dueReturns, busy, refresh = {
        runTask {
            assetState = assetState.copy(dueReturns = null)
            val due = api.dueAssetReturns(warehouse, installationId)
            assetState = assetState.copy(dueReturns = due)
        }
    }, more = { afterDue, afterCode, afterId ->
        runTask {
            val page = api.dueAssetReturns(warehouse, installationId, afterDue, afterCode, afterId)
            val previous = assetState.dueReturns?.items.orEmpty()
            assetState = assetState.copy(dueReturns = page.copy(items = (previous + page.items).distinctBy { it.assetId }))
        }
    }, select = { id ->
        runTask {
            assetState = assetState.copy(selected = null, movements = emptyList(), serviceDetail = null)
            loadAsset(id)
        }
    })
    OutlinedTextField(assetSearch, { assetSearch = it }, label = { Text("بحث الأصل: كود / مسلسل / اسم / Tag · Asset code, serial, name or tag") }, singleLine = true, enabled = !busy)
    OutlinedButton({
        runTask {
            assetState = AssetInventoryState(search = assetSearch.trim())
            val catalog = api.assetCatalog(warehouse, installationId)
            val items = api.assets(warehouse, assetSearch.trim(), null, installationId)
            assetState = assetState.copy(catalog = catalog, items = items,
                nextAfterCode = items.lastOrNull()?.assetCode?.takeIf { items.size == 100 })
        }
    }, enabled = !busy) { Text("بحث الأصول / Search assets") }
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
        }, enabled = !busy) { Text("تحميل المزيد من الأصول / Load more assets") }
    }
    assetState.selected?.let { passport ->
        Text("${passport.identity.assetCode} · ${passport.identity.condition}")
        passport.custody?.let { custody ->
            Text("Custody · ${custody.employeeCode ?: custody.locationCode ?: "Unavailable"}")
            custody.expectedReturnAt?.let { Text("Expected return · $it") }
        }
        if (passport.canOperatePhysical) PhysicalAssetOperations(passport, assetState.copy(loading = busy), ::assetAction)
        else Text("العملية الفعلية غير مصرح بها / Physical operation not authorized.")
        key(passport.identity.id) {
            AssetMovementHistory(assetState.movements, assetState.locations)
        }
    }

    Text("المخزون / Stock · transfer / return / consumption", style = MaterialTheme.typography.titleMedium)
    OutlinedButton({
        runTask {
            stockState = StockInventoryState()
            val page = api.stockItems(organizationId, installationId)
            stockState = stockState.copy(items = page,
                nextAfterCode = page.lastOrNull()?.itemCode?.takeIf { page.size == 100 })
        }
    }, enabled = !busy) { Text("تحميل أصناف المخزون / Load stock catalog") }
    OutlinedTextField(stockSearch, { stockSearch = it }, label = { Text("فلترة المخزون بالكود أو الاسم / Filter loaded stock by code or name") }, singleLine = true, enabled = !busy)
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
        }, enabled = !busy) { Text("تحميل المزيد من المخزون / Load more stock") }
    }
    stockState.selected?.let { item ->
        Text("${item.itemCode} · ${item.name}")
        Text("المتاح فعليًا في المخزن / On hand in this Warehouse", style = MaterialTheme.typography.titleMedium)
        stockState.balances.forEach { Text("${it.locationCode} · ${it.onHandQuantity} ${item.baseUnitCode}") }
        StockStorageTransfer(item, stockState.copy(loading = busy), warehouse, ::stockAction)
        Text("حركات التخزين / Local storage movement receipts", style = MaterialTheme.typography.titleMedium)
        stockState.movements.forEach { movement ->
            Text("${movement.type} · ${movement.quantity} ${movement.unitCode} · ${movement.occurredAt}")
            Text("Movement receipt · ${movement.id}")
        }
        IssuedStockOperations(item, warehouse, stockState.copy(loading = busy), ::stockAction)
        StockFulfillmentHistorySection(item, warehouse, stockState.copy(loading = busy), ::stockAction)
    }
}

@Composable
fun WarehousePhysicalHandoffAuthorityGate(
    canOperate: Boolean,
    authorizedContent: @Composable () -> Unit,
) {
    if (canOperate) {
        authorizedContent()
    } else {
        HiltechStateTag(
            "التسليم الفعلي غير متاح لهذه الهوية · سلطة تعديل المخزن مرفوضة.",
            HiltechStateTone.BLOCKED,
        )
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
