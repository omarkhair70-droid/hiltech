package com.hiltech.shared.core.payroll

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.ui.Alignment
import com.hiltech.shared.core.design.*
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
    val colors = LocalHiltechOperationalColors.current
    var creating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var openDetail by remember(state.selected?.period?.periodId) { mutableStateOf(state.selected != null) }
    var exceptionsOnly by remember(state.selected?.period?.periodId) { mutableStateOf(false) }
    var periodCode by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf("") }
    var endDate by remember { mutableStateOf("") }
    var cutoffDate by remember { mutableStateOf("") }
    var targetPayment by remember { mutableStateOf("") }
    var reviewNote by remember { mutableStateOf("") }

    CompositionLocalProvider(LocalLayoutDirection provides LocalHiltechLanguage.current.direction) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = if (hostOwnsVerticalScroll) Modifier else Modifier.verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    HiltechSectionHeading("PAYROLL", hiltechText("فترات المرتبات", "Payroll periods"),
                        hiltechText("راجع فترات المرتبات وبيانات الموظفين واستثناءات المدخلات.", "Review payroll periods, employees and input exceptions."))
                }
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text(hiltechText("تحديث", "Refresh")) }
            }
            if (state.loading) CircularProgressIndicator()
            state.errorCode?.let { Text(hiltechText("تعذر تنفيذ العملية: ", "Unable to complete operation: ") + it) }

            if (!openDetail || state.selected == null) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(hiltechText("ابحث برمز الفترة أو التاريخ", "Find a period or date")) })
                val matching = state.periods?.items.orEmpty().filter { period ->
                    query.isBlank() || listOf(period.periodCode, period.startDate, period.endDate).any { it.contains(query.trim(), true) }
                }
                if (!state.loading && matching.isEmpty()) Text(hiltechText("لا توجد فترات مطابقة.", "No matching periods."))
                matching.forEach { period ->
                    Surface(onClick = { openDetail = true; onSelect(period.periodId) },
                        enabled = !state.loading, modifier = Modifier.fillMaxWidth(),
                        color = colors.surface, contentColor = colors.primaryText, shape = HiltechShape.medium) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(period.periodCode, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                HiltechStateTag(period.state, HiltechStateTone.NEUTRAL)
                            }
                            Text(period.startDate + " — " + period.endDate, color = colors.secondaryText)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                HiltechKeyValue(hiltechText("الموظفون", "Employees"), period.employeeCount.toString(), Modifier.weight(1f))
                                HiltechKeyValue(hiltechText("استثناءات المدخلات", "Input exceptions"), period.exceptionCount.toString(), Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            if (state.canPrepare && (!openDetail || state.selected == null)) TextButton(onClick = { creating = !creating }) {
                Text(if (creating) hiltechText("إغلاق", "Close") else hiltechText("+ فترة جديدة", "+ New period"))
            }
            if (state.canPrepare && creating && (!openDetail || state.selected == null)) {
                Text(hiltechText("فترة جديدة", "New period"), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(value = periodCode, onValueChange = { periodCode = it }, label = { Text(hiltechText("رمز الفترة", "Period code")) })
                OutlinedTextField(value = startDate, onValueChange = { startDate = it }, label = { Text(hiltechText("من YYYY-MM-DD", "From YYYY-MM-DD")) })
                OutlinedTextField(value = endDate, onValueChange = { endDate = it }, label = { Text(hiltechText("إلى YYYY-MM-DD", "To YYYY-MM-DD")) })
                OutlinedTextField(value = cutoffDate, onValueChange = { cutoffDate = it }, label = { Text(hiltechText("آخر يوم لتجميع المدخلات", "Input cutoff date")) })
                OutlinedTextField(value = targetPayment, onValueChange = { targetPayment = it }, label = { Text(hiltechText("تاريخ الصرف المستهدف — اختياري، ليس أمر صرف", "Target payment date (optional, not a payment order)")) })
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
                ) { Text(hiltechText("إنشاء الفترة", "Create period")) }
            }

            state.selected?.takeIf { openDetail }?.let { detail ->
                TextButton(onClick = { openDetail = false }) { Text(hiltechText("العودة إلى الفترات", "Back to periods")) }
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(detail.period.periodCode, style = MaterialTheme.typography.headlineSmall)
                    Text(detail.period.startDate + " — " + detail.period.endDate, color = colors.secondaryText)
                    HiltechStateTag(detail.period.state, HiltechStateTone.NEUTRAL)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !exceptionsOnly, onClick = { exceptionsOnly = false }, label = { Text(hiltechText("كل الموظفين", "All employees")) })
                    FilterChip(selected = exceptionsOnly, onClick = { exceptionsOnly = true }, label = { Text(hiltechText("الاستثناءات", "Exceptions")) })
                }
                if (detail.period.state == "OPEN" && state.canPrepare) {
                    Button(onClick = { onCaptureInputs(detail.period.periodId) }) {
                        Text(hiltechText("تثبيت لقطة البيانات الحالية", "Capture current input snapshot"))
                    }
                }
                Text(hiltechText("المدخلات الناقصة لا تعني صفرًا. لا يمكن حساب أو اعتماد الرواتب من هنا.", "Missing inputs are not zero. Payroll calculation and approval are not available here."))
                if (detail.employees.isEmpty()) {
                    Text(hiltechText("لا توجد لقطة موظفين لهذه الفترة.", "No employee snapshot for this period."))
                }
                detail.employees.filter { !exceptionsOnly || it.sourceChanged || it.exceptions.isNotEmpty() }.forEach { employee ->
                    Text(
                        employee.displayName + " · " + employee.employeeCode,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("حالة التوظيف: " + employee.employeeState)
                    if (employee.sourceChanged) {
                        Text(hiltechText("تغيّرت بيانات المصدر بعد أخذ اللقطة — يجب مراجعة التغيير", "Source data changed after the snapshot; review the change."))
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
                                    label = { Text(hiltechText("ملاحظة مراجعة، لا تُزيل الاستثناء", "Review note (does not clear the exception)")) },
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
                                ) { Text(hiltechText("تسجيل مراجعة", "Record review")) }
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
    CompositionLocalProvider(LocalLayoutDirection provides LocalHiltechLanguage.current.direction) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(hiltechText("كشف المرتب", "Payslip"), style = MaterialTheme.typography.titleMedium)
            Text(hiltechText("لن يتاح كشف راتب شخصي قبل اعتماد سياسات المرتبات ونتيجة الصرف الفعلية.", "Personal payslips require approved payroll policies and actual payment results."))
        }
    }
}
