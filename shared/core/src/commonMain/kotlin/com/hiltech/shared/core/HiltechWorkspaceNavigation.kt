package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * Workspace *navigation*, never a permission or purchasing-authority decision.
 * Host feature flags determine whether procurement is offered; existing server
 * identity/organization/rights checks remain the sole access authority.
 */
enum class HiltechWorkspace(val title: String) {
    OVERVIEW("Overview"),
    PROCUREMENT("Procurement"),
}

fun visibleHiltechWorkspaces(
    signedIn: Boolean,
    procurementFeatureEnabled: Boolean,
    activeOrganizationAvailable: Boolean,
): List<HiltechWorkspace> =
    if (signedIn && procurementFeatureEnabled && activeOrganizationAvailable) {
        listOf(HiltechWorkspace.OVERVIEW, HiltechWorkspace.PROCUREMENT)
    } else {
        listOf(HiltechWorkspace.OVERVIEW)
    }

fun allowedHiltechWorkspace(
    requested: HiltechWorkspace,
    available: List<HiltechWorkspace>,
): HiltechWorkspace =
    requested.takeIf { it in available } ?: HiltechWorkspace.OVERVIEW

@Composable
fun HiltechWorkspaceNavigation(
    workspaces: List<HiltechWorkspace>,
    selected: HiltechWorkspace,
    onSelect: (HiltechWorkspace) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        workspaces.forEach { workspace ->
            val isSelected = selected == workspace
            val semantics = Modifier.semantics {
                stateDescription = if (isSelected) {
                    "Current workspace"
                } else {
                    "Open workspace"
                }
            }
            if (isSelected) {
                Button(onClick = { onSelect(workspace) }, modifier = semantics) {
                    Text(workspace.title)
                }
            } else {
                OutlinedButton(onClick = { onSelect(workspace) }, modifier = semantics) {
                    Text(workspace.title)
                }
            }
        }
    }
}
