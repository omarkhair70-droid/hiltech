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
)

@Composable
fun PayrollRunReviewSection(
    state: PayrollRunReviewUiState,
    periodId: String,
    onPrepareDraft: (String) -> Unit,
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
                        Text("الموظف " + line.employeeId, style = MaterialTheme.typography.titleSmall)
                        Text("إجمالي " + line.gross + " | خصم " + line.deductions)
                        Text("صافي " + line.net + " " + line.currency)
                    }
                }
                if (!run.approvalAllowed || !run.paymentAllowed) {
                    Text("لا يسمح هذا التشغيل باعتماد أو صرف مباشر.")
                }
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
                            else -> "غير مؤكدة — قيد المراجعة"
                        })
                    }
                }
            }
        }
    }
}
