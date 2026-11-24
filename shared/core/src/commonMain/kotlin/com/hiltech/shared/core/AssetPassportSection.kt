package com.hiltech.shared.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.*
import com.hiltech.shared.core.warehouse.AssetCatalogDto
import com.hiltech.shared.core.warehouse.AssetDueReturnQueueDto
import com.hiltech.shared.core.warehouse.AssetCondition
import com.hiltech.shared.core.warehouse.AssetIdentityDto
import com.hiltech.shared.core.warehouse.AssetMovementDto
import com.hiltech.shared.core.warehouse.AssetPassportDto
import com.hiltech.shared.core.warehouse.AssetTagLookupDto
import com.hiltech.shared.core.warehouse.AssetServiceDetailDto
import com.hiltech.shared.core.warehouse.AssetServiceQueueEntryDto
import com.hiltech.shared.core.warehouse.StorageLocationDto

data class AssetInventoryState(
    val catalog: AssetCatalogDto? = null,
    val search: String = "",
    val items: List<AssetIdentityDto> = emptyList(),
    val nextAfterCode: String? = null,
    val selected: AssetPassportDto? = null,
    val movements: List<AssetMovementDto> = emptyList(),
    val locations: List<StorageLocationDto> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val serviceQueue: List<AssetServiceQueueEntryDto>? = null,
    val serviceQueueCursor: String? = null,
    val serviceDetail: AssetServiceDetailDto? = null,
    val dueReturns: AssetDueReturnQueueDto? = null,
)

data class AssetLookupState(val result: AssetTagLookupDto? = null, val loading: Boolean = false, val message: String? = null)

sealed interface AssetAction {
    data class Search(val query: String) : AssetAction
    data class More(val afterCode: String) : AssetAction
    data class Select(val id: String) : AssetAction
    data object RefreshServiceQueue : AssetAction
    data object RefreshDueReturns : AssetAction
    data class MoreDueReturns(val afterExpectedReturnAt: String, val afterAssetCode: String, val afterAssetId: String) : AssetAction
    data class MoreServiceQueue(val afterCode: String) : AssetAction
    sealed interface Command : AssetAction
    data class Service(val command: AssetServiceCommand) : Command
    data class Register(
        val type: String, val code: String, val name: String, val manufacturer: String?, val model: String?,
        val serial: String?, val ownership: String, val condition: AssetCondition, val initialLocation: String?,
    ) : Command
    data class Tag(val id: String, val version: Long, val type: String, val previous: String?) : Command
    data class Opening(
        val id: String, val version: Long, val condition: AssetCondition, val location: String?,
        val employee: String?, val work: String?, val cutover: String?,
    ) : Command
    data class Correct(
        val id: String, val version: Long, val condition: AssetCondition, val location: String?,
        val employee: String?, val work: String?, val sourceMovement: String, val reason: String,
    ) : Command
    data class ReturnPhysical(val id: String, val version: Long, val movement: String, val location: String, val condition: AssetCondition) : Command
    data class TransferPhysical(val id: String, val version: Long, val movement: String, val location: String) : Command
    data class InspectPhysical(val id: String, val version: Long, val movement: String, val condition: AssetCondition,
        val notes: String?, val damageIncident: String?) : Command
    data class Approve(
        val batchId: String, val rowId: String, val version: Long, val code: String,
        val name: String, val ownership: String, val condition: AssetCondition,
    ) : Command
}

