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
    var busy by remember(organizationId, installationId) { mutableStateOf(false) }
    var error by remember(organizationId, installationId) { mutableStateOf<String?>(null) }
    var confirmation by remember(organizationId, installationId) { mutableStateOf<String?>(null) }
    var eligible by remember(organizationId, installationId) { mutableStateOf<List<ServiceEligibleNeedDto>>(emptyList()) }
    var eligibleLoaded by remember(organizationId, installationId) { mutableStateOf(false) }
    var supplierSearchLoaded by remember(organizationId, installationId) { mutableStateOf(false) }
    var rfqId by remember(organizationId, installationId) { mutableStateOf<String?>(null) }
    var supplierSearch by remember(organizationId, installationId) { mutableStateOf("") }
    var suppliers by remember(organizationId, installationId) { mutableStateOf<List<SupplierRegistryCandidateDto>>(emptyList()) }
    var supplierResultsQuery by remember(organizationId, installationId) { mutableStateOf<String?>(null) }
    var supplierResultsRfq by remember(organizationId, installationId) { mutableStateOf<String?>(null) }
    var supplierSearchGeneration by remember(organizationId, installationId) { mutableStateOf(0L) }
    var selectedSupplier by remember(organizationId, installationId) { mutableStateOf<SupplierRegistryCandidateDto?>(null) }
    var reference by remember(organizationId, installationId) { mutableStateOf("") }
    var revision by remember(organizationId, installationId) { mutableStateOf("1") }
    var currency by remember(organizationId, installationId) { mutableStateOf("EGP") }
    var amount by remember(organizationId, installationId) { mutableStateOf("") }
    var tax by remember(organizationId, installationId) { mutableStateOf("0") }
    var terms by remember(organizationId, installationId) { mutableStateOf("") }
    var validUntil by remember(organizationId, installationId) { mutableStateOf("") }
    var comparison by remember(organizationId, installationId) { mutableStateOf<List<ServiceSupplierQuoteDto>>(emptyList()) }
    val money = Regex("^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,6})?$")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("عروض موردي الخدمات / Service quotes", style = MaterialTheme.typography.titleMedium)
            Text("المصدر الأصلي PDF أو صورة. تسجيل العرض لا يعني إرساله للمورد أو الموافقة عليه. الصلاحيات من السيرفر فقط.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = {
                busy = true; error = null
                // Denied/failed refresh must not leave an old RFQ selectable.
                eligible = emptyList()
                eligibleLoaded = false
                supplierSearchLoaded = false
                rfqId = null
                comparison = emptyList()
                selectedSupplier = null
                supplierSearchGeneration++
                suppliers = emptyList()
                supplierResultsQuery = null
                supplierResultsRfq = null
                scope.launch {
                    try {
                        eligible = sourcing.eligibleNeeds(organizationId, installationId)
                        eligibleLoaded = true
                    }
                    catch (e: Exception) { error = e.message ?: "No sourcing permission." }
                    finally { busy = false }
                }
            }) { Text("الطلبات المراجعة فنيًا والمتاحة للشراء") }
            if(eligibleLoaded && eligible.isEmpty()) {
                Text("لا توجد طلبات خدمات مؤهلة لتسجيل عروض موردين حاليًا.")
            }
            eligible.forEach { need ->
                OutlinedButton(enabled = !busy && need.sourceCurrent, onClick = {
                    busy = true; error = null; confirmation = null
                    // Revoke the old selection *before* network access: if
                    // source loading fails, no previous supplier can be sent
                    // against a different RFQ.
                    supplierSearchGeneration++
                    rfqId = null
                    comparison = emptyList()
                    supplierSearch = ""
                    supplierSearchLoaded = false
                    suppliers = emptyList()
                    supplierResultsQuery = null
                    supplierResultsRfq = null
                    selectedSupplier = null
                    reference = ""
                    revision = "1"
                    currency = "EGP"
                    amount = ""
                    tax = "0"
                    terms = ""
                    validUntil = ""
                    scope.launch {
                        try {
                            val nextRfq = need.rfqId ?: sourcing.open(
                                need.needId,
                                OpenServiceRfqDto(newOperationId(), need.needVersion),
                                installationId,
                            ).rfq.id
                            val freshComparison = sourcing.comparison(nextRfq, installationId)
                            rfqId = nextRfq
                            comparison = freshComparison
                        } catch (e: Exception) { error = e.message ?: "Service sourcing source changed." }
                        finally { busy = false }
                    }
                }) { Text("طلب · " + need.specification.take(75)) }
            }
            rfqId?.let { activeRfq ->
                Text("طلب عروض RFQ " + activeRfq.take(8), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = supplierSearch, onValueChange = {
                        if (it != supplierSearch) {
                            supplierSearch = it
                            supplierSearchLoaded = false
                            supplierSearchGeneration++
                            suppliers = emptyList()
                            supplierResultsQuery = null
                            supplierResultsRfq = null
                            selectedSupplier = null
                        }
                    },
                    label = { Text("ابحث عن مورد مسجل بالاسم / Registered supplier") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                OutlinedButton(enabled = !busy && supplierSearch.trim().length in 2..80,
                    onClick = {
                        val query = supplierSearch.trim()
                        val originRfq = activeRfq
                        supplierSearchGeneration++
                        val generation = supplierSearchGeneration
                        suppliers = emptyList()
                        supplierSearchLoaded = false
                        supplierResultsQuery = null
                        supplierResultsRfq = null
                        selectedSupplier = null
                        busy = true; error = null
                        scope.launch {
                            try {
                                val results = supplierRegistry.search(
                                    organizationId, query, installationId)
                                if (serviceSupplierSearchResponseIsCurrent(
                                        currentGeneration = supplierSearchGeneration,
                                        responseGeneration = generation,
                                        input = supplierSearch,
                                        submittedQuery = query,
                                        activeRfqId = rfqId,
                                        responseRfqId = originRfq,
                                    )
                                ) {
                                    suppliers = results
                                    supplierResultsQuery = query
                                    supplierResultsRfq = originRfq
                                    supplierSearchLoaded = true
                                }
                            } catch (e: Exception) {
                                if (supplierSearchGeneration == generation) {
                                    error = e.message ?: "Supplier directory unavailable."
                                }
                            } finally { busy = false }
                        }
                    },
                ) { Text("بحث في سجل الموردين / Search suppliers") }
                val visibleSuppliers = suppliers.takeIf {
                    serviceSupplierResultsBelongToCurrentQuery(
                        input = supplierSearch,
                        submittedQuery = supplierResultsQuery,
                        activeRfqId = activeRfq,
                        resultRfqId = supplierResultsRfq,
                    )
                }.orEmpty()
                if(supplierSearchLoaded && visibleSuppliers.isEmpty()) {
                    Text("لم يظهر مورد مسجل مطابق لهذا البحث؛ راجع الاسم أو حدّث المصدر.")
                }
                visibleSuppliers.forEach { supplier ->
                    OutlinedButton(enabled = !busy, onClick = { selectedSupplier = supplier }) {
                        Text((if (selectedSupplier?.supplierOrganizationId == supplier.supplierOrganizationId) "✓ " else "") +
                            supplier.displayName + " · " + supplier.supplierStatus)
                    }
                }
                selectedSupplier?.let {
                    Text("المورد المختار من السجل · " + it.displayName +
                        " · الأهلية الفعلية يعيد السيرفر فحصها عند التسجيل.")
                }
                OutlinedTextField(value = reference, onValueChange = { reference = it },
                    label = { Text("رقم عرض السعر الأصلي عند المورد") }, enabled = !busy)
                OutlinedTextField(value = revision, onValueChange = { revision = it },
                    label = { Text("رقم مراجعة عرض السعر") }, enabled = !busy)
                OutlinedTextField(value = currency, onValueChange = { currency = it.uppercase() },
                    label = { Text("Currency") }, enabled = !busy)
                OutlinedTextField(value = amount, onValueChange = { amount = it },
                    label = { Text("قيمة الخدمة") }, enabled = !busy)
                OutlinedTextField(value = tax, onValueChange = { tax = it },
                    label = { Text("الضريبة") }, enabled = !busy)
                OutlinedTextField(value = terms, onValueChange = { terms = it },
                    label = { Text("شروط تقديم الخدمة كما وردت من المورد") },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                OutlinedTextField(value = validUntil, onValueChange = { validUntil = it },
                    label = { Text("صلاحية العرض حتى YYYY-MM-DD (اختياري)") }, enabled = !busy)
                val valid = selectedSupplier?.let { current ->
                    visibleSuppliers.any { it.supplierOrganizationId == current.supplierOrganizationId }
                } == true &&
                    reference.trim().length in 2..96 &&
                    (revision.toIntOrNull()?.let { it >= 1 } == true) &&
                    Regex("[A-Z]{3}").matches(currency) &&
                    money.matches(amount) && money.matches(tax) &&
                    terms.trim().length in 12..2000 &&
                    (validUntil.isBlank() || servicePurchaseDateIsReal(validUntil))
                OutlinedButton(enabled = !busy && valid, onClick = {
                    // The native picker suspends. Freeze the selected actual
                    // server-source context and form before opening it.
                    val chosenSupplierId = requireNotNull(selectedSupplier).supplierOrganizationId
                    val chosenQueryGeneration = supplierSearchGeneration
                    val chosenReference = reference.trim()
                    val chosenRevision = requireNotNull(revision.toIntOrNull())
                    val chosenCurrency = currency
                    val chosenAmount = amount
                    val chosenTax = tax
                    val chosenTerms = terms.trim()
                    val chosenValidUntil = validUntil.ifBlank { null }
                    busy = true; error = null; confirmation = null
                    scope.launch {
                        try {
                            val file = pickQuoteFile()
                                ?: throw IllegalStateException("Document selection cancelled; no supplier quote recorded.")
                            check(rfqId == activeRfq && supplierSearchGeneration == chosenQueryGeneration &&
                                selectedSupplier?.supplierOrganizationId == chosenSupplierId
                            ) { "Supplier source changed while choosing the document; please select it again." }
                            val result = uploader.uploadAndRecord(
                                rfqId = activeRfq,
                                supplierOrganizationId = chosenSupplierId,
                                installationId = installationId,
                                supplierQuoteReference = chosenReference,
                                revisionNumber = chosenRevision,
                                currency = chosenCurrency,
                                serviceAmount = chosenAmount, taxAmount = chosenTax,
                                serviceTerms = chosenTerms, validUntil = chosenValidUntil,
                                contentType = file.contentType, originalFileName = file.originalFileName,
                                fileBytes = file.bytes, fileSha256 = file.sha256,
                                capturedAt = file.capturedAt,
                                slotOperationId = newOperationId(),
                                reserveOperationId = newOperationId(),
                                finalizeOperationId = newOperationId(),
                                recordOperationId = newOperationId(),
                            )
                            confirmation = "تم تسجيل عرض المورد الأصلي " +
                                result.recorded.quote.id.take(8) +
                                " · لم يتم إرساله أو ترسيته تلقائيًا."
                            // Do not leave a successfully recorded original quote
                            // ready to submit again with a fresh operation ID.
                            selectedSupplier = null
                            supplierSearch = ""
                            suppliers = emptyList()
                            supplierResultsQuery = null
                            supplierResultsRfq = null
                            supplierSearchLoaded = false
                            supplierSearchGeneration++
                            reference = ""
                            revision = "1"
                            currency = "EGP"
                            amount = ""
                            tax = "0"
                            terms = ""
                            validUntil = ""
                            try {
                                comparison = sourcing.comparison(activeRfq, installationId)
                            } catch (refreshError: Exception) {
                                error = "تم تسجيل العرض، لكن تعذر تحديث المقارنة؛ أعد تحميل مصدر العروض."
                            }
                        } catch (e: Exception) {
                            error = e.message ?: "Quote was not recorded; check the actual source."
                        } finally { busy = false }
                    }
                }) { Text("اختيار ملف العرض الأصلي وتسجيله") }
                comparison.forEach { quote ->
                    Text(quote.quoteReference + " · " + quote.currency + " " + quote.totalAmount +
                        " · " + (if (quote.sourceCurrent) "ساري المصدر" else "تغير المصدر"),
                        style = MaterialTheme.typography.bodySmall)
                }
                confirmation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if(busy) Text("جارٍ التحقق من سجل الموردين أو حفظ المستند…")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
