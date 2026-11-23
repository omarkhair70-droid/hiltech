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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Production-shared Compose surface; host integration remains a distinct lane.
 * Never load or cache this behind the general People directory.
 */
data class Payroll9aUiState(
    val loading: Boolean = false,
    val periods: PayrollPeriodListDto? = null,
    val selected: PayrollPeriodDetailDto? = null,
    val errorCode: String? = null,
    val canPrepare: Boolean = false,
    val canReview: Boolean = false,
)

@Composable
fun Payroll9aAdminSection(
    state: Payroll9aUiState,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onCreatePeriod: (String, String, String, String, String?) -> Unit,
    onCaptureInputs: (String) -> Unit,
    onReviewException: (String, String, String) -> Unit,
    hostOwnsVerticalScroll: Boolean = false,
) {
    var periodCode by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }
    var cutoffDate by remember { mutableStateOf("") }
    var targetPayment by remember { mutableStateOf("") }
    var reviewNote by remember { mutableStateOf("") }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = if (hostOwnsVerticalScroll) Modifier else Modifier.verticalScroll(rememberScrollState()),
        ) {
            Text("فترات المرتبات", style = MaterialTheme.typography.titleMedium)
            Text("تحضير ومراجعة المصادر فقط — الحساب والاعتماد والصرف غير متاحين.")
            if (state.loading) CircularProgressIndicator()
            state.errorCode?.let { Text("تعذر تنفيذ العملية: " + it) }
            OutlinedButton(onClick = onRefresh) { Text("تحديث") }

            state.periods?.items?.forEach { period ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(period.periodCode, style = MaterialTheme.typography.titleSmall)
                        Text(period.startDate + " — " + period.endDate)
                        Text("الموظفون: " + period.employeeCount +
                            " | استثناءات المدخلات: " + period.exceptionCount)
                    }
                    OutlinedButton(onClick = { onSelect(period.periodId) }) {
                        Text("تفاصيل")
                    }
                }
            }

            if (state.canPrepare) {
                Text("فترة جديدة", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(value = periodCode, onValueChange = { periodCode = it }, label = { Text("رمز الفترة") })
                OutlinedTextField(value = startDate, onValueChange = { startDate = it }, label = { Text("من YYYY-MM-DD") })
                OutlinedTextField(value = endDate, onValueChange = { endDate = it }, label = { Text("إلى YYYY-MM-DD") })
                OutlinedTextField(value = cutoffDate, onValueChange = { cutoffDate = it }, label = { Text("آخر يوم لتجميع المدخلات") })
                OutlinedTextField(value = targetPayment, onValueChange = { targetPayment = it }, label = { Text("تاريخ الصرف المستهدف — اختياري، ليس أمر صرف") })
                Button(
                    enabled = periodCode.isNotBlank() && startDate.isNotBlank() &&
                        endDate.isNotBlank() && cutoffDate.isNotBlank(),
                    onClick = {
                        onCreatePeriod(
                            periodCode,
                            startDate,
                            endDate,
                            cutoffDate,
                            targetPayment.ifBlank { null },
                        )
                    },
                ) { Text("إنشاء الفترة") }
            }

            state.selected?.let { detail ->
                Text("تفاصيل " + detail.period.periodCode, style = MaterialTheme.typography.titleMedium)
                Text("الحالة: " + detail.period.state)
                if (detail.period.state == "OPEN" && state.canPrepare) {
                    Button(onClick = { onCaptureInputs(detail.period.periodId) }) {
                        Text("تثبيت لقطة البيانات الحالية")
                    }
                }
                Text("المدخلات الناقصة لا تعني صفرًا. لا يمكن حساب أو اعتماد الرواتب من هنا.")
                if (detail.employees.isEmpty()) {
                    Text("لا توجد لقطة موظفين لهذه الفترة.")
                }
                detail.employees.forEach { employee ->
                    Text(
                        employee.displayName + " · " + employee.employeeCode,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("حالة التوظيف: " + employee.employeeState)
                    if (employee.sourceChanged) {
                        Text("تغيّرت بيانات المصدر بعد أخذ اللقطة — يجب مراجعة التغيير")
                    }
                    employee.exceptions.forEach { exception ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("مدخل غير جاهز: " + exception.code)
                            Text("المصدر: " + exception.sourceDomain +
                                " · لا يُسمح بالحساب حتى حلّ مصدر البيانات")
                            if (exception.reviews.isNotEmpty()) {
                                Text("مراجعات موثقة: " + exception.reviews.size)
                            }
                            if (state.canReview) {
                                OutlinedTextField(
                                    value = reviewNote,
                                    onValueChange = { reviewNote = it },
                                    label = { Text("ملاحظة مراجعة، لا تُزيل الاستثناء") },
                                )
                                OutlinedButton(
                                    enabled = reviewNote.isNotBlank(),
                                    onClick = {
                                        onReviewException(
                                            detail.period.periodId,
                                            exception.exceptionId,
                                            reviewNote,
                                        )
                                        reviewNote = ""
                                    },
                                ) { Text("تسجيل مراجعة") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Employee navigation must not expose even draft coworker data or fictional payslips. */
@Composable
fun Payroll9aEmployeePendingSection() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("كشف المرتب", style = MaterialTheme.typography.titleMedium)
            Text("لن يتاح كشف راتب شخصي قبل اعتماد سياسات المرتبات ونتيجة الصرف الفعلية.")
        }
    }
}
