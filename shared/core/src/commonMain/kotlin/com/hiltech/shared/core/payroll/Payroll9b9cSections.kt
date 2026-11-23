package com.hiltech.shared.core.payroll

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
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
)

@Composable
fun PayrollRunReviewSection(
    state: PayrollRunReviewUiState,
    periodId: String,
    onPrepareDraft: (String) -> Unit,
    onRequestApproval: (String) -> Unit,
    onReleaseForFinanceReview: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text("مراجعة المرتبات", style = MaterialTheme.typography.titleMedium)
            Text("أرقام من مصادر موثقة فقط؛ هذه ليست خطوة اعتماد أو صرف.")
            if (state.loading) CircularProgressIndicator()
            state.errorCode?.let { Text("تعذّر إتمام العملية: " + it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh) { Text("تحديث") }
                if (state.mayPrepare && periodId.isNotBlank()) {
                    Button(onClick = { onPrepareDraft(periodId) }) {
                        Text("تجهيز مسودة من المدخلات المعتمدة")
                    }
                }
            }
            state.run?.let { run ->
                Text("التشغيل رقم " + run.generation, style = MaterialTheme.typography.titleSmall)
                Text("الحالة: " + run.state)
                Text("الصافي: " + run.net + " " + run.currency,
                    style = MaterialTheme.typography.titleMedium)
                Text("الاستحقاقات " + run.gross + " | الخصومات " + run.deductions +
                    " | المستردات " + run.reimbursements)
                Text("نسخة المصادر: " + run.sourceFingerprint.take(12))
                run.lines.forEach { line ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            line.displayName + " — " + line.employeeCode,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text("إجمالي " + line.gross + " | خصم " + line.deductions)
                        Text("صافي " + line.net + " " + line.currency)
                    }
                }
                if (state.mayRequestApproval) {
                    OutlinedButton(onClick = { onRequestApproval(run.runVersionId) }) {
                        Text("إحالة النسخة للموافقة حسب سياسة الشركة")
                    }
                }
                if (state.mayReleaseForFinanceReview) {
                    Button(onClick = { onReleaseForFinanceReview(run.runVersionId) }) {
                        Text("تثبيت اعتماد النسخة للمراجعة المالية")
                    }
                }
                state.approval?.let {
                    Text("طلب الموافقة: " +
                        (it.approvalState ?: if (it.approvalRequired) "معلّق" else "اعتماد إضافي غير مطلوب"))
                }
                state.release?.let {
                    Text("تسليم للمراجعة المالية: " + it.status +
                        " — " + it.decisionPath)
                }
                state.finance?.let { finance ->
                    Text("المطابقة المالية", style = MaterialTheme.typography.titleSmall)
                    Text("الحالة: " + when (finance.status) {
                        "PAID" -> "مؤكد بالكامل من المالية"
                        "PARTIALLY_PAID" -> "صرف جزئي"
                        "NO_PAYMENT_REQUIRED" -> "لا توجد مبالغ تحتاج تحويلًا"
                        "REQUIRES_REVIEW" -> "مراجعة نتائج الصرف مطلوبة"
                        "NOT_RELEASED" -> "لم يُعتمد التسليم للمالية"
                        "FINANCE_NOT_CONNECTED" -> "مصدر نتيجة المالية غير متصل"
                        else -> "لم يُنفّذ صرف مؤكد"
                    })
                    val noTransferCount = finance.employeeOutcomes.count {
                        it.outcome == "NOT_APPLICABLE"
                    }
                    Text("تحويلات مؤكدة " + finance.settledCount +
                        " | لم تكتمل " + finance.unresolvedCount +
                        " | لا تحتاج تحويلًا " + noTransferCount +
                        " | إجمالي الموظفين " + finance.employeeCount)
                    finance.employeeOutcomes.forEach { person ->
                        val label = run.lines.firstOrNull {
                            it.employeeId == person.employeeId
                        }?.displayName ?: person.employeeId
                        Text(label + ": " + when (person.outcome) {
                            "SUCCESS" -> "مؤكد"
                            "FAILED" -> "فشل"
                            "RETURNED" -> "مرتجع"
                            "PENDING" -> "معلّق"
                            "UNKNOWN" -> "نتيجة التحويل غير محسومة"
                            "NOT_APPLICABLE" -> "لا يوجد مبلغ مطلوب تحويله"
                            else -> "غير معروف"
                        })
                    }
                }
                Text("عمليات البنك الفعلية غير متاحة من شاشة Payroll.")

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
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text("كشوف مرتباتي", style = MaterialTheme.typography.titleMedium)
            Text("يتم عرض كشوفك المعتمدة المرتبطة بحسابك فقط.")
            if (state.loading) CircularProgressIndicator()
            state.errorCode?.let { Text("تعذّر عرض الكشوف: " + it) }
            OutlinedButton(onClick = onRefresh) { Text("تحديث") }
            state.payslips?.let { data ->
                if (data.items.isEmpty()) Text("لا يوجد كشف معتمد متاح حاليًا.")
                data.items.forEach { slip ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("الفترة " + slip.periodStart + " — " + slip.periodEnd,
                            style = MaterialTheme.typography.titleSmall)
                        Text("الاستحقاقات: " + slip.gross + " " + slip.currency)
                        Text("الخصومات: " + slip.deductions + " " + slip.currency)
                        Text("المستردات: " + slip.reimbursements + " " + slip.currency)
                        Text("الصافي: " + slip.net + " " + slip.currency,
                            style = MaterialTheme.typography.titleMedium)
                        Text("حالة الصرف: " + when (slip.paymentStatus) {
                            "SUCCESS" -> "تم التأكيد من المالية"
                            "FAILED" -> "فشل الصرف"
                            "RETURNED" -> "عاد التحويل"
                            "PENDING" -> "قيد التنفيذ"
                            "UNKNOWN" -> "نتيجة التحويل غير محسومة"
                            "NOT_APPLICABLE" -> "لا يوجد مبلغ مطلوب تحويله"
                            else -> "غير مؤكدة — قيد المراجعة"
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
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text("سجل نسخ المرتبات", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onRefresh) { Text("تحديث") }
            if (loading) CircularProgressIndicator()
            if (history?.items?.isEmpty() == true) Text("لم تُجهّز نسخ لهذه الفترة.")
            history?.items?.forEach { run ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("النسخة " + run.generation +
                        " — " + run.state, style = MaterialTheme.typography.titleSmall)
                    Text("صافي " + run.net + " " + run.currency +
                        " | عدد الموظفين " + run.employeeCount)
                    OutlinedButton(onClick = { onOpenRun(run.runVersionId) }) {
                        Text("فتح نسخة التشغيل")
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
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text("مستحقات نهاية الخدمة", style = MaterialTheme.typography.titleMedium)
            Text("مراجعة ملف خروج الموظف من مصدره الأصلي.")
            OutlinedButton(onClick = onRefresh) { Text("تحديث") }
            if (loading) CircularProgressIndicator()
            errorCode?.let { Text("تعذرت المراجعة: " + it) }
            finalPay?.let { report ->
                Text("آخر يوم عمل: " + report.lastWorkingDate)
                Text("ملف الخروج: " + report.offboardingState)
                Text("الموارد البشرية: " + (report.hrClearanceState ?: "غير معروفة"))
                Text("المالية: " + (report.financeClearanceState ?: "غير معروفة"))
                Text("مراجعة المرتبات: " + (report.payrollClearanceState ?: "غير معروفة"))
                if (report.blockers.isNotEmpty()) {
                    Text("في نقاط تحتاج مراجعة", style = MaterialTheme.typography.titleSmall)
                    report.blockers.forEach { blocker ->
                        Text("• " + when (blocker) {
                            "HR_CLEARANCE_NOT_CONFIRMED" -> "شؤون الموظفين لم تعتمد إنهاء الإجراءات"
                            "FINANCE_CLEARANCE_NOT_CONFIRMED" -> "المالية لم تؤكد تسوية العهد والسلف"
                            "VERIFIED_FINAL_PAY_SOURCE_MISSING_OR_STALE" ->
                                "مستحقات نهاية الخدمة تحتاج مصدرًا ومراجعة معتمدين"
                            "FINAL_PAY_RUN_NOT_RELEASED_OR_STALE" ->
                                "نسخة المرتب النهائية غير معتمدة أو تغير مصدرها"
                            "FINANCE_PAYMENT_RESULT_NOT_CONNECTED" ->
                                "نتيجة الصرف لم تصل من المالية بعد"
                            "FINAL_PAY_FINANCE_INSTRUCTION_NOT_MATCHED" ->
                                "تعليمات المالية لا تطابق صافي المستحقات"
                            "FINAL_PAY_PAYMENT_NOT_CONFIRMED" ->
                                "التحويل لم يتأكد نجاحه من المالية"
                            "FINAL_PAY_FINANCE_UNEXPECTED_EVENT" ->
                                "نتيجة مالية ظهرت رغم عدم وجود أي تحويل مطلوب"
                            else -> "تحتاج مراجعة المصدر: " + blocker
                        })
                    }
                } else if (report.paymentStatus == "NO_PAYMENT_REQUIRED") {
                    Text("المستحقات النهائية مُراجعة، ولا يوجد مبلغ يحتاج إلى تحويل.")
                } else {
                    Text("مصادر المستحقات ونتيجة الصرف مؤكدة للمراجعة النهائية.")
                }
                report.frozenNet?.let { net ->
                    Text("الصافي المسجل: " + net + " " + (report.currency ?: ""))
                }
                Text("حالة الصرف: " + when (report.paymentStatus) {
                    "NO_PAYMENT_REQUIRED" -> "لا يوجد مبلغ مطلوب تحويله"
                    "SUCCESS" -> "تم التأكيد من المالية"
                    "FAILED" -> "فشل الصرف"
                    "RETURNED" -> "عاد التحويل"
                    "PENDING" -> "قيد التنفيذ"
                    "UNKNOWN" -> "نتيجة التحويل غير محسومة"
                    else -> "لم تتأكد المالية من الصرف"
                })
                OutlinedButton(
                    onClick = { onOpenOffboardingCase(report.offboardingCaseId) },
                ) {
                    Text("فتح ملف خروج الموظف")
                }
                Text("أي صرف أو تسوية فعلية تتم داخل النظام المالي، وليس من هذه الشاشة.")
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
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text("تسليم كشف المرتبات للمالية", style = MaterialTheme.typography.titleMedium)
            Text("تسليم دفعة للمراجعة والاعتمادات المالية، وليس تحويل فلوس.")
            if (loading) CircularProgressIndicator()
            errorCode?.let { Text("تعذر تسليم الدفعة: " + it) }
            if (outcome == null) {
                if (released && payrollReviewerAuthorized && financeIntakeAvailable &&
                    runVersionId.isNotBlank() && !loading
                ) {
                    Button(onClick = { onHandoff(runVersionId) }) {
                        Text("إرسال الدفعة المعتمدة للمراجعة المالية")
                    }
                } else {
                    Text("التسليم غير متاح حتى يتم اعتماد كشف المرتبات وتوصيل المالية.")
                }
            } else {
                Text("حالة الدفعة: " + when (outcome.state) {
                    "REVIEW_REQUIRED" -> "في انتظار مراجعة المالية"
                    else -> "تحتاج مراجعة"
                })
                Text("عدد الموظفين: " + outcome.employeeCount)
                Text("مبالغ تستلزم تحويل: " + outcome.transferCandidates)
                Text("صافي الدفعة: " + outcome.payableTotal + " " + outcome.currency)
                Text("مرجع الدفعة: " + outcome.financeReviewBatchId)
                OutlinedButton(onClick = onOpenFinance) {
                    Text("فتح مراجعة المالية")
                }
                Text("التحويل البنكي الفعلي يتم فقط من النظام المالي بعد اعتماده.")
            }
        }
    }
}
