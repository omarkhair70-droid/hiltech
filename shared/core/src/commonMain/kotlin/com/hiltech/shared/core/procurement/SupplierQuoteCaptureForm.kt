package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Host-selected bytes remain in this authenticated session only. */
data class SupplierQuotePickedFile(
    val fileName: String, val contentType: String, val sha256: String, val bytes: ByteArray, val capturedAt: String,
)

/** A verified quotation source is captured once; retry preserves its exact revision and operation. */
@Composable
fun SupplierQuoteCaptureForm(
    api: SupplierSourcingApiClient,
    uploader: SupplierQuoteDocumentUploader,
    pickFile: suspend () -> SupplierQuotePickedFile?,
    rfq: SupplierRfqDto,
    suppliers: List<SourcingSupplierCandidateDto>,
    installationId: String,
    newOperationId: () -> String,
    onRecorded: () -> Unit = {},
) = key(api, rfq.id, installationId) {
    val scope = rememberCoroutineScope()
    val commands = api.commands
    var supplierId by rememberSaveable { mutableStateOf(uploader.pendingInput?.takeIf { it.rfqId == rfq.id }?.supplierOrganizationId.orEmpty()) }
    var reference by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("EGP") }
    var quantity by rememberSaveable { mutableStateOf(rfq.capturedQuantity) }
    var unit by rememberSaveable { mutableStateOf(rfq.unitCode) }
    var price by rememberSaveable { mutableStateOf("") }
    var tax by rememberSaveable { mutableStateOf("0") }
    var days by rememberSaveable { mutableStateOf("") }
    var validUntil by rememberSaveable { mutableStateOf("") }
    var delivery by rememberSaveable { mutableStateOf("") }
    var payment by rememberSaveable { mutableStateOf("") }
    var warranty by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var validation by remember { mutableStateOf(false) }
    val selected = suppliers.firstOrNull { it.supplierOrganizationId == supplierId }
    val document = uploader.verified(rfq.id, supplierId)
    val pendingUpload = uploader.pendingInput
    val editable = rfq.sourceCurrent && !busy && commands.pending == null && !commands.busy && pendingUpload == null
    val valid = selected != null && reference.trim().length in 1..120 && currency.matches(Regex("[A-Z]{3}")) &&
        quoteDecimalValid(quantity, positive = true) && unit.trim().length in 1..32 && quoteDecimalValid(price, positive = false) &&
        quoteDecimalValid(tax, positive = false) && (days.isBlank() || days.toIntOrNull()?.let { it >= 0 } == true) &&
        (validUntil.isBlank() || hiltechDateToUtcMillis(validUntil) != null) &&
        listOf(delivery, payment, warranty).all { it.length <= 500 } && document != null

    suspend fun upload(input: SupplierQuoteDocumentUploadInput) {
        busy = true; error = null
        try { uploader.upload(input, installationId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "QUOTE_UPLOAD_UNCONFIRMED" }
        finally { busy = false }
    }
    suspend fun execute(command: SupplierSourcingCommand.Quote) {
        try {
            val receipt = api.recordQuote(command.rfqId, command.payload, installationId)
            require(receipt.rfqId == command.rfqId && receipt.revisionNumber == command.payload.expectedRevisionNumber)
            commands.recorded[command.rfqId] = receipt
            commands.succeeded(command); uploader.consumed(command.rfqId, command.payload.supplierOrganizationId)
            onRecorded()
        } catch (cancelled: CancellationException) { commands.failed(command, cancelled); throw cancelled }
        catch (failure: Exception) { commands.failed(command, failure) }
    }
    @Composable fun primaryFields(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HiltechRecordPicker(hiltechText("المورد", "Supplier"), supplierId,
                suppliers.map { HiltechRecordChoice(it.supplierOrganizationId, it.displayName, it.organizationCode) },
                { supplierId = it }, allowKnownReference = false, enabled = editable, allowClear = false)
            OutlinedTextField(reference, { reference = it }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                label = { Text(hiltechText("رقم عرض المورد", "Supplier quotation reference")) },
                supportingText = { Text(hiltechText("انسخ الرقم الموجود على عرض المورد.", "Use the reference printed on the supplier quotation.")) },
                isError = validation && reference.trim().length !in 1..120)
            OutlinedTextField(quantity, { quantity = quoteNumericInput(it) }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                label = { Text(hiltechText("الكمية المعروضة", "Quoted quantity")) }, isError = validation && !quoteDecimalValid(quantity, true))
            OutlinedTextField(unit, { unit = it }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                label = { Text(hiltechText("وحدة القياس في العرض", "Quoted unit")) }, isError = validation && unit.isBlank())
        }
    }
    @Composable fun commercialFields(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(currency, { currency = it.uppercase().take(3) }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                label = { Text(hiltechText("العملة", "Currency")) }, supportingText = { Text("EGP · USD · SAR · AED · EUR") },
                isError = validation && !currency.matches(Regex("[A-Z]{3}")))
            OutlinedTextField(price, { price = quoteNumericInput(it) }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                label = { Text(hiltechText("سعر الوحدة", "Unit price")) }, isError = validation && !quoteDecimalValid(price, false))
            OutlinedTextField(tax, { tax = quoteNumericInput(it) }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                label = { Text(hiltechText("إجمالي الضريبة", "Total tax amount")) }, isError = validation && !quoteDecimalValid(tax, false))
            HiltechDateField(hiltechText("صالح حتى", "Valid until"), validUntil, { validUntil = it }, enabled = editable)
        }
    }
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("تسجيل عرض مورد", "Record a supplier quotation"), style = MaterialTheme.typography.titleMedium)
        Text(hiltechText("أدخل بيانات العرض كما وصلت من المورد وأرفق مستنده. تُحفظ أي نسخة مصححة إلى جانب السجل السابق.",
            "Enter the quotation as received and attach its document. A corrected revision is saved alongside its previous history."), style = MaterialTheme.typography.bodyMedium)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 720.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                primaryFields(Modifier.weight(1f)); commercialFields(Modifier.weight(1f))
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                primaryFields(Modifier.fillMaxWidth()); commercialFields(Modifier.fillMaxWidth())
            }
        }
        TextButton(enabled = !busy, onClick = { details = !details }) { Text(if (details) hiltechText("إخفاء شروط العرض", "Hide quotation terms") else hiltechText("إضافة مدة التوريد وشروط العرض", "Add delivery lead time and quotation terms")) }
        if (details) {
            OutlinedTextField(days, { days = quoteNumericInput(it) }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(hiltechText("مدة التوريد بالأيام · اختياري", "Delivery lead time in days · optional")) },
                isError = validation && days.isNotBlank() && days.toIntOrNull()?.let { it >= 0 } != true)
            OutlinedTextField(delivery, { delivery = it }, Modifier.fillMaxWidth(), enabled = editable,
                label = { Text(hiltechText("شروط التوريد · اختياري", "Delivery terms · optional")) }, isError = delivery.length > 500)
            OutlinedTextField(payment, { payment = it }, Modifier.fillMaxWidth(), enabled = editable,
                label = { Text(hiltechText("شروط السداد · اختياري", "Payment terms · optional")) }, isError = payment.length > 500)
            OutlinedTextField(warranty, { warranty = it }, Modifier.fillMaxWidth(), enabled = editable,
                label = { Text(hiltechText("الضمان الوارد في العرض · اختياري", "Quoted warranty terms · optional")) }, isError = warranty.length > 500)
        }
        HorizontalDivider()
        Text(hiltechText("مستند عرض السعر", "Quotation document"), style = MaterialTheme.typography.titleSmall)
        Text(hiltechText("PDF أو صورة PNG/JPEG حتى 16 ميجابايت.", "PDF or PNG/JPEG image, up to 16 MB."), style = MaterialTheme.typography.bodySmall)
        document?.let { file ->
            HiltechStateTag(hiltechText("تم التحقق من الملف", "File verified"), HiltechStateTone.CURRENT)
            Text(file.fileName ?: hiltechText("مستند العرض المرفق", "Attached quotation document"))
        }
        if (pendingUpload != null) {
            Text(hiltechText("رفع محفوظ: ${pendingUpload.originalFileName.orEmpty()}", "Saved upload: ${pendingUpload.originalFileName.orEmpty()}"))
            Text(hiltechText("متابعة الرفع تستخدم الملف نفسه وتتحقق من نتيجته قبل تسجيل العرض.", "Resume uses the same file and checks its result before recording the quotation."))
            TextButton(enabled = !busy, onClick = { scope.launch { upload(pendingUpload) } }) { Text(hiltechText("متابعة توثيق الملف", "Resume document verification")) }
            TextButton(enabled = !busy, onClick = { uploader.discardPendingUpload(); error = null }) {
                Text(hiltechText("ترك هذا الملف واختيار ملف آخر", "Discard this upload and choose another file"))
            }
        } else TextButton(enabled = editable && selected != null, onClick = {
            val supplier = selected ?: return@TextButton
            busy = true; error = null
            scope.launch {
                try {
                    val file = pickFile()
                    if (file != null) upload(SupplierQuoteDocumentUploadInput(rfq.id, supplier.supplierOrganizationId,
                        newOperationId(), newOperationId(), newOperationId(), file.contentType, file.fileName, file.sha256, file.bytes, file.capturedAt))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "QUOTE_FILE_UNAVAILABLE" }
                finally { busy = false }
            }
        }) { Text(if (document == null) hiltechText("اختيار مستند العرض", "Choose quotation document") else hiltechText("استبدال المستند", "Replace document")) }
        if (busy) CircularProgressIndicator()
        if (validation && !valid) Text(hiltechText("اختر المورد وأكمل رقم العرض والكمية والعملة والسعر والضريبة وأرفق ملفًا موثقًا.",
            "Choose a supplier, complete the reference, quantity, currency, price and tax, and attach a verified document."), color = MaterialTheme.colorScheme.error)
        error?.let { code -> Text(when (code) {
            "QUOTE_UPLOAD_UNCONFIRMED" -> hiltechText("لم يكتمل تأكيد الملف. تابع العملية المحفوظة للتحقق من النتيجة.", "Document confirmation is incomplete. Resume the saved upload to check its result.")
            "QUOTE_FILE_UNAVAILABLE" -> hiltechText("تعذّرت قراءة الملف. اختر PDF أو صورة صالحة ضمن الحد المسموح.", "The file could not be read. Choose a valid PDF or image within the size limit.")
            else -> sourcingFailureText(code)
        }, color = MaterialTheme.colorScheme.error) }
        commands.errorCode?.let { Text(sourcingFailureText(it), color = MaterialTheme.colorScheme.error) }
        Button(enabled = editable, onClick = {
            validation = true
            if (!valid) return@Button
            val supplier = selected ?: return@Button
            val file = document ?: return@Button
            busy = true; error = null
            scope.launch {
                try {
                    val current = api.comparison(rfq.id, installationId)
                    require(current.rfq.id == rfq.id && current.rfq.sourceCurrent)
                    val revision = (current.comparison.latestBySupplier.firstOrNull { it.supplierOrganizationId == supplier.supplierOrganizationId }?.revisionNumber ?: 0) + 1
                    val command = SupplierSourcingCommand.Quote(rfq.id, RecordSupplierQuoteDto(newOperationId(), supplier.supplierOrganizationId,
                        revision, reference.trim(), currency.trim(), quantity.trim(), unit.trim(), price.trim(), tax.trim(),
                        days.takeIf(String::isNotBlank)?.toInt(), validUntil.takeIf(String::isNotBlank), delivery.trim().takeIf(String::isNotBlank),
                        payment.trim().takeIf(String::isNotBlank), warranty.trim().takeIf(String::isNotBlank), file.evidenceReference, file.sha256), rfq.requirementId)
                    if (commands.begin(command)) execute(command)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = sourcingFailureCode(failure) }
                finally { busy = false }
            }
        }) { Text(hiltechText("حفظ عرض المورد", "Save supplier quotation")) }
        if (!rfq.sourceCurrent) Text(hiltechText("تغيرت نسخة الاحتياج. افتح النسخة الحالية لاستكمال التسجيل.",
            "The requirement revision changed. Open its current revision to continue."), color = MaterialTheme.colorScheme.error)
    }
}

internal fun quoteNumericInput(value: String): String = value.map { character -> when (character) {
    in '٠'..'٩' -> '0' + (character - '٠')
    in '۰'..'۹' -> '0' + (character - '۰')
    '٫' -> '.'
    else -> character
} }.joinToString("")

internal fun quoteDecimalValid(value: String, positive: Boolean): Boolean =
    value.matches(Regex("(0|[1-9][0-9]{0,11})(\\.[0-9]{1,6})?")) && (!positive || value.any { it in '1'..'9' })