@Composable
fun AssetPassportSection(state: AssetInventoryState, warehouseId: String?, dispatch: (AssetAction) -> Unit, requestedAssetId: String? = null, onAssetFocusConsumed: () -> Unit = {}) {
    val colors = LocalHiltechOperationalColors.current
    var search by remember(warehouseId) { mutableStateOf(state.search) }
    var showRegistration by remember(warehouseId) { mutableStateOf(false) }
    var mode by remember(warehouseId) { mutableStateOf(AssetDirectoryMode.Catalogue) }
    var detailMode by remember(state.selected?.identity?.id) { mutableStateOf(state.selected != null) }
    androidx.compose.runtime.LaunchedEffect(requestedAssetId, state.selected?.identity?.id, state.loading) {
        if (requestedAssetId != null) {
            mode = AssetDirectoryMode.Catalogue
            if (state.selected?.identity?.id == requestedAssetId) {
                detailMode = true
                onAssetFocusConsumed()
            } else if (!state.loading && state.message != null) onAssetFocusConsumed()
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(hiltechText("المعدات والأصول", "Equipment & assets"), style = MaterialTheme.typography.titleLarge)
        Text(hiltechText("سجل المعدة وحالتها وعهدتها وخدمتها.", "Asset passport, condition, custody and service."), color = colors.secondaryText, style = MaterialTheme.typography.bodyMedium)
        if (warehouseId == null) {
            Text(hiltechText("اختر مخزنًا.", "Select a warehouse."))
            return@Column
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssetDirectoryMode.entries.forEach { choice ->
                FilterChip(selected = mode == choice, onClick = { mode = choice; detailMode = false }, label = { Text(hiltechText(choice.title, choice.english)) })
            }
        }
        state.message?.let { Text(it) }
        val detail: @Composable () -> Unit = {
            state.selected?.takeIf { requestedAssetId == null || it.identity.id == requestedAssetId }?.let { AssetPassportDetail(it, state, dispatch) }
                ?: HiltechPanel(Modifier.fillMaxWidth()) { Text(hiltechText("اختر معدة لفتح سجلها.", "Select an asset to open its passport.")) }
        }
        when (mode) {
            AssetDirectoryMode.Service -> {
                AssetServiceQueue(state, dispatch)
                state.selected?.let { AssetPassportDetail(it, state, dispatch) }
            }
            AssetDirectoryMode.Returns -> {
                AssetDueReturnsSection(state.dueReturns, state.loading,
                    refresh = { dispatch(AssetAction.RefreshDueReturns) },
                    more = { due, code, id -> dispatch(AssetAction.MoreDueReturns(due, code, id)) },
                    select = { dispatch(AssetAction.Select(it)) })
                state.selected?.let { AssetPassportDetail(it, state, dispatch) }
            }
            AssetDirectoryMode.Catalogue -> {
                val directory: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(search, { search = it }, modifier = Modifier.weight(1f),
                                label = { Text(hiltechText("كود أو اسم أو مسلسل", "Code, name or serial")) }, singleLine = true)
                            Button({ dispatch(AssetAction.Search(search.trim())) }, enabled = !state.loading) {
                                Text(if (state.loading) hiltechText("تحميل", "Loading") else hiltechText("بحث", "Search"))
                            }
                        }
                        val catalog = state.catalog
                        if (catalog?.canRegister == true) {
                            TextButton(onClick = { showRegistration = !showRegistration }) {
                                Text(if (showRegistration) hiltechText("إغلاق التسجيل", "Close registration") else hiltechText("+ تسجيل معدة", "+ Register asset"))
                            }
                            if (showRegistration) HiltechPanel(Modifier.fillMaxWidth()) {
                                if (catalog.types.isEmpty()) Text(hiltechText("لا توجد أنواع معدات مفعّلة.", "No active asset types."))
                                else AssetRegistrationForm(state, dispatch)
                            }
                        }
                        if (catalog != null && state.items.isEmpty()) Text(hiltechText("لا توجد معدات مطابقة في النتائج.", "No matching assets."))
                        state.items.forEach { item ->
                            Surface(onClick = { detailMode = true; dispatch(AssetAction.Select(item.id)) },
                                enabled = !state.loading, color = if (item.id == state.selected?.identity?.id) colors.selected else colors.surface,
                                contentColor = colors.primaryText, shape = HiltechShape.medium, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(item.assetCode, color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                                    Text(item.name ?: item.assetCode, style = MaterialTheme.typography.titleMedium)
                                    Text(item.typeName, color = colors.secondaryText)
                                    Text(listOfNotNull(item.manufacturer, item.model, item.serialNumber).joinToString(" · "),
                                        color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                                    HiltechStateTag(item.condition.name, HiltechStateTone.NEUTRAL)
                                }
                            }
                        }
                        state.nextAfterCode?.let { cursor ->
                            OutlinedButton({ dispatch(AssetAction.More(cursor)) }, enabled = !state.loading) { Text(hiltechText("تحميل معدات إضافية", "Load more assets")) }
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 860.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.weight(.9f)) { directory() }
                        Column(Modifier.weight(1.5f)) { detail() }
                    } else if (detailMode && state.selected != null) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { detailMode = false }) { Text(hiltechText("العودة إلى المعدات", "Back to assets")) }
                        detail()
                    } else directory()
                }
            }
        }
    }
}

