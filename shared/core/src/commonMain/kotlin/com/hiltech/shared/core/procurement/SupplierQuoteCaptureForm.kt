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

/** Host-selected bytes are never saved to a local purchasing queue. */
data class SupplierQuotePickedFile(
    val fileName: String,
    val contentType: String,
    val sha256: String,
    val bytes: ByteArray,
    val capturedAt: String,
)

/** Online-only, buyer-restricted quotation capture; never issues a PO. */
@Composable
fun SupplierQuoteCaptureForm(
    api: SupplierSourcingApiClient,
    uploader: SupplierQuoteDocumentUploader,
    pickFile: suspend () -> SupplierQuotePickedFile?,
    rfq: SupplierRfqDto,
    suppliers: List<SourcingSupplierCandidateDto>,
    installationId: String,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var supplierId by remember(rfq.id) { mutableStateOf<String?>(null) }
    var menu by remember(rfq.id) { mutableStateOf(false) }
    var reference by remember(rfq.id) { mutableStateOf("") }
    var currency by remember(rfq.id) { mutableStateOf("EGP") }
    var quantity by remember(rfq.id) { mutableStateOf(rfq.capturedQuantity) }
    var unit by remember(rfq.id) { mutableStateOf(rfq.unitCode) }
    var price by remember(rfq.id) { mutableStateOf("") }
    var tax by remember(rfq.id) { mutableStateOf("0") }
    var days by remember(rfq.id) { mutableStateOf("") }
    var validUntil by remember(rfq.id) { mutableStateOf("") }
    var delivery by remember(rfq.id) { mutableStateOf("") }
    var payment by remember(rfq.id) { mutableStateOf("") }
    var warranty by remember(rfq.id) { mutableStateOf("") }
    var document by remember(rfq.id) { mutableStateOf<VerifiedSupplierQuoteDocument?>(null) }
    var pendingOperation by remember(rfq.id) { mutableStateOf<String?>(null) }
    var busy by remember(rfq.id) { mutableStateOf(false) }
    var status by remember(rfq.id) { mutableStateOf("") }
    var error by remember(rfq.id) { mutableStateOf("") }
    val selected = suppliers.firstOrNull { it.supplierOrganizationId == supplierId }
    val decimal = Regex("^\\d+(\\.\\d+)?$")

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Record supplier quotation", style = MaterialTheme.typography.titleSmall)
        Text("Only verified private documents are accepted. No supplier award or PO.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            enabled = rfq.sourceCurrent && !busy && suppliers.isNotEmpty(),
            onClick = { menu = true },
        ) { Text(selected?.displayName ?: "Choose linked supplier") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            suppliers.forEach { supplier ->
                DropdownMenuItem(
                    text = { Text(supplier.displayName) },
                    onClick = {
                        if (supplierId != supplier.supplierOrganizationId) {
                            supplierId = supplier.supplierOrganizationId
                            document = null
                            pendingOperation = null
                        }
                        menu = false
                    },
                )
            }
        }
        OutlinedTextField(reference, { reference = it; pendingOperation = null },
            label = { Text("Supplier quote reference") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(quantity, { quantity = it; pendingOperation = null },
            label = { Text("Quoted quantity") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(unit, { unit = it; pendingOperation = null },
            label = { Text("Quoted unit") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(currency, { currency = it.uppercase(); pendingOperation = null },
            label = { Text("ISO currency") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(price, { price = it; pendingOperation = null },
            label = { Text("Unit price") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(tax, { tax = it; pendingOperation = null },
            label = { Text("Tax amount") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(days, { days = it; pendingOperation = null },
            label = { Text("Delivery days (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(validUntil, { validUntil = it; pendingOperation = null },
            label = { Text("Valid until YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(delivery, { delivery = it; pendingOperation = null },
            label = { Text("Delivery terms") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(payment, { payment = it; pendingOperation = null },
            label = { Text("Payment terms") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(warranty, { warranty = it; pendingOperation = null },
            label = { Text("Warranty terms") }, modifier = Modifier.fillMaxWidth())

        OutlinedButton(
            enabled = rfq.sourceCurrent && selected != null && !busy,
            onClick = {
                val supplier = selected ?: return@OutlinedButton
                busy = true
                error = ""
                scope.launch {
                    try {
                        val selectedFile = pickFile()
                        if (selectedFile != null) {
                            document = null
                            pendingOperation = null
                            document = uploader.upload(
                                SupplierQuoteDocumentUploadInput(
                                    rfqId = rfq.id,
                                    supplierOrganizationId = supplier.supplierOrganizationId,
                                    slotOperationId = newOperationId(),
                                    reserveOperationId = newOperationId(),
                                    finalizeOperationId = newOperationId(),
                                    contentType = selectedFile.contentType,
                                    originalFileName = selectedFile.fileName,
                                    sha256 = selectedFile.sha256,
                                    bytes = selectedFile.bytes,
                                    capturedAt = selectedFile.capturedAt,
                                ),
                                installationId,
                            )
                            status = "Restricted quotation document verified READY."
                        }
                    } catch (failure: Exception) {
                        error = failure.message ?: "Quotation document verification failed."
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) "Uploading…" else "Choose and verify quotation file") }
        document?.let {
            Text("Verified file: " + it.sizeBytes + " bytes",
                style = MaterialTheme.typography.bodySmall)
        }
        val ready = rfq.sourceCurrent && !busy && selected != null && document != null &&
            reference.isNotBlank() && currency.matches(Regex("^[A-Z]{3}$")) &&
            quantity.matches(decimal) && unit.isNotBlank() &&
            price.matches(decimal) && tax.matches(decimal) &&
            (days.isBlank() || days.toIntOrNull()?.let { n -> n >= 0 } == true)
        OutlinedButton(enabled = ready, onClick = {
            val supplier = selected ?: return@OutlinedButton
            val file = document ?: return@OutlinedButton
            busy = true
            error = ""
            scope.launch {
                try {
                    val current = api.comparison(rfq.id, installationId)
                    val revision = (current.comparison.latestBySupplier
                        .firstOrNull { it.supplierOrganizationId == supplier.supplierOrganizationId }
                        ?.revisionNumber ?: 0) + 1
                    val operation = pendingOperation ?: newOperationId().also {
                        pendingOperation = it
                    }
                    val receipt = api.recordQuote(
                        rfq.id,
                        RecordSupplierQuoteDto(
                            operationId = operation,
                            supplierOrganizationId = supplier.supplierOrganizationId,
                            expectedRevisionNumber = revision,
                            supplierQuoteReference = reference.trim(),
                            currency = currency.trim(),
                            quotedQuantity = quantity.trim(),
                            quotedUnitCode = unit.trim(),
                            unitPrice = price.trim(),
                            taxAmount = tax.trim(),
                            deliveryDays = days.takeIf { it.isNotBlank() }?.toInt(),
                            validUntil = validUntil.trim().takeIf { it.isNotBlank() },
                            deliveryTerms = delivery.trim().takeIf { it.isNotBlank() },
                            paymentTerms = payment.trim().takeIf { it.isNotBlank() },
                            warrantyTerms = warranty.trim().takeIf { it.isNotBlank() },
                            sourceEvidenceRef = file.evidenceReference,
                            sourceSha256 = file.sha256,
                        ),
                        installationId,
                    )
                    pendingOperation = null
                    document = null
                    status = "Recorded quote revision " + receipt.revisionNumber +
                        ". No supplier was awarded and no PO was issued."
                } catch (failure: Exception) {
                    error = failure.message ?: "Quote was not recorded."
                } finally {
                    busy = false
                }
            }
        }) { Text("Record verified quotation") }
        if (!rfq.sourceCurrent) {
            Text("Source revision changed — writes blocked.", color = MaterialTheme.colorScheme.error)
        }
        if (status.isNotEmpty()) Text(status)
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
