package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.warehouse.AssetServiceDetailDto
import com.hiltech.shared.core.warehouse.SafeAssetAvailabilityDto

sealed interface AssetServiceCommand {
    val assetId: String
    val baseVersion: Long

    data class Report(
        override val assetId: String, override val baseVersion: Long, val type: String, val description: String,
        val evidenceId: String?,
    ) : AssetServiceCommand
    data class Transition(
        override val assetId: String, override val baseVersion: Long, val incidentId: String,
        val incidentVersion: Long, val state: String, val resolution: String?, val ownerId: String?,
    ) : AssetServiceCommand
    data class Calibrate(
        override val assetId: String, override val baseVersion: Long, val type: String, val result: String,
        val calibratedAt: String, val validUntil: String?, val provider: String?, val certificateId: String?, val notes: String?,
    ) : AssetServiceCommand
    data class Send(
        override val assetId: String, override val baseVersion: Long, val type: String, val description: String, val provider: String?,
    ) : AssetServiceCommand
    data class Complete(
        override val assetId: String, override val baseVersion: Long, val serviceId: String, val version: Long, val notes: String,
    ) : AssetServiceCommand
}

@Composable
fun AssetServiceQueue(state: AssetInventoryState, dispatch: (AssetAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Asset service queue", style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ dispatch(AssetAction.RefreshServiceQueue) }, enabled = !state.loading) { Text("Refresh service queue") }
        val entries = state.serviceQueue ?: return@Column
        if (entries.isEmpty()) Text("No blocked assets or service due")
        entries.forEach { entry ->
            val info = entry.availability
            val detail = when {
                info.state != "AVAILABLE" -> statusLabel(info.state)
                info.calibrationStatus == "DUE" -> "Calibration due"
                else -> "Maintenance " + statusLabel(info.maintenanceStatus).lowercase()
            }
            OutlinedButton({ dispatch(AssetAction.Select(entry.id)) }, enabled = !state.loading) {
                Text(listOfNotNull(entry.assetCode, entry.name, detail).joinToString(" · "))
            }
            if (info.calibrationStatus in setOf("DUE", "EXPIRED")) info.calibrationDueAt?.let { Text("Calibration due $it") }
            if (info.maintenanceStatus == "DUE") info.maintenanceDueAt?.let { Text("Maintenance due $it") }
            info.reasons.map { availabilityReasonLabel(it.code) }.distinct().forEach { Text(it) }
        }
        state.serviceQueueCursor?.let { cursor ->
            OutlinedButton({ dispatch(AssetAction.MoreServiceQueue(cursor)) }, enabled = !state.loading) { Text("More service records") }
        }
    }
}