private enum class AssetDirectoryMode(val title: String, val english: String) {
    Catalogue("دليل المعدات", "Asset directory"), Service("الخدمة والمعايرة", "Service & calibration"), Returns("المرتجعات المستحقة", "Due returns"),
}

@Composable
private fun AssetRegistrationForm(state: AssetInventoryState, dispatch: (AssetAction) -> Unit) {
    val types = state.catalog?.types.orEmpty()
    var typeId by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var manufacturer by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var ownership by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf(AssetCondition.UNKNOWN) }
    var initialLocation by remember { mutableStateOf("") }
    val type = types.firstOrNull { it.id == typeId }
    assetPicker("Asset type", typeId, types.map { it.id to "${it.name} · revision ${it.revision}" }) { typeId = it }
    if (type?.calibrationRequired == true) Text("Calibration required")
    if (type?.highValueOrRestricted == true) Text("High-value or restricted Asset")
    assetField("Asset code", code) { code = it }
    assetField("Name", name) { name = it }
    assetField("Manufacturer", manufacturer) { manufacturer = it }
    assetField("Model", model) { model = it }
    assetField(if (type?.serialRequired == true) "Serial number required" else "Serial number", serial) { serial = it }
    assetField("Ownership type code", ownership) { ownership = it }
    conditionPicker(condition) { condition = it }
    assetPicker("Initial storage", initialLocation, listOf("" to "No initial storage") + state.locationChoices()) { initialLocation = it }
    Button({
        dispatch(AssetAction.Register(requireNotNull(typeId), code.trim(), name.trim(), manufacturer.optional(),
            model.optional(), serial.optional(), ownership.trim(), condition, initialLocation.optional()))
    }, enabled = !state.loading && type != null && code.isNotBlank() && name.isNotBlank() && ownership.isNotBlank() &&
        (!type.serialRequired || serial.isNotBlank())) { Text("Register asset") }
}

