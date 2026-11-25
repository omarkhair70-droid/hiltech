package com.hiltech.shared.core.payroll

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalLayoutDirection
import com.hiltech.shared.core.design.LocalHiltechLanguage
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
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = if (hostOwnsVerticalScroll) Modifier else Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text(hiltechText("مراجعة المرتبات", "Payroll review"), style = MaterialTheme.typography.titleMedium)
            Text(hiltechText("راجع الحساب من مصادره المعتمدة، ثم أرسل النسخة للمراجعة وثبّت اعتمادها.", "Review the calculation from approved sources, then submit and release the exact version."))
            if (state.loading) CircularProgressIndicator()
            state.errorCode?.let { Text(payrollMessage(it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = !state.loading && !state.commandLocked) { Text(hiltechText("تحديث", "Refresh")) }
                if (state.mayPrepare && periodId.isNotBlank()) {
                    Button(onClick = { onPrepareDraft(periodId) }, enabled = !state.loading && !state.commandLocked) {
                        Text(hiltechText("تجهيز مسودة من المدخلات المعتمدة", "Calculate from approved inputs"))
                    }
                }
            }
            state.run?.let { run ->
                Text(hiltechText("التشغيل رقم ", "Run ") + run.generation, style = MaterialTheme.typography.titleSmall)
                Text(hiltechText("الحالة: ", "Status: ") + payrollLifecycleLabel(state.releaseState))
                Text(hiltechText("الصافي: ", "Net: ") + run.net + " " + run.currency,
                    style = MaterialTheme.typography.titleMedium)
                Text(hiltechText("الاستحقاقات ", "Earnings ") + run.gross + hiltechText(" | الخصومات ", " | Deductions ") + run.deductions +
                    hiltechText(" | المستردات ", " | Reimbursements ") + run.reimbursements)

                run.lines.forEach { line ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            line.displayName + " — " + line.employeeCode,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(hiltechText("إجمالي ", "Gross ") + line.gross + hiltechText(" | خصم ", " | Deductions ") + line.deductions)
                        Text(hiltechText("صافي ", "Net ") + line.net + " " + line.currency)
                    }
                }
                state.review?.takeIf { it.runVersionId == run.runVersionId }?.let { review ->
                    Text(hiltechText("مراجعة النسخة: ", "Run review: ") + payrollRunReviewLabel(review.approvalState))
                    if (review.approvalState in setOf("POLICY_REVIEW_REQUIRED", "REJECTED", "CHANGE_REQUESTED", "SOURCE_CHANGED", "SUPERSEDED")) {
                        Text(hiltechText("راجع المصادر وأعد حساب نسخة جديدة لعرضها على المراجع الحالي.", "Review the sources and calculate a new version for the current reviewer."))
                    }
                    if (review.canDecide && review.approvalRequestId != null) {
                        var note by remember(run.runVersionId, review.approvalRequestId) { mutableStateOf(retainedDecisionComment.orEmpty()) }
                        Text(hiltechText("قرارك يخص القيم والموظفين في هذه النسخة فقط.", "Your decision applies to the values and employees in this version only."))
                        OutlinedTextField(note, { note = it.take(1000) }, modifier = Modifier.fillMaxWidth(),
                            enabled = !state.loading && !state.commandLocked, label = { Text(hiltechText("ملاحظة المراجعة", "Review note")) },
                            supportingText = { Text(hiltechText("السبب مطلوب لطلب تعديل أو رفض النسخة.", "A reason is required to request changes or reject this version.")) })
                        Button(onClick = { onDecide(run.runVersionId, review.approvalRequestId, "APPROVE", note.trim().takeIf(String::isNotBlank)) },
                            enabled = !state.loading && !state.commandLocked) { Text(hiltechText("اعتماد حساب هذه النسخة", "Approve this payroll calculation")) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onDecide(run.runVersionId, review.approvalRequestId, "REQUEST_CHANGE", note.trim()) },
                                enabled = !state.loading && !state.commandLocked && note.isNotBlank()) { Text(hiltechText("طلب تعديل الحساب", "Request calculation changes")) }
                            TextButton(onClick = { onDecide(run.runVersionId, review.approvalRequestId, "REJECT", note.trim()) },
                                enabled = !state.loading && !state.commandLocked && note.isNotBlank()) { Text(hiltechText("رفض النسخة", "Reject version")) }
                        }
                    }
                }
                if (state.mayRequestApproval) {
                    OutlinedButton(onClick = { onRequestApproval(run.runVersionId) }, enabled = !state.loading && !state.commandLocked) {
                        Text(hiltechText("إحالة النسخة للموافقة حسب سياسة الشركة", "Request review under company policy"))
                    }
                }
                if (state.mayReleaseForFinanceReview) {
                    Button(onClick = { onReleaseForFinanceReview(run.runVersionId) }, enabled = !state.loading && !state.commandLocked) {
                        Text(hiltechText("تثبيت اعتماد النسخة للمراجعة المالية", "Release this version for Finance review"))
                    }
                }
                state.approval?.takeIf { state.review == null }?.let {
                    Text(hiltechText("طلب الموافقة: ", "Approval request: ") +
                        (it.approvalState?.let { state -> payrollSourceStateLabel(state) } ?: if (it.approvalRequired) hiltechText("معلّق", "Pending") else hiltechText("اعتماد إضافي غير مطلوب", "No additional approval required")))
                }
                state.release?.let {
                    Text(if (it.status == "RELEASED_FOR_FINANCE_REVIEW") hiltechText(
                        "تم تثبيت اعتماد هذه النسخة للمراجعة المالية.", "This version was released for Finance review.")
                    else hiltechText("راجع حالة اعتماد النسخة الحالية.", "Review the release status of the current version."))
                }
                state.finance?.let { finance ->
                    Text(hiltechText("المطابقة المالية", "Finance reconciliation"), style = MaterialTheme.typography.titleSmall)
                    Text(hiltechText("الحالة: ", "Status: ") + when (finance.status) {
                        "PAID" -> hiltechText("مؤكد بالكامل من المالية", "Fully confirmed by Finance")
                        "PARTIALLY_PAID" -> hiltechText("صرف جزئي", "Partially paid")
                        "NO_PAYMENT_REQUIRED" -> hiltechText("لا توجد مبالغ تحتاج تحويلًا", "No transfer required")
                        "REQUIRES_REVIEW" -> hiltechText("مراجعة نتائج الصرف مطلوبة", "Payment results require review")
                        "NOT_RELEASED" -> hiltechText("لم يُعتمد التسليم للمالية", "Not yet released for Finance review")
                        "FINANCE_NOT_CONNECTED", "PAYMENT_STATUS_UNAVAILABLE" -> hiltechText("حالة الصرف غير متاحة الآن", "Payment status is currently unavailable")
                        "PAYMENT_NOT_RECORDED" -> hiltechText("لم تُسجّل نتيجة صرف بعد", "No payment result has been recorded")
                        else -> hiltechText("لم يُنفّذ صرف مؤكد", "Payment has not been confirmed")
                    })
                    val noTransferCount = finance.employeeOutcomes.count {
                        it.outcome == "NOT_APPLICABLE"
                    }
                    Text(hiltechText("تحويلات مؤكدة ", "Confirmed transfers ") + finance.settledCount +
                        hiltechText(" | لم تكتمل ", " | Unresolved ") + finance.unresolvedCount +
                        hiltechText(" | لا تحتاج تحويلًا ", " | No transfer required ") + noTransferCount +
                        hiltechText(" | إجمالي الموظفين ", " | Employees ") + finance.employeeCount)
                    finance.employeeOutcomes.forEach { person ->
                        val label = run.lines.firstOrNull {
                            it.employeeId == person.employeeId
                        }?.displayName ?: hiltechText("موظف من النسخة", "Employee in this run")
                        Text(label + ": " + when (person.outcome) {
                            "SUCCESS" -> hiltechText("مؤكد", "Confirmed")
                            "FAILED" -> hiltechText("فشل", "Failed")
                            "RETURNED" -> hiltechText("مرتجع", "Returned")
                            "PENDING" -> hiltechText("معلّق", "Pending")
                            "UNKNOWN" -> hiltechText("نتيجة التحويل غير محسومة", "Transfer outcome is unknown")
                            "PAYMENT_NOT_RECORDED" -> hiltechText("لم تُسجّل نتيجة صرف بعد", "No payment result has been recorded")
                            "PAYMENT_STATUS_UNAVAILABLE" -> hiltechText("حالة الصرف غير متاحة الآن", "Payment status is currently unavailable")
                            "NOT_APPLICABLE" -> hiltechText("لا يوجد مبلغ مطلوب تحويله", "No transfer required")
                            else -> hiltechText("غير معروف", "Unknown")
                        })
                    }
                }
                Text(hiltechText("تسجّل المالية نتيجة الصرف الفعلية وتطابقها.", "Finance records and reconciles the actual payment outcome."))

            }
        }
    }
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
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = if (hostOwnsVerticalScroll) Modifier else Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text(hiltechText("كشوف مرتباتي", "My payslips"), style = MaterialTheme.typography.titleMedium)
            Text(hiltechText("يتم عرض كشوفك المعتمدة المرتبطة بحسابك فقط.", "Approved payslips linked to your signed-in account."))
            if (state.loading) CircularProgressIndicator()
            state.errorCode?.let { Text(payrollMessage(it)) }
            OutlinedButton(onClick = onRefresh, enabled = !state.loading) { Text(hiltechText("تحديث", "Refresh")) }
            state.payslips?.let { data ->
                if (data.items.isEmpty()) Text(hiltechText("لا يوجد كشف معتمد متاح حاليًا.", "No released payslip is currently available."))
                data.items.forEach { slip ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(hiltechText("الفترة ", "Period ") + payrollDisplayDate(slip.periodStart) + " — " + payrollDisplayDate(slip.periodEnd),
                            style = MaterialTheme.typography.titleSmall)
                        Text(hiltechText("الاستحقاقات: ", "Earnings: ") + slip.gross + " " + slip.currency)
                        Text(hiltechText("الخصومات: ", "Deductions: ") + slip.deductions + " " + slip.currency)
                        Text(hiltechText("المستردات: ", "Reimbursements: ") + slip.reimbursements + " " + slip.currency)
                        Text(hiltechText("الصافي: ", "Net: ") + slip.net + " " + slip.currency,
                            style = MaterialTheme.typography.titleMedium)
                        Text(hiltechText("حالة الصرف: ", "Payment status: ") + when (slip.paymentStatus) {
                            "SUCCESS" -> hiltechText("تم التأكيد من المالية", "Confirmed by Finance")
                            "FAILED" -> hiltechText("فشل الصرف", "Payment failed")
                            "RETURNED" -> hiltechText("عاد التحويل", "Transfer returned")
                            "PENDING" -> hiltechText("قيد التنفيذ", "In progress")
                            "UNKNOWN" -> hiltechText("نتيجة التحويل غير محسومة", "Transfer outcome is unknown")
                            "PAYMENT_NOT_RECORDED" -> hiltechText("لم تُسجّل نتيجة صرف بعد", "No payment result has been recorded")
                            "PAYMENT_STATUS_UNAVAILABLE" -> hiltechText("حالة الصرف غير متاحة الآن", "Payment status is currently unavailable")
                            "NOT_APPLICABLE" -> hiltechText("لا يوجد مبلغ مطلوب تحويله", "No transfer required")
                            else -> hiltechText("غير مؤكدة — قيد المراجعة", "Unconfirmed — under review")
                        })
                    }
                }
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
) {
    CompositionLocalProvider(LocalLayoutDirection provides LocalHiltechLanguage.current.direction) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = if (hostOwnsVerticalScroll) Modifier else Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text(hiltechText("سجل نسخ المرتبات", "Payroll run history"), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onRefresh) { Text(hiltechText("تحديث", "Refresh")) }
            if (loading) CircularProgressIndicator()
            if (history?.items?.isEmpty() == true) Text(hiltechText("لم تُجهّز نسخ لهذه الفترة.", "No run has been prepared for this period."))
            history?.items?.forEach { run ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(hiltechText("النسخة ", "Version ") + run.generation +
                        " — " + payrollLifecycleLabel(run.releaseState), style = MaterialTheme.typography.titleSmall)
                    Text(hiltechText("صافي ", "Net ") + run.net + " " + run.currency +
                        hiltechText(" | عدد الموظفين ", " | Employees ") + run.employeeCount)
                    OutlinedButton(onClick = { onOpenRun(run.runVersionId) }) {
                        Text(hiltechText("فتح نسخة التشغيل", "Open run"))
                    }
                }
            }
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
