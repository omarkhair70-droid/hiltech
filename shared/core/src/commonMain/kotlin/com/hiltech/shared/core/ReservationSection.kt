package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.warehouse.RequirementCandidatesDto
import com.hiltech.shared.core.warehouse.ReservationDto
import com.hiltech.shared.core.warehouse.ResourceCandidateDto
import com.hiltech.shared.core.warehouse.WarehouseReservationQueueDto
import com.hiltech.shared.core.warehouse.WorkResourceCandidatesDto
import com.hiltech.shared.core.warehouse.WorkResourceDemandDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

interface ReservationUiPort {
    suspend fun candidates(work: String): WorkResourceCandidatesDto
    suspend fun reservations(work: String): List<ReservationDto>
    suspend fun queue(warehouse: String): WarehouseReservationQueueDto
    suspend fun request(work: WorkResourceCandidatesDto, demand: WorkResourceDemandDto, resource: String, quantity: String?, unit: String?)
    suspend fun reserve(work: String, workVersion: Long, demand: WorkResourceDemandDto, candidate: ResourceCandidateDto, expiry: String, quantity: String?)
    suspend fun release(reservation: ReservationDto, reason: String)
}

@Composable
fun ProjectResources(work: String, port: ReservationUiPort) {
    var candidates by remember(work, port) { mutableStateOf<WorkResourceCandidatesDto?>(null) }
    var reservations by remember(work, port) { mutableStateOf(emptyList<ReservationDto>()) }
    val task = rememberReservationTask(work)
    suspend fun refresh() {
        candidates = port.candidates(work)
        reservations = port.reservations(work)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton({ task.launch { refresh() } }, enabled = !task.busy) { Text("Load resource requirements") }
        task.message?.let { Text(it) }
        candidates?.let { data -> ProjectResourceResults(data, reservations, task.busy) { demand, resource, amount, unit ->
            task.launch {
                port.request(data, demand, resource, amount, unit)
                refresh()
            }
        } }
    }
}

@Composable
fun ProjectResourceResults(
    data: WorkResourceCandidatesDto, reservations: List<ReservationDto>, busy: Boolean,
    request: (WorkResourceDemandDto, String, String?, String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (data.requirements.isEmpty()) Text("No resource requirements")
            data.requirements.forEach { requirement ->
                ResourceRequirement(requirement)
                if (data.canRequest && requirement.demand.version == null) {
                    ResourceRequestForm(requirement.demand, busy) { resource, amount, unit ->
                        request(requirement.demand, resource, amount, unit)
                    }
                }
            }
            if (reservations.isNotEmpty()) Text("Warehouse reservations", style = MaterialTheme.typography.titleSmall)
            reservations.forEach { ReservationSummary(it) }
    }
}

@Composable
fun WarehouseReservations(warehouse: String, port: ReservationUiPort) {
    var queue by remember(warehouse, port) { mutableStateOf<WarehouseReservationQueueDto?>(null) }
    var selected by remember(warehouse) { mutableStateOf<String?>(null) }
    val task = rememberReservationTask(warehouse)
    suspend fun refresh() { queue = port.queue(warehouse) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Resource reservations", style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ task.launch { refresh() } }, enabled = !task.busy) { Text("Refresh reservation queue") }
        task.message?.let { Text(it) }
        val data = queue ?: return@Column
        WarehouseReservationResults(data, selected, task.busy, { selected = it }, { work, requirement, candidate, expiry, amount ->
            task.launch {
                port.reserve(work.workOrderId, work.workVersion, requirement.demand, candidate, expiry, amount)
                refresh()
            }
        }, { reservation, reason ->
            task.launch {
                port.release(reservation, reason)
                refresh()
            }
        })
    }
}

@Composable
fun WarehouseReservationResults(
    data: WarehouseReservationQueueDto, selected: String?, busy: Boolean, select: (String) -> Unit,
    reserve: (com.hiltech.shared.core.warehouse.WarehouseWorkRequestDto, RequirementCandidatesDto, ResourceCandidateDto, String, String?) -> Unit,
    release: (ReservationDto, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (data.requests.isEmpty() && data.reservations.isEmpty()) Text("No resource requests or reservations")
        assetPicker("Work request", selected, data.requests.map { it.workOrderId to "${it.code} · ${it.title}" }, select = select)
        data.requests.firstOrNull { it.workOrderId == selected }?.let { work ->
            work.requirements.forEach { requirement ->
                ResourceRequirement(requirement)
                if (data.canReserve) ReserveResourceForm(requirement, busy) { candidate, expiry, amount ->
                    reserve(work, requirement, candidate, expiry, amount)
                }
            }
        }
        data.reservations.forEach { reservation ->
            ReservationSummary(reservation)
            if (data.canReserve && reservation.state == "ACTIVE") {
                var reason by remember(reservation.id, reservation.version) { mutableStateOf("") }
                assetField("Release reason", reason) { reason = it }
                OutlinedButton({ release(reservation, reason.trim()) },
                    enabled = !busy && reason.isNotBlank()) { Text("Release reservation") }
            }
        }
    }
}