@Composable
private fun AssetPassportDetail(passport: AssetPassportDto, state: AssetInventoryState, dispatch: (AssetAction) -> Unit) {
    val asset = passport.identity
    val custody = passport.custody
    val colors = LocalHiltechOperationalColors.current
    var tab by remember(asset.id) { mutableStateOf(AssetDetailTab.Passport) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HiltechPanel(Modifier.fillMaxWidth()) {
            Text(asset.assetCode, color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
            Text(asset.name ?: asset.assetCode, style = MaterialTheme.typography.headlineSmall)
            Text(asset.typeName, color = colors.secondaryText, style = MaterialTheme.typography.bodyMedium)
            HiltechStateTag(asset.condition.name, HiltechStateTone.NEUTRAL)
            HiltechKeyValue(hiltechText("العهدة الحالية", "Current custody"), custody?.locationCode ?: custody?.employeeCode?.let { "موظف " + it }
                ?: "لم تُسجل أو لا تتوفر بيانات العهدة")
            custody?.expectedReturnAt?.let { HiltechKeyValue(hiltechText("الموعد المتوقع للعودة", "Expected return"), it) }
            if (asset.duplicateSerial) Text(hiltechText("الرقم المسلسل يحتاج مراجعة", "Serial number needs review"), color = MaterialTheme.colorScheme.error)
        }
        val tabs = AssetDetailTab.entries.filter { choice ->
            when (choice) {
                AssetDetailTab.Operations -> passport.canInitialize || passport.canCorrect || (passport.canOperatePhysical && custody != null)
                AssetDetailTab.Service -> state.serviceDetail != null
                else -> true
            }
        }
        val current = tab.takeIf { it in tabs } ?: AssetDetailTab.Passport
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEach { choice ->
                FilterChip(selected = current == choice, onClick = { tab = choice }, label = { Text(hiltechText(choice.title, choice.english)) })
            }
        }
        when (current) {
            AssetDetailTab.Passport -> HiltechPanel(Modifier.fillMaxWidth()) {
                HiltechKeyValue(hiltechText("الشركة المصنعة / الموديل", "Manufacturer / model"), listOfNotNull(asset.manufacturer, asset.model).joinToString(" · ").ifEmpty { "غير متاح" })
                HiltechKeyValue(hiltechText("الرقم المسلسل", "Serial number"), asset.serialNumber ?: "غير متاح")
                HiltechKeyValue(hiltechText("الحالة", "Status"), asset.lifecycle)
                custody?.since?.let { HiltechKeyValue(hiltechText("العهدة منذ", "In custody since"), it) }
                asset.sourceImportRowId?.let { HiltechKeyValue(hiltechText("مرجع سجل الاستيراد", "Import record reference"), it) }
                custody?.projectId?.let { HiltechKeyValue(hiltechText("مرجع المشروع", "Project reference"), it) }
                custody?.siteId?.let { HiltechKeyValue(hiltechText("مرجع الموقع", "Site reference"), it) }
                custody?.workOrderId?.let { HiltechKeyValue(hiltechText("مرجع العمل", "Work reference"), it) }
            }
            AssetDetailTab.Tags -> AssetTags(passport, state.loading, dispatch)
            AssetDetailTab.Operations -> {
                if (passport.canInitialize || passport.canCorrect) AssetCustodyForm(passport, state, dispatch)
                if (passport.canOperatePhysical && custody != null) PhysicalAssetOperations(passport, state, dispatch)
            }
            AssetDetailTab.Service -> state.serviceDetail?.let { AssetServiceDetail(asset.id, it, state.loading, dispatch) }
            AssetDetailTab.History -> AssetMovementHistory(state.movements, state.locations)
        }
    }
}

private enum class AssetDetailTab(val title: String, val english: String) {
    Passport("السجل", "Passport"), Tags("الملصقات", "Tags"), Operations("العهدة والحركة", "Custody & movements"), Service("الخدمة", "Service"), History("التاريخ", "History"),
}

/** The same immutable movement receipts on Windows and Android. */
@Composable
fun AssetMovementHistory(movements: List<AssetMovementDto>, locations: List<StorageLocationDto>) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton({ expanded = !expanded }) {
            Text(if (expanded) "Hide Asset movement history" else "Show Asset movement history")
        }
        if (expanded) {
            val locationCodes = locations.associate { it.storageLocationId to it.code }
            if (movements.isEmpty()) Text("No movements recorded")
            if (movements.size >= 100) Text("Showing at most the latest 100 movements.")
            movements.forEach {
                val from = it.fromLocationId?.let { id -> locationCodes[id] ?: id } ?: it.fromEmployeeId
                val to = it.toLocationId?.let { id -> locationCodes[id] ?: id } ?: it.toEmployeeId
                Text("${it.type.replace('_', ' ')} · ${it.occurredAt}")
                if (from != null || to != null) Text(listOfNotNull(from, to).joinToString(" → "))
                it.condition?.let { condition -> Text("Condition · $condition") }
                it.workOrderId?.let { work -> Text("Work · $work") }
                SelectionContainer { Text("Movement receipt · ${it.id}") }
                it.reason?.let { reason -> Text(reason) }
                it.correctedMovementId?.let { id -> Text("Corrects $id") }
            }
        }
    }
}

