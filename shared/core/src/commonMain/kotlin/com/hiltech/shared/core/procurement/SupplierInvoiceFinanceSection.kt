package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
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

/** Actual Finance review of restricted supplier invoices; no payment buttons. */
@Composable
fun SupplierInvoiceFinanceSection(
    api: SupplierInvoiceFinanceApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    documentUploader: SupplierInvoiceDocumentUploader? = null,
    pickInvoiceFile: (suspend () -> SupplierInvoiceSelectedFile?)? = null,
    readOnly: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(organizationId) { mutableStateOf(false) }
    var loaded by remember(organizationId) { mutableStateOf(false) }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }
    var invoices by remember(organizationId) {
        mutableStateOf<List<SupplierInvoiceDto>>(emptyList())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Supplier invoices · Finance review", style = MaterialTheme.typography.titleLarge)
        Text(
            "Source-bound PO, warehouse receipt and supplier invoice. Review only; " +
                "no payable or payment is generated.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!readOnly && documentUploader != null && pickInvoiceFile != null) {
            SupplierInvoiceCaptureSection(
                api, documentUploader, pickInvoiceFile, organizationId, installationId,
                newOperationId, onCaptured = {
                    scope.launch {
                        try {
                            invoices = api.list(organizationId, installationId)
                            loaded = true
                        } catch (failure: Exception) {
                            error = failure.message ?: "Invoice captured; refresh review queue."
                        }
                    }
                },
            )
        }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    invoices = api.list(organizationId, installationId)
                    loaded = true
                } catch (failure: Exception) {
                    loaded = false
                    invoices = emptyList()
                    error = failure.message ?: "Finance invoice access unavailable."
                } finally {
                    busy = false
                }
            }
        }) { Text(if (busy) "Loading invoices…" else "Refresh authorized supplier invoices") }
        if (loaded && invoices.isEmpty()) {
            Text("No verified supplier invoices available for review.")
        }
        invoices.forEach { invoice ->
            SupplierInvoiceReviewRow(api, invoice, installationId, newOperationId, readOnly)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SupplierInvoiceReviewRow(
    api: SupplierInvoiceFinanceApiClient,
    invoice: SupplierInvoiceDto,
    installationId: String,
    newOperationId: () -> String,
    readOnly: Boolean,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(invoice.id) { mutableStateOf(false) }
    var error by remember(invoice.id) { mutableStateOf<String?>(null) }
    var snapshot by remember(invoice.id) { mutableStateOf<SupplierInvoiceMatchDto?>(null) }
    var matched by remember(invoice.id) { mutableStateOf<SupplierInvoiceMatchDto?>(null) }
    var handoff by remember(invoice.id) { mutableStateOf<SupplierInvoiceHandoffDto?>(null) }
    var confirmHandoff by remember(invoice.id) { mutableStateOf(false) }
    var matchOperation by remember(invoice.id) { mutableStateOf<String?>(null) }
    var handoffOperation by remember(invoice.id) { mutableStateOf<String?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(invoice.invoiceNumber, style = MaterialTheme.typography.titleMedium)
            Text(invoice.totalAmount + " " + invoice.currency + " · " + invoice.quantity +
                " units · PO " + invoice.purchaseOrderId.take(8),
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        snapshot = api.preview(invoice.id, installationId)
                        matched = null
                        handoff = null
                        confirmHandoff = false
                        matchOperation = null
                        handoffOperation = null
                    } catch (failure: Exception) {
                        snapshot = null
                        error = failure.message ?: "Unable to verify PO/receipt/invoice."
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Inspect current three-way match") }
            snapshot?.let { preview ->
                Text("Result: " + preview.resultCode +
                    " · ordered " + preview.poQuantity +
                    " · accepted " + preview.receivedQuantity +
                    " · invoiced " + preview.invoicedQuantity)
                Text("Quantity variance " + preview.quantityVariance +
                    " · price variance " + preview.priceVariance +
                    " · tax variance " + preview.taxVariance,
                    style = MaterialTheme.typography.bodySmall)
                if (!readOnly && matched == null) {
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val op = matchOperation ?: newOperationId().also {
                                    matchOperation = it
                                }
                                matched = api.recordMatch(
                                    invoice.id, RecordSupplierInvoiceMatchDto(
                                        op, preview.sourceSha256,
                                    ), installationId,
                                )
                                matchOperation = null
                            } catch (failure: Exception) {
                                error = failure.message ?: "Invoice source changed. Refresh preview."
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Record immutable match review") }
                }
            }
            matched?.let { result ->
                Text("Recorded review " + (result.revision ?: 0L) + " · " + result.resultCode)
                if (!readOnly && result.resultCode == "MATCHED" && result.matchEventId != null &&
                    handoff == null
                ) {
                    if (!confirmHandoff) {
                        OutlinedButton(enabled = !busy, onClick = { confirmHandoff = true }) {
                            Text("Prepare Finance review handoff")
                        }
                    } else {
                        Text("This queues a reviewed invoice reference; it does not authorize payment.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    val op = handoffOperation ?: newOperationId().also {
                                        handoffOperation = it
                                    }
                                    handoff = api.handoff(invoice.id,
                                        SupplierInvoiceHandoffRequestDto(op, result.matchEventId),
                                        installationId)
                                    handoffOperation = null
                                    confirmHandoff = false
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Handoff refused; recheck evidence."
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text("Confirm Finance handoff") }
                        OutlinedButton(enabled = !busy, onClick = {
                            confirmHandoff = false
                        }) { Text("Cancel") }
                    }
                }
            }
            handoff?.let { Text("Finance review status: " + it.state) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
