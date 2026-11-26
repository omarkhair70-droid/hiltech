package com.hiltech.shared.core.payroll

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import com.hiltech.shared.core.design.LocalHiltechLanguage
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechKeyValue
import com.hiltech.shared.core.design.HiltechRecordChoice
import com.hiltech.shared.core.design.HiltechRecordPicker
import com.hiltech.shared.core.design.HiltechRecordTable
import com.hiltech.shared.core.design.HiltechTableColumn
import com.hiltech.shared.core.design.HiltechTableRecord
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.hiltechText
import androidx.compose.ui.unit.dp

/** Native Windows/Android source-bound review; host route is DevOps-owned. */
data class PayrollRunReviewUiState(
    val loading: Boolean = false,
    val run: PayrollRunDto? = null,
    val errorCode: String? = null,
    val mayPrepare: Boolean = false,
    val mayRequestApproval: Boolean = false,
    val mayReleaseForFinanceReview: Boolean = false,
    val approval: PayrollApprovalResultDto? = null,
    val release: PayrollReleaseResultDto? = null,
    val finance: PayrollRunFinanceStatusDto? = null,
    val commandLocked: Boolean = false,
    val releaseState: String = "NOT_RELEASED",
    val review: PayrollRunReviewDto? = null,
)

@Composable
fun PayrollRunReviewSection(
    state: PayrollRunReviewUiState,
    periodId: String,
    onPrepareDraft: (String) -> Unit,
    onRequestApproval: (String) -> Unit,
    onReleaseForFinanceReview: (String) -> Unit,
    onRefresh: () -> Unit,
    hostOwnsVerticalScroll: Boolean = false,
    onDecide: (String, String, String, String?) -> Unit = { _, _, _, _ -> },
    retainedDecisionComment: String? = null,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LocalHiltechLanguage.current.direction) {
        Column(Modifier.fillMaxWidth().then(if (hostOwnsVerticalScroll) Modifier else Modifier.verticalScroll(rememberScrollState())),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.run?.let { hiltechText("كشف الرواتب · النسخة ${it.generation}", "Payroll run · version ${it.generation}") }
                        ?: hiltechText("حساب الرواتب", "Payroll calculation"), style = MaterialTheme.typography.titleLarge)
                    Text(hiltechText("قيم محسوبة من المصادر المعتمدة للفترة.", "Values calculated from the period’s approved sources."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text(hiltechText("تحديث", "Refresh")) }
            }
            if (state.loading && !hostOwnsVerticalScroll) CircularProgressIndicator()
            state.errorCode?.let { Text(payrollMessage(it), color = MaterialTheme.colorScheme.error) }
            if (state.mayPrepare && periodId.isNotBlank()) OutlinedButton(onClick = { onPrepareDraft(periodId) },
                enabled = !state.loading && !state.commandLocked) {
                Text(hiltechText("تجهيز مسودة من المدخلات المعتمدة", "Calculate from approved inputs"))
            }
            state.run?.let { run ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    HiltechStateTag(payrollLifecycleLabel(state.releaseState),
                        if (state.releaseState in setOf("RELEASED", "RELEASED_FOR_FINANCE_REVIEW")) HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL)
                    Text(hiltechText("${run.lines.size} موظفين", "${run.lines.size} employees"), style = MaterialTheme.typography.labelLarge)
                }
                PayrollAmounts(run.net, run.gross, run.deductions, run.reimbursements, run.currency)
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(hiltechText("تفاصيل الموظفين", "Employee breakdown"), style = MaterialTheme.typography.titleMedium)
                    Text(hiltechText("تشمل الموافقة جميع الموظفين في هذه النسخة.", "Approval covers every employee in this version."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HiltechRecordTable(listOf(
                        HiltechTableColumn(hiltechText("الموظف", "Employee"), 2.2f),
                        HiltechTableColumn(hiltechText("الاستحقاقات", "Earnings"), numeric = true),
                        HiltechTableColumn(hiltechText("الخصومات", "Deductions"), numeric = true),
                        HiltechTableColumn(hiltechText("المستردات", "Reimbursements"), numeric = true),
                        HiltechTableColumn(hiltechText("الصافي", "Net"), 1.2f, numeric = true),
                    ), run.lines.map { line -> HiltechTableRecord(listOf(
                        line.displayName + "\n" + line.employeeCode, line.gross, line.deductions, line.reimbursements, line.net + " " + line.currency,
                    )) }, hiltechText("لا توجد بنود موظفين في هذه النسخة.", "This version contains no employee lines."))
                }
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(hiltechText("المراجعة والاعتماد", "Review and release"), style = MaterialTheme.typography.titleMedium)
                    state.review?.takeIf { it.runVersionId == run.runVersionId }?.let { review ->
                        HiltechStateTag(payrollRunReviewLabel(review.approvalState), when (review.approvalState) {
                            "APPROVED", "RELEASED" -> HiltechStateTone.CURRENT
                            "PENDING" -> HiltechStateTone.ATTENTION
                            "POLICY_REVIEW_REQUIRED", "REJECTED", "CHANGE_REQUESTED", "SOURCE_CHANGED", "SUPERSEDED" -> HiltechStateTone.BLOCKED
                            else -> HiltechStateTone.NEUTRAL
                        })
                        if (review.approvalState in setOf("POLICY_REVIEW_REQUIRED", "REJECTED", "CHANGE_REQUESTED", "SOURCE_CHANGED", "SUPERSEDED")) {
                            Text(hiltechText("راجع المصادر وأعد حساب نسخة جديدة لعرضها على المراجع الحالي.",
                                "Review the sources and calculate a new version for the current reviewer."))
                        }
                        if (review.canDecide && review.approvalRequestId != null) {
                            var note by remember(run.runVersionId, review.approvalRequestId) { mutableStateOf(retainedDecisionComment.orEmpty()) }
                            Text(hiltechText("قرارك يخص القيم والموظفين في هذه النسخة فقط.", "Your decision applies to the values and employees in this version only."),
                                style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(note, { note = it.take(1000) }, modifier = Modifier.fillMaxWidth(),
                                enabled = !state.loading && !state.commandLocked, label = { Text(hiltechText("ملاحظة المراجعة", "Review note")) },
                                supportingText = { Text(hiltechText("السبب مطلوب لطلب تعديل أو رفض النسخة.", "A reason is required to request changes or reject this version.")) })
                            Button(onClick = { onDecide(run.runVersionId, review.approvalRequestId, "APPROVE", note.trim().takeIf(String::isNotBlank)) },
                                enabled = !state.loading && !state.commandLocked) { Text(hiltechText("اعتماد حساب هذه النسخة", "Approve this payroll calculation")) }
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val actions: @Composable () -> Unit = {
                                    OutlinedButton(onClick = { onDecide(run.runVersionId, review.approvalRequestId, "REQUEST_CHANGE", note.trim()) },
                                        enabled = !state.loading && !state.commandLocked && note.isNotBlank()) { Text(hiltechText("طلب تعديل الحساب", "Request calculation changes")) }
                                    TextButton(onClick = { onDecide(run.runVersionId, review.approvalRequestId, "REJECT", note.trim()) },
                                        enabled = !state.loading && !state.commandLocked && note.isNotBlank()) { Text(hiltechText("رفض النسخة", "Reject version")) }
                                }
                                if (maxWidth < 560.dp) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
                                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
                            }
                        }
                    }
                    if (state.mayRequestApproval) OutlinedButton(onClick = { onRequestApproval(run.runVersionId) },
                        enabled = !state.loading && !state.commandLocked) {
                        Text(hiltechText("إحالة النسخة للموافقة حسب سياسة الشركة", "Request review under company policy"))
                    }
                    if (state.mayReleaseForFinanceReview) Button(onClick = { onReleaseForFinanceReview(run.runVersionId) },
                        enabled = !state.loading && !state.commandLocked) {
                        Text(hiltechText("تثبيت اعتماد النسخة للمراجعة المالية", "Release this version for Finance review"))
                    }
                    state.approval?.takeIf { state.review == null }?.let {
                        Text(hiltechText("طلب الموافقة: ", "Approval request: ") +
                            (it.approvalState?.let { status -> payrollSourceStateLabel(status) } ?: if (it.approvalRequired)
                                hiltechText("معلّق", "Pending") else hiltechText("اعتماد إضافي غير مطلوب", "No additional approval required")))
                    }
                    state.release?.let {
                        Text(if (it.status == "RELEASED_FOR_FINANCE_REVIEW") hiltechText(
                            "تم تثبيت اعتماد هذه النسخة للمراجعة المالية.", "This version was released for Finance review.")
                        else hiltechText("راجع حالة اعتماد النسخة الحالية.", "Review the release status of the current version."),
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                state.finance?.let { finance -> PayrollFinanceResultPanel(run, finance) }
            }
        }
    }
}

