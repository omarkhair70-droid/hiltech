package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/**
 * Native Finance-controlled supplier invoice upload and capture.
 * No opaque document references; the private object MUST reach READY.
 */
@Composable
fun SupplierInvoiceCaptureSection(
    api: SupplierInvoiceFinanceApiClient,
    uploader: SupplierInvoiceDocumentUploader,
    pickInvoiceFile: suspend () -> SupplierInvoiceSelectedFile?,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    onCaptured: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(organizationId) { mutableStateOf(false) }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }
    var eligible by remember(organizationId) {
        mutableStateOf<List<InvoicablePurchaseOrderDto>>(emptyList())
    }
    var loaded by remember(organizationId) { mutableStateOf(false) }
    var selectedId by remember(organizationId) { mutableStateOf<String?>(null) }
    var poMenu by remember(organizationId) { mutableStateOf(false) }
    var file by remember(selectedId) { mutableStateOf<SupplierInvoiceSelectedFile?>(null) }
    var verified by remember(selectedId) { mutableStateOf<VerifiedSupplierInvoiceDocument?>(null) }
    var slotOp by remember(selectedId) { mutableStateOf<String?>(null) }
    var reserveOp by remember(selectedId) { mutableStateOf<String?>(null) }
    var finalizeOp by remember(selectedId) { mutableStateOf<String?>(null) }
    var captureOp by remember(selectedId) { mutableStateOf<String?>(null) }
    var invoiceNumber by remember(selectedId) { mutableStateOf("") }
    var invoiceDate by remember(selectedId) { mutableStateOf("") }
    var quantity by remember(selectedId) { mutableStateOf("") }
    var unitPrice by remember(selectedId) { mutableStateOf("") }
    var tax by remember(selectedId) { mutableStateOf("") }
    var captured by remember(selectedId) { mutableStateOf<SupplierInvoiceDto?>(null) }
    val selected = eligible.firstOrNull { it.purchaseOrderId == selectedId }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Capture a verified supplier invoice", style = MaterialTheme.typography.titleMedium)
        Text("Choose a current PO. Upload a private PDF/image and enter its exact commercial values. Capture is a Finance record, not payment approval.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    eligible = api.eligibleOrders(organizationId, installationId)
                    loaded = true
                    if (eligible.none { it.purchaseOrderId == selectedId }) selectedId = null
                } catch (failure: Exception) {
                    loaded = false
                    eligible = emptyList()
                    error = failure.message ?: "Finance is not authorized for PO invoices."
                } finally { busy = false }
            }
        }) { Text(if (busy) "Loading…" else "Load authorized supplier POs") }
        if (loaded && eligible.isEmpty()) Text("No current POs eligible for invoice capture.")
        if (eligible.isNotEmpty()) {
            OutlinedButton(enabled = !busy, onClick = { poMenu = true }) {
                Text(selected?.let { it.poCode + " · " + it.currency } ?: "Choose purchase order")
            }
            DropdownMenu(expanded = poMenu, onDismissRequest = { poMenu = false }) {
                eligible.forEach { po ->
                    DropdownMenuItem(
                        text = { Text(po.poCode + " · " + po.quantity + " " + po.currency) },
                        onClick = {
                            selectedId = po.purchaseOrderId
                            poMenu = false
                            quantity = po.quantity
                            unitPrice = po.unitPrice
                            tax = po.taxAmount
                            file = null
                            verified = null
                            captured = null
                        },
                    )
                }
            }
        }
        if (selected != null && captured == null) {
            Text("PO " + selected.poCode + " · supplier " +
                selected.supplierOrganizationId.take(8) + " · " + selected.currency,
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = invoiceNumber,
                onValueChange = { invoiceNumber = it; captureOp = null },
                label = { Text("Exact supplier invoice number") })
            OutlinedTextField(value = invoiceDate,
                onValueChange = { invoiceDate = it; captureOp = null },
                label = { Text("Invoice date YYYY-MM-DD") })
            OutlinedTextField(value = quantity,
                onValueChange = { quantity = it; captureOp = null },
                label = { Text("Invoiced quantity") })
            OutlinedTextField(value = unitPrice,
                onValueChange = { unitPrice = it; captureOp = null },
                label = { Text("Invoice unit price (" + selected.currency + ")") })
            OutlinedTextField(value = tax,
                onValueChange = { tax = it; captureOp = null },
                label = { Text("Invoice tax (" + selected.currency + ")") })
            OutlinedButton(enabled = !busy && verified == null, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        file = pickInvoiceFile()
                        slotOp = null; reserveOp = null; finalizeOp = null
                    } catch (failure: Exception) {
                        error = failure.message ?: "Unable to open the local invoice document."
                    } finally { busy = false }
                }
            }) { Text(file?.originalFileName ?: "Choose private invoice PDF/image") }
            val selectedFile = file
            if (selectedFile != null && verified == null) {
                Text(selectedFile.originalFileName + " · digest " +
                    selectedFile.sha256.take(12), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val sOp = slotOp ?: newOperationId().also { slotOp = it }
                            val rOp = reserveOp ?: newOperationId().also { reserveOp = it }
                            val fOp = finalizeOp ?: newOperationId().also { finalizeOp = it }
                            verified = uploader.upload(
                                SupplierInvoiceDocumentUploadInput(
                                    selected.purchaseOrderId, sOp, rOp, fOp,
                                    selectedFile.contentType, selectedFile.originalFileName,
                                    selectedFile.sha256, selectedFile.bytes, selectedFile.capturedAt,
                                ), installationId,
                            )
                            file = null
                            slotOp = null; reserveOp = null; finalizeOp = null
                        } catch (failure: Exception) {
                            error = failure.message ?: "Private Evidence did not become READY."
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "Verifying…" else "Upload and verify invoice document") }
            }
            verified?.let { evidence ->
                Text("READY private invoice evidence · " + evidence.evidenceId.take(8),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy && invoiceNumber.trim().length >= 2 &&
                    Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(invoiceDate) &&
                    quantity.isNotBlank() && unitPrice.isNotBlank() && tax.isNotBlank(),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val op = captureOp ?: newOperationId().also { captureOp = it }
                                captured = api.capture(
                                    selected.purchaseOrderId,
                                    CaptureSupplierInvoiceDto(
                                        op, evidence.documentSlotId, evidence.evidenceId,
                                        invoiceNumber.trim(), invoiceDate.trim(), quantity,
                                        selected.currency, unitPrice, tax,
                                    ), installationId,
                                )
                                captureOp = null
                                onCaptured()
                            } catch (failure: Exception) {
                                error = failure.message ?: "Invoice capture denied; check READY evidence, duplicate or changed PO."
                            } finally { busy = false }
                        }
                    },
                ) { Text(if (busy) "Recording…" else "Capture verified supplier invoice") }
            }
        }
        captured?.let {
            Text("Invoice " + it.invoiceNumber + " recorded. Review its PO/receipt match below.")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
