package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechRecordChoice
import com.hiltech.shared.core.design.HiltechRecordPicker
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.hiltechDateText
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@Composable
fun FinancePayrollDisbursementWorkspace(
    organizationId: String,
    sessionScope: String,
    client: FinancePayrollDisbursementApiClient,
    installationId: String,
    newOperationId: () -> String,
    onReauthenticate: () -> Unit = {},
) = key(sessionScope, organizationId) {
    val jobs = rememberCoroutineScope()
    var access by remember { mutableStateOf<FinancePayrollCapabilityDto?>(null) }
    var batches by remember { mutableStateOf(emptyList<FinancePayrollBatchDto>()) }
    var run by remember { mutableStateOf<FinancePayrollRunDto?>(null) }
    var runId by rememberSaveable { mutableStateOf("") }
    var instructionId by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf("SUCCESS") }
    var bankId by rememberSaveable { mutableStateOf("") }
    var bankLines by remember { mutableStateOf(emptyList<FinancePayrollBankLineDto>()) }
    var reason by rememberSaveable { mutableStateOf("") }
    var beneficiaryConfirmed by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var runLoading by remember { mutableStateOf(false) }
    var bankLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var runFailed by remember { mutableStateOf(false) }
    var bankFailed by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var pendingJson by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmationDetail by rememberSaveable { mutableStateOf("") }
    var dialog by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var unknown by rememberSaveable { mutableStateOf(false) }
    var rejected by rememberSaveable { mutableStateOf(false) }
    var commandError by rememberSaveable { mutableStateOf<String?>(null) }
    var acknowledgement by rememberSaveable { mutableStateOf<String?>(null) }
    var rejectProposal by remember { mutableStateOf<FinancePayrollProposalDto?>(null) }
    var rejectionReason by rememberSaveable { mutableStateOf("") }
    var evidenceDocument by remember { mutableStateOf<FinancePayrollEvidenceDocumentDto?>(null) }
    var evidenceLoading by remember { mutableStateOf<String?>(null) }
    var evidenceError by remember { mutableStateOf<Pair<String, String>?>(null) }
    val pending = pendingJson?.let { Json.decodeFromString<FinancePayrollIntent>(it) }
    val batch = batches.firstOrNull { it.runVersionId == runId }
    val instruction = run?.instructions?.firstOrNull { it.instructionId == instructionId }
    val actions = if (instruction?.outcome == "SUCCESS") {
        if (instruction.resultAction == "SUCCESS") listOf("RETURNED", "VOID_MATCH") else listOf("RETURNED")
    } else listOf("SUCCESS")
    val enabled = pending == null && !submitting && !loading && !runLoading && error == null && !runFailed

    fun prepare(intent: FinancePayrollIntent, detail: String) {
        pendingJson = Json.encodeToString(FinancePayrollIntent.serializer(), intent)
        confirmationDetail = detail; dialog = true; attempted = false; unknown = false
        rejected = false; commandError = null; acknowledgement = null
    }

    LaunchedEffect(refresh) {
        loading = true; error = null
        try {
            val current = client.capabilities(organizationId, installationId)
            access = current
            batches = if (current.canPrepare || current.canReview) client.batches(organizationId, installationId) else emptyList()
            if (batches.none { it.runVersionId == runId }) runId = batches.firstOrNull()?.runVersionId.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = (failure as? HiltechApiException)?.code ?: "LOAD_FAILED" }
        finally { loading = false }
    }

    LaunchedEffect(runId, refresh) {
        if (runId.isBlank()) { run = null; return@LaunchedEffect }
        runLoading = true; runFailed = false
        try {
            val current = client.run(organizationId, runId, installationId)
            run = current
            if (current.instructions.none { it.instructionId == instructionId }) instructionId = current.instructions.firstOrNull()?.instructionId.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { run = null; runFailed = true }
        finally { runLoading = false }
    }

    LaunchedEffect(instructionId, actions) {
        if (action !in actions) action = actions.first()
        reason = ""; beneficiaryConfirmed = false
    }

    LaunchedEffect(instructionId, action, refresh) {
        bankId = ""; bankLines = emptyList(); bankFailed = false
        if (instructionId.isBlank() || action == "VOID_MATCH" || access?.canPrepare != true) return@LaunchedEffect
        bankLoading = true
        try { bankLines = client.bankLines(organizationId, instructionId, action, installationId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { bankFailed = true }
        finally { bankLoading = false }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HiltechPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(hiltechText("صرف الرواتب والمطابقة", "Payroll disbursement records"), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(enabled = pending == null && !submitting && !loading, onClick = { refresh += 1 }) { Text(hiltechText("تحديث", "Refresh")) }
            }
            Text(hiltechText("اربط حركة بنكية تمت بالفعل بكشف الرواتب المعتمد. تظهر نتيجة الصرف للموظف بعد مراجعة مستقلة للدليل والمستفيد.",
                "Match an actual bank movement to released payroll. The employee's payment status changes after an independent review of the evidence and beneficiary."))
            acknowledgement?.let {
                HiltechStateTag(when (it) {
                    "PREPARED" -> hiltechText("تم تجهيز سجل المطابقة", "Reconciliation instructions prepared")
                    "SUBMITTED_FOR_REVIEW" -> hiltechText("أُرسلت المطابقة للمراجع المستقل", "Match submitted for independent review")
                    "REJECTED" -> hiltechText("تم رفض المطابقة دون تسجيل صرف", "Match rejected without recording payment")
                    else -> hiltechText("تم تسجيل النتيجة المعتمدة", "Approved result recorded")
                }, HiltechStateTone.CURRENT)
                if (error != null || runFailed) Text(hiltechText("النتيجة مؤكدة، لكن العرض المحدث لم يُحمّل. أعد التحديث دون إنشاء طلب آخر.",
                    "The result is confirmed, but the refreshed view could not load. Refresh without creating another request."))
            }
            if (pending != null) Button(enabled = !submitting, onClick = { dialog = true }) {
                Text(hiltechText("متابعة الطلب المحفوظ", "Resume retained request"))
            }
            if (loading) CircularProgressIndicator()
            when {
                error != null -> {
                    Text(hiltechText("تعذر تحميل كشوف الصرف. تحقق من الاتصال والصلاحية ثم حدّث العرض.", "Disbursement records could not load. Check your connection and access, then refresh."))
                    if (error in setOf("UNAUTHENTICATED", "REAUTH_REQUIRED")) TextButton(onClick = onReauthenticate) { Text(hiltechText("تأكيد الدخول", "Confirm sign-in")) }
                }
                access?.let { !it.canPrepare && !it.canReview } == true -> Text(hiltechText(
                    "تحتاج مسؤولية إعداد صرف الرواتب أو مراجعته. يمكن إسنادها من مركز مسؤوليات الموظفين.",
                    "Assign the Payroll disbursement preparation or review responsibility through employee administration to use this workspace."))
                access?.reviewConfigured == false -> Text(hiltechText(
                    "لم يُحدد المراجع المستقل بعد. يضبط مسؤول الإدارة مسار «مراجعة نتائج صرف الرواتب» من إعدادات المراجعة داخل التطبيق.",
                    "An independent reviewer has not been selected. Configure Payroll result review in the application's review-workflow settings."))
            }
            if (batches.isNotEmpty()) HiltechRecordPicker(
                label = hiltechText("كشف الرواتب", "Payroll run"), value = runId,
                choices = batches.map { HiltechRecordChoice(it.runVersionId,
                    hiltechDateText(it.periodStart) + " — " + hiltechDateText(it.periodEnd),
                    hiltechText("الإصدار ", "Generation ") + it.generation + " · " + it.payableTotal + " " + it.currency) },
                onChange = { runId = it; instructionId = "" }, allowKnownReference = false, allowClear = false,
                enabled = !loading && pending == null && !submitting,
            ) else if (!loading && error == null && access?.let { it.canPrepare || it.canReview } == true) Text(hiltechText(
                "لا توجد كشوف محالة إلى المالية. بعد اعتماد كشف الرواتب، أرسله للمراجعة المالية من مساحة الرواتب.",
                "No payroll runs have been handed to Finance. Release a payroll run and hand it off from the payroll workspace."))
            batch?.let { current ->
                Text(hiltechText("الموظفون: ", "Employees: ") + current.employeeCount + " · " + current.payableTotal + " " + current.currency)
                if (current.transferCount == 0) Text(hiltechText("لا توجد مبالغ مستحقة للتحويل في هذا الكشف.", "This run has no positive amounts requiring payment."))
                else if (!current.prepared && access?.canPrepare == true) Button(enabled = enabled, onClick = {
                    prepare(FinancePayrollIntent(newOperationId(), organizationId, "PREPARE_BATCH", current.reviewBatchId,
                        current.periodStart + " — " + current.periodEnd), current.payableTotal + " " + current.currency)
                }) { Text(hiltechText("تجهيز مطابقة الموظفين", "Prepare employee reconciliation")) }
            }
            if (runLoading) CircularProgressIndicator()
            if (runFailed) Text(hiltechText("تعذر تحميل تفاصيل الكشف. استخدم التحديث للمحاولة مجددًا.", "The run details could not load. Refresh to retry."))
        }

        if (run?.instructions?.isNotEmpty() == true) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sourceForm: @Composable () -> Unit = {
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(hiltechText("حالة الموظف", "Employee payment status"), style = MaterialTheme.typography.titleLarge)
                    HiltechRecordPicker(label = hiltechText("الموظف", "Employee"), value = instructionId,
                        choices = run!!.instructions.map { HiltechRecordChoice(it.instructionId, it.employeeName, it.employeeCode + " · " + it.amount + " " + it.currency) },
                        onChange = { instructionId = it }, allowKnownReference = false, allowClear = false, enabled = enabled)
                    instruction?.let { current ->
                        HiltechStateTag(financePayrollStatus(current.outcome), if (current.outcome == "SUCCESS") HiltechStateTone.CURRENT else HiltechStateTone.ATTENTION)
                        Text(current.amount + " " + current.currency, style = MaterialTheme.typography.headlineSmall)
                        current.recordedAt?.let { Text(hiltechText("آخر تسجيل: ", "Last recorded: ") + hiltechDateText(it.take(10))) }
                        if (access?.canPrepare == true) {
                            HiltechRecordPicker(label = hiltechText("النتيجة المراد توثيقها", "Result to document"), value = action,
                                choices = actions.map { HiltechRecordChoice(it, financePayrollAction(it)) },
                                onChange = { action = it; beneficiaryConfirmed = false }, allowKnownReference = false, allowClear = false, enabled = enabled)
                            if (action == "VOID_MATCH") Text(hiltechText(
                                "يصحح هذا الإجراء إسناد حركة بنكية خاطئة. يبقى سجلها محفوظًا وتعود حالة الموظف إلى المراجعة؛ لا يعكس أموالًا من البنك.",
                                "This corrects an incorrectly matched bank movement. History is retained and the employee's status returns to review; it does not reverse bank funds."))
                            else {
                                if (bankLoading) CircularProgressIndicator()
                                if (bankFailed) Text(hiltechText("تعذر تحميل الحركات البنكية. حدّث العرض لإعادة المحاولة.", "Bank movements could not load. Refresh to retry."))
                                else if (!bankLoading && bankLines.isEmpty()) Text(hiltechText(
                                    "لا توجد حركة معتمدة وغير مستخدمة تطابق صافي هذا الموظف وعملته. استورد كشف البنك وراجعه من مساحة المالية أولًا.",
                                    "No approved, unused bank movement matches this employee's net and currency. Import and review the bank statement in Finance first."))
                                else HiltechRecordPicker(label = hiltechText("الحركة البنكية المعتمدة", "Approved bank movement"), value = bankId,
                                    choices = bankLines.map { HiltechRecordChoice(it.bankStatementLineId, it.reference,
                                        hiltechDateText(it.bookedOn) + " · " + it.bankName + " · " + it.maskedAccount) },
                                    onChange = { bankId = it; beneficiaryConfirmed = false }, allowKnownReference = false, enabled = enabled && !bankLoading)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = beneficiaryConfirmed, onCheckedChange = { beneficiaryConfirmed = it }, enabled = enabled && bankId.isNotEmpty())
                                    Text(hiltechText("تحققت من أن الحركة تخص هذا الموظف والمبلغ المعروض.", "I verified that the movement belongs to this employee and the displayed amount."), Modifier.weight(1f))
                                }
                            }
                            OutlinedTextField(reason, onValueChange = { if (it.length <= 500 && it.none { c -> c.code < 32 }) reason = it },
                                label = { Text(hiltechText("ملاحظة المطابقة وسببها", "Reconciliation note and reason")) },
                                supportingText = { Text(hiltechText("اكتب وصفًا واضحًا من ٨ أحرف على الأقل.", "Enter a clear note of at least 8 characters.")) },
                                modifier = Modifier.fillMaxWidth(), enabled = enabled)
                            Button(enabled = enabled && access?.reviewConfigured == true && reason.trim().length >= 8 &&
                                (action == "VOID_MATCH" || (!bankLoading && !bankFailed && bankLines.any { it.bankStatementLineId == bankId } && beneficiaryConfirmed)), onClick = {
                                val op = newOperationId()
                                prepare(FinancePayrollIntent(op, organizationId, "PROPOSE_RESULT", current.instructionId, current.employeeName,
                                    result = FinancePayrollPrepareResultDto(op, current.resultVersion, action,
                                        bankStatementLineId = bankId.takeIf { action != "VOID_MATCH" },
                                        correctsEventId = current.resultEventId.takeIf { action == "VOID_MATCH" },
                                        beneficiaryConfirmed = beneficiaryConfirmed, reason = reason.trim())),
                                    current.amount + " " + current.currency + "\n" + (bankLines.firstOrNull { it.bankStatementLineId == bankId }?.reference ?: "") + "\n" + reason.trim())
                            }) { Text(hiltechText("مراجعة وإرسال للاعتماد", "Review and submit")) }
                        }
                    }
                }
            }
            val reviewList: @Composable () -> Unit = {
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(hiltechText("المراجعات وسجل النتائج", "Reviews and result history"), style = MaterialTheme.typography.titleLarge)
                    val proposals = run?.proposals.orEmpty()
                    if (proposals.isEmpty()) Text(hiltechText("لم تُقدّم مطابقات لهذا الكشف بعد.", "No matches have been submitted for this run yet."))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(proposals, key = { it.proposalId }) { p ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(p.employeeName, style = MaterialTheme.typography.titleMedium)
                                Text(p.amount + " " + p.currency + " · " + financePayrollAction(p.action))
                                HiltechStateTag(financePayrollStatus(p.state), if (p.state == "RECORDED") HiltechStateTone.CURRENT else HiltechStateTone.ATTENTION)
                                p.bankReference?.let { Text(it + " · " + p.bankName.orEmpty()) }
                                p.bookedOn?.let { Text(hiltechDateText(it)) }
                                Text(p.reason)
                                if (p.preparedByName.isNotBlank()) Text(hiltechText("أعدّها: ", "Prepared by: ") + p.preparedByName, style = MaterialTheme.typography.bodySmall)
                                if (p.canOpenEvidence) OutlinedButton(enabled = enabled && evidenceLoading == null, onClick = {
                                    val originRun = runId
                                    evidenceLoading = p.proposalId; evidenceError = null
                                    jobs.launch {
                                        try {
                                            val original = client.evidenceContent(organizationId, p.proposalId, p.evidenceId, installationId)
                                            if (runId == originRun) { evidenceDocument = original; refresh += 1 }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) {
                                            evidenceError = p.proposalId to ((failure as? HiltechApiException)?.code ?: "EVIDENCE_STORAGE_UNAVAILABLE")
                                        } finally { evidenceLoading = null }
                                    }
                                }) { Text(if (evidenceLoading == p.proposalId) hiltechText("جارٍ فتح المستند…", "Opening source…")
                                    else hiltechText("فتح مستند البنك الأصلي", "Open original bank document")) }
                                evidenceError?.takeIf { it.first == p.proposalId }?.let { Text(financePayrollError(it.second), color = MaterialTheme.colorScheme.error) }
                                if (p.canReview && !p.evidenceViewed) Text(hiltechText("افتح المستند الأصلي وراجع حركة المستفيد قبل الاعتماد.",
                                    "Open the original document and check the beneficiary's movement before approving."), style = MaterialTheme.typography.bodySmall)
                                if (p.canReview && !p.canOpenEvidence) Text(hiltechText("تعذر الوصول إلى الملف الأصلي المرتبط. حدّث الحالة وراجع مستند كشف البنك قبل متابعة الاعتماد.",
                                    "The linked original file is unavailable. Refresh the review and check the bank statement document before approving."), color = MaterialTheme.colorScheme.error)
                                if (p.canReview) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = enabled && p.evidenceViewed, onClick = {
                                        val op = newOperationId()
                                        prepare(FinancePayrollIntent(op, organizationId, "REVIEW_RESULT", p.proposalId, p.employeeName,
                                            review = FinancePayrollReviewResultDto(op, "APPROVE")),
                                            p.amount + " " + p.currency + "\n" + p.bankReference.orEmpty() + "\n" + p.reason)
                                    }) { Text(hiltechText("اعتماد وتسجيل", "Approve and record")) }
                                    OutlinedButton(enabled = enabled && p.state == "PENDING", onClick = { rejectProposal = p; rejectionReason = "" }) {
                                        Text(hiltechText("رفض", "Reject"))
                                    }
                                }
                                if (p.state == "STALE") Text(hiltechText("تغير المصدر أو المراجع. أعد المطابقة من الحالة الحالية.",
                                    "The source or reviewer changed. Prepare a new match from the current state."))
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
            if (maxWidth >= 960.dp) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { sourceForm() }
                Column(Modifier.weight(1f)) { reviewList() }
            } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { sourceForm(); reviewList() }
        }
    }

    evidenceDocument?.let { document -> FinancePayrollEvidenceViewer(document) { evidenceDocument = null } }

    rejectProposal?.let { proposal -> AlertDialog(onDismissRequest = { rejectProposal = null },
        title = { Text(hiltechText("سبب رفض المطابقة", "Reason for rejecting the match")) },
        text = { OutlinedTextField(rejectionReason, { if (it.length <= 500 && it.none { c -> c.code < 32 }) rejectionReason = it },
            label = { Text(hiltechText("السبب", "Reason")) }) },
        confirmButton = { TextButton(enabled = rejectionReason.isNotBlank(), onClick = {
            val op = newOperationId()
            prepare(FinancePayrollIntent(op, organizationId, "REVIEW_RESULT", proposal.proposalId, proposal.employeeName,
                review = FinancePayrollReviewResultDto(op, "REJECT", rejectionReason.trim())), rejectionReason.trim())
            rejectProposal = null
        }) { Text(hiltechText("مراجعة الرفض", "Review rejection")) } },
        dismissButton = { TextButton(onClick = { rejectProposal = null }) { Text(hiltechText("رجوع", "Back")) } }) }

    pending?.takeIf { dialog }?.let { intent -> AlertDialog(
        onDismissRequest = { if (!submitting) { dialog = false; if (!attempted) pendingJson = null } },
        title = { Text(hiltechText("تأكيد الإجراء المالي", "Confirm Finance action")) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(intent.title, style = MaterialTheme.typography.titleMedium)
            Text(confirmationDetail)
            Text(when (intent.kind) {
                "PREPARE_BATCH" -> hiltechText("تجهيز سجل داخلي بالمستحقات المعتمدة لمطابقتها مع الحركات البنكية.", "Prepare internal approved obligations for matching to actual bank movements.")
                "PROPOSE_RESULT" -> hiltechText("ستُرسل المطابقة للمراجعة المستقلة قبل تغيير حالة صرف الموظف.", "Submit the match for independent review before changing the employee's payment status.")
                else -> if (intent.review?.decision == "APPROVE") hiltechText("أعتمد تطابق المستفيد والمبلغ والدليل المعروض، وأسجل النتيجة في تاريخ صرف الموظف.",
                    "I approve the beneficiary, amount and evidence match and record its result in the employee's disbursement history.")
                    else hiltechText("سيُحفظ الرفض مع سببه، ولن يُسجل صرف من هذا الطلب.", "Record the rejection and its reason without recording payment from this request.")
            })
            if (submitting) CircularProgressIndicator()
            commandError?.let { Text(financePayrollError(it), color = MaterialTheme.colorScheme.error) }
            if (unknown) Text(hiltechText("النتيجة غير مؤكدة. تم الاحتفاظ بنفس الطلب لإعادة المحاولة دون تكرار التسجيل.", "The outcome is unconfirmed. Retry this retained request without duplicating the record."))
            if (rejected) Text(hiltechText("لم يُطبق الطلب. أعد تحميل الحالة قبل إعداد تغيير جديد.", "This request was not applied. Reload the current state before preparing a new change."))
            if (commandError in setOf("UNAUTHENTICATED", "REAUTH_REQUIRED")) TextButton(onClick = onReauthenticate) { Text(hiltechText("تأكيد الدخول", "Confirm sign-in")) }
        } },
        confirmButton = { TextButton(enabled = !submitting, onClick = {
            if (rejected) {
                pendingJson = null; dialog = false; attempted = false; unknown = false; rejected = false; commandError = null; refresh += 1
                return@TextButton
            }
            val previousUnknown = unknown
            submitting = true; attempted = true; unknown = true; commandError = null
            jobs.launch {
                try {
                    val receipt = client.apply(intent, installationId)
                    acknowledgement = receipt.state; pendingJson = null; dialog = false; attempted = false; unknown = false; rejected = false
                    reason = ""; beneficiaryConfirmed = false; refresh += 1
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    commandError = (failure as? HiltechApiException)?.code ?: "OUTCOME_UNCONFIRMED"
                    val disposition = financePayrollFailureDisposition(previousUnknown, failure)
                    unknown = disposition.unknown; rejected = disposition.rejected
                } finally { submitting = false }
            }
        }) { Text(when { rejected -> hiltechText("تحميل الحالة الحالية", "Reload current state"); attempted -> hiltechText("إعادة نفس الطلب", "Retry same request"); else -> hiltechText("تأكيد", "Confirm") }) } },
        dismissButton = { TextButton(enabled = !submitting, onClick = { dialog = false; if (!attempted) pendingJson = null }) {
            Text(if (attempted) hiltechText("الاحتفاظ والرجوع", "Keep request and go back") else hiltechText("رجوع", "Back"))
        } },
    ) }
}