@Composable
private fun PayrollAmounts(net: String, gross: String, deductions: String, reimbursements: String, currency: String) {
    val values = listOf(hiltechText("الصافي", "Net") to net, hiltechText("الاستحقاقات", "Earnings") to gross,
        hiltechText("الخصومات", "Deductions") to deductions, hiltechText("المستردات", "Reimbursements") to reimbursements)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val groups = if (maxWidth >= 760.dp) listOf(values) else values.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEach { group -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                group.forEach { (label, amount) -> HiltechPanel(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(amount, style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Ltr),
                        color = if (label == values.first().first) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(currency, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            } }
        }
    }
}

@Composable
private fun PayrollFinanceResultPanel(run: PayrollRunDto, finance: PayrollRunFinanceStatusDto) {
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("المطابقة المالية", "Finance reconciliation"), style = MaterialTheme.typography.titleMedium)
        Text(when (finance.status) {
            "PAID" -> hiltechText("مؤكد بالكامل من المالية", "Fully confirmed by Finance")
            "PARTIALLY_PAID" -> hiltechText("صرف جزئي", "Partially paid")
            "NO_PAYMENT_REQUIRED" -> hiltechText("لا توجد مبالغ تحتاج تحويلًا", "No transfer required")
            "REQUIRES_REVIEW" -> hiltechText("مراجعة نتائج الصرف مطلوبة", "Payment results require review")
            "NOT_RELEASED" -> hiltechText("لم يُعتمد التسليم للمالية", "Not yet released for Finance review")
            "FINANCE_NOT_CONNECTED", "PAYMENT_STATUS_UNAVAILABLE" -> hiltechText("حالة الصرف غير متاحة الآن", "Payment status is currently unavailable")
            "PAYMENT_NOT_RECORDED" -> hiltechText("لم تُسجّل نتيجة صرف بعد", "No payment result has been recorded")
            else -> hiltechText("لم يُنفّذ صرف مؤكد", "Payment has not been confirmed")
        }, style = MaterialTheme.typography.titleSmall)
        val noTransfer = finance.employeeOutcomes.count { it.outcome == "NOT_APPLICABLE" }
        Text(hiltechText("تحويلات مؤكدة: ${finance.settledCount} · تحتاج متابعة: ${finance.unresolvedCount} · لا تحتاج تحويلًا: $noTransfer",
            "Confirmed transfers: ${finance.settledCount} · Unresolved: ${finance.unresolvedCount} · No transfer required: $noTransfer"),
            style = MaterialTheme.typography.bodyMedium)
        HiltechRecordTable(listOf(HiltechTableColumn(hiltechText("الموظف", "Employee"), 1.4f),
            HiltechTableColumn(hiltechText("نتيجة الصرف", "Payment outcome"))),
            finance.employeeOutcomes.map { outcome -> HiltechTableRecord(listOf(
                run.lines.firstOrNull { it.employeeId == outcome.employeeId }?.displayName ?: hiltechText("موظف من النسخة", "Employee in this run"),
                payrollPaymentOutcome(outcome.outcome),
            )) }, hiltechText("لا توجد نتائج صرف متاحة لهذه النسخة.", "No payment outcomes are available for this version."))
        Text(hiltechText("تسجّل المالية نتيجة الصرف الفعلية وتطابقها.", "Finance records and reconciles the actual payment outcome."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun payrollPaymentOutcome(outcome: String): String = when (outcome) {
    "SUCCESS" -> hiltechText("مؤكد من المالية", "Confirmed by Finance")
    "FAILED" -> hiltechText("فشل الصرف", "Payment failed")
    "RETURNED" -> hiltechText("عاد التحويل", "Transfer returned")
    "PENDING" -> hiltechText("قيد التنفيذ", "In progress")
    "UNKNOWN" -> hiltechText("نتيجة التحويل غير محسومة", "Transfer outcome is unknown")
    "PAYMENT_NOT_RECORDED" -> hiltechText("لم تُسجّل نتيجة صرف بعد", "No payment result has been recorded")
    "PAYMENT_STATUS_UNAVAILABLE" -> hiltechText("حالة الصرف غير متاحة الآن", "Payment status is currently unavailable")
    "NOT_APPLICABLE" -> hiltechText("لا يوجد مبلغ مطلوب تحويله", "No transfer required")
    else -> hiltechText("غير مؤكدة — قيد المراجعة", "Unconfirmed — under review")
}

data class OwnPayslipUiState(
    val loading: Boolean = false,
    val payslips: OwnPayslipsDto? = null,
    val errorCode: String? = null,
)

@Composable
fun OwnPayslipSection(
    state: OwnPayslipUiState,
    onRefresh: () -> Unit,
    hostOwnsVerticalScroll: Boolean = false,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LocalHiltechLanguage.current.direction) {
        Column(Modifier.fillMaxWidth().then(if (hostOwnsVerticalScroll) Modifier else Modifier.verticalScroll(rememberScrollState())),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(hiltechText("كشوف مرتباتي", "My payslips"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text(hiltechText("تحديث", "Refresh")) }
            }
            Text(hiltechText("يتم عرض كشوفك المعتمدة المرتبطة بحسابك فقط.", "Approved payslips linked to your signed-in account."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.loading) CircularProgressIndicator()
            state.errorCode?.let { Text(payrollMessage(it), color = MaterialTheme.colorScheme.error) }
            state.payslips?.let { data ->
                if (data.items.isEmpty()) Text(hiltechText("لا يوجد كشف معتمد متاح حاليًا.", "No released payslip is currently available."))
                data.items.forEach { slip -> HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(payrollDisplayDate(slip.periodStart) + " — " + payrollDisplayDate(slip.periodEnd), style = MaterialTheme.typography.titleMedium)
                    Text(hiltechText("الصافي", "Net"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(slip.net + " " + slip.currency, style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.primary)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val values = listOf(hiltechText("الاستحقاقات", "Earnings") to slip.gross,
                            hiltechText("الخصومات", "Deductions") to slip.deductions,
                            hiltechText("المستردات", "Reimbursements") to slip.reimbursements)
                        if (maxWidth >= 640.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            values.forEach { (label, amount) -> HiltechKeyValue(label, "$amount ${slip.currency}", Modifier.weight(1f)) }
                        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            values.forEach { (label, amount) -> HiltechKeyValue(label, "$amount ${slip.currency}") }
                        }
                    }
                    Text(hiltechText("حالة الصرف", "Payment status"), style = MaterialTheme.typography.labelLarge)
                    HiltechStateTag(payrollPaymentOutcome(slip.paymentStatus), when (slip.paymentStatus) {
                        "SUCCESS" -> HiltechStateTone.CURRENT
                        "FAILED", "RETURNED" -> HiltechStateTone.BLOCKED
                        "PENDING", "UNKNOWN" -> HiltechStateTone.ATTENTION
                        else -> HiltechStateTone.NEUTRAL
                    })
                } }
            }
        }
    }
}


/** Source-backed history: selecting a run never initiates a payment. */
@Composable
fun PayrollRunHistorySection(
    history: PayrollRunHistoryDto?,
    loading: Boolean,
    onOpenRun: (String) -> Unit,
    onRefresh: () -> Unit,
    hostOwnsVerticalScroll: Boolean = false,
    compact: Boolean = false,
    selectedRunVersionId: String? = null,
    selectionEnabled: Boolean = true,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LocalHiltechLanguage.current.direction) {
        if (compact) {
            if (history?.items?.isNotEmpty() == true) HiltechRecordPicker(
                hiltechText("نسخة كشف الرواتب", "Payroll version"), selectedRunVersionId.orEmpty(),
                history.items.map { run -> HiltechRecordChoice(run.runVersionId,
                    hiltechText("النسخة ${run.generation}", "Version ${run.generation}") + " · " + payrollLifecycleLabel(run.releaseState),
                    run.net + " " + run.currency + hiltechText(" · ${run.employeeCount} موظفين", " · ${run.employeeCount} employees")) },
                onChange = onOpenRun, enabled = !loading && selectionEnabled, allowKnownReference = false, allowClear = false,
            )
        } else Column(verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = if (hostOwnsVerticalScroll) Modifier else Modifier.verticalScroll(rememberScrollState())) {
            Text(hiltechText("سجل نسخ المرتبات", "Payroll run history"), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onRefresh, enabled = !loading) { Text(hiltechText("تحديث", "Refresh")) }
            if (loading) CircularProgressIndicator()
            if (history?.items?.isEmpty() == true) Text(hiltechText("لم تُجهّز نسخ لهذه الفترة.", "No run has been prepared for this period."))
            history?.items?.forEach { run -> HiltechPanel(Modifier.fillMaxWidth()) {
                Text(hiltechText("النسخة ", "Version ") + run.generation + " · " + payrollLifecycleLabel(run.releaseState),
                    style = MaterialTheme.typography.titleSmall)
                Text(hiltechText("صافي ", "Net ") + run.net + " " + run.currency +
                    hiltechText(" · عدد الموظفين ", " · Employees ") + run.employeeCount)
                OutlinedButton(onClick = { onOpenRun(run.runVersionId) }, enabled = !loading && selectionEnabled) {
                    Text(hiltechText("فتح نسخة التشغيل", "Open run"))
                }
            } }
        }
    }
}


/** Final pay consumes the existing People offboarding case; no device maths. */
@Composable
fun PayrollFinalPayReviewSection(
    finalPay: PayrollFinalPayReadinessDto?,
    loading: Boolean,
    errorCode: String?,
    onOpenOffboardingCase: (String) -> Unit,
    onRefresh: () -> Unit,
    hostOwnsVerticalScroll: Boolean = false,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LocalHiltechLanguage.current.direction) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = if (hostOwnsVerticalScroll) Modifier else Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text(hiltechText("مستحقات نهاية الخدمة", "Final pay"), style = MaterialTheme.typography.titleMedium)
            Text(hiltechText("مراجعة ملف خروج الموظف من مصدره الأصلي.", "Review the employee’s original offboarding record."))
            OutlinedButton(onClick = onRefresh) { Text(hiltechText("تحديث", "Refresh")) }
            if (loading) CircularProgressIndicator()
            errorCode?.let { Text(hiltechText("تعذرت المراجعة: ", "Unable to load the review: ") + it) }
            finalPay?.let { report ->
                Text(hiltechText("آخر يوم عمل: ", "Last working day: ") + payrollDisplayDate(report.lastWorkingDate))
                Text(hiltechText("ملف الخروج: ", "Offboarding: ") + report.offboardingState)
                Text(hiltechText("الموارد البشرية: ", "People: ") + (report.hrClearanceState ?: hiltechText("غير معروفة", "Unknown")))
                Text(hiltechText("المالية: ", "Finance: ") + (report.financeClearanceState ?: hiltechText("غير معروفة", "Unknown")))
                Text(hiltechText("مراجعة المرتبات: ", "Payroll review: ") + (report.payrollClearanceState ?: hiltechText("غير معروفة", "Unknown")))
                if (report.blockers.isNotEmpty()) {
                    Text(hiltechText("في نقاط تحتاج مراجعة", "Items requiring review"), style = MaterialTheme.typography.titleSmall)
                    report.blockers.forEach { blocker ->
                        Text("• " + when (blocker) {
                            "HR_CLEARANCE_NOT_CONFIRMED" -> hiltechText("شؤون الموظفين لم تعتمد إنهاء الإجراءات", "People has not completed clearance")
                            "FINANCE_CLEARANCE_NOT_CONFIRMED" -> hiltechText("المالية لم تؤكد تسوية العهد والسلف", "Finance has not confirmed advance and imprest settlement")
                            "VERIFIED_FINAL_PAY_SOURCE_MISSING_OR_STALE" ->
                                hiltechText("مستحقات نهاية الخدمة تحتاج مصدرًا ومراجعة معتمدين", "Final pay needs an approved source and review")
                            "FINAL_PAY_RUN_NOT_RELEASED_OR_STALE" ->
                                hiltechText("نسخة المرتب النهائية غير معتمدة أو تغير مصدرها", "Final pay run is not released or its source changed")
                            "FINANCE_PAYMENT_RESULT_NOT_CONNECTED" ->
                                hiltechText("نتيجة الصرف لم تصل من المالية بعد", "Finance has not supplied a payment result yet")
                            "FINAL_PAY_FINANCE_INSTRUCTION_NOT_MATCHED" ->
                                hiltechText("تعليمات المالية لا تطابق صافي المستحقات", "Finance instructions do not match the final net amount")
                            "FINAL_PAY_PAYMENT_NOT_CONFIRMED" ->
                                hiltechText("التحويل لم يتأكد نجاحه من المالية", "Finance has not confirmed a successful transfer")
                            "FINAL_PAY_FINANCE_UNEXPECTED_EVENT" ->
                                hiltechText("نتيجة مالية ظهرت رغم عدم وجود أي تحويل مطلوب", "A Finance result exists without a required transfer")
                            else -> hiltechText("تحتاج مراجعة المصدر: ", "Review source: ") + blocker
                        })
                    }
                } else if (report.paymentStatus == "NO_PAYMENT_REQUIRED") {
                    Text(hiltechText("المستحقات النهائية مُراجعة، ولا يوجد مبلغ يحتاج إلى تحويل.", "Final pay has been reviewed and no transfer is required."))
                } else {
                    Text(hiltechText("مصادر المستحقات ونتيجة الصرف مؤكدة للمراجعة النهائية.", "Entitlement sources and payment results are confirmed for final review."))
                }
                report.frozenNet?.let { net ->
                    Text(hiltechText("الصافي المسجل: ", "Recorded net: ") + net + " " + (report.currency ?: ""))
                }
                Text(hiltechText("حالة الصرف: ", "Payment status: ") + when (report.paymentStatus) {
                    "NO_PAYMENT_REQUIRED" -> hiltechText("لا يوجد مبلغ مطلوب تحويله", "No transfer required")
                    "SUCCESS" -> hiltechText("تم التأكيد من المالية", "Confirmed by Finance")
                    "FAILED" -> hiltechText("فشل الصرف", "Payment failed")
                    "RETURNED" -> hiltechText("عاد التحويل", "Transfer returned")
                    "PENDING" -> hiltechText("قيد التنفيذ", "In progress")
                    "UNKNOWN" -> hiltechText("نتيجة التحويل غير محسومة", "Transfer outcome is unknown")
                            "PAYMENT_NOT_RECORDED" -> hiltechText("لم تُسجّل نتيجة صرف بعد", "No payment result has been recorded")
                            "PAYMENT_STATUS_UNAVAILABLE" -> hiltechText("حالة الصرف غير متاحة الآن", "Payment status is currently unavailable")
                    else -> hiltechText("لم تتأكد المالية من الصرف", "Finance has not confirmed payment")
                })
                OutlinedButton(
                    onClick = { onOpenOffboardingCase(report.offboardingCaseId) },
                ) {
                    Text(hiltechText("فتح ملف خروج الموظف", "Open offboarding record"))
                }
                Text(hiltechText("أي صرف أو تسوية فعلية تتم داخل النظام المالي، وليس من هذه الشاشة.", "Finance owns actual payments and settlement."))
            }
        }
    }
}


