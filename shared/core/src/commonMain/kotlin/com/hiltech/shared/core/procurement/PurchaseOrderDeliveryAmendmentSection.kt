package com.hiltech.shared.core.procurement

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A real issuer-only destination amendment, never a commercial-price edit.
 * Proposal, new human approval and V(N+1) apply are separate actions.
 */
@Composable
fun PurchaseOrderDeliveryAmendmentSection(
    issueApi: PurchaseOrderIssueApiClient,
    api: PurchaseOrderDeliveryAmendmentApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(organizationId) { mutableStateOf(false) }
    var rows by remember(organizationId) {
        mutableStateOf<List<PurchaseOrderLifecycleSummaryDto>>(emptyList())
    }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Approved delivery amendments", style = MaterialTheme.typography.titleMedium)
        Text(
            "Delivery destination only; quote, supplier, quantity and prices are unchanged.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    rows = issueApi.issuedOrders(organizationId, installationId)
                        .filter { it.effectiveState == "ISSUED" &&
                            it.supplierDispatchState == "NOT_SENT" }
                } catch (failure: Exception) {
                    rows = emptyList()
                    error = failure.message ?: "Purchase order history is unavailable."
                } finally {
                    busy = false
                }
            }
        }) { Text("Load current unsent purchase orders") }
        rows.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(row.code + " · v" + row.version + " · " +
                    row.total + " " + row.currency)
                PurchaseOrderDeliveryAmendmentRow(
                    api, row, installationId, newOperationId,
                    onApplied = { applied ->
                        rows = rows.map { old ->
                            if (old.id == applied.purchaseOrderId)
                                old.copy(version = applied.orderVersion)
                            else old
                        }
                    },
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PurchaseOrderDeliveryAmendmentRow(
    api: PurchaseOrderDeliveryAmendmentApiClient,
    order: PurchaseOrderLifecycleSummaryDto,
    installationId: String,
    newOperationId: () -> String,
    onApplied: (PurchaseOrderDeliveryAmendmentAppliedReceiptDto) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(order.id) { mutableStateOf(false) }
    var error by remember(order.id) { mutableStateOf<String?>(null) }
    var destinations by remember(order.id) {
        mutableStateOf<List<PurchaseOrderDeliveryDestinationDto>>(emptyList())
    }
    var selected by remember(order.id) { mutableStateOf<String?>(null) }
    var menuOpen by remember(order.id) { mutableStateOf(false) }
    var reason by remember(order.id) { mutableStateOf("") }
    var proposal by remember(order.id) {
        mutableStateOf<PurchaseOrderDeliveryAmendmentStatusDto?>(null)
    }
    var requested by remember(order.id) { mutableStateOf<String?>(null) }
    var pendingProposalOp by remember(order.id) { mutableStateOf<String?>(null) }
    var pendingApprovalOp by remember(order.id) { mutableStateOf<String?>(null) }
    var pendingApplyOp by remember(order.id) { mutableStateOf<String?>(null) }
    var applied by remember(order.id) {
        mutableStateOf<PurchaseOrderDeliveryAmendmentAppliedReceiptDto?>(null)
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    proposal = api.current(order.id, installationId)
                    destinations = api.destinations(order.id, installationId)
                } catch (failure: Exception) {
                    proposal = null
                    destinations = emptyList()
                    error = failure.message ?: "Cannot read authorized amendment state."
                } finally {
                    busy = false
                }
            }
        }) { Text("Review destination amendment / approval status") }

        if (proposal == null && applied == null && destinations.isNotEmpty()) {
            OutlinedButton(enabled = !busy, onClick = { menuOpen = true }) {
                Text(destinations.firstOrNull { it.storageLocationId == selected }
                    ?.let { it.name + " · " + it.kind } ?: "Choose new delivery destination")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                destinations.forEach { dest ->
                    DropdownMenuItem(
                        text = { Text(dest.name + " · " + dest.code) },
                        onClick = {
                            selected = dest.storageLocationId
                            pendingProposalOp = null
                            menuOpen = false
                        },
                    )
                }
            }
            OutlinedTextField(
                value = reason, onValueChange = {
                    reason = it
                    pendingProposalOp = null
                },
                label = { Text("Reason for location change") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
            )
            if (selected != null) {
                OutlinedButton(
                    enabled = !busy && reason.trim().length in 12..1000,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val op = pendingProposalOp ?: newOperationId().also {
                                    pendingProposalOp = it
                                }
                                api.propose(order.id,
                                    ProposePurchaseOrderDeliveryAmendmentDto(
                                        op, order.version, requireNotNull(selected), reason.trim(),
                                    ), installationId)
                                pendingProposalOp = null
                                proposal = api.current(order.id, installationId)
                            } catch (failure: Exception) {
                                error = failure.message ?: "Amendment proposal denied."
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Record proposed destination for fresh approval") }
            }
        }

        proposal?.let { current ->
            Text("Amendment for PO v" + current.baseVersion + " · " +
                current.approvalState, style = MaterialTheme.typography.bodySmall)
            if (current.approvalState == "NOT_REQUESTED") {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val op = pendingApprovalOp ?: newOperationId().also {
                                pendingApprovalOp = it
                            }
                            val receipt = api.requestApproval(
                                current.proposalId,
                                RequestPurchaseOrderDeliveryAmendmentApprovalDto(op),
                                installationId,
                            )
                            requested = receipt.approvalState
                            pendingApprovalOp = null
                            proposal = api.current(order.id, installationId)
                        } catch (failure: Exception) {
                            error = failure.message ?: "Approval policy unavailable."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Send destination change for independent approval") }
            }
            if (current.approvalState == "APPROVED" && applied == null) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val op = pendingApplyOp ?: newOperationId().also {
                                pendingApplyOp = it
                            }
                            val result = api.apply(
                                current.proposalId,
                                ApplyPurchaseOrderDeliveryAmendmentDto(
                                    op, current.baseVersion,
                                ),
                                installationId,
                            )
                            applied = result
                            pendingApplyOp = null
                            onApplied(result)
                        } catch (failure: Exception) {
                            error = failure.message ?: "Approved change is no longer current."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Apply approved delivery amendment") }
            }
        }
        applied?.let { result ->
            Text("Delivery amended on " + result.code + " · v" + result.orderVersion +
                ". No supplier notice, stock or finance posting.",
                style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