@Composable
fun AssetServiceDetail(id: String, state: AssetServiceDetailDto, busy: Boolean, dispatch: (AssetAction) -> Unit) {
    val availability = state.availability
    Text("Availability · ${statusLabel(availability.state)}", style = MaterialTheme.typography.titleMedium)
    Text("Calibration · ${statusLabel(availability.calibrationStatus)}")
    availability.calibrationDueAt?.let { Text("Calibration valid until $it") }
    if (availability.certificateOnFile) Text("Calibration certificate on file")
    Text("Maintenance · ${statusLabel(availability.maintenanceStatus)}")
    availability.maintenanceDueAt?.let { Text("Maintenance due $it") }
    availability.reasons.forEach { reason ->
        Text(availabilityReasonLabel(reason.code))
        val source = listOfNotNull(statusLabel(reason.sourceType), reason.sourceId, reason.sourceVersion?.let { "version $it" })
        Text(source.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
    val version = availability.assetVersion
    val capabilities = state.capabilities
    var panel by remember(id, version) { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (capabilities.canReport) OutlinedButton({ panel = if (panel == "incident") "" else "incident" }, enabled = !busy) { Text("Report incident") }
        if (capabilities.canSend) OutlinedButton({ panel = if (panel == "service") "" else "service" }, enabled = !busy) { Text("Send for service") }
        if (capabilities.canManageService) {
            OutlinedButton({ panel = if (panel == "calibration") "" else "calibration" }, enabled = !busy) { Text("Record calibration") }
        }
    }
    val submit: (AssetServiceCommand) -> Unit = { dispatch(AssetAction.Service(it)) }
    when (panel) {
        "incident" -> ServiceIntakeForm(listOf("DAMAGE", "MISSING", "LOST"), "Evidence record ID (optional)", "Report incident", busy) {
            type, description, evidence -> submit(AssetServiceCommand.Report(id, version, type, description, evidence))
        }
        "service" -> ServiceIntakeForm(listOf("MAINTENANCE", "CALIBRATION"), "Provider reference (optional)", "Send for service", busy) {
            type, description, provider -> submit(AssetServiceCommand.Send(id, version, type, description, provider))
        }
        "calibration" -> CalibrationForm(id, version, busy, submit)
    }
    if (state.incidents.isNotEmpty()) Text("Incidents", style = MaterialTheme.typography.titleSmall)
    state.incidents.forEach { incident ->
        Text("${statusLabel(incident.type)} · ${statusLabel(incident.state)} · ${incident.reportedAt}")
        Text(incident.description)
        incident.resolution?.let { Text("Resolution · $it") }
        incident.investigationOwnerId?.let { Text("Investigation owner · $it") }
        incident.workOrderId?.let { Text("Work order · $it") }
        if (incident.evidenceOnFile) Text("Incident evidence on file")
        if (capabilities.canManageService && incident.state != "CLOSED") {
            var resolution by remember(incident.id, incident.version) { mutableStateOf("") }
            var owner by remember(incident.id, incident.version) { mutableStateOf("") }
            if (incident.state == "OPEN") {
                assetField("Investigation owner ID (optional)", owner) { owner = it }
                OutlinedButton({
                    submit(AssetServiceCommand.Transition(id, version, incident.id, incident.version, "UNDER_REVIEW", null, owner.optional()))
                }, enabled = !busy) { Text("Start review") }
            }
            if (incident.state == "RESOLVED") {
                OutlinedButton({
                    submit(AssetServiceCommand.Transition(id, version, incident.id, incident.version, "CLOSED", null, null))
                }, enabled = !busy) { Text("Close incident") }
            } else {
                assetField("Resolution", resolution) { resolution = it }
                OutlinedButton({
                    submit(AssetServiceCommand.Transition(id, version, incident.id, incident.version, "RESOLVED", resolution.trim(), null))
                }, enabled = !busy && resolution.isNotBlank()) { Text("Resolve incident") }
            }
        }
    }
    if (state.calibrations.isNotEmpty()) Text("Calibration history", style = MaterialTheme.typography.titleSmall)
    state.calibrations.forEach {
        Text("${it.type} · ${statusLabel(it.result)} · ${it.calibratedAt}")
        it.validUntil?.let { due -> Text("Valid until $due") }
        it.providerRef?.let { provider -> Text("Provider · $provider") }
        if (it.certificateOnFile) Text("Certificate on file")
        it.notes?.let { notes -> Text(notes) }
    }
    if (state.maintenance.isNotEmpty()) Text("Service history", style = MaterialTheme.typography.titleSmall)
    state.maintenance.forEach { service ->
        Text("${statusLabel(service.type)} · ${statusLabel(service.state)} · sent ${service.sentAt}")
        Text(service.description)
        service.providerRef?.let { Text("Provider · $it") }
        service.completedAt?.let { Text("Completed $it") }
        service.completionNotes?.let { Text(it) }
        if (capabilities.canManageService && service.state == "IN_PROGRESS") {
            var notes by remember(service.id) { mutableStateOf("") }
            assetField("Completion notes", notes) { notes = it }
            OutlinedButton({
                submit(AssetServiceCommand.Complete(id, version, service.id, service.version, notes.trim()))
            }, enabled = !busy && notes.isNotBlank()) { Text("Complete service") }
        }
    }
}

@Composable
private fun ServiceIntakeForm(
    types: List<String>, referenceLabel: String, action: String, busy: Boolean, submit: (String, String, String?) -> Unit,
) {
    var type by remember(types) { mutableStateOf(types.first()) }
    var description by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    assetPicker("Type", type, types.map { it to statusLabel(it) }) { type = it }
    assetField("Description", description) { description = it }
    assetField(referenceLabel, reference) { reference = it }
    Button({ submit(type, description.trim(), reference.optional()) }, enabled = !busy && description.isNotBlank()) { Text(action) }
}

@Composable
private fun CalibrationForm(id: String, version: Long, busy: Boolean, submit: (AssetServiceCommand) -> Unit) {
    var type by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("PASSED") }
    var calibrated by remember { mutableStateOf("") }
    var until by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("") }
    var certificate by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    assetField("Calibration type", type) { type = it }
    assetPicker("Result", result, listOf("PASSED" to "Passed", "FAILED" to "Failed")) { result = it }
    assetField("Calibrated at (ISO 8601)", calibrated) { calibrated = it }
    if (result == "PASSED") assetField("Valid until (ISO 8601)", until) { until = it }
    assetField("Provider reference (optional)", provider) { provider = it }
    assetField("Certificate evidence ID (optional)", certificate) { certificate = it }
    assetField("Notes (optional)", notes) { notes = it }
    Button({
        submit(AssetServiceCommand.Calibrate(id, version, type.trim(), result, calibrated.trim(),
            until.optional().takeIf { result == "PASSED" }, provider.optional(), certificate.optional(), notes.optional()))
    }, enabled = !busy && type.isNotBlank() && calibrated.isNotBlank() && (result == "FAILED" || until.isNotBlank())) {
        Text("Save calibration record")
    }
}

@Composable
fun SafeAssetAvailability(state: SafeAssetAvailabilityDto) {
    Text("Availability · ${statusLabel(state.state)}")
    state.reasonCodes.forEach { Text(availabilityReasonLabel(it)) }
    if (state.incidentTypes.isNotEmpty()) Text("Active incidents · " + state.incidentTypes.joinToString { statusLabel(it) })
    Text("Calibration · ${statusLabel(state.calibrationStatus)}")
    state.calibrationDueAt?.let { Text("Calibration valid until $it") }
    if (state.certificateOnFile) Text("Calibration certificate on file")
}

private fun statusLabel(value: String) = value.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun availabilityReasonLabel(code: String): String = when (code) {
    "ASSET_RETIRED" -> "Asset retired"
    "CONDITION_UNFIT" -> "Asset condition is unfit"
    "ASSET_TYPE_POLICY_UNAVAILABLE" -> "Asset type policy is unavailable"
    "CALIBRATION_MISSING" -> "Required calibration is missing"
    "CALIBRATION_EXPIRED" -> "Required calibration has expired"
    "CALIBRATION_FAILED" -> "Latest calibration failed"
    "ACTIVE_INCIDENT" -> "An active incident blocks availability"
    "ASSET_IN_EMPLOYEE_CUSTODY" -> "Asset is in employee custody"
    "STORAGE_CUSTODY_UNVERIFIED" -> "Current storage custody is unverified"
    else -> statusLabel(code)
}
