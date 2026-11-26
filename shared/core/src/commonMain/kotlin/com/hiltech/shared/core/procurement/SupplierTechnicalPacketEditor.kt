@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.hiltech.shared.core.procurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hiltech.shared.core.design.*
import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** One source-driven screen for purchasing preparation and the existing technical reviewer responsibility. */
@Composable
fun SupplierTechnicalPacketEditor(
    client: SupplierTechnicalApiClient, evidence: EvidenceApiClient, quoteId: String, identityId: String, organizationId: String,
    installationId: String, newOperationId: () -> String, pickDocument: suspend () -> ServiceSelectedFile?,
    onReauthenticate: () -> Unit = {}, onClose: () -> Unit, onChanged: () -> Unit = {},
) = key(client, quoteId, identityId, organizationId, installationId) {
    val language = LocalHiltechLanguage.current
    val jobs = rememberCoroutineScope()
    val commands = client.commands
    val json = remember { Json { encodeDefaults = true; explicitNulls = false } }
    val uploader = remember(client, evidence) { SupplierTechnicalUploadCoordinator(client, evidence) }
    var detail by remember { mutableStateOf<SupplierTechnicalDetailDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var acknowledgement by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf("PACKET") }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draftJson by rememberSaveable { mutableStateOf(json.encodeToString(SupplierTechnicalPacketFields())) }
    val draft = json.decodeFromString<SupplierTechnicalPacketFields>(draftJson)
    var attempted by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var selectedPacketId by rememberSaveable { mutableStateOf<String?>(null) }
    var decision by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var reviewed by rememberSaveable { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<SupplierTechnicalCommand?>(null) }
    var uploadOpen by rememberSaveable { mutableStateOf(false) }
    var uploadTitle by rememberSaveable { mutableStateOf("") }
    var chosen by remember { mutableStateOf<ServiceSelectedFile?>(null) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    var viewer by remember { mutableStateOf<Pair<String?, SupplierTechnicalEvidenceDto>?>(null) }
    var page by remember { mutableStateOf<SupplierTechnicalEvidencePageDto?>(null) }
    var image by remember { mutableStateOf<ByteArray?>(null) }
    var pageLoading by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var requestedPage by remember { mutableStateOf(0) }
    var displayedReceipt by remember { mutableStateOf<String?>(null) }
    val latest = detail?.packets?.firstOrNull()
    val selectedPacket = detail?.packets?.firstOrNull { it.packetId == selectedPacketId } ?: latest
    val locked = commands.pending != null || commands.busy || commands.upload != null || commands.uploadBusy

    fun clearViewer() { viewer = null; page = null; image = null; pageError = null; displayedReceipt = null }
    fun clearSource() { detail = null; clearViewer() }
    fun failureCode(failure: Throwable) = (failure as? HiltechApiException)?.code ?: failure.message?.takeIf { it == "SUPPLIER_TECHNICAL_RESELECT_SAME_FILE" } ?: "LOAD_FAILED"
    fun revoked(failure: Throwable): Boolean = (failure as? HiltechApiException)?.httpStatus in setOf(401, 403, 404) ||
        (failure as? HiltechApiException)?.code in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED")

    suspend fun refresh() {
        loading = true; loadError = null
        try { detail = client.detail(organizationId, quoteId, installationId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { clearSource(); loadError = failureCode(failure) }
        finally { loading = false }
    }

    suspend fun execute(command: SupplierTechnicalCommand) {
        try {
            when (command) {
                is SupplierTechnicalCommand.Publish -> client.publish(command.organizationId, command.quoteId, command.payload, installationId)
                is SupplierTechnicalCommand.Assess -> client.assess(command.organizationId, command.quoteId, command.payload, installationId)
            }
            commands.succeeded(command); confirmation = null; editing = false; selectedPacketId = null; clearViewer()
            acknowledgement = if (command is SupplierTechnicalCommand.Publish) language.text("تم نشر البيان الفني للمراجعة.", "The technical packet is published for review.")
                else language.text("تم تسجيل نتيجة المراجعة الفنية.", "The technical review result is recorded.")
            reason = ""; reviewed = false; decision = ""; onChanged(); refresh()
        } catch (cancelled: CancellationException) { commands.failed(command, cancelled); throw cancelled }
        catch (failure: Exception) {
            commands.failed(command, failure); confirmation = null; clearViewer()
            if (revoked(failure)) { clearSource(); loadError = failureCode(failure) }
            else if (commands.pending == null) refresh()
        }
    }

    suspend fun loadPage(index: Int) {
        val requested = viewer ?: return
        requestedPage = index; page = null; image = null; pageError = null; displayedReceipt = null; pageLoading = true
        try {
            val delivered = client.page(organizationId, quoteId, requested.first, requested.second, index, installationId)
            if (viewer != requested || requestedPage != index) return
            val bytes = Base64.Default.decode(delivered.imageBase64); check(bytes.size in 1..16_777_216)
            page = delivered; image = bytes
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { pageError = supplierTechnicalProblem(failureCode(failure), language); if (revoked(failure)) { clearSource(); loadError = failureCode(failure) } }
        finally { pageLoading = false }
    }
    fun openDocument(packet: String?, doc: SupplierTechnicalEvidenceDto) { viewer = packet to doc; jobs.launch { loadPage(0) } }

    suspend fun upload() {
        if (commands.uploadBusy) return
        var intent = commands.upload
        if (intent == null) {
            val source = detail ?: return
            val file = chosen
            if (!source.canPrepare || uploadTitle.trim().length !in 1..240 || file == null || !supplierTechnicalFileValid(file)) { uploadError = "SUPPLIER_TECHNICAL_FILE_INVALID"; return }
            intent = SupplierTechnicalUploadIntent(newOperationId(), newOperationId(), newOperationId(), organizationId, quoteId,
                source.quote.itemName + " · " + source.quote.supplierName, uploadTitle.trim(), file.originalFileName, file.contentType, file.bytes.size.toLong(), file.sha256, file.capturedAt)
            commands.upload = intent; commands.uploadFile = file
        }
        if (intent.organizationId != organizationId || intent.quoteId != quoteId) return
        commands.uploadBusy = true; commands.uploadError = null; uploadError = null
        try {
            val ready = uploader.upload(intent, commands.uploadFile ?: chosen, installationId) { commands.upload = it }
            if (editing) draftJson = json.encodeToString(draft.copy(evidenceIds = (draft.evidenceIds + ready.evidenceId).distinct()))
            commands.upload = null; commands.uploadFile = null; chosen = null; uploadOpen = false; uploadTitle = ""
            acknowledgement = language.text("تم حفظ المستند الفني. اختره ضمن البيان الذي ستنشره.", "The technical document is saved. Select it in the packet you publish.")
            onChanged(); refresh()
        } catch (cancelled: CancellationException) { commands.uploadError = "SUPPLIER_TECHNICAL_RESULT_UNCONFIRMED"; throw cancelled }
        catch (failure: Exception) { commands.uploadError = failureCode(failure); if (revoked(failure)) { clearSource(); loadError = failureCode(failure) } }
        finally { commands.uploadBusy = false }
    }

    LaunchedEffect(quoteId) { refresh() }
    LaunchedEffect(selectedPacket?.packetId) { decision = ""; reviewed = false; formError = null }

    Dialog(onDismissRequest = { if (!commands.busy && !commands.uploadBusy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 1180.dp).fillMaxWidth(.96f).heightIn(max = 940.dp), shape = HiltechShape.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(hiltechText("الملف الفني للمورد", "Supplier technical file"), style = MaterialTheme.typography.headlineSmall)
                        detail?.quote?.let { Text(it.itemName + " · " + it.supplierName, style = MaterialTheme.typography.bodyLarge) }
                    }
                    TextButton(onClick = { clearViewer(); jobs.launch { refresh() } }, enabled = !loading && !commands.busy) { Text(hiltechText("تحديث", "Refresh")) }
                    TextButton(onClick = onClose, enabled = !commands.busy && !commands.uploadBusy) { Text(hiltechText("إغلاق", "Close")) }
                }
                if (loading || commands.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                acknowledgement?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                loadError?.let { Text(supplierTechnicalProblem(it, language)) }
                commands.errorCode?.let { Text(supplierTechnicalProblem(it, language)) }
                if (loadError in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED") || commands.errorCode in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED"))
                    OutlinedButton(onClick = onReauthenticate) { Text(hiltechText("تجديد تسجيل الدخول", "Sign in again")) }
                commands.pending?.let { pending ->
                    HiltechPanel { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(supplierTechnicalProblem("SUPPLIER_TECHNICAL_RESULT_UNCONFIRMED", language))
                        if (detail != null) Text(pending.title, style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = { commands.retry()?.let { jobs.launch { execute(it) } } }, enabled = !commands.busy) { Text(hiltechText("إعادة نفس الطلب", "Retry same request")) }
                    } }
                }
                commands.upload?.takeIf { it.quoteId == quoteId }?.let {
                    OutlinedButton(onClick = { uploadOpen = true }, enabled = !commands.uploadBusy) { Text(hiltechText("استكمال رفع نفس المستند", "Resume the same document upload")) }
                }
                detail?.let { data ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("PACKET", "REVIEW", "HISTORY").forEach { item ->
                            FilterChip(tab == item, { tab = item; formError = null }, enabled = !editing, label = { Text(when (item) {
                                "PACKET" -> hiltechText("البيان الفني", "Technical packet"); "REVIEW" -> hiltechText("نتيجة المراجعة", "Review result"); else -> hiltechText("التاريخ والتصحيحات", "History and corrections")
                            }) })
                        }
                    }
                    BoxWithConstraints {
                        val context: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                SupplierTechnicalContext(if (!editing) selectedPacket?.needSnapshot ?: data.quote else data.quote)
                                if (!editing) selectedPacket?.let { packet ->
                                    Text(hiltechText("المستندات الفنية لهذه النسخة", "This version’s technical originals"), style = MaterialTheme.typography.titleMedium)
                                    if (packet.details.evidenceIds.isEmpty()) Text(hiltechText("المصدر الفني مسجل في البيان المكتوب.", "The technical source is recorded in the written packet."), style = MaterialTheme.typography.bodyMedium)
                                    if (packet.documents.size != packet.details.evidenceIds.size) Text(supplierTechnicalProblem("SUPPLIER_TECHNICAL_ORIGINAL_UNAVAILABLE", language))
                                    packet.documents.forEach { doc ->
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(doc.title, style = MaterialTheme.typography.bodyLarge)
                                            OutlinedButton(onClick = { openDocument(packet.packetId, doc) }, enabled = !commands.busy) { Text(hiltechText("فتح المستند الفني", "Open technical original")) }
                                            if (doc.evidenceId in packet.displayedEvidenceIds) Text(hiltechText("تم عرضه لهذه النسخة", "Displayed for this version"), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                        val main: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (editing) {
                                    Text(if (latest == null) hiltechText("إعداد بيان فني", "Prepare technical packet") else hiltechText("تصحيح البيان الفني", "Correct technical packet"), style = MaterialTheme.typography.titleLarge)
                                    SupplierTechnicalForm(draft, data.availableDocuments, !locked, attempted, { draftJson = json.encodeToString(it); formError = null }, { uploadOpen = true }, { openDocument(null, it) })
                                    formError?.let { Text(supplierTechnicalProblem(it, language), color = MaterialTheme.colorScheme.error) }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(onClick = {
                                            attempted = true
                                            if (!supplierTechnicalFieldsValid(draft)) formError = "FORM_INCOMPLETE"
                                            else confirmation = SupplierTechnicalCommand.Publish(organizationId, quoteId, data.quote.itemName + " · " + data.quote.supplierName,
                                                SupplierTechnicalPublishRequest(newOperationId(), latest?.packetRevision ?: 0, draft))
                                        }, enabled = !locked && data.quote.sourceCurrent && data.quote.latestQuote) { Text(hiltechText("مراجعة البيان قبل النشر", "Review packet before publishing")) }
                                        TextButton(onClick = { editing = false; attempted = false; formError = null }, enabled = !locked) { Text(hiltechText("إلغاء التحرير", "Cancel editing")) }
                                    }
                                } else when (tab) {
                                    "PACKET" -> {
                                        if (selectedPacket == null) Text(hiltechText("أضف المواصفات والفروق ومصدرها ليتمكن المراجع الفني من المقارنة.", "Add the specification, deviations and their source so the technical reviewer can compare them."))
                                        else SupplierTechnicalPacketRead(selectedPacket)
                                        if (data.canPrepare) Button(onClick = {
                                            draftJson = json.encodeToString((latest?.details ?: SupplierTechnicalPacketFields()).copy(nonFinancialConfirmed = false, correctionReason = ""))
                                            editing = true; attempted = false; formError = null; selectedPacketId = null
                                        }, enabled = !locked && data.quote.sourceCurrent && data.quote.latestQuote) { Text(if (latest == null) hiltechText("إعداد البيان الفني", "Prepare technical packet") else hiltechText("تسجيل تصحيح جديد", "Record a correction")) }
                                    }
                                    "REVIEW" -> {
                                        HiltechStateTag(supplierTechnicalLabel(data.effectiveDecision, language), if (data.effectiveDecision == "COMPLIANT") HiltechStateTone.CURRENT else HiltechStateTone.ATTENTION)
                                        selectedPacket?.let { SupplierTechnicalPacketRead(it) }
                                        val currentSelection = selectedPacket?.packetId == latest?.packetId
                                        if (data.canReview && currentSelection) {
                                            SupplierTechnicalReviewControls(latest, decision, reason, reviewed, !locked, { decision = it; formError = null }, { reason = it; formError = null }, { reviewed = it; formError = null })
                                            formError?.let { Text(supplierTechnicalProblem(it, language), color = MaterialTheme.colorScheme.error) }
                                            val originalsDisplayed = decision != "COMPLIANT" || latest?.let { it.displayedEvidenceIds.containsAll(it.details.evidenceIds) } == true
                                            Button(onClick = {
                                                val coherent = decision in setOf("COMPLIANT", "NON_COMPLIANT", "NEEDS_CLARIFICATION") && reason.trim().length in 8..1000 &&
                                                    (decision == "NEEDS_CLARIFICATION" || latest?.sourceCurrent == true && reviewed)
                                                if (!coherent) formError = "FORM_INCOMPLETE"
                                                else confirmation = SupplierTechnicalCommand.Assess(organizationId, quoteId, data.quote.itemName + " · " + data.quote.supplierName,
                                                    SupplierTechnicalAssessmentCommand(newOperationId(), data.latestAssessmentRevision + 1, decision, reason.trim(), latest?.packetId, latest != null && reviewed))
                                            }, enabled = !locked && originalsDisplayed) { Text(hiltechText("مراجعة النتيجة قبل التسجيل", "Review result before recording")) }
                                        } else Text(if (!currentSelection) hiltechText("هذه نسخة تاريخية. عد إلى البيان الحالي لاتخاذ قرار جديد.", "This is a historical version. Return to the current packet to make a new decision.")
                                            else hiltechText("النتيجة متاحة للقراءة. يسجّل القرار صاحب مسؤولية المراجعة الفنية الحالية.", "The result is available to read. The current technical reviewer records the decision."))
                                    }
                                    "HISTORY" -> {
                                        Text(hiltechText("نسخ البيان الفني", "Technical packet versions"), style = MaterialTheme.typography.titleMedium)
                                        data.packets.forEach { packet ->
                                            OutlinedButton(onClick = { selectedPacketId = packet.packetId; tab = "PACKET" }) {
                                                Text(hiltechText("نسخة", "Version") + " ${packet.packetRevision} · ${packet.preparedByName} · ${hiltechDateText(packet.preparedAt)}")
                                            }
                                        }
                                        HorizontalDivider()
                                        Text(hiltechText("قرارات المراجعة", "Review decisions"), style = MaterialTheme.typography.titleMedium)
                                        if (data.history.isEmpty()) Text(hiltechText("لم تُسجّل مراجعة فنية بعد.", "No technical review is recorded yet."))
                                        data.history.forEach { row ->
                                            HiltechPanel { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(supplierTechnicalLabel(row.decision, language), style = MaterialTheme.typography.titleMedium)
                                                Text(row.reviewerName + " · " + hiltechDateText(row.reviewedAt), style = MaterialTheme.typography.labelLarge)
                                                Text(row.justification)
                                                Text(row.packetRevision?.let { hiltechText("نسخة البيان", "Packet version") + " $it" }
                                                    ?: hiltechText("قرار تاريخي يسبق البيان الفني الحالي", "Historical decision predating the current technical packet"), style = MaterialTheme.typography.labelMedium)
                                            } }
                                        }
                                    }
                                }
                                if (selectedPacketId != null && selectedPacketId != latest?.packetId) TextButton(onClick = { selectedPacketId = null; tab = "PACKET" }) { Text(hiltechText("العودة إلى البيان الحالي", "Return to current packet")) }
                            }
                        }
                        if (maxWidth >= 860.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(Modifier.weight(1.8f)) { main() }; Column(Modifier.weight(1f)) { context() }
                        } else Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { context(); main() }
                    }
                }
            }
        }
    }

    confirmation?.let { command -> AlertDialog(
        onDismissRequest = { if (!commands.busy) confirmation = null },
        title = { Text(if (command is SupplierTechnicalCommand.Publish) hiltechText("تأكيد نشر البيان الفني", "Confirm technical packet publication") else hiltechText("تأكيد نتيجة المراجعة الفنية", "Confirm technical review result")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(command.title)
            when (command) {
                is SupplierTechnicalCommand.Publish -> {
                    Text(hiltechText("ستظهر المواصفات والمصدر والمرفقات المختارة للمراجعين الفنيين.", "The specification, source and selected attachments will be visible to technical reviewers."))
                    Text(command.payload.details.correctionReason)
                }
                is SupplierTechnicalCommand.Assess -> { Text(supplierTechnicalLabel(command.payload.decision, language)); Text(command.payload.justification) }
            }
        } },
        confirmButton = { TextButton(onClick = { if (commands.begin(command)) jobs.launch { execute(command) } }, enabled = !commands.busy) { Text(hiltechText("تأكيد", "Confirm")) } },
        dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !commands.busy) { Text(hiltechText("عودة للمراجعة", "Back to review")) } },
    ) }

    if (uploadOpen) {
        val intent = commands.upload?.takeIf { it.quoteId == quoteId }
        AlertDialog(onDismissRequest = { if (!commands.uploadBusy) uploadOpen = false },
            title = { Text(hiltechText("مستند فني للمراجعة", "Technical review document")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(hiltechText("اختر أصلًا فنيًا منفصلًا خاليًا من الأسعار وشروط الدفع. سيُتاح مع البيان عند نشره.", "Choose a separate technical original without prices or payment terms. It becomes available with the packet when published."))
                OutlinedTextField(intent?.title ?: uploadTitle, { uploadTitle = it }, label = { Text(hiltechText("اسم واضح للمستند الفني", "Clear technical document title")) }, enabled = intent == null && !commands.uploadBusy, modifier = Modifier.fillMaxWidth())
                (commands.uploadFile ?: chosen)?.let { Text(it.originalFileName) }
                OutlinedButton(onClick = { jobs.launch {
                    try { pickDocument()?.let { file ->
                        if (!supplierTechnicalFileValid(file)) uploadError = "SUPPLIER_TECHNICAL_FILE_INVALID"
                        else if (intent != null && !supplierTechnicalFileMatches(intent, file)) uploadError = "SUPPLIER_TECHNICAL_RESELECT_SAME_FILE"
                        else { chosen = file; if (intent != null) commands.uploadFile = file; uploadError = null }
                    } } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { uploadError = "SUPPLIER_TECHNICAL_FILE_INVALID" }
                } }, enabled = !commands.uploadBusy) { Text(if (intent == null) hiltechText("اختيار الملف", "Choose file") else hiltechText("اختيار نفس الملف", "Choose the same file")) }
                (uploadError ?: commands.uploadError)?.let { Text(supplierTechnicalProblem(it, language)) }
                if (commands.uploadError in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED")) TextButton(onClick = onReauthenticate) { Text(hiltechText("تجديد تسجيل الدخول", "Sign in again")) }
                if (commands.uploadError == "EVIDENCE_UPLOAD_SESSION_EXPIRED") TextButton(onClick = {
                    commands.upload = null; commands.uploadFile = null; commands.uploadError = null; chosen = null; uploadOpen = false
                }) { Text(hiltechText("إنهاء محاولة الرفع المنتهية", "End the expired upload attempt")) }
                if (commands.uploadBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            } },
            confirmButton = { TextButton(onClick = { jobs.launch { upload() } }, enabled = !commands.uploadBusy && commands.pending == null) { Text(if (intent == null) hiltechText("حفظ المستند الفني", "Save technical document") else hiltechText("استكمال نفس الرفع", "Resume the same upload")) } },
            dismissButton = { TextButton(onClick = { uploadOpen = false }, enabled = !commands.uploadBusy) { Text(hiltechText("عودة", "Back")) } },
        )
    }
    viewer?.let { (_, doc) -> ProtectedDocumentPageViewer(doc.title, requestedPage, page?.pageCount ?: 0, image, pageLoading, pageError,
        onPage = { jobs.launch { loadPage(it) } }, onRetry = { jobs.launch { loadPage(requestedPage) } }, onClose = ::clearViewer,
        onDisplayed = {
            val rendered = page
            if (rendered?.viewReceiptId != null && displayedReceipt != rendered.viewReceiptId) {
                displayedReceipt = rendered.viewReceiptId
                jobs.launch {
                    try { client.confirmDisplayed(rendered, installationId); refresh() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        pageError = supplierTechnicalProblem(failureCode(failure), language); displayedReceipt = null
                        if (revoked(failure)) { clearSource(); loadError = failureCode(failure) }
                    }
                }
            }
        }) }
}
