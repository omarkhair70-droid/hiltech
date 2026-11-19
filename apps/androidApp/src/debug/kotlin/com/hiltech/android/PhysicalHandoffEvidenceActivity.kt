package com.hiltech.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.hiltech.shared.core.HiltechShell
import com.hiltech.shared.core.HiltechShellState
import com.hiltech.shared.core.ReservedFulfillmentSection
import com.hiltech.shared.core.ReservedFulfillmentUiPort
import com.hiltech.shared.core.identity.IdentityBootstrapDto
import com.hiltech.shared.core.warehouse.*
import kotlinx.coroutines.launch

/** DEBUG-only UI evidence. No API, token, real inventory or mutation. */
class PhysicalHandoffEvidenceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val warehouse = "synthetic-warehouse"
        val expiry = "2099-01-01T00:00:00Z"
        val demand = WorkResourceDemandDto("synthetic-requirement", 3, "MATERIAL", "MATERIAL", "MATERIAL",
            stockItemId = "synthetic-stock", quantity = "8", unitCode = "EA", version = 1)
        val candidate = ResourceCandidateDto("STOCK", "synthetic-stock", "SYNTH-CABLE", "synthetic-bin",
            4, expiry, "8", "EA", emptyList())
        val requirement = RequirementCandidatesDto(demand, "STOCK_RESERVED", listOf(candidate))
        val work = WarehouseWorkRequestDto("synthetic-work", "SYNTH-WORK", "Synthetic installation", 5, listOf(requirement))
        val reservation = ReservationDto("STOCK", "synthetic-reservation", candidate.id, work.workOrderId,
            demand.requirementId, "synthetic-project", "synthetic-site", candidate.locationId,
            "8", "EA", "ACTIVE", 1, expiry, "synthetic-operator")
        val person = FulfillmentEmployeeChoiceDto("synthetic-employee", "SYNTH-EMP", "Synthetic recipient")
        setContent {
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            var queue by remember { mutableStateOf(WarehouseReservationQueueDto(true, listOf(work), listOf(reservation))) }
            val port = remember {
                object : ReservedFulfillmentUiPort {
                    override suspend fun recipients(warehouse: String, search: String): List<FulfillmentEmployeeChoiceDto> {
                        check(warehouse == "synthetic-warehouse")
                        return listOf(person)
                    }

                    override suspend fun fulfill(reservation: ReservationDto, work: WarehouseWorkRequestDto,
                        requirement: RequirementCandidatesDto, employee: String, expectedReturn: String?): String {
                        check(reservation.id == "synthetic-reservation" && reservation.version == 1L)
                        check(work.workVersion == 5L && requirement.demand.requirementVersion == 3L)
                        check(employee == person.id && expectedReturn == null && reservation.quantity == "8")
                        return "SYNTH-HANDOFF-RECEIPT"
                    }
                }
            }
            HiltechShell(
                state = HiltechShellState.SignedIn(
                    identity = IdentityBootstrapDto(
                        identityId = "99999999-9999-4999-8999-999999999999",
                        identityStatus = "ACTIVE",
                        identityVersion = 1,
                        organizations = emptyList(),
                        teams = emptyList(),
                    ),
                ),
                warehouseOperator = {
                    ReservedFulfillmentSection(warehouse, queue, port, busy,
                        run = { action ->
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    try { action() } finally { busy = false }
                                }
                            }
                        },
                        onRecorded = { record ->
                            check(record == "SYNTH-HANDOFF-RECEIPT")
                            queue = queue.copy(reservations = listOf(reservation.copy(state = "FULFILLED", version = 2)))
                        },
                    )
                },
            )
        }
    }
}