@Composable
private fun financePayrollAction(action: String): String = when (action) {
    "SUCCESS" -> hiltechText("صرف مؤيد بحركة بنكية", "Payment evidenced by bank movement")
    "RETURNED" -> hiltechText("تحويل مرتجع", "Returned transfer")
    else -> hiltechText("تصحيح إسناد حركة خاطئة", "Void an incorrect bank match")
}
@Composable
private fun financePayrollStatus(status: String): String = when (status) {
    "SUCCESS" -> hiltechText("صرف مؤكد بالمراجعة", "Payment confirmed by review")
    "RETURNED" -> hiltechText("التحويل مرتجع", "Transfer returned")
    "RECORDED" -> hiltechText("نتيجة مسجلة", "Result recorded")
    "PENDING", "PROPOSED" -> hiltechText("بانتظار المراجعة", "Awaiting review")
    "APPROVED" -> hiltechText("معتمد · جاهز للتسجيل", "Approved · ready to record")
    "REJECTED" -> hiltechText("مرفوض", "Rejected")
    "STALE" -> hiltechText("يحتاج تحديث المصدر", "Source needs refreshing")
    "PAYMENT_NOT_RECORDED" -> hiltechText("لم تسجل نتيجة صرف", "Payment result not recorded")
    else -> hiltechText("يحتاج مراجعة مالية", "Finance review required")
}
@Composable
private fun financePayrollError(code: String): String = when (code) {
    "FINANCE_PAYROLL_REVIEW_SETUP_REQUIRED" -> hiltechText("أكمل إعداد المراجع المستقل من إعدادات مسارات المراجعة.", "Configure an independent reviewer in review-workflow settings.")
    "FINANCE_PAYROLL_INDEPENDENT_REVIEWER_REQUIRED" -> hiltechText("يلزم مراجع مستقل عن مُعدّ الطلب والمستفيد. حدّث إسناد المراجعة من الإعدادات.", "Select a reviewer who is independent of the preparer and beneficiary in review-workflow settings.")
    "FINANCE_PAYROLL_SOURCE_CHANGED", "FINANCE_PAYROLL_BATCH_ALREADY_PREPARED" -> hiltechText("تغيرت حالة المصدر. حمّل الحالة الحالية قبل إعداد تغيير آخر.", "The source changed. Reload its current state before preparing another change.")
    "FINANCE_PAYROLL_PROOF_NOT_READY" -> hiltechText("دليل كشف البنك غير جاهز. أكمل رفعه ومراجعته في المالية.", "The bank statement evidence is not ready. Complete its upload and review in Finance.")
    "FINANCE_PAYROLL_EVIDENCE_REVIEW_REQUIRED" -> hiltechText("افتح المستند الأصلي المرتبط بهذه المطابقة قبل الاعتماد.", "Open the original document linked to this match before approving.")
    "FINANCE_PAYROLL_EVIDENCE_UNAVAILABLE" -> hiltechText("المستند غير متاح ضمن صلاحيتك الحالية أو لم يعد مرتبطًا بهذا الطلب. حدّث شاشة المراجعة.", "This document is unavailable under your current responsibility or source relationship. Refresh the review.")
    "FINANCE_PAYROLL_EVIDENCE_SOURCE_MISMATCH" -> hiltechText("تعذر التحقق من مطابقة الملف المحفوظ لكشف البنك المعتمد. لم يُسجل فتح دليل صالح للاعتماد.", "The stored file could not be verified against the approved bank source. No valid evidence-view receipt was recorded.")
    "EVIDENCE_STORAGE_UNAVAILABLE" -> hiltechText("تعذر تحميل المستند حاليًا. أعد فتحه عندما يعود الاتصال.", "The source document could not be loaded. Reopen it when the connection is available.")
    "FINANCE_PAYROLL_DIFFERENTIAL_SOURCE_REQUIRED" -> hiltechText("هذا تصحيح بفروق تسوية؛ لا يمكن تجهيز كامل الراتب مرة أخرى من هذا المسار.", "This correction requires differential settlement; the full salary cannot be prepared again through this flow.")
    "FINANCE_PAYROLL_UNAVAILABLE" -> hiltechText("الصلاحية الحالية لا تتيح هذا الإجراء. راجع مسؤول المسؤوليات.", "Your current responsibility does not permit this action. Contact the responsibility administrator.")
    else -> hiltechText("تعذر تأكيد النتيجة. تحقق من الاتصال ثم تابع نفس الطلب.", "The result could not be confirmed. Check the connection and resume the same request.")
}