@Composable
private fun AssetTags(passport: AssetPassportDto, busy: Boolean, dispatch: (AssetAction) -> Unit) {
    val asset = passport.identity
    val current = passport.tags.singleOrNull { it.active && it.primary }
    var tagType by remember(asset.id, asset.version) { mutableStateOf(passport.permittedTagTypes.firstOrNull()) }
    Text("Tags", style = MaterialTheme.typography.titleSmall)
    if (passport.tags.isEmpty()) Text("No tags issued")
    passport.tags.forEach { tag ->
        Text("${tag.type} · ${if (tag.active && tag.primary) "Active primary" else "Ended"} · issued ${tag.issuedAt}")
        SelectionContainer { Text(tag.code) }
        tag.endedAt?.let { Text("Ended $it") }
        tag.replacesTagId?.let { prior ->
            passport.tags.firstOrNull { it.id == prior }?.let { Text("Replaces ${it.code}") }
        }
    }
    if (passport.permittedTagTypes.isNotEmpty()) {
        assetPicker("Tag type", tagType, passport.permittedTagTypes.map { it to it }, !busy) { tagType = it }
        OutlinedButton({
            dispatch(AssetAction.Tag(asset.id, asset.version, requireNotNull(tagType), current?.id))
        }, enabled = !busy && tagType in passport.permittedTagTypes) {
            Text(if (current == null) "Issue primary tag" else "Replace primary tag")
        }
    }
}

@Composable
private fun AssetCustodyForm(passport: AssetPassportDto, state: AssetInventoryState, dispatch: (AssetAction) -> Unit) {
    val asset = passport.identity
    val current = passport.custody
    var expanded by remember(asset.id, asset.version) { mutableStateOf(false) }
    val action = if (current == null) "Record opening custody" else "Correct current custody"
    OutlinedButton({ expanded = !expanded }, enabled = !state.loading) { Text(if (expanded) "Close custody form" else action) }
    if (!expanded) return
    var target by remember { mutableStateOf("Storage") }
    var location by remember { mutableStateOf<String?>(null) }
    var employee by remember { mutableStateOf("") }
    var work by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var cutover by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf(asset.condition) }
    val verifiedOpening = current == null && asset.sourceImportRowId != null
    assetPicker("Custody target", target, listOf("Storage" to "Storage", "Employee" to "Employee")) { target = it }
    val toEmployee = target == "Employee"
    if (toEmployee) {
        assetField("Employee record ID", employee) { employee = it }
        if (!verifiedOpening) assetField("Work order ID (optional)", work) { work = it }
    } else assetPicker("Storage location", location, state.locationChoices()) { location = it }
    if (verifiedOpening) assetField("Verified cutover time (ISO 8601)", cutover) { cutover = it }
    if (current != null) assetField("Correction reason", reason) { reason = it }
    conditionPicker(condition) { condition = it }
    val selectedLocation = location.takeUnless { toEmployee }
    val selectedEmployee = employee.optional()?.takeIf { toEmployee }
    val workOrder = work.optional()?.takeIf { toEmployee && !verifiedOpening }
    val validTarget = if (toEmployee) employee.trim().matches(assetRecordId) &&
        (workOrder == null || workOrder.matches(assetRecordId)) else selectedLocation != null
    Button({
        if (current == null) {
            dispatch(AssetAction.Opening(asset.id, asset.version, condition, selectedLocation, selectedEmployee, workOrder, cutover.optional()))
        } else {
            dispatch(AssetAction.Correct(asset.id, asset.version, condition, selectedLocation, selectedEmployee,
                workOrder, current.sourceMovementId, reason.trim()))
        }
    }, enabled = !state.loading && validTarget && (!verifiedOpening || cutover.isNotBlank()) &&
        (current == null || reason.isNotBlank())) { Text(action) }
}

@Composable
internal fun ApproveAssetImportForm(batchId: String, rowId: String, version: Long, disabled: Boolean, dispatch: (AssetAction) -> Unit) {
    var code by remember(rowId, version) { mutableStateOf("") }
    var name by remember(rowId, version) { mutableStateOf("") }
    var ownership by remember(rowId, version) { mutableStateOf("") }
    var condition by remember(rowId, version) { mutableStateOf(AssetCondition.UNKNOWN) }
    assetField("Asset code", code) { code = it }
    assetField("Asset name", name) { name = it }
    assetField("Ownership type code", ownership) { ownership = it }
    conditionPicker(condition) { condition = it }
    OutlinedButton({
        dispatch(AssetAction.Approve(batchId, rowId, version, code.trim(), name.trim(), ownership.trim(), condition))
    }, enabled = !disabled && code.isNotBlank() && name.isNotBlank() && ownership.isNotBlank()) { Text("Approve verified Asset") }
}

