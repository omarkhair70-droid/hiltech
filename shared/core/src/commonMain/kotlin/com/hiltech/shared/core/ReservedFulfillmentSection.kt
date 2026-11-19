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
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.warehouse.FulfillmentEmployeeChoiceDto
import com.hiltech.shared.core.warehouse.RequirementCandidatesDto
import com.hiltech.shared.core.warehouse.ReservationDto
import com.hiltech.shared.core.warehouse.WarehouseReservationQueueDto
import com.hiltech.shared.core.warehouse.WarehouseWorkRequestDto

interface ReservedFulfillmentUiPort {
    suspend fun recipients(warehouse: String, search: String): List<FulfillmentEmployeeChoiceDto>
    suspend fun fulfill(reservation: ReservationDto, work: WarehouseWorkRequestDto,
        requirement: RequirementCandidatesDto, employee: String, expectedReturn: String?): String
}

@Composable
fun ReservedFulfillmentSection(warehouse: String, data: WarehouseReservationQueueDto, port: ReservedFulfillmentUiPort,
    busy: Boolean, run: (suspend () -> Unit) -> Unit,
    onRecorded: suspend (String) -> Unit = {},
) {
    val actionable = data.reservations.filter { reservation ->
        reservation.state == "ACTIVE" && data.requests.any { work ->
            work.workOrderId == reservation.workOrderId &&
                work.requirements.any { it.demand.requirementId == reservation.requirementId }
        }
    }
    var selected by remember(warehouse) { mutableStateOf<String?>(null) }
    var search by remember(warehouse) { mutableStateOf("") }
    var recipients by remember(warehouse, port) { mutableStateOf<List<FulfillmentEmployeeChoiceDto>>(emptyList()) }
    var loaded by remember(warehouse, port) { mutableStateOf(false) }
    var employee by remember(warehouse, selected) { mutableStateOf<String?>(null) }
    var expectedReturn by remember(warehouse, selected) { mutableStateOf("") }
    var receipt by remember(warehouse) { mutableStateOf<String?>(null) }
    val reservation = actionable.firstOrNull { it.id == selected }
    val work = data.requests.firstOrNull { it.workOrderId == reservation?.workOrderId }
    val requirement = work?.requirements?.firstOrNull { it.demand.requirementId == reservation?.requirementId }
    var confirmed by remember(warehouse, selected, employee, expectedReturn, reservation?.version, work?.workVersion,
        requirement?.demand?.requirementVersion) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Physical handoff", style = MaterialTheme.typography.titleMedium)
        receipt?.let { Text("Recorded movement · $it") }
        if (actionable.isEmpty()) {
            Text("No current reserved resources awaiting handoff.")
            return@Column
        }
        assetPicker("Reservation", selected, actionable.map { r ->
            val context = data.requests.first { it.workOrderId == r.workOrderId }
            val resource = context.requirements.first { it.demand.requirementId == r.requirementId }
                .candidates.firstOrNull { it.id == r.resourceId }?.code ?: r.resourceId
            r.id to listOfNotNull(context.code, resource, r.quantity, r.unitCode).joinToString(" · ")
        }, !busy) { selected = it }
        if (reservation == null || work == null || requirement == null) return@Column
        Text("Work · ${work.code} · ${work.title}")
        assetField("Find active employee by name or code", search) { search = it }
        OutlinedButton({
            run {
                employee = null
                recipients = emptyList()
                confirmed = false
                loaded = false
                recipients = port.recipients(warehouse, search.trim())
                loaded = true
            }
        }, enabled = !busy) { Text("Find eligible employees") }
        if (loaded && recipients.isEmpty()) Text("No active employees match.")
        assetPicker("Accountable employee", employee, recipients.map { it.id to "${it.code} · ${it.name}" }, !busy) { employee = it }
        if (reservation.kind == "ASSET") {
            assetField("Expected return (optional ISO-8601 timestamp)", expectedReturn) { expectedReturn = it }
        } else {
            Text("Issue entire reserved quantity · ${reservation.quantity.orEmpty()} ${reservation.unitCode.orEmpty()}")
        }
        if (!confirmed) {
            OutlinedButton({ confirmed = true }, enabled = !busy && recipients.any { it.id == employee }) {
                Text("Review physical handoff")
            }
        } else {
            Text("Confirm actual physical handoff to ${recipients.firstOrNull { it.id == employee }?.name.orEmpty()}.")
            if (reservation.kind == "ASSET") Text("Expected return · ${expectedReturn.optional() ?: "Not specified"}")
            OutlinedButton({
                val actor = requireNotNull(employee)
                val due = expectedReturn.optional().takeIf { reservation.kind == "ASSET" }
                run {
                    val recorded = port.fulfill(reservation, work, requirement, actor, due)
                    receipt = recorded
                    confirmed = false
                    selected = null
                    onRecorded(recorded)
                }
            }, enabled = !busy && recipients.any { it.id == employee }) {
                Text(if (reservation.kind == "ASSET") "Record checkout" else "Record stock issue")
            }
            OutlinedButton({ confirmed = false }, enabled = !busy) { Text("Cancel") }
        }
    }
}
