package com.hiltech.shared.core.procurement

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechOperationalTheme
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechSpace

/**
 * Navigation is not business authorization. Each destination keeps the exact
 * host flag and server-authorized APIs owned by its existing operator section.
 */
enum class ProcurementWorkArea(val title: String) {
    REQUIREMENTS("Work requirements"),
    SOURCING("Supplier sourcing"),
    ISSUANCE("PO issuance"),
}

fun visibleProcurementWorkAreas(
    requirementsEnabled: Boolean,
    sourcingEnabled: Boolean,
    issuerEnabled: Boolean,
): List<ProcurementWorkArea> = buildList {
    if (requirementsEnabled) add(ProcurementWorkArea.REQUIREMENTS)
    if (sourcingEnabled) add(ProcurementWorkArea.SOURCING)
    if (issuerEnabled) add(ProcurementWorkArea.ISSUANCE)
}

fun allowedProcurementWorkArea(
    requested: ProcurementWorkArea?,
    available: List<ProcurementWorkArea>,
): ProcurementWorkArea? = requested?.takeIf { it in available } ?: available.firstOrNull()

@Composable
fun ProcurementWorkAreaNavigation(
    areas: List<ProcurementWorkArea>,
    selected: ProcurementWorkArea,
    onSelect: (ProcurementWorkArea) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        areas.forEach { area ->
            val selectedHere = area == selected
            val modifier = Modifier.semantics {
                stateDescription = if (selectedHere) "Current procurement area"
                    else "Open procurement area"
            }
            if (selectedHere) {
                Button(onClick = { onSelect(area) }, modifier = modifier) {
                    Text(area.title)
                }
            } else {
                OutlinedButton(onClick = { onSelect(area) }, modifier = modifier) {
                    Text(area.title)
                }
            }
        }
    }
}

/**
 * Integrated native procurement route for the actual signed-in Desktop mount.
 * Operators are real existing composables supplied by Main.kt; no sample API
 * or synthetic authorization exists in this router.
 */
@Composable
fun ProcurementWorkAreaRouter(
    identityId: String?,
    organizationId: String?,
    requirementsEnabled: Boolean,
    sourcingEnabled: Boolean,
    issuerEnabled: Boolean,
    content: @Composable (ProcurementWorkArea) -> Unit,
) {
    if (identityId.isNullOrBlank() || organizationId.isNullOrBlank()) return
    val available = visibleProcurementWorkAreas(
        requirementsEnabled = requirementsEnabled,
        sourcingEnabled = sourcingEnabled,
        issuerEnabled = issuerEnabled,
    )
    if (available.isEmpty()) return
    var requested by remember(identityId, organizationId, available) {
        mutableStateOf(available.first())
    }
    val selected = allowedProcurementWorkArea(requested, available) ?: return
    var pendingExit by remember(identityId, organizationId, available) {
        mutableStateOf<ProcurementWorkArea?>(null)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HiltechSpace.medium),
    ) {
        HiltechOperationalTheme {
            HiltechPanel(Modifier.fillMaxWidth()) {
                HiltechSectionHeading(
                    index = "HILTECH / PROCUREMENT",
                    title = "Work areas",
                    subtitle = "One task at a time. Server-authorized source and approvals remain authoritative.",
                )
                ProcurementWorkAreaNavigation(
                    areas = available,
                    selected = selected,
                    onSelect = { destination ->
                        if (destination != selected) pendingExit = destination
                    },
                )
            }
        }
        pendingExit?.let { destination ->
            AlertDialog(
                onDismissRequest = { pendingExit = null },
                title = { Text("Change procurement work area?") },
                text = {
                    Text(
                        "Unsubmitted entries in the current area will reset. " +
                            "An in-flight server operation may still finish. " +
                            "Reopen and verify the latest authorized source before retrying."
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        requested = destination
                        pendingExit = null
                    }) { Text("Switch work area") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { pendingExit = null }) {
                        Text("Stay here")
                    }
                },
            )
        }
        content(selected)
    }
}

/** Actual empty state for signed-in requisition context; no fabricated work. */
@Composable
fun ProcurementNoAssignedWorkOrders() {
    HiltechOperationalTheme {
        HiltechPanel(Modifier.fillMaxWidth()) {
            HiltechSectionHeading(
                index = "01 / WORK REQUIREMENTS",
                title = "No assigned work orders",
                subtitle = "The current authorized project context has no WorkOrders to request materials for.",
            )
        }
    }
}
