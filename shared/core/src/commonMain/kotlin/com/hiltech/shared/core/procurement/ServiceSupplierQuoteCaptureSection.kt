package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
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
 * Actual online buyer entry for non-stock supplier quotes. A supplier is
 * selected from the canonical registry and the native host supplies real
 * bounded bytes. A successful quote is neither an award nor an automatic send.
 */
@Composable
fun ServiceSupplierQuoteCaptureSection(
    sourcing: ServiceSupplierSourcingApiClient,
    supplierRegistry: SupplierMasterDataApiClient,
    uploader: ServiceSupplierQuoteUploader,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    pickQuoteFile: suspend () -> ServiceSelectedFile?,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(organizationId) { mutableStateOf(false) }
    var error by remember(organizationId) { mutableStateOf<String?>(null) }
    var confirmation by remember(organizationId) { mutableStateOf<String?>(null) }
    var eligible by remember(organizationId) { mutableStateOf<List<ServiceEligibleNeedDto>>(emptyList()) }
    var rfqId by remember(organizationId) { mutableStateOf<String?>(null) }
    var supplierSearch by remember(organizationId) { mutableStateOf("") }
    var suppliers by remember(organizationId) { mutableStateOf<List<SupplierRegistryCandidateDto>>(emptyList()) }
    var selectedSupplier by remember(organizationId) { mutableStateOf<SupplierRegistryCandidateDto?>(null) }
    var reference by remember(organizationId) { mutableStateOf("") }
    var revision by remember(organizationId) { mutableStateOf("1") }
    var currency by remember(organizationId) { mutableStateOf("EGP") }
    var amount by remember(organizationId) { mutableStateOf("") }
    var tax by remember(organizationId) { mutableStateOf("0") }
    var terms by remember(organizationId) { mutableStateOf("") }
    var validUntil by remember(organizationId) { mutableStateOf("") }
    var comparison by remember(organizationId) { mutableStateOf<List<ServiceSupplierQuoteDto>>(emptyList()) }
    val money = Regex("^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,6})?$")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Supplier quotes for external services", style = MaterialTheme.typography.titleMedium)
            Text("Upload the original supplier PDF/image. All supplier and document permissions are enforced by the server.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try { eligible = sourcing.eligibleNeeds(organizationId, installationId) }
                    catch (e: Exception) { error = e.message ?: "No sourcing permission." }
                    finally { busy = false }
                }
            }) { Text("Load technically reviewed service needs") }
            eligible.forEach { need ->
                OutlinedButton(enabled = !busy && need.sourceCurrent, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            rfqId = need.rfqId ?: sourcing.open(
                                need.needId,
                                OpenServiceRfqDto(newOperationId(), need.needVersion),
                                installationId,
                            ).rfq.id
                            comparison = sourcing.comparison(requireNotNull(rfqId), installationId)
                            selectedSupplier = null
                        } catch (e: Exception) { error = e.message ?: "Service sourcing source changed." }
                        finally { busy = false }
                    }
                }) { Text("Source · " + need.specification.take(75)) }
            }
            rfqId?.let { activeRfq ->
                Text("RFQ " + activeRfq.take(8), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = supplierSearch, onValueChange = { supplierSearch = it; selectedSupplier = null },
                    label = { Text("Find a real registered supplier by name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(enabled = !busy && supplierSearch.trim().length in 2..80,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                suppliers = supplierRegistry.search(
                                    organizationId, supplierSearch.trim(), installationId)
                            } catch (e: Exception) {
                                error = e.message ?: "Supplier directory unavailable."
                            } finally { busy = false }
                        }
                    },
                ) { Text("Search company supplier registry") }
                suppliers.forEach { supplier ->
                    OutlinedButton(enabled = !busy, onClick = { selectedSupplier = supplier }) {
                        Text((if (selectedSupplier?.supplierOrganizationId == supplier.supplierOrganizationId) "✓ " else "") +
                            supplier.displayName + " · " + supplier.supplierStatus)
                    }
                }
                selectedSupplier?.let { Text("Selected verified supplier · " + it.displayName) }
                OutlinedTextField(value = reference, onValueChange = { reference = it },
                    label = { Text("Supplier's original quote reference") })
                OutlinedTextField(value = revision, onValueChange = { revision = it },
                    label = { Text("Quote revision number") })
                OutlinedTextField(value = currency, onValueChange = { currency = it.uppercase() },
                    label = { Text("Currency") })
                OutlinedTextField(value = amount, onValueChange = { amount = it },
                    label = { Text("Service amount") })
                OutlinedTextField(value = tax, onValueChange = { tax = it },
                    label = { Text("Tax amount") })
                OutlinedTextField(value = terms, onValueChange = { terms = it },
                    label = { Text("Actual supplier service terms") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = validUntil, onValueChange = { validUntil = it },
                    label = { Text("Valid until (YYYY-MM-DD, optional)") })
                val valid = selectedSupplier != null &&
                    reference.trim().length in 2..96 &&
                    (revision.toIntOrNull()?.let { it >= 1 } == true) &&
                    Regex("[A-Z]{3}").matches(currency) &&
                    money.matches(amount) && money.matches(tax) &&
                    terms.trim().length in 12..2000 &&
                    (validUntil.isBlank() || Regex("\\d{4}-\\d{2}-\\d{2}").matches(validUntil))
                OutlinedButton(enabled = !busy && valid, onClick = {
                    busy = true; error = null; confirmation = null
                    scope.launch {
                        try {
                            val file = pickQuoteFile()
                                ?: error("Document selection cancelled; no supplier quote recorded.")
                            val result = uploader.uploadAndRecord(
                                rfqId = activeRfq,
                                supplierOrganizationId = requireNotNull(selectedSupplier).supplierOrganizationId,
                                installationId = installationId,
                                supplierQuoteReference = reference.trim(),
                                revisionNumber = requireNotNull(revision.toIntOrNull()),
                                currency = currency,
                                serviceAmount = amount, taxAmount = tax,
                                serviceTerms = terms.trim(), validUntil = validUntil.ifBlank { null },
                                contentType = file.contentType, originalFileName = file.originalFileName,
                                fileBytes = file.bytes, fileSha256 = file.sha256,
                                capturedAt = file.capturedAt,
                                slotOperationId = newOperationId(),
                                reserveOperationId = newOperationId(),
                                finalizeOperationId = newOperationId(),
                                recordOperationId = newOperationId(),
                            )
                            comparison = sourcing.comparison(activeRfq, installationId)
                            confirmation = "Verified source quote " + result.recorded.quote.id.take(8) +
                                " recorded. Supplier was not contacted or awarded."
                        } catch (e: Exception) {
                            error = e.message ?: "Quote was not recorded; check the actual source."
                        } finally { busy = false }
                    }
                }) { Text("Choose actual quote file and record verified offer") }
                comparison.forEach { quote ->
                    Text(quote.quoteReference + " · " + quote.currency + " " + quote.totalAmount +
                        " · " + (if (quote.sourceCurrent) "Current" else "Source changed"),
                        style = MaterialTheme.typography.bodySmall)
                }
                confirmation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
