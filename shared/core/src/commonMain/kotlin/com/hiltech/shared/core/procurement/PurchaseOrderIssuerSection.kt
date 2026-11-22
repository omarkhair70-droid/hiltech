package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * Independent company issuer desk; does not inherit confidential buyer quote
 * visibility and does not notify suppliers or receive stock.
 */
@Composable
fun PurchaseOrderIssuerSection(
    api: PurchaseOrderIssueApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    cancellationEnabled: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(organizationId) { mutableStateOf(false) }
    var loaded by remember(organizationId) { mutableStateOf(false) }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }
    var queue by remember(organizationId) {
        mutableStateOf<List<PurchaseOrderIssuerQueueItemDto>>(emptyList())
    }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Company purchase-order issuer", style = MaterialTheme.typography.titleLarge)
        Text("Only approved, current internal candidates. Issuing never dispatches to suppliers.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    queue = api.queue(organizationId, installationId)
                    loaded = true
                } catch (failure: Exception) {
                    queue = emptyList()
                    loaded = false
                    error = failure.message ?: "Issuer authority or purchase policy not available."
                } finally {
                    busy = false
                }
            }
        }) { Text(if (busy) "Loading…" else "Refresh authorized PO candidates") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (cancellationEnabled) {
            PurchaseOrderCancellationSection(
                api = api,
                organizationId = organizationId,
                installationId = installationId,
                newOperationId = newOperationId,
            )
        }
        if (loaded && queue.isEmpty()) Text("No current, approved and unissued candidates.")
        queue.forEach { candidate ->
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(candidate.supplierName, style = MaterialTheme.typography.titleSmall)
                    Text(candidate.quantity + " " + candidate.unitCode + " · " +
                        candidate.total + " " + candidate.currency)
                    Text("Approved policy revision " + candidate.approvalPolicyVersion,
                        style = MaterialTheme.typography.bodySmall)
                    PurchaseOrderIssuerAction(
                        api, candidate, installationId, newOperationId,
                        onIssued = {
                            queue = queue.filterNot { it.candidateId == candidate.candidateId }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PurchaseOrderIssuerAction(
    api: PurchaseOrderIssueApiClient,
    candidate: PurchaseOrderIssuerQueueItemDto,
    installationId: String,
    newOperationId: () -> String,
    onIssued: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(candidate.candidateId) { mutableStateOf(false) }
    var destinations by remember(candidate.candidateId) {
        mutableStateOf<List<PurchaseOrderDeliveryDestinationDto>>(emptyList())
    }
    var fetched by remember(candidate.candidateId) { mutableStateOf(false) }
    var selected by remember(candidate.candidateId) { mutableStateOf<String?>(null) }
    var menu by remember(candidate.candidateId) { mutableStateOf(false) }
    var pendingOp by remember(candidate.candidateId) { mutableStateOf<String?>(null) }
    var error by remember(candidate.candidateId) { mutableStateOf<String?>(null) }
    var issued by remember(candidate.candidateId) { mutableStateOf<PurchaseOrderIssueReceiptDto?>(null) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    destinations = api.destinations(candidate.candidateId, installationId)
                    selected = null
                    pendingOp = null
                    fetched = true
                } catch (failure: Exception) {
                    fetched = false
                    error = failure.message ?: "Cannot inspect authorized delivery locations."
                } finally {
                    busy = false
                }
            }
        }) { Text("Load delivery destinations") }
        if (fetched && destinations.isEmpty()) Text("No valid destination for this work.")
        val target = destinations.firstOrNull { it.storageLocationId == selected }
        if (destinations.isNotEmpty()) {
            OutlinedButton(enabled = !busy, onClick = { menu = true }) {
                Text(target?.let { it.name + " · " + it.code } ?: "Select exact warehouse/site location")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                destinations.forEach { destination ->
                    DropdownMenuItem(
                        text = { Text(destination.name + " · " + destination.kind) },
                        onClick = {
                            selected = destination.storageLocationId
                            pendingOp = null
                            menu = false
                        },
                    )
                }
            }
        }
        if (target != null && issued == null) {
            Text("This creates an internally ISSUED purchase order. Supplier transmission, " +
                "stock receiving and payment are separate.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val op = pendingOp ?: newOperationId().also { pendingOp = it }
                        issued = api.issue(
                            candidate.candidateId,
                            IssuePurchaseOrderDto(op, 1, target.storageLocationId),
                            installationId,
                        )
                        pendingOp = null
                        onIssued()
                    } catch (failure: Exception) {
                        error = failure.message ?: "PO issuance denied. Refresh approval and source."
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Issuing…" else "Issue internal PO to selected destination") }
        }
        issued?.let {
            Text("Issued " + it.code + " · " + it.supplierDispatchState,
                style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