/**
 * Finance REVIEW handoff only. The host must supply an authenticated Payroll
 * reviewer and a connected Finance intake source before showing this action.
 * Does not say "paid", initiate bank execution or display bank account data.
 */
@Composable
fun PayrollFinanceReviewHandoffSection(
    runVersionId: String,
    released: Boolean,
    payrollReviewerAuthorized: Boolean,
    financeIntakeAvailable: Boolean,
    loading: Boolean,
    errorCode: String?,
    outcome: PayrollFinanceHandoffResultDto?,
    onHandoff: (String) -> Unit,
    onOpenFinance: () -> Unit,
    hostOwnsVerticalScroll: Boolean = false,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LocalHiltechLanguage.current.direction) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = if (hostOwnsVerticalScroll) Modifier else Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text(hiltechText("تسليم كشف المرتبات للمالية", "Payroll handoff to Finance"), style = MaterialTheme.typography.titleMedium)
            Text(hiltechText("تسليم دفعة للمراجعة والاعتمادات المالية، وليس تحويل فلوس.", "Send the released run for Finance review and approval."))
            if (loading) CircularProgressIndicator()
            errorCode?.let { Text(hiltechText("تعذر تسليم الدفعة: ", "Unable to hand off the batch: ") + it) }
            if (outcome == null) {
                if (released && payrollReviewerAuthorized && financeIntakeAvailable &&
                    runVersionId.isNotBlank() && !loading
                ) {
                    Button(onClick = { onHandoff(runVersionId) }) {
                        Text(hiltechText("إرسال الدفعة المعتمدة للمراجعة المالية", "Send the released batch to Finance review"))
                    }
                } else {
                    Text(hiltechText("التسليم غير متاح حتى يتم اعتماد كشف المرتبات وتوصيل المالية.", "Release the payroll and make Finance review available before handoff."))
                }
            } else {
                Text(hiltechText("حالة الدفعة: ", "Batch status: ") + when (outcome.state) {
                    "REVIEW_REQUIRED" -> hiltechText("في انتظار مراجعة المالية", "Awaiting Finance review")
                    else -> hiltechText("تحتاج مراجعة", "Requires review")
                })
                Text(hiltechText("عدد الموظفين: ", "Employees: ") + outcome.employeeCount)
                Text(hiltechText("مبالغ تستلزم تحويل: ", "Transfer candidates: ") + outcome.transferCandidates)
                Text(hiltechText("صافي الدفعة: ", "Batch net: ") + outcome.payableTotal + " " + outcome.currency)
                Text(hiltechText("مرجع الدفعة: ", "Batch reference: ") + outcome.financeReviewBatchId)
                OutlinedButton(onClick = onOpenFinance) {
                    Text(hiltechText("فتح مراجعة المالية", "Open Finance review"))
                }
                Text(hiltechText("التحويل البنكي الفعلي يتم فقط من النظام المالي بعد اعتماده.", "Finance handles the actual bank transfer after approval."))
            }
        }
    }
}
