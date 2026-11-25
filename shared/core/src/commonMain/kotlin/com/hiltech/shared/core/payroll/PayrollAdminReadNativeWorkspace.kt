package com.hiltech.shared.core.payroll

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.NativeOperationalWorkspace
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Online, source-authorized payroll operation surface shared by both native hosts. */
fun payrollAdminReadNativeWorkspace(
    client: Payroll9aApiClient,
    identityId: String,
    organizationId: String,
    installationId: String,
    newOperationId: (() -> String)? = null,
    runClient: Payroll9b9cApiClient? = null,
    onReauthenticate: () -> Unit = {},
): NativeOperationalWorkspace {
    // Kept by the identity-scoped workspace, so leaving this screen does not
    // create a new operation after a timeout. No salary data is cached to disk.
    val commands = PayrollCommandSession()
    return NativeOperationalWorkspace(
        id = "payroll-admin-read",
        label = "إدارة المرتبات",
        canReadSource = { client.capabilities(organizationId, installationId); true },
        hostOwnsScroll = false,
        content = { _ ->
            val jobs = rememberCoroutineScope()
            var refresh by remember(identityId, organizationId, installationId) { mutableIntStateOf(0) }
            var periods by remember(identityId, organizationId, installationId) { mutableStateOf<PayrollPeriodListDto?>(null) }
            var capabilities by remember(identityId, organizationId, installationId) { mutableStateOf<PayrollCapabilitiesDto?>(null) }
            var selectedId by remember(identityId, organizationId, installationId) { mutableStateOf(commands.pending?.action?.periodId) }
            var detail by remember(identityId, organizationId, installationId) { mutableStateOf<PayrollPeriodDetailDto?>(null) }
            var sources by remember(identityId, organizationId, installationId) { mutableStateOf<PayrollSourcesDto?>(null) }
            var history by remember(identityId, organizationId, installationId) { mutableStateOf<PayrollRunHistoryDto?>(null) }
            var selectedRunId by remember(identityId, organizationId, installationId) { mutableStateOf(commands.pending?.action?.runId) }
            var run by remember(identityId, organizationId, installationId) { mutableStateOf<PayrollRunDto?>(null) }
            var runReview by remember(identityId, organizationId, installationId) { mutableStateOf<PayrollRunReviewDto?>(null) }
            var finance by remember(identityId, organizationId, installationId) { mutableStateOf<PayrollRunFinanceStatusDto?>(null) }
            var approval by remember(identityId, organizationId, installationId) { mutableStateOf<PayrollApprovalResultDto?>(null) }
            var release by remember(identityId, organizationId, installationId) { mutableStateOf<PayrollReleaseResultDto?>(null) }
            var loading by remember(identityId, organizationId, installationId) { mutableStateOf(false) }
            var error by remember(identityId, organizationId, installationId) { mutableStateOf<String?>(null) }
            var sourceError by remember(identityId, organizationId, installationId) { mutableStateOf<String?>(null) }
            var runError by remember(identityId, organizationId, installationId) { mutableStateOf<String?>(null) }
            var success by remember(identityId, organizationId, installationId) { mutableStateOf(false) }
            var savedSources by remember(identityId, organizationId, installationId) { mutableIntStateOf(0) }
            var tab by remember(selectedId) { mutableIntStateOf(when (commands.pending?.action) {
                is PayrollNativeAction.SaveSource, is PayrollNativeAction.RequestSourceReview, is PayrollNativeAction.DecideSource -> 1
                is PayrollNativeAction.PrepareRun, is PayrollNativeAction.RequestRunReview, is PayrollNativeAction.DecideRun, is PayrollNativeAction.ReleaseRun, is PayrollNativeAction.HandoffRun -> 2
                else -> 0
            }) }

            LaunchedEffect(client, runClient, identityId, organizationId, installationId, selectedId, selectedRunId, refresh) {
                loading = true
                error = null
                sourceError = null
                runError = null
                capabilities = null
                if (detail?.period?.periodId != selectedId) {
                    detail = null
                    sources = null
                    history = null
                    run = null
                    runReview = null
                    finance = null
                }
                try {
                    capabilities = client.capabilities(organizationId, installationId)
                    if (capabilities?.runsAvailable != true) { history = null; run = null; runReview = null }
                    if (capabilities?.runApprovalAvailable != true) runReview = null
                    if (capabilities?.canReview != true || capabilities?.financeStatusAvailable != true) finance = null
                    if (capabilities?.periodsAvailable != true) {
                        error = "PAYROLL_PERIODS_PAUSED"
                        periods = null; detail = null; sources = null; history = null; run = null; runReview = null; finance = null
                        return@LaunchedEffect
                    }
                    periods = client.list(organizationId, installationId)
                    val id = selectedId
                    if (id != null && periods?.items?.any { it.periodId == id } == true) {
                        val loaded = client.detail(id, installationId)
                        check(loaded.period.periodId == id && loaded.period.organizationId == organizationId)
                        detail = loaded
                        try {
                            val response = client.sources(id, installationId)
                            check(response.periodId == id)
                            sources = response
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { sourceError = "PAYROLL_SOURCE_READ_UNAVAILABLE" }
                        if (runClient != null && capabilities?.runsAvailable == true) try {
                            val page = runClient.history(id, installationId)
                            check(page.payrollPeriodId == id)
                            history = page
                            val runId = selectedRunId?.takeIf { candidate -> page.items.any { it.runVersionId == candidate } }
                                ?: page.items.firstOrNull()?.runVersionId
                            if (runId != null) {
                                val response = runClient.readDraft(runId, installationId)
                                check(response.runVersionId == runId && response.payrollPeriodId == id && response.organizationId == organizationId)
                                run = response
                                runReview = null
                                if (capabilities?.runApprovalAvailable == true) runReview = runClient.review(runId, installationId)
                                if (capabilities?.canReview == true && capabilities?.financeStatusAvailable == true) {
                                    val status = runClient.financeStatus(runId, installationId)
                                    check(status.runVersionId == runId)
                                    finance = status
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { runError = "PAYROLL_RUN_READ_UNAVAILABLE" }
                    } else if (id != null) {
                        selectedId = null
                        selectedRunId = null
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    val apiFailure = failure as? HiltechApiException
                    error = apiFailure?.code ?: "PAYROLL_SOURCE_READ_UNAVAILABLE"
                    if (apiFailure?.httpStatus in listOf(401, 403)) {
                        periods = null; detail = null; sources = null; history = null; run = null; runReview = null; finance = null
                        approval = null; release = null
                    }
                }
                finally { loading = false }
            }

            fun execute(command: PayrollNativeCommand) {
                success = false
                jobs.launch {
                    try {
                        when (val action = command.action) {
                            is PayrollNativeAction.CreatePeriod -> {
                                val request = action.request.copy(operationId = command.operationId)
                                val result = client.create(request, installationId).period
                                check(result.period.organizationId == organizationId && result.period.periodCode == request.periodCode &&
                                    result.period.startDate == request.startDate && result.period.endDate == request.endDate)
                                selectedId = result.period.periodId
                                selectedRunId = null
                            }
                            is PayrollNativeAction.Capture -> {
                                val result = client.captureInputs(action.periodId, installationId)
                                check(result.period.period.periodId == action.periodId && result.period.period.organizationId == organizationId)
                            }
                            is PayrollNativeAction.ReviewException -> {
                                val result = client.reviewException(action.periodId, action.exceptionId,
                                    ReviewPayrollExceptionRequestDto(command.operationId, action.note), installationId)
                                check(result.exceptionId == action.exceptionId)
                            }
                            is PayrollNativeAction.SaveSource -> {
                                val result = client.saveSource(action.periodId, action.request.copy(operationId = command.operationId), installationId)
                                check(result.record.periodId == action.periodId && result.record.domain == action.request.domain &&
                                    result.record.employeeId == action.request.employeeId && result.record.preparedByIdentityId == identityId &&
                                    result.record.replacesRecordId == action.request.replacesRecordId)
                                savedSources++
                            }
                            is PayrollNativeAction.RequestSourceReview -> {
                                val result = client.requestSourceApproval(action.recordId, command.operationId, installationId)
                                check(result.record.recordId == action.recordId)
                            }
                            is PayrollNativeAction.DecideSource -> {
                                val result = client.decideSource(action.recordId,
                                    PayrollSourceDecisionRequestDto(command.operationId, action.decision, action.comment), installationId)
                                check(result.record.recordId == action.recordId)
                            }
                            is PayrollNativeAction.PrepareRun -> {
                                val result = requireNotNull(runClient).createDraft(action.periodId, command.operationId, installationId).run
                                check(result.payrollPeriodId == action.periodId && result.organizationId == organizationId)
                                selectedRunId = result.runVersionId
                                approval = null
                                release = null
                            }
                            is PayrollNativeAction.RequestRunReview -> approval = requireNotNull(runClient).requestApproval(
                                action.runId, command.operationId, "PAYROLL_RUN_REVIEW", installationId)
                            is PayrollNativeAction.DecideRun -> {
                                requireNotNull(runClient).decideRun(action.runId, PayrollRunDecisionRequestDto(command.operationId,
                                    action.approvalRequestId, action.decision, action.comment), installationId)
                                approval = null
                            }
                            is PayrollNativeAction.ReleaseRun -> {
                                val result = requireNotNull(runClient).releaseForFinanceReview(action.runId, command.operationId, installationId)
                                check(result.runVersionId == action.runId)
                                release = result
                            }
                            is PayrollNativeAction.HandoffRun -> {
                                val result = requireNotNull(runClient).handoffToFinanceReview(action.runId, command.operationId, installationId)
                                check(result.runVersionId == action.runId && !result.bankExecutionAvailable)
                            }
                        }
                        if (commands.succeed(command)) { success = true; refresh++ }
                    } catch (cancelled: CancellationException) {
                        commands.fail(command, cancelled)
                        throw cancelled
                    } catch (failure: Exception) { commands.fail(command, failure) }
                }
            }
            fun submit(action: PayrollNativeAction) {
                val generator = newOperationId ?: return
                if (loading) return
                commands.begin(action, generator)?.let(::execute)
            }
            val locked = loading || commands.locked
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (loading || commands.busy) CircularProgressIndicator()
                if (commands.errorCode != null) Text(payrollMessage(requireNotNull(commands.errorCode)), color = MaterialTheme.colorScheme.error)
                if (commands.requiresReauthentication || error == "REAUTH_REQUIRED") OutlinedButton(
                    enabled = !commands.busy, onClick = onReauthenticate,
                ) { Text(hiltechText("تأكيد تسجيل الدخول", "Confirm sign-in")) }
                if (commands.locked && !commands.busy) OutlinedButton(onClick = { commands.retry()?.let(::execute) }) {
                    Text(hiltechText("التحقق بنفس العملية", "Retry the same operation"))
                }
                if (success) Text(hiltechText("تم تسجيل العملية بنجاح وتحديث بيانات المصدر.", "The operation was recorded and source data refreshed."))
                error?.let { Text(payrollMessage(it), color = MaterialTheme.colorScheme.error) }
                if (selectedId != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(hiltechText("الفترة", "Period"), hiltechText("المصادر", "Sources"), hiltechText("الحساب", "Calculation")).forEachIndexed { index, title ->
                        FilterChip(tab == index, { tab = index }, label = { Text(title) }, enabled = !commands.locked)
                    }
                }
                if (selectedId == null || tab == 0) Payroll9aAdminSection(
                    state = Payroll9aUiState(loading = loading || commands.busy, commandLocked = commands.locked, periods = periods, selected = detail,
                        canPrepare = capabilities?.canPrepare == true && capabilities?.periodsAvailable == true && newOperationId != null,
                        canReview = capabilities?.canReview == true && newOperationId != null),
                    onRefresh = { refresh++ },
                    onSelect = { id -> if (!locked && periods?.items?.any { it.periodId == id } == true) {
                        selectedId = id; selectedRunId = null; approval = null; release = null
                    } },
                    onCreatePeriod = { code, start, end, cutoff, payment -> submit(PayrollNativeAction.CreatePeriod(
                        CreatePayrollPeriodRequestDto("", organizationId, code, start, end, cutoff, payment))) },
                    onCaptureInputs = { submit(PayrollNativeAction.Capture(it)) },
                    onReviewException = { period, exception, note -> submit(PayrollNativeAction.ReviewException(period, exception, note)) },
                    hostOwnsVerticalScroll = true,
                    onBackToPeriods = { if (!commands.locked) { selectedId = null; selectedRunId = null } },
                    retainedCreateRequest = (commands.pending?.action as? PayrollNativeAction.CreatePeriod)?.request,
                )
                if (selectedId != null && tab == 1) {
                    sourceError?.let { Text(payrollMessage(it)) }
                    val data = sources
                    val current = detail
                    if (data != null && current != null) PayrollSourcesSection(data, current.employees, identityId,
                        capabilities?.canPrepare == true && newOperationId != null,
                        capabilities?.canReview == true && newOperationId != null,
                        locked || sourceError != null, savedSources,
                        onSave = { submit(PayrollNativeAction.SaveSource(data.periodId, it)) },
                        onRequestApproval = { submit(PayrollNativeAction.RequestSourceReview(it, data.periodId)) },
                        onDecide = { recordId, decision, comment -> submit(PayrollNativeAction.DecideSource(recordId, decision, data.periodId, comment)) },
                        retainedSourceRequest = (commands.pending?.action as? PayrollNativeAction.SaveSource)?.request,
                        retainedRecordId = when (val pending = commands.pending?.action) {
                            is PayrollNativeAction.RequestSourceReview -> pending.recordId
                            is PayrollNativeAction.DecideSource -> pending.recordId
                            else -> null
                        }, retainedDecisionComment = (commands.pending?.action as? PayrollNativeAction.DecideSource)?.comment)
                    OutlinedButton(onClick = { refresh++ }, enabled = !commands.busy) { Text(hiltechText("تحديث المصادر", "Refresh sources")) }
                }
                if (selectedId != null && tab == 2) {
                    sources?.blockers?.forEach { Text(payrollMessage(it)) }
                    if (capabilities?.runsAvailable == false) Text(payrollMessage("PAYROLL_RUNS_PAUSED"))
                    if (capabilities?.runReviewConfigured == false) Text(payrollMessage("PAYROLL_RUN_REVIEW_NOT_CONFIGURED"))
                    if (capabilities?.runApprovalAvailable == false || capabilities?.releaseAvailable == false) Text(payrollMessage("PAYROLL_RUN_REVIEW_PAUSED"))
                    if (capabilities?.financeStatusAvailable == false) Text(payrollMessage("PAYROLL_FINANCE_STATUS_PAUSED"))
                    if (runClient == null) Text(hiltechText("خدمة حساب المرتبات غير متاحة في هذه النسخة.", "Payroll calculation is unavailable in this client version."))
                    else {
                        PayrollRunHistorySection(history, loading,
                            onOpenRun = { if (!locked) { selectedRunId = it; approval = null; release = null } },
                            onRefresh = { refresh++ }, hostOwnsVerticalScroll = true)
                        PayrollRunReviewSection(PayrollRunReviewUiState(
                            loading = loading || commands.busy, commandLocked = commands.locked || runError != null, run = run, errorCode = runError,
                            releaseState = history?.items?.firstOrNull { it.runVersionId == run?.runVersionId }?.releaseState ?: "NOT_RELEASED",
                            mayPrepare = capabilities?.canPrepare == true && capabilities?.runsAvailable == true && newOperationId != null && sources?.calculationReady == true,
                            mayRequestApproval = runReview?.canRequestReview == true && capabilities?.runApprovalAvailable == true && newOperationId != null,
                            mayReleaseForFinanceReview = runReview?.canRelease == true && capabilities?.releaseAvailable == true && newOperationId != null,
                            approval = approval, release = release, finance = finance, review = runReview), requireNotNull(selectedId),
                            onPrepareDraft = { submit(PayrollNativeAction.PrepareRun(it)) },
                            onRequestApproval = { submit(PayrollNativeAction.RequestRunReview(it, requireNotNull(selectedId))) },
                            onReleaseForFinanceReview = { submit(PayrollNativeAction.ReleaseRun(it, requireNotNull(selectedId))) },
                            onRefresh = { refresh++ }, hostOwnsVerticalScroll = true,
                            onDecide = { runId, requestId, decision, comment -> submit(PayrollNativeAction.DecideRun(runId,
                                requireNotNull(selectedId), requestId, decision, comment)) },
                            retainedDecisionComment = (commands.pending?.action as? PayrollNativeAction.DecideRun)?.comment)
                        val currentRun = run
                        if (currentRun != null && capabilities?.canReview == true && finance?.publicationApproved == true && newOperationId != null) {
                            if (capabilities?.financeHandoffAvailable == false) Text(payrollMessage("PAYROLL_FINANCE_HANDOFF_PAUSED"))
                            OutlinedButton(onClick = { submit(PayrollNativeAction.HandoffRun(currentRun.runVersionId, currentRun.payrollPeriodId)) }, enabled = !locked && capabilities?.financeHandoffAvailable == true) {
                                Text(hiltechText("إرسال النسخة المعتمدة للمراجعة المالية", "Send the released run to Finance review"))
                            }
                        }
                    }
                }
            }
        },
    )
}
