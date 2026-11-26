package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.*
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Named current sources and complete quotation preparation on both native hosts. */
@Composable
fun SupplierSourcingBuyerSection(
    api: SupplierSourcingApiClient,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    quoteDocumentUploader: SupplierQuoteDocumentUploader? = null,
    pickQuoteFile: suspend () -> SupplierQuotePickedFile? = { null },
    purchaseCandidateApi: PurchaseOrderCandidateApiClient? = null,
    purchaseApprovalRequestEnabled: Boolean = false,
    technicalPacketEditor: (@Composable (quoteId: String, onClose: () -> Unit, onChanged: () -> Unit) -> Unit)? = null,
) = key(api, organizationId, installationId) {
    val scope = rememberCoroutineScope()
    val language = LocalHiltechLanguage.current
    val commands = api.commands
    var loading by remember { mutableStateOf(true) }
    var needs by remember { mutableStateOf<List<SourcingEligibleNeedDto>>(emptyList()) }
    var suppliers by remember { mutableStateOf<List<SourcingSupplierCandidateDto>>(emptyList()) }
    var requirementId by remember { mutableStateOf("") }
    var rounds by remember { mutableStateOf<List<SupplierRfqDto>>(emptyList()) }
    var roundId by remember { mutableStateOf("") }
    var comparison by remember { mutableStateOf<SupplierRfqComparisonDto?>(null) }
    var sourceLoading by remember { mutableStateOf(false) }
    var compareLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var compareError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var quoteId by remember { mutableStateOf("") }
    var capture by remember { mutableStateOf(false) }
    var technicalQuoteId by remember { mutableStateOf<String?>(null) }
    val pendingOpen = commands.pending as? SupplierSourcingCommand.Open
    val pendingQuote = commands.pending as? SupplierSourcingCommand.Quote
    val locked = commands.pending != null || commands.busy
    val need = needs.firstOrNull { it.requirementId == requirementId }
    val selectedRound = rounds.firstOrNull { it.id == roundId }

    LaunchedEffect(refresh) {
        loading = true; error = null
        try {
            val nextNeeds = api.eligibleNeeds(organizationId, installationId)
            val nextSuppliers = api.eligibleSuppliers(organizationId, installationId)
            needs = nextNeeds; suppliers = nextSuppliers
            if (need == null && nextNeeds.none { it.requirementId == requirementId } && !locked) requirementId = ""
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { needs = emptyList(); suppliers = emptyList(); error = sourcingFailureCode(failure) }
        finally { loading = false }
    }
    LaunchedEffect(requirementId, refresh) {
        rounds = emptyList(); comparison = null; sourceError = null
        if (requirementId.isBlank()) return@LaunchedEffect
        sourceLoading = true
        try {
            rounds = api.roundsForRequirement(requirementId, installationId)
            if (rounds.none { it.id == roundId }) roundId = rounds.firstOrNull { it.sourceCurrent }?.id ?: rounds.firstOrNull()?.id.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { sourceError = sourcingFailureCode(failure) }
        finally { sourceLoading = false }
    }
    LaunchedEffect(roundId, refresh) {
        comparison = null; compareError = null
        if (roundId.isBlank()) return@LaunchedEffect
        compareLoading = true
        try {
            comparison = api.comparison(roundId, installationId)
            if (comparison?.comparison?.latestBySupplier?.none { it.quoteId == quoteId } == true) quoteId = ""
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { compareError = sourcingFailureCode(failure) }
        finally { compareLoading = false }
    }
    suspend fun execute(command: SupplierSourcingCommand) {
        try {
            when (command) {
                is SupplierSourcingCommand.Open -> {
                    val receipt = api.openRfq(command.requirementId, command.payload, installationId)
                    require(receipt.rfq.requirementId == command.requirementId &&
                        receipt.rfq.capturedRequirementVersion == command.payload.expectedRequirementVersion)
                    commands.opened[command.requirementId] = receipt
                    requirementId = command.requirementId; roundId = receipt.rfq.id
                }
                is SupplierSourcingCommand.Quote -> {
                    val receipt = api.recordQuote(command.rfqId, command.payload, installationId)
                    require(receipt.rfqId == command.rfqId && receipt.revisionNumber == command.payload.expectedRevisionNumber)
                    commands.recorded[command.rfqId] = receipt
                    quoteDocumentUploader?.consumed(command.rfqId, command.payload.supplierOrganizationId)
                    requirementId = command.requirementId; roundId = command.rfqId
                }
            }
            commands.succeeded(command); refresh++
        } catch (cancelled: CancellationException) { commands.failed(command, cancelled); throw cancelled }
        catch (failure: Exception) { commands.failed(command, failure) }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HiltechPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(hiltechText("عروض الموردين", "Supplier quotations"), style = MaterialTheme.typography.titleLarge)
                    Text(hiltechText("احتياج معتمد فنيًا ← عروض موثقة ← مقترح شراء", "Technically ready need → documented quotations → purchase proposal"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(enabled = !loading && !sourceLoading && !commands.busy, onClick = { refresh++ }) { Text(hiltechText("تحديث", "Refresh")) }
            }
            if (loading) CircularProgressIndicator()
            error?.let { Text(sourcingFailureText(it), color = MaterialTheme.colorScheme.error) }
            commands.errorCode?.let { Text(sourcingFailureText(it), color = MaterialTheme.colorScheme.error) }
            if (commands.pending != null) {
                Text(hiltechText("نحتفظ بالطلب نفسه حتى تتأكد نتيجته. يمكنك مغادرة الشاشة والعودة داخل الحساب الحالي.",
                    "The exact request is retained until its result is confirmed. You can leave and return within this session."))
                pendingOpen?.let { Text(hiltechText("إنشاء طلب عروض · النسخة ${it.payload.expectedRequirementVersion}",
                    "Opening quotation request · revision ${it.payload.expectedRequirementVersion}")) }
                pendingQuote?.let { Text(hiltechText("تسجيل العرض ${it.payload.supplierQuoteReference} · النسخة ${it.payload.expectedRevisionNumber}",
                    "Recording quotation ${it.payload.supplierQuoteReference} · revision ${it.payload.expectedRevisionNumber}")) }
                Button(enabled = !commands.busy, onClick = { commands.retry()?.let { scope.launch { execute(it) } } }) {
                    Text(if (commands.busy) hiltechText("جارٍ التأكيد…", "Confirming…") else hiltechText("التحقق من نتيجة العملية", "Check operation result"))
                }
            }
            if (!loading && error == null && needs.isEmpty()) Text(hiltechText("لا توجد احتياجات جاهزة لطلب عروض. تظهر هنا بعد إتمام المراجعة الفنية.",
                "No needs are ready for sourcing. They appear here after technical review."))
            if (needs.isNotEmpty()) HiltechRecordPicker(
                hiltechText("الاحتياج", "Required item"), requirementId,
                needs.map { item -> HiltechRecordChoice(item.requirementId, item.stockItemName?.takeIf(String::isNotBlank)
                    ?: language.text("صنف من سجل المخزن", "Warehouse catalog item"),
                    listOfNotNull(item.stockItemCode?.takeIf(String::isNotBlank), "${item.requestedQuantity} ${item.unitCode}",
                        language.text("نسخة ${item.technicalVersion}", "revision ${item.technicalVersion}")).joinToString(" · ")) },
                { requirementId = it; roundId = ""; quoteId = ""; capture = false }, allowKnownReference = false, enabled = !locked && !loading,
            )
            need?.let { current ->
                HiltechStateTag(if (current.sourceCurrent) hiltechText("مصدر حالي", "Current source") else hiltechText("يحتاج تحديث المصدر", "Source needs refresh"),
                    if (current.sourceCurrent) HiltechStateTone.CURRENT else HiltechStateTone.BLOCKED)
                if (!current.sourcingRoundExists && current.sourceCurrent) Button(enabled = !locked, onClick = {
                    val command = SupplierSourcingCommand.Open(current.requirementId, OpenSupplierRfqDto(newOperationId(), current.technicalVersion))
                    if (commands.begin(command)) scope.launch { execute(command) }
                }) { Text(hiltechText("بدء طلب عروض", "Start quotation request")) }
                commands.opened[current.requirementId]?.let {
                    Text(hiltechText("طلب العروض محفوظ. أضف عروض الموردين للمقارنة.", "Quotation request saved. Add supplier quotations to compare."), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (sourceLoading) CircularProgressIndicator()
        sourceError?.let { Text(sourcingFailureText(it), color = MaterialTheme.colorScheme.error) }
        if (rounds.isNotEmpty()) {
            HiltechRecordPicker(hiltechText("طلب العروض", "Quotation request"), roundId,
                rounds.map { HiltechRecordChoice(it.id, language.text("نسخة الاحتياج ${it.capturedRequirementVersion}", "Requirement revision ${it.capturedRequirementVersion}"),
                    language.text("${it.quoteCount} عرض · ", "${it.quoteCount} quotations · ") + if (it.sourceCurrent) language.text("حالي", "current") else language.text("سجل سابق", "historical")) },
                { roundId = it; quoteId = ""; capture = false }, allowKnownReference = false, allowClear = false, enabled = !locked)
        } else if (need != null && !sourceLoading && sourceError == null) Text(hiltechText("لم يبدأ طلب عروض لهذا الاحتياج بعد.", "No quotation request has been started for this need."))
        selectedRound?.let { round ->
            if (!round.sourceCurrent) Text(hiltechText("هذه نسخة سابقة. يمكنك الاطلاع على عروضها، ويبدأ أي إجراء جديد من نسخة الاحتياج الحالية.",
                "This is a previous revision. Its quotations remain readable; new work starts from the current requirement revision."))
            commands.recorded[round.id]?.let { receipt ->
                Text(hiltechText("تم حفظ عرض المورد · النسخة ${receipt.revisionNumber}", "Supplier quotation saved · revision ${receipt.revisionNumber}"), color = MaterialTheme.colorScheme.primary)
            }
            if (round.sourceCurrent && quoteDocumentUploader != null) {
                if (suppliers.isEmpty() && !loading) Text(hiltechText("أضف موردًا نشطًا من سجل الموردين لتسجيل عرضه.", "Add an active supplier in the supplier register to record a quotation."))
                else TextButton(enabled = !locked, onClick = { capture = !capture }) { Text(if (capture) hiltechText("إغلاق نموذج العرض", "Close quotation form") else hiltechText("إضافة عرض مورد أو نسخة مصححة", "Add or revise a supplier quotation")) }
                if (capture) SupplierQuoteCaptureForm(api, quoteDocumentUploader, pickQuoteFile, round, suppliers, installationId, newOperationId,
                    onRecorded = { refresh++ })
            }
            if (compareLoading) CircularProgressIndicator()
            compareError?.let { Text(sourcingFailureText(it), color = MaterialTheme.colorScheme.error) }
            comparison?.let { view ->
                val rows = view.comparison.latestBySupplier
                Text(hiltechText("مقارنة أحدث عروض الموردين", "Compare latest supplier quotations"), style = MaterialTheme.typography.titleMedium)
                HiltechRecordTable(listOf(
                    HiltechTableColumn(hiltechText("المورد", "Supplier"), 1.6f),
                    HiltechTableColumn(hiltechText("العرض", "Quotation")),
                    HiltechTableColumn(hiltechText("الكمية", "Quantity"), numeric = true),
                    HiltechTableColumn(hiltechText("الإجمالي", "Total"), numeric = true),
                    HiltechTableColumn(hiltechText("التقييم الفني", "Technical review"), 1.3f)),
                    rows.map { row -> HiltechTableRecord(listOf(supplierLabel(row, suppliers, language),
                        "${row.quoteReference} · ${row.revisionNumber}", "${row.quantity} ${row.unitCode}", "${row.total} ${row.currency}",
                        sourcingTechnicalText(row.technicalCompliance, language))) },
                    hiltechText("لم تُسجّل عروض موردين بعد.", "No supplier quotations have been recorded yet."))
                if (rows.isNotEmpty()) HiltechRecordPicker(hiltechText("تفاصيل العرض ومقترح الشراء", "Quotation details and purchase proposal"), quoteId,
                    rows.map { HiltechRecordChoice(it.quoteId, supplierLabel(it, suppliers, language), "${it.quoteReference} · ${it.total} ${it.currency}") },
                    { quoteId = it }, allowKnownReference = false, enabled = !locked)
                rows.firstOrNull { it.quoteId == quoteId }?.let { quote -> HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(supplierLabel(quote, suppliers, language), style = MaterialTheme.typography.titleMedium)
                    HiltechKeyValue(hiltechText("صالح حتى", "Valid until"), hiltechDateText(quote.validUntil))
                    quote.deliveryDays?.let { HiltechKeyValue(hiltechText("مدة التوريد", "Delivery lead time"), hiltechText("$it يوم", "$it days")) }
                    quote.issues.forEach { Text(sourcingIssueText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    if (technicalPacketEditor != null) {
                        TextButton(enabled = !locked, onClick = { technicalQuoteId = quote.quoteId }) {
                            Text(hiltechText("البيان والمراجعة الفنية", "Technical packet and review"))
                        }
                    }
                    if (purchaseCandidateApi != null && round.sourceCurrent) {
                        HorizontalDivider()
                        PurchaseOrderCandidateAction(purchaseCandidateApi, round.id, quote, installationId, newOperationId, purchaseApprovalRequestEnabled)
                    }
                } }
            }
        }
    }
    technicalQuoteId?.let { selected ->
        technicalPacketEditor?.invoke(selected, { technicalQuoteId = null; refresh++ }, { refresh++ })
    }
}

internal fun supplierLabel(row: SupplierQuoteComparisonRowDto, suppliers: List<SourcingSupplierCandidateDto>, language: HiltechLanguage): String =
    row.supplierName?.takeIf(String::isNotBlank)?.let { it + row.supplierCode?.takeIf(String::isNotBlank)?.let { code -> " · $code" }.orEmpty() }
        ?: suppliers.firstOrNull { it.supplierOrganizationId == row.supplierOrganizationId }?.displayLabel()
        ?: language.text("مورد مسجل · ${row.quoteReference}", "Recorded supplier · ${row.quoteReference}")

internal fun sourcingTechnicalText(value: String, language: HiltechLanguage): String = when (value) {
    "COMPLIANT" -> language.text("مطابق فنيًا", "Technically compliant")
    "NON_COMPLIANT" -> language.text("غير مطابق", "Not compliant")
    "NEEDS_CLARIFICATION" -> language.text("يحتاج توضيحًا", "Clarification requested")
    else -> language.text("بانتظار التقييم", "Awaiting assessment")
}

internal fun sourcingFailureCode(failure: Throwable): String = (failure as? HiltechApiException)?.code ?: "SOURCING_LOAD_FAILED"

@Composable
internal fun sourcingFailureText(code: String): String = when (code) {
    "SOURCING_RESULT_UNCONFIRMED" -> hiltechText("لم يصل تأكيد النتيجة. تحقّق باستخدام الطلب المحفوظ قبل بدء طلب آخر.", "The result is unconfirmed. Check the saved request before starting another.")
    "UNAUTHENTICATED", "AUTH_TOKEN_REQUIRED", "REAUTHENTICATION_REQUIRED" -> hiltechText("أعد تسجيل الدخول ثم تابع الطلب المحفوظ.", "Sign in again, then continue the saved request.")
    "SOURCING_UNAVAILABLE", "OBJECT_NOT_VISIBLE", "FORBIDDEN" -> hiltechText("لا يتاح المصدر لهذا الحساب حاليًا. راجع مسؤول الشركة لتأكيد مسؤوليتك.", "This source is not currently available to this account. Ask the company administrator to check your responsibility.")
    "SOURCING_SOURCE_STALE", "VERSION_CONFLICT", "PROCUREMENT_SOURCE_STALE" -> hiltechText("تغيّر المصدر أو العرض. حدّث القائمة وراجع النسخة الحالية.", "The source or quotation changed. Refresh and review the current revision.")
    "SOURCING_INVALID", "SOURCING_REQUEST_INVALID" -> hiltechText("راجع بيانات العرض والحقول المطلوبة قبل الحفظ.", "Review the quotation details and required fields before saving.")
    else -> hiltechText("تعذّر إتمام الطلب الآن. احتفظ بالبيانات وأعد المحاولة بعد التحقق من الاتصال.", "The request could not complete. Keep the details and retry after checking the connection.")
}

@Composable
private fun sourcingIssueText(code: String): String = when (code) {
    "INVALID_CURRENCY" -> hiltechText("عملة العرض غير صالحة.", "The quotation currency is invalid.")
    "CURRENCY_DIFFERS_NO_FX_CONVERSION" -> hiltechText("العملات مختلفة؛ لا يحوّل النظام الأسعار تلقائيًا للمقارنة.", "Currencies differ; prices are not automatically converted for comparison.")
    "QUANTITY_DIFFERS" -> hiltechText("كمية العرض تختلف عن الاحتياج.", "The quoted quantity differs from the requirement.")
    "UNIT_DIFFERS" -> hiltechText("وحدة العرض تختلف عن وحدة الاحتياج.", "The quoted unit differs from the requirement.")
    "VALIDITY_UNCONFIRMED" -> hiltechText("لم يُحدد تاريخ انتهاء العرض.", "The quotation expiry date has not been recorded.")
    "QUOTE_EXPIRED" -> hiltechText("انتهت صلاحية العرض؛ اطلب نسخة حديثة من المورد.", "The quotation has expired; request an updated supplier quotation.")
    "TECHNICAL_NON_COMPLIANT" -> hiltechText("العرض غير مطابق للتقييم الفني.", "The quotation did not meet the technical assessment.")
    "TECHNICAL_CLARIFICATION_REQUIRED" -> hiltechText("المراجع الفني طلب توضيحًا قبل متابعة الشراء.", "The technical reviewer requested clarification before purchasing can proceed.")
    "TECHNICAL_COMPLIANCE_UNCONFIRMED" -> hiltechText("ينتظر العرض تقييم المسؤول الفني.", "The quotation awaits assessment by the technical reviewer.")
    else -> hiltechText("يحتاج العرض مراجعة المصدر قبل استخدامه.", "The quotation needs source review before use.")
}