@Composable
private fun ResourceRequirement(requirement: RequirementCandidatesDto) {
    Text("${requirement.demand.key} · ${resourceLabel(requirement.reason)}", style = MaterialTheme.typography.titleSmall)
    requirement.demand.quantity?.let { Text("Requested " + listOfNotNull(it, requirement.demand.unitCode).joinToString(" ")) }
    requirement.candidates.forEach { candidate ->
        Text(listOfNotNull(candidate.code, candidate.availableQuantity, candidate.unitCode,
            candidate.reasonCodes.joinToString { resourceLabel(it) }).joinToString(" · "))
    }
}

@Composable
private fun ResourceRequestForm(demand: WorkResourceDemandDto, busy: Boolean, submit: (String, String?, String?) -> Unit) {
    var id by remember(demand.requirementId) { mutableStateOf("") }
    var amount by remember(demand.requirementId) { mutableStateOf("") }
    var unit by remember(demand.requirementId) { mutableStateOf("") }
    val stock = demand.family == "MATERIAL" || demand.type == "MATERIAL"
    assetField(if (stock) "Stock item ID" else "Asset type ID", id) { id = it }
    if (stock) {
        assetField("Required quantity", amount) { amount = it }
        assetField("Canonical unit", unit) { unit = it }
    }
    OutlinedButton({ submit(id.trim(), amount.optional(), unit.optional()) },
        enabled = !busy && id.trim().matches(reservationUuid) && (!stock || (amount.isNotBlank() && unit.isNotBlank()))) {
        Text("Request Warehouse resources")
    }
}

@Composable
private fun ReserveResourceForm(requirement: RequirementCandidatesDto, busy: Boolean, submit: (ResourceCandidateDto, String, String?) -> Unit) {
    val options = requirement.candidates.filter { it.reasonCodes.any { reason -> reason == "ASSET_AVAILABLE" || reason == "STOCK_AVAILABLE" } }
    if (options.isEmpty()) return
    val demand = requirement.demand
    var candidate by remember(demand.requirementId, demand.requirementVersion) { mutableStateOf<String?>(null) }
    var expiry by remember(demand.requirementId, demand.requirementVersion) { mutableStateOf("") }
    var amount by remember(demand.requirementId, demand.requirementVersion) { mutableStateOf("") }
    fun ResourceCandidateDto.key() = "$id:$locationId"
    assetPicker("Resource", candidate, options.map { it.key() to listOfNotNull(it.code, it.locationId, it.availableQuantity).joinToString(" · ") }) {
        candidate = it
    }
    val selected = options.firstOrNull { it.key() == candidate }
    assetField("Expiry (ISO 8601)", expiry) { expiry = it }
    if (selected?.kind == "STOCK") assetField("Quantity · ${selected.unitCode}", amount) { amount = it }
    OutlinedButton({ selected?.let { submit(it, expiry.trim(), amount.optional()) } },
        enabled = !busy && selected != null && expiry.isNotBlank() && (selected.kind != "STOCK" || amount.isNotBlank())) {
        Text("Reserve resource")
    }
}

@Composable
private fun ReservationSummary(reservation: ReservationDto) {
    Text(listOfNotNull(resourceLabel(reservation.kind), resourceLabel(reservation.state), reservation.quantity,
        reservation.unitCode, "Expires ${reservation.expiresAt}").joinToString(" · "))
    Text("Work ${reservation.workOrderId} · Project ${reservation.projectId}")
    reservation.endReason?.let { Text(it) }
}

@Composable
private fun rememberReservationTask(key: String): ReservationTask {
    val scope = rememberCoroutineScope()
    return remember(key) { ReservationTask { action -> scope.launch { action() } } }
}

private class ReservationTask(private val start: (suspend () -> Unit) -> Unit) {
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    fun launch(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        start {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message = failure.message ?: "Resource request failed."
            } finally {
                busy = false
            }
        }
    }
}

private val reservationUuid = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
private fun resourceLabel(code: String) = code.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
