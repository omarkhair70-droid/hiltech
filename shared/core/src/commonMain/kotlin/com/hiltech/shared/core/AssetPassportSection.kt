package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.hiltech.shared.core.warehouse.AssetCatalogDto
import com.hiltech.shared.core.warehouse.AssetCondition
import com.hiltech.shared.core.warehouse.AssetIdentityDto
import com.hiltech.shared.core.warehouse.AssetMovementDto
import com.hiltech.shared.core.warehouse.AssetPassportDto
import com.hiltech.shared.core.warehouse.AssetTagLookupDto
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
)

data class AssetLookupState(val result: AssetTagLookupDto? = null, val loading: Boolean = false, val message: String? = null)

sealed interface AssetAction {
    data class Search(val query: String) : AssetAction
    data class More(val afterCode: String) : AssetAction
    data class Select(val id: String) : AssetAction
    sealed interface Command : AssetAction
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
    data class Approve(
        val batchId: String, val rowId: String, val version: Long, val code: String,
        val name: String, val ownership: String, val condition: AssetCondition,
    ) : Command
}

@Composable
fun AssetPassportSection(state: AssetInventoryState, warehouseId: String?, dispatch: (AssetAction) -> Unit) {
    var search by remember(warehouseId) { mutableStateOf(state.search) }
    var showRegistration by remember(warehouseId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Asset Passport", style = MaterialTheme.typography.titleLarge)
        if (warehouseId == null) {
            Text("Select a warehouse.")
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(search, { search = it }, modifier = Modifier.weight(1f),
                label = { Text("Search assets") }, placeholder = { Text("Code, serial, manufacturer, model, type or tag") }, singleLine = true)
            OutlinedButton({ dispatch(AssetAction.Search(search.trim())) }, enabled = !state.loading) {
                Text(if (state.loading) "Loading assets" else "Search assets")
            }
        }
        state.message?.let { Text(it) }
        val catalog = state.catalog ?: return@Column
        if (catalog.canRegister) {
            OutlinedButton({ showRegistration = !showRegistration }) {
                Text(if (showRegistration) "Close registration" else "Register an asset")
            }
            if (showRegistration) {
                if (catalog.types.isEmpty()) Text("No active Asset types are configured.")
                else AssetRegistrationForm(state, dispatch)
            }
        }
        if (state.items.isEmpty()) Text("No matching assets")
        assetPicker("Asset", state.selected?.identity?.id, state.items.map {
            it.id to listOfNotNull(it.assetCode, it.name).joinToString(" · ")
        }, !state.loading) { dispatch(AssetAction.Select(it)) }
        state.nextAfterCode?.let { cursor ->
            OutlinedButton({ dispatch(AssetAction.More(cursor)) }, enabled = !state.loading) { Text("Load more assets") }
        }
        state.selected?.let { AssetPassportDetail(it, state, dispatch) }
    }
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
    HorizontalDivider()
    Text(listOfNotNull(asset.assetCode, asset.name).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
    Text("${asset.typeName} · revision ${asset.typeRevision} · ${asset.lifecycle}")
    Text(listOfNotNull(asset.manufacturer, asset.model).joinToString(" · ").ifEmpty { "Manufacturer and model unknown" })
    Text("Serial number · ${asset.serialNumber ?: "Unknown"}")
    if (asset.duplicateSerial) Text("Review duplicate serial number", color = MaterialTheme.colorScheme.error)
    Text("Condition · ${asset.condition.name.lowercase().replaceFirstChar { it.uppercase() }}")
    asset.sourceImportRowId?.let { Text("Import row · $it") }
    Text("Current custody", style = MaterialTheme.typography.titleSmall)
    if (custody == null) Text("Opening custody has not been recorded.")
    custody?.let {
        Text(it.locationCode ?: it.employeeCode?.let { code -> "Employee $code" } ?: "Custody target unavailable")
        it.since?.let { since -> Text("Since $since") }
        it.projectId?.let { id -> Text("Project · $id") }
        it.siteId?.let { id -> Text("Site · $id") }
        it.workOrderId?.let { id -> Text("Work order · $id") }
    }
    AssetTags(passport, state.loading, dispatch)
    if (passport.canInitialize || passport.canCorrect) AssetCustodyForm(passport, state, dispatch)
    Text("Movement history", style = MaterialTheme.typography.titleSmall)
    val locationCodes = state.locations.associate { it.storageLocationId to it.code }
    if (state.movements.isEmpty()) Text("No movements recorded")
    state.movements.forEach {
        val from = it.fromLocationId?.let { id -> locationCodes[id] ?: id } ?: it.fromEmployeeId
        val to = it.toLocationId?.let { id -> locationCodes[id] ?: id } ?: it.toEmployeeId
        Text("${it.type.replace('_', ' ')} · ${it.occurredAt}")
        if (from != null || to != null) Text(listOfNotNull(from, to).joinToString(" → "))
        it.reason?.let { reason -> Text(reason) }
        it.correctedMovementId?.let { id -> Text("Corrects $id") }
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
        }
    }
}

@Composable
private fun conditionPicker(condition: AssetCondition, select: (AssetCondition) -> Unit) =
    assetPicker("Condition", condition.name, AssetCondition.entries.map { it.name to it.name.lowercase() }) { select(AssetCondition.valueOf(it)) }

@Composable
private fun assetField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true)
}

@Composable
private fun assetPicker(label: String, selection: String?, choices: List<Pair<String, String>>, enabled: Boolean = true, select: (String) -> Unit) {
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

private fun String.optional() = trim().ifEmpty { null }
private fun AssetInventoryState.locationChoices() = locations.filter { it.lifecycleState == "ACTIVE" }
    .map { it.storageLocationId to "${it.code} · ${it.name}" }
private val assetRecordId = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
