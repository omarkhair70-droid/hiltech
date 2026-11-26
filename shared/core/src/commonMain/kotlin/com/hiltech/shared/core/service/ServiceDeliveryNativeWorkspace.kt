@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hiltech.shared.core.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.hiltech.shared.core.NativeOperationalWorkspace
import com.hiltech.shared.core.design.*
import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.procurement.ServiceSelectedFile
import com.hiltech.shared.core.projects.ProjectSummaryDto
import com.hiltech.shared.core.projects.ProjectsApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

fun serviceDeliveryNativeWorkspace(
    projects: ProjectsApiClient,
    delivery: ServiceDeliveryApiClient,
    handover: HandoverApiClient,
    reviews: HandoverReviewApiClient,
    evidence: EvidenceApiClient,
    identityId: String,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    pickDocument: suspend () -> ServiceSelectedFile?,
    exportDocument: suspend (filename: String, contentType: String, bytes: ByteArray, expectedSha256: String) -> Boolean,
    onReauthenticate: () -> Unit = {},
): NativeOperationalWorkspace = NativeOperationalWorkspace(
    id = "handover-read", label = "التسليم والضمان", hostOwnsScroll = false,
    canReadSource = {
        val queue = delivery.reviewQueue(organizationId, installationId)
        if (queue.canReview || queue.canPrepare || queue.items.isNotEmpty()) true
        else {
            val accessible = projects.list(organizationId, installationId, 200).items
            accessible.any { project ->
                try { handover.readiness(project.projectId, installationId).organizationId == organizationId }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            }
        }
    },
    content = {
        ServiceDeliveryWorkspace(projects, delivery, handover, reviews, evidence,
            identityId, organizationId, installationId, newOperationId, pickDocument, exportDocument, onReauthenticate)
    },
)