@Composable
fun AssetTagLookupSection(state: AssetLookupState, lookup: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    val valid = !state.loading && Regex("[A-Za-z0-9_-]{1,128}").matches(code.trim())
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (valid) {
            lookup(code.trim())
            code = ""
            keyboard?.hide()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Asset tag lookup", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(code, { code = it.take(128) },
            label = { Text("Scan or enter opaque tag code") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }),
            modifier = Modifier.onPreviewKeyEvent {
                if (it.key == Key.Enter && it.type == KeyEventType.KeyDown && valid) {
                    submit()
                    true
                } else false
            })
        Button(submit, enabled = valid) { Text(if (state.loading) "Looking up asset" else "Look up asset") }
        state.message?.let { Text(it) }
        state.result?.let {
            Text(listOfNotNull(it.assetCode, it.name).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
            Text(it.typeName)
            listOfNotNull(it.manufacturer, it.model).joinToString(" · ").takeIf(String::isNotBlank)?.let { detail -> Text(detail) }
            it.serialNumber?.let { serial -> Text("Serial number · $serial") }
            Text("Condition · ${it.condition.name.lowercase().replaceFirstChar { letter -> letter.uppercase() }}")
            Text("Current custody · ${it.locationCode ?: it.employeeCode?.let { code -> "Employee $code" } ?: "Unrecorded"}")
            it.custodySince?.let { since -> Text("Since $since") }
            it.availability?.let { availability -> SafeAssetAvailability(availability) }
        }
    }
}

@Composable
private fun conditionPicker(condition: AssetCondition, select: (AssetCondition) -> Unit) =
    assetPicker("Condition", condition.name, AssetCondition.entries.map { it.name to it.name.lowercase() }) { select(AssetCondition.valueOf(it)) }

@Composable
internal fun assetField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true)
}

@Composable
internal fun assetPicker(label: String, selection: String?, choices: List<Pair<String, String>>, enabled: Boolean = true, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton({ expanded = true }, enabled = enabled && choices.isNotEmpty()) {
            Text(choices.firstOrNull { it.first == selection }?.second?.let { "$label · $it" } ?: label)
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (value, description) ->
                DropdownMenuItem(text = { Text(description) }, onClick = { expanded = false; select(value) })
            }
        }
    }
}

internal fun String.optional() = trim().ifEmpty { null }
private fun AssetInventoryState.locationChoices() = locations.filter { it.lifecycleState == "ACTIVE" }
    .map { it.storageLocationId to "${it.code} · ${it.name}" }
private val assetRecordId = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

/** Read-only snapshot; selecting a row reloads the authoritative passport. */
@Composable
fun AssetDueReturnsSection(queue: AssetDueReturnQueueDto?, busy: Boolean, refresh: () -> Unit,
    more: (String, String, String) -> Unit, select: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Expected Asset returns", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(refresh, enabled = !busy) { Text("Load expected returns") }
        queue?.let { result ->
            Text("As of ${result.asOf} · Server time")
            if (result.items.isEmpty()) Text("No current custody with a recorded return date.")
            result.items.forEach { entry ->
                Text(listOfNotNull(entry.assetCode, entry.assetName).joinToString(" · "))
                Text("${entry.employeeCode} · ${entry.employeeName}")
                Text("${if (entry.overdue) "Overdue" else "Expected"} · ${entry.expectedReturnAt}",
                    color = if (entry.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                entry.workOrderId?.let { Text("Work · $it") }
                OutlinedButton({ select(entry.assetId) }, enabled = !busy) { Text("Open ${entry.assetCode}") }
            }
            val due = result.nextExpectedReturnAt
            val code = result.nextAssetCode
            val id = result.nextAssetId
            if (result.limited && due != null && code != null && id != null) {
                OutlinedButton({ more(due, code, id) }, enabled = !busy) { Text("Load more expected returns") }
            }
        }
    }
}
