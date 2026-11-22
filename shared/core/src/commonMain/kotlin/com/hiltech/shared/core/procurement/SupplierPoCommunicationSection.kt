package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Attested external transmission is not a message sent by HILTECH. */
@Composable
fun SupplierPoCommunicationSection(
    issued: PurchaseOrderIssueApiClient,
    communications: SupplierPoCommunicationApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    currentInstant: () -> String,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(organizationId) { mutableStateOf(false) }
    var rows by remember(organizationId) { mutableStateOf<List<PurchaseOrderLifecycleSummaryDto>>(emptyList()) }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Supplier dispatch / acknowledgement evidence",
            style = MaterialTheme.typography.titleMedium)
        Text("Only record a communication verified outside HILTECH using a real provider reference. This screen never sends a PO to a supplier.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; error = null
            scope.launch {
                try { rows = issued.issuedOrders(organizationId, installationId) }
                catch (failure: Exception) {
                    rows = emptyList()
                    error = failure.message ?: "Current issuer authority not available."
                } finally { busy = false }
            }
        }) { Text(if (busy) "Loading…" else "Refresh issued POs") }
        rows.filter { it.effectiveState == "ISSUED" }.forEach { row ->
            SupplierPoCommunicationAction(
                row, communications, installationId, newOperationId, currentInstant,
                afterAttest = {
                    scope.launch {
                        try { rows = issued.issuedOrders(organizationId, installationId) }
                        catch (_: Exception) {}
                    }
                },
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SupplierPoCommunicationAction(
    order: PurchaseOrderLifecycleSummaryDto,
    api: SupplierPoCommunicationApiClient,
    installationId: String,
    newOperationId: () -> String,
    currentInstant: () -> String,
    afterAttest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(order.id) { mutableStateOf(false) }
    var loaded by remember(order.id) { mutableStateOf(false) }
    var history by remember(order.id) { mutableStateOf<List<SupplierPoCommunicationDto>>(emptyList()) }
    var error by remember(order.id) { mutableStateOf<String?>(null) }
    var ref by remember(order.id) { mutableStateOf("") }
    var channel by remember(order.id) { mutableStateOf("EMAIL") }
    var menu by remember(order.id) { mutableStateOf(false) }
    var pendingOperation by remember(order.id) { mutableStateOf<String?>(null) }
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(order.code + " · " + order.supplierDispatchState,
                style = MaterialTheme.typography.titleSmall)
            OutlinedButton(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        history = api.history(order.id, installationId)
                        loaded = true
                    } catch (failure: Exception) {
                        loaded = false
                        error = failure.message ?: "Communication history unavailable."
                    } finally { busy = false }
                }
            }) { Text("Verify current communication history") }
            if (loaded) {
                history.forEach { event ->
                    Text(event.eventType + " · " + event.channel + " · " + event.externalReference,
                        style = MaterialTheme.typography.bodySmall)
                }
                val type = when {
                    history.none { it.eventType == "EXTERNAL_DISPATCH_ATTESTED" } ->
                        "EXTERNAL_DISPATCH_ATTESTED"
                    history.none { it.eventType == "SUPPLIER_ACK_ATTESTED" } ->
                        "SUPPLIER_ACK_ATTESTED"
                    else -> null
                }
                if (type != null) {
                    Text(if (type == "EXTERNAL_DISPATCH_ATTESTED")
                        "Record verified dispatch evidence" else "Record verified supplier acknowledgement",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = ref, onValueChange = {
                        ref = it; pendingOperation = null
                    }, label = { Text("Verified external provider/reference ID") })
                    OutlinedButton(enabled = !busy, onClick = { menu = true }) {
                        Text("Channel: " + channel)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf("EMAIL", "SUPPLIER_PORTAL", "HAND_DELIVERY",
                            "OTHER_VERIFIED_CHANNEL").forEach { value ->
                            DropdownMenuItem(text = { Text(value) }, onClick = {
                                channel = value; pendingOperation = null; menu = false
                            })
                        }
                    }
                    OutlinedButton(enabled = !busy && ref.trim().length >= 8,
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                try {
                                    val op = pendingOperation ?: newOperationId().also {
                                        pendingOperation = it
                                    }
                                    val entry = api.attest(
                                        order.id, AttestSupplierPoCommunicationDto(
                                            op, order.version, type, channel, ref.trim(),
                                            currentInstant(),
                                        ), installationId,
                                    )
                                    if (entry.eventType != type) error("Unexpected attestation result")
                                    pendingOperation = null
                                    ref = ""
                                    history = api.history(order.id, installationId)
                                    afterAttest()
                                } catch (failure: Exception) {
                                    error = failure.message ?: "PO version or issuer authority changed."
                                } finally { busy = false }
                            }
                        },
                    ) { Text(if (busy) "Saving…" else "Save verified external communication") }
                } else {
                    Text("Dispatch and acknowledgement are both recorded.")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