/** Native workflow, shared by Windows and Android. Source-owned proof and command receipts remain authoritative. */
@Composable
fun ServiceDeliveryWorkspace(
    projects: ProjectsApiClient,
    delivery: ServiceDeliveryApiClient,
    handover: HandoverApiClient,
    reviews: HandoverReviewApiClient,
    evidence: EvidenceApiClient,
    identityId: String,
    organizationId: String,
    installationId: String,
    newOperationId: () -> String,
    pickDocument: suspend () -> ServiceSelectedFile?,
    exportDocument: suspend (filename: String, contentType: String, bytes: ByteArray, expectedSha256: String) -> Boolean,
    onReauthenticate: () -> Unit = {},
) = key(identityId, organizationId, installationId) {
    val language = LocalHiltechLanguage.current
    val jobs = rememberCoroutineScope()
    val json = remember { Json { encodeDefaults = true; explicitNulls = false } }
    val uploader = remember(delivery, evidence) { ServiceDeliveryUploadCoordinator(delivery, evidence) }
    var projectList by remember { mutableStateOf(emptyList<ProjectSummaryDto>()) }
    var queue by remember { mutableStateOf<ServiceDeliveryReviewQueueDto?>(null) }
    var source by remember { mutableStateOf<ServiceDeliveryWorkspaceDto?>(null) }
    var selectedProject by rememberSaveable { mutableStateOf("") }
    var selectedProposalId by rememberSaveable { mutableStateOf("") }
    var selectedProposal by remember { mutableStateOf<ServiceDeliveryProposalDto?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("HANDOVER") }
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var action by rememberSaveable { mutableStateOf("") }
    var detailsJson by rememberSaveable { mutableStateOf(json.encodeToString(ServiceDeliveryDetails())) }
    val draftDetails = json.decodeFromString<ServiceDeliveryDetails>(detailsJson)
    var formAttempted by remember { mutableStateOf(false) }
    var incidentOpen by rememberSaveable { mutableStateOf(false) }
    var incidentJson by rememberSaveable { mutableStateOf(json.encodeToString(ServiceDeliveryIncidentDraft())) }
    val incidentDraft = json.decodeFromString<ServiceDeliveryIncidentDraft>(incidentJson)
    var incidentAttempted by remember { mutableStateOf(false) }
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = pendingJson?.let { json.decodeFromString<ServiceDeliveryIntent>(it) }
    var commandAttempted by rememberSaveable { mutableStateOf(false) }
    var uncertain by rememberSaveable { mutableStateOf(false) }
    var rejected by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var acknowledgement by rememberSaveable { mutableStateOf<String?>(null) }
    var commandError by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewReason by rememberSaveable { mutableStateOf("") }
    var packageId by rememberSaveable { mutableStateOf("") }
    var checkSummary by remember { mutableStateOf<HandoverReviewSummaryDto?>(null) }
    var checkEvents by remember { mutableStateOf(emptyList<HandoverReviewEventDto>()) }
    var checkType by rememberSaveable { mutableStateOf("") }
    var checkReason by rememberSaveable { mutableStateOf("") }
    var checkLoading by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var uploadPurpose by rememberSaveable { mutableStateOf("") }
    var uploadTitle by rememberSaveable { mutableStateOf("") }
    var uploadJson by rememberSaveable { mutableStateOf<String?>(null) }
    val uploadIntent = uploadJson?.let { json.decodeFromString<ServiceDeliveryUploadIntent>(it) }
    var chosenFile by remember { mutableStateOf<ServiceSelectedFile?>(null) }
    var uploadBusy by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    var viewerProof by remember { mutableStateOf<ServiceDeliveryProofDto?>(null) }
    var viewerProject by remember { mutableStateOf("") }
    var viewerProposal by remember { mutableStateOf<String?>(null) }
    var viewerPage by remember { mutableStateOf<ServiceDeliveryEvidencePageDto?>(null) }
    var viewerBytes by remember { mutableStateOf<ByteArray?>(null) }
    var viewerLoading by remember { mutableStateOf(false) }
    var viewerError by remember { mutableStateOf<String?>(null) }
    var viewerRequestedPage by remember { mutableIntStateOf(0) }
    var confirmedPage by remember { mutableStateOf<String?>(null) }
    val enabled = !loading && !submitting && !uploadBusy && pending == null && error == null

    fun setDetails(value: ServiceDeliveryDetails) { detailsJson = json.encodeToString(value) }
    fun startIncident() { incidentOpen = true; incidentAttempted = false }
    fun clearViewer() { viewerProof = null; viewerPage = null; viewerBytes = null; viewerError = null; confirmedPage = null }
    fun prepare(intent: ServiceDeliveryIntent) {
        if (pending != null || submitting) return
        pendingJson = json.encodeToString(intent); confirm = true; commandAttempted = false; uncertain = false; rejected = false
        acknowledgement = null; commandError = null
    }
    fun startAction(kind: String, old: ServiceDeliveryEventDto? = null) {
        val s = source ?: return
        action = kind; formAttempted = false
        val fresh = when (kind) {
            "HANDOVER_RELEASE" -> ServiceDeliveryDetails(packageVersionId = s.packages.maxByOrNull { it.versionNumber }?.packageVersionId)
            "HANDOVER_CLIENT_RESPONSE" -> ServiceDeliveryDetails(releaseEventId = s.activeReleaseEventId,
                correctsEventId = s.events.filter { it.action == kind && it.details.releaseEventId == s.activeReleaseEventId }.maxByOrNull { it.eventNumber }?.eventId)
            "HANDOVER_WITHDRAW" -> ServiceDeliveryDetails(releaseEventId = s.activeReleaseEventId)
            else -> old?.details?.copy(correctsEventId = old.eventId, reason = "") ?: ServiceDeliveryDetails()
        }
        setDetails(fresh)
    }
    suspend fun loadPage(index: Int) {
        val proof = viewerProof ?: return
        val project = viewerProject; val proposal = viewerProposal
        viewerRequestedPage = index; viewerLoading = true; viewerBytes = null; viewerPage = null; viewerError = null; confirmedPage = null
        try {
            val page = delivery.evidencePage(organizationId, project, proposal, proof, index, installationId)
            if (viewerProof?.evidenceId != proof.evidenceId || viewerProposal != proposal || viewerProject != project) return
            val bytes = Base64.Default.decode(page.imageBase64)
            check(bytes.size in 1..16_777_216)
            viewerPage = page; viewerBytes = bytes
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { viewerError = serviceDeliveryProblem((failure as? HiltechApiException)?.code, language) }
        finally { viewerLoading = false }
    }
    fun openProof(proof: ServiceDeliveryProofDto, project: String, proposal: String?) {
        viewerProof = proof; viewerProject = project; viewerProposal = proposal
        jobs.launch { loadPage(0) }
    }
    suspend fun execute(intent: ServiceDeliveryIntent) {
        if (submitting) return
        val previousUncertain = uncertain
        submitting = true; commandAttempted = true; uncertain = true; rejected = false; commandError = null
        try {
            var openedIncident: InternalSupportTicketDto? = null
            when (intent.kind) {
                "SNAPSHOT" -> handover.createDraft(intent.projectId, requireNotNull(intent.draft), installationId)
                "CHECK" -> reviews.record(intent.projectId, requireNotNull(intent.versionId), requireNotNull(intent.internalReview), installationId)
                "PROPOSE" -> delivery.propose(intent.organizationId, intent.projectId, requireNotNull(intent.proposal), installationId)
                "REVIEW" -> delivery.review(intent.organizationId, intent.projectId, requireNotNull(intent.proposalId), requireNotNull(intent.review), installationId)
                "SUPPORT_TICKET" -> openedIncident = delivery.openIncident(intent.projectId, identityId, requireNotNull(intent.ticket), installationId)
                else -> kotlin.error("Invalid saved command")
            }
            acknowledgement = intent.title; pendingJson = null; confirm = false; uncertain = false; rejected = false
            if (openedIncident != null) {
                tab = "WARRANTY"; action = "WARRANTY_ASSESSMENT"; incidentOpen = false
                setDetails(ServiceDeliveryDetails(ticketId = openedIncident.ticketId, incidentDescription = openedIncident.description))
                incidentJson = json.encodeToString(ServiceDeliveryIncidentDraft())
            } else action = ""
            checkReason = ""; reviewReason = ""; refresh++
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val disposition = serviceDeliveryFailure(previousUncertain, failure)
            uncertain = disposition.unknown; rejected = disposition.rejected
            commandError = (failure as? HiltechApiException)?.code
            if ((failure as? HiltechApiException)?.httpStatus in setOf(401, 403, 404)) { source = null; selectedProposal = null; clearViewer() }
        } finally { submitting = false }
    }
    suspend fun sendUpload() {
        var intent = uploadIntent
        if (uploadBusy) return
        if (intent == null) {
            val file = chosenFile
            if (selectedProject.isBlank() || uploadPurpose.isBlank() || uploadTitle.isBlank() || file == null || !serviceDeliverySelectedFileValid(file)) {
                uploadError = serviceDeliveryProblem("FORM_INCOMPLETE", language); return
            }
            intent = ServiceDeliveryUploadIntent(newOperationId(), newOperationId(), newOperationId(), organizationId, selectedProject,
                uploadPurpose, uploadTitle.trim(), file.originalFileName, file.contentType, file.bytes.size.toLong(), file.sha256, file.capturedAt)
            uploadJson = json.encodeToString(intent)
        }
        uploadBusy = true; uploadError = null
        try {
            val ready = uploader.upload(intent, chosenFile, installationId) { progress -> uploadJson = json.encodeToString(progress) }
            if (action.isNotBlank() && intent.projectId == selectedProject) setDetails(if (intent.purpose == "AGREEMENT")
                draftDetails.copy(agreementEvidenceId = ready.evidenceId) else draftDetails.copy(proofEvidenceId = ready.evidenceId))
            uploadJson = null; uploadPurpose = ""; chosenFile = null; uploadTitle = ""
            acknowledgement = language.text("تم حفظ المستند الأصلي", "Original document saved"); refresh++
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { uploadError = serviceDeliveryProblem((failure as? HiltechApiException)?.code ?: failure.message?.takeIf { it == "SERVICE_DELIVERY_RESELECT_SAME_FILE" }, language) }
        finally { uploadBusy = false }
    }

    LaunchedEffect(refresh) {
        loading = true; error = null; clearViewer()
        try {
            queue = delivery.reviewQueue(organizationId, installationId)
            projectList = try { projects.list(organizationId, installationId, 200).items.filter { it.organizationId == organizationId } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: HiltechApiException) { if (failure.httpStatus in setOf(403, 404) && queue?.canReview == true) emptyList() else throw failure }
            if (selectedProject.isNotBlank()) source = delivery.workspace(organizationId, selectedProject, installationId)
            if (selectedProposalId.isNotBlank()) selectedProposal = delivery.proposal(organizationId, selectedProposalId, installationId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            error = serviceDeliveryProblem((failure as? HiltechApiException)?.code, language)
            projectList = emptyList(); queue = null; source = null; selectedProposal = null; checkSummary = null; checkEvents = emptyList()
        } finally { loading = false }
    }
    LaunchedEffect(selectedProject) {
        if (selectedProject.isBlank()) { source = null; return@LaunchedEffect }
        loading = true; source = null; error = null; clearViewer(); packageId = ""
        try { source = delivery.workspace(organizationId, selectedProject, installationId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = serviceDeliveryProblem((failure as? HiltechApiException)?.code, language) }
        finally { loading = false }
    }
    LaunchedEffect(selectedProposalId) {
        if (selectedProposalId.isBlank()) { selectedProposal = null; return@LaunchedEffect }
        loading = true; selectedProposal = null; error = null; clearViewer()
        try { selectedProposal = delivery.proposal(organizationId, selectedProposalId, installationId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = serviceDeliveryProblem((failure as? HiltechApiException)?.code, language) }
        finally { loading = false }
    }
    LaunchedEffect(selectedProject, packageId, refresh) {
        checkSummary = null; checkEvents = emptyList(); checkError = null
        if (selectedProject.isBlank() || packageId.isBlank()) return@LaunchedEffect
        checkLoading = true
        try {
            checkSummary = reviews.summary(selectedProject, packageId, installationId)
            checkEvents = reviews.events(selectedProject, packageId, installationId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { checkError = language.text("تعذّر تحميل سجل الفحص لهذه النسخة.", "This version's check history could not be loaded.") }
        finally { checkLoading = false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                HiltechSectionHeading("DELIVERY · CARE", hiltechText("التسليم والضمان", "Handover and warranty"),
                    hiltechText("من المستند الأصلي إلى الإصدار واستجابة العميل وقرار التغطية.", "From original documents to release, client response and coverage decisions."))
            }
            TextButton(enabled = !loading && !submitting && !uploadBusy, onClick = { refresh++ }) { Text(hiltechText("تحديث", "Refresh")) }
        }
        acknowledgement?.let {
            HiltechPanel(Modifier.fillMaxWidth()) {
                HiltechStateTag(hiltechText("النتيجة مؤكدة", "Result confirmed"), HiltechStateTone.CURRENT)
                Text(it)
                if (error != null) Text(hiltechText("تم تأكيد الحفظ؛ تعذّر تحديث العرض فقط.", "Saving was confirmed; only the refreshed view could not be loaded."))
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        pending?.let { saved ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                Text(saved.title, style = MaterialTheme.typography.titleMedium)
                Text(if (uncertain) hiltechText("لم يصل تأكيد نهائي. أعد إرسال نفس الطلب؛ لا تُنشئ بديلًا قبل معرفة نتيجته.",
                    "No final confirmation arrived. Retry the same request; do not create a replacement before its result is known.")
                    else if (rejected) hiltechText("رفض المصدر هذا الطلب. راجع السبب قبل تجهيز طلب مصحح.", "The source rejected this request. Review the reason before preparing a correction.")
                    else hiltechText("طلب محفوظ للمراجعة والتأكيد.", "Saved request awaiting your confirmation."))
                if (commandError != null || uncertain || rejected) Text(serviceDeliveryProblem(commandError, language), color = MaterialTheme.colorScheme.error)
                if (commandError in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED")) TextButton(onClick = onReauthenticate) { Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in")) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !submitting && !rejected, onClick = { if (commandAttempted) jobs.launch { execute(saved) } else confirm = true }) {
                        Text(if (commandAttempted) hiltechText("إعادة نفس الطلب", "Retry same request") else hiltechText("مراجعة وتأكيد", "Review and confirm"))
                    }
                    if (!commandAttempted || rejected) TextButton(enabled = !submitting, onClick = {
                        if (saved.kind == "SUPPORT_TICKET") incidentOpen = true
                        pendingJson = null; confirm = false; commandError = null
                    }) {
                        Text(hiltechText("العودة للتحرير", "Return to editing"))
                    }
                }
            }
        }
        if (selectedProject.isBlank() && selectedProposalId.isBlank()) {
            val requests = queue?.items.orEmpty()
            if (queue?.canReview == true || requests.isNotEmpty()) {
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(hiltechText("طلبات تحتاج مراجعتي", "Requests awaiting my review"), style = MaterialTheme.typography.titleLarge)
                    if (requests.isEmpty()) Text(hiltechText("لا توجد طلبات معلقة حاليًا.", "No requests are pending now."))
                    requests.forEach { p ->
                        DeliveryProposalRow(p, enabled = !loading && !submitting) { selectedProposalId = p.proposalId }
                    }
                }
            }
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(hiltechText("بحث بالمشروع أو العميل أو الكود", "Search project, client or code")) })
            val matching = projectList.filter { search.isBlank() || listOf(it.name, it.projectCode, it.clientDisplayName).any { value -> value.contains(search.trim(), true) } }
            matching.forEach { project ->
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                    Text(project.projectCode + " · " + project.clientDisplayName, style = MaterialTheme.typography.bodyMedium)
                    TextButton(enabled = !loading && !submitting, onClick = { selectedProject = project.projectId; action = "" }) { Text(hiltechText("فتح ملف التسليم والضمان", "Open handover and warranty")) }
                }
            }
            if (!loading && error == null && matching.isEmpty()) Text(hiltechText("لا توجد مشاريع مطابقة متاحة لهذا الحساب.", "No matching projects are available to this account."))
            if (projectList.size == 200) Text(hiltechText("تُعرض أحدث ٢٠٠ مشروع متاح. افتح المشروع من مساحة المشاريع إذا لم يظهر هنا.", "The latest 200 accessible projects are shown. Open the project from Projects if it is not listed here."))
        } else {
            TextButton(enabled = !submitting && !uploadBusy, onClick = {
                if (selectedProposalId.isNotBlank()) { selectedProposalId = ""; selectedProposal = null }
                else { selectedProject = ""; source = null; action = "" }
                clearViewer(); error = null
            }) { Text(hiltechText("عودة إلى القائمة", "Back to list")) }
        }
        selectedProposal?.let { p ->
            ServiceDeliveryProposalPanel(p, enabled = !submitting && pending == null, reason = reviewReason, onReason = { reviewReason = it },
                onOpen = { openProof(it, p.projectId, p.proposalId) }, onDecision = { decision ->
                    val op = newOperationId()
                    prepare(ServiceDeliveryIntent(op, organizationId, p.projectId, "REVIEW",
                        language.text("تسجيل قرار المراجعة: ", "Record review decision: ") + serviceDeliveryLabel(p.action, language),
                        proposalId = p.proposalId, review = ServiceDeliveryReviewRequest(op, decision, reviewReason.trim().ifBlank { null })))
                })
        }
        if (selectedProposalId.isBlank()) source?.let { s ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                Text(s.projectName, style = MaterialTheme.typography.headlineSmall)
                Text(s.projectCode + " · " + s.clientName)
                HiltechStateTag(serviceDeliveryLabel(s.deliveryState, language), if (s.deliveryState == "CLIENT_ACCEPTED") HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("HANDOVER" to language.text("التسليم", "Handover"), "WARRANTY" to language.text("الضمان", "Warranty"),
                        "DOCUMENTS" to language.text("المستندات", "Documents"), "HISTORY" to language.text("التاريخ والمراجعات", "History and reviews")).forEach { (id, label) ->
                        FilterChip(selected = tab == id, onClick = { tab = id; action = "" }, enabled = pending == null && !submitting, label = { Text(label) })
                    }
                }
            }
            if (action.isNotBlank()) ServiceDeliveryForm(s, action, draftDetails, enabled, formAttempted, ::setDetails,
                onSubmit = {
                    formAttempted = true
                    if (serviceDeliveryFormComplete(action, draftDetails)) {
                        val op = newOperationId()
                        val base = if (action.startsWith("HANDOVER_")) s.handoverEventNumber
                            else draftDetails.correctsEventId?.let { id -> s.events.firstOrNull { it.eventId == id }?.eventNumber } ?: 0
                        prepare(ServiceDeliveryIntent(op, organizationId, s.projectId, "PROPOSE", serviceDeliveryLabel(action, language),
                            proposal = ServiceDeliveryProposeRequest(op, action, base, draftDetails)))
                    }
                }, onCancel = { action = "" }, onOpen = { openProof(it, s.projectId, null) }, onUpload = { purpose ->
                    uploadPurpose = purpose; uploadTitle = ""; chosenFile = null; uploadError = null
                }, onCreateIncident = ::startIncident)
            else when (tab) {
                "HANDOVER" -> {
                    ServiceDeliveryReadiness(s)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(enabled = enabled, onClick = {
                            val op = newOperationId()
                            prepare(ServiceDeliveryIntent(op, organizationId, s.projectId, "SNAPSHOT", language.text("حفظ نسخة جديدة للمراجعة", "Save a new review version"),
                                draft = HandoverDraftRequestDto(op, s.projectVersion, s.packages.maxOfOrNull { it.versionNumber } ?: 0)))
                        }) { Text(hiltechText("تجهيز نسخة للمراجعة", "Prepare review version")) }
                        Button(enabled = enabled, onClick = { startAction("HANDOVER_RELEASE") }) { Text(hiltechText("إصدار حزمة التسليم", "Issue handover package")) }
                        if (s.activeReleaseEventId != null) {
                            OutlinedButton(enabled = enabled, onClick = { startAction("HANDOVER_CLIENT_RESPONSE") }) { Text(hiltechText("تسجيل استجابة العميل", "Record client response")) }
                            TextButton(enabled = enabled, onClick = { startAction("HANDOVER_WITHDRAW") }) { Text(hiltechText("سحب الإصدار", "Withdraw release")) }
                        }
                    }
                    if (s.activeReleaseEventId != null) {
                        val releaseId = s.activeReleaseEventId
                        val release = s.events.firstOrNull { it.eventId == releaseId }
                        val approved = s.proposals.firstOrNull { it.proposalId == release?.proposalId }
                        HiltechPanel(Modifier.fillMaxWidth()) {
                            Text(hiltechText("ملفات الإصدار الحالي", "Current release files"), style = MaterialTheme.typography.titleMedium)
                            Text(hiltechText("احفظ الحزمة ثم سلّمها بالوسيلة المعتمدة. التصدير لا يرسلها للعميل تلقائيًا.", "Save the package and deliver it through the agreed channel. Export does not send it to the client."))
                            fun export(evidenceId: String?) { jobs.launch {
                                exportBusy = true
                                try {
                                    val file = delivery.packageDownload(organizationId, s.projectId, releaseId, evidenceId, installationId)
                                    val saved = exportDocument(file.filename, file.contentType, Base64.Default.decode(file.base64), file.sha256)
                                    if (saved) acknowledgement = language.text("تم حفظ الملف على الجهاز", "File saved on the device")
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = serviceDeliveryProblem((failure as? HiltechApiException)?.code, language) }
                                finally { exportBusy = false }
                            } }
                            Button(enabled = !exportBusy && enabled, onClick = { export(null) }) { Text(hiltechText("حفظ حزمة التسليم", "Save handover package")) }
                            approved?.packageDocuments?.forEach { document ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(document.documentTitle.ifBlank { language.text("مستند تسليم", "Handover document") }, Modifier.weight(1f))
                                    TextButton(enabled = !exportBusy && enabled, onClick = { export(document.evidenceId) }) { Text(hiltechText("حفظ الأصل", "Save original")) }
                                }
                            }
                        }
                    }
                    HiltechPanel(Modifier.fillMaxWidth()) {
                        Text(hiltechText("فحص نسخة الحزمة", "Check package version"), style = MaterialTheme.typography.titleMedium)
                        HiltechRecordPicker(hiltechText("نسخة المراجعة", "Review version"), packageId,
                            s.packages.map { HiltechRecordChoice(it.packageVersionId, language.text("النسخة ", "Version ") + it.versionNumber,
                                language.text("عدد المستندات: ", "Document count: ") + it.documents.size) }, { packageId = it }, allowKnownReference = false, enabled = enabled)
                        if (checkLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        checkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        s.packages.firstOrNull { it.packageVersionId == packageId }?.let { version -> DeliveryDocumentList(version.documents) }
                        checkSummary?.let { check ->
                            if (!check.sourceStillMatches || !check.isLatestPackageVersion) Text(hiltechText("هذه نسخة تاريخية أو تغيّر مصدرها؛ جهّز نسخة جديدة قبل الإصدار.", "This is a historical version or its source changed. Prepare a new version before release."))
                            val types = when (check.reviewState) {
                                "NOT_STARTED" -> listOf("CHECK_STARTED")
                                "CHECKING", "FINDINGS_RECORDED" -> listOf("FINDING_RECORDED") + if (check.reviewState == "FINDINGS_RECORDED") listOf("RECHECK_REQUESTED") else emptyList()
                                else -> emptyList()
                            }
                            if (types.isNotEmpty() && check.sourceStillMatches && check.isLatestPackageVersion) {
                                HiltechRecordPicker(hiltechText("إجراء الفحص", "Check action"), checkType, types.map { HiltechRecordChoice(it, serviceDeliveryLabel(it, language)) }, { checkType = it }, allowKnownReference = false, enabled = enabled)
                                OutlinedTextField(checkReason, { checkReason = it }, Modifier.fillMaxWidth(), minLines = 2, enabled = enabled, label = { Text(hiltechText("ملاحظات الفحص", "Check notes")) })
                                OutlinedButton(enabled = enabled && checkType in types && checkReason.trim().isNotBlank(), onClick = {
                                    val op = newOperationId()
                                    prepare(ServiceDeliveryIntent(op, organizationId, s.projectId, "CHECK", serviceDeliveryLabel(checkType, language), versionId = packageId,
                                        internalReview = HandoverReviewRecordDto(op, check.latestEventNumber, checkType, checkReason.trim())))
                                }) { Text(hiltechText("تسجيل الفحص", "Record check")) }
                            }
                            checkEvents.forEach { e ->
                                Text(serviceDeliveryLabel(e.eventType, language) + " · " + hiltechDateText(e.createdAt.take(10)), style = MaterialTheme.typography.titleSmall)
                                Text(e.reason)
                            }
                        }
                    }
                }
                "WARRANTY" -> {
                    Text(hiltechText("تسجيل شروط الضمان وقرارات التغطية يعتمد على مستندات الشركة الفعلية؛ لا يبدأ ضمان تلقائي بمجرد تسليم المشروع.",
                        "Warranty terms and coverage decisions use the company's actual agreements. Handover alone does not start a warranty."))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = enabled, onClick = { startAction("WARRANTY_TERMS") }) { Text(hiltechText("إضافة شروط ضمان", "Add warranty terms")) }
                        OutlinedButton(enabled = enabled, onClick = ::startIncident) { Text(hiltechText("فتح بلاغ جديد", "Open new incident")) }
                        OutlinedButton(enabled = enabled, onClick = { startAction("WARRANTY_ASSESSMENT") }) { Text(hiltechText("تقييم تغطية بلاغ", "Assess incident coverage")) }
                    }
                    if (s.warrantyTerms.isEmpty()) Text(hiltechText("لا توجد شروط ضمان معتمدة لهذا المشروع بعد.", "No warranty terms have been approved for this project yet."))
                    s.warrantyTerms.forEach { event ->
                        HiltechPanel(Modifier.fillMaxWidth()) {
                            ServiceDeliveryDetailsView(event.action, event.details, event.scopeName)
                            TextButton(enabled = enabled, onClick = { startAction("WARRANTY_TERMS", event) }) { Text(hiltechText("تصحيح الشروط مع حفظ التاريخ", "Correct terms and retain history")) }
                        }
                    }
                    s.events.filter { it.action == "WARRANTY_ASSESSMENT" }.distinctBy { it.details.ticketId }.forEach { event ->
                        HiltechPanel(Modifier.fillMaxWidth()) {
                            ServiceDeliveryDetailsView(event.action, event.details, event.scopeName)
                            TextButton(enabled = enabled, onClick = { startAction("WARRANTY_ASSESSMENT", event) }) { Text(hiltechText("تصحيح قرار التغطية", "Correct coverage decision")) }
                        }
                    }
                }
                "DOCUMENTS" -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("AGREEMENT", "CLIENT_RESPONSE", "WARRANTY_SUPPORT", "INCIDENT").forEach { purpose ->
                            OutlinedButton(enabled = enabled, onClick = { uploadPurpose = purpose; uploadTitle = ""; uploadError = null; chosenFile = null }) { Text(serviceDeliveryLabel(purpose, language)) }
                        }
                    }
                    if (s.proofDocuments.isEmpty()) Text(hiltechText("أرفق اتفاقًا أو استجابة عميل أو دليل بلاغ ليظهر هنا.", "Attach an agreement, client response or incident evidence to see it here."))
                    s.proofDocuments.forEach { proof ->
                        HiltechPanel(Modifier.fillMaxWidth()) {
                            Text(proof.title, style = MaterialTheme.typography.titleMedium)
                            Text(serviceDeliveryLabel(proof.purpose, language))
                            TextButton(enabled = enabled, onClick = { openProof(proof, s.projectId, null) }) { Text(hiltechText("فتح الأصل", "Open original")) }
                        }
                    }
                }
                else -> {
                    Text(hiltechText("الطلبات والقرارات محفوظة بترتيبها، والتصحيحات مرتبطة بما سبقها.", "Requests and decisions are retained in order; corrections reference the preceding record."))
                    s.proposals.forEach { p -> DeliveryProposalRow(p, enabled = !submitting) { selectedProposalId = p.proposalId } }
                    s.events.forEach { event ->
                        HiltechPanel(Modifier.fillMaxWidth()) {
                            Text(serviceDeliveryLabel(event.action, language) + " · " + event.eventNumber, style = MaterialTheme.typography.titleMedium)
                            Text(hiltechDateText(event.recordedAt.take(10)) + " · " + event.recordedByName)
                            ServiceDeliveryDetailsView(event.action, event.details, event.scopeName)
                        }
                    }
                }
            }
            if (tab != "HISTORY" && action.isBlank()) s.proposals.filter { it.state in setOf("PENDING", "APPROVED", "STALE") }.forEach { p ->
                DeliveryProposalRow(p, enabled = !submitting) { selectedProposalId = p.proposalId }
            }
        }
    }

    if (incidentOpen && pending == null) AlertDialog(
        onDismissRequest = { if (!submitting) incidentOpen = false },
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(.95f), properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(hiltechText("فتح بلاغ للمشروع", "Open project incident")) },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            ServiceDeliveryIncidentForm(incidentDraft, enabled, incidentAttempted,
                { incidentJson = json.encodeToString(it) }, onSave = {
                    incidentAttempted = true
                    val current = source
                    if (incidentDraft.complete() && current != null) {
                        val op = newOperationId()
                        incidentOpen = false
                        prepare(ServiceDeliveryIntent(op, organizationId, current.projectId, "SUPPORT_TICKET",
                            language.text("فتح بلاغ: ", "Open incident: ") + incidentDraft.title.trim(),
                            ticket = CreateInternalSupportTicketDto(op, current.projectVersion, incidentDraft.title.trim(), incidentDraft.description.trim(), incidentDraft.severity, incidentDraft.priority)))
                    }
                }, onCancel = { incidentOpen = false })
        } },
        confirmButton = {},
    )
    if (confirm && pending != null) AlertDialog(
        onDismissRequest = { if (!submitting) confirm = false },
        title = { Text(pending.title) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(source?.projectName ?: selectedProposal?.projectName.orEmpty(), style = MaterialTheme.typography.titleMedium)
            pending.proposal?.let { ServiceDeliveryDetailsView(it.action, it.details, source?.scopeChoices?.firstOrNull { s -> s.id == it.details.scopeId }?.title) }
            pending.internalReview?.let { Text(it.reason) }
            pending.ticket?.let {
                Text(it.title, style = MaterialTheme.typography.titleMedium); Text(it.description)
                Text(serviceIncidentLabel(it.severityCode, language) + " · " + serviceIncidentLabel(it.priorityCode, language))
                Text(hiltechText("سيظهر البلاغ داخل هذا المشروع ويمكن متابعة تقييم تغطيته بعد الحفظ.",
                    "The incident will appear in this project, ready for a separate coverage assessment."))
            }
            pending.review?.let { review ->
                Text(when (review.decision) { "APPROVE" -> language.text("اعتماد السجل وتثبيت النتيجة", "Approve and record the result"); "REJECT" -> language.text("رفض الطلب مع حفظ السبب", "Reject with a recorded reason"); else -> language.text("إغلاق الطلب لتغيّر المصدر دون اعتماد نتيجته", "Close because the source changed without approving its result") })
                review.comment?.let { Text(it) }
            }
            Text(hiltechText("سيُعاد التحقق من المصدر والمسؤوليات عند التنفيذ.", "Current sources and responsibilities are checked again when executed."))
        } },
        confirmButton = { Button(enabled = !submitting, onClick = { jobs.launch { execute(pending) } }) { Text(hiltechText("تأكيد", "Confirm")) } },
        dismissButton = { TextButton(enabled = !submitting, onClick = { confirm = false }) { Text(hiltechText("عودة", "Back")) } },
    )
    if (uploadPurpose.isNotBlank() || uploadIntent != null) AlertDialog(
        onDismissRequest = { if (!uploadBusy) { uploadPurpose = ""; if (uploadIntent == null) chosenFile = null } },
        modifier = Modifier.widthIn(max = 700.dp).fillMaxWidth(.95f), properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(hiltechText("إرفاق مستند أصلي", "Attach original document")) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(serviceDeliveryLabel(uploadIntent?.purpose ?: uploadPurpose, language))
            Text(hiltechText("PDF أو صورة، بحد أقصى ١٦ ميجابايت. يبقى المستند داخل نطاق المشروع.", "PDF or image, up to 16 MiB. The document remains scoped to this project."))
            OutlinedTextField(uploadIntent?.title ?: uploadTitle, { uploadTitle = it }, Modifier.fillMaxWidth(), enabled = uploadIntent == null && !uploadBusy,
                label = { Text(hiltechText("اسم واضح للمستند", "Clear document title")) })
            Text(chosenFile?.originalFileName ?: uploadIntent?.filename ?: language.text("لم يُحدد ملف", "No file selected"))
            OutlinedButton(enabled = !uploadBusy, onClick = { jobs.launch {
                try {
                    val file = pickDocument() ?: return@launch
                    if (!serviceDeliverySelectedFileValid(file) || (uploadIntent != null && !serviceDeliveryFileMatches(uploadIntent, file)))
                        uploadError = serviceDeliveryProblem(if (uploadIntent != null) "SERVICE_DELIVERY_RESELECT_SAME_FILE" else "SERVICE_DELIVERY_PREVIEW_UNAVAILABLE", language)
                    else { chosenFile = file; uploadError = null }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { uploadError = language.text("تعذّر اختيار الملف. حاول مرة أخرى.", "File selection failed. Try again.") }
            } }) { Text(if (uploadIntent == null) hiltechText("اختيار الملف", "Choose file") else hiltechText("اختيار نفس الملف للمتابعة", "Choose the same file to resume")) }
            uploadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (uploadBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { Button(enabled = !uploadBusy && !submitting, onClick = { jobs.launch { sendUpload() } }) { Text(hiltechText("حفظ المستند", "Save document")) } },
        dismissButton = { TextButton(enabled = !uploadBusy, onClick = { uploadPurpose = ""; uploadJson = null; chosenFile = null; uploadError = null }) {
            Text(hiltechText("إلغاء الرفع الحالي", "Cancel current upload"))
        } },
    )
    viewerProof?.let { proof ->
        ProtectedDocumentPageViewer(proof.title, viewerRequestedPage, viewerPage?.pageCount ?: 0, viewerBytes, viewerLoading, viewerError,
            onPage = { page -> jobs.launch { loadPage(page) } }, onRetry = { jobs.launch { loadPage(viewerRequestedPage) } }, onClose = ::clearViewer,
            onDisplayed = {
                val page = viewerPage
                if (page?.proposalId != null && page.viewReceiptId != confirmedPage) {
                    confirmedPage = page.viewReceiptId
                    jobs.launch {
                        try {
                            delivery.confirmDisplayed(page, installationId)
                            val current = delivery.proposal(organizationId, page.proposalId, installationId)
                            if (selectedProposalId == current.proposalId) selectedProposal = current
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            viewerError = serviceDeliveryProblem((failure as? HiltechApiException)?.code, language)
                            viewerBytes = null
                            if ((failure as? HiltechApiException)?.httpStatus in setOf(401, 403, 404)) selectedProposal = null
                        }
                    }
                }
            })
    }
}
