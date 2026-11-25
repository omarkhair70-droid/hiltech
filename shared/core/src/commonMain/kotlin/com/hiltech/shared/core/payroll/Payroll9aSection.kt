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
    val commandLocked: Boolean = false,
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
    onBackToPeriods: () -> Unit = {},
    retainedCreateRequest: CreatePayrollPeriodRequestDto? = null,
) {
    val colors = LocalHiltechOperationalColors.current
    val blocked = state.loading || state.commandLocked
    var creating by remember { mutableStateOf(retainedCreateRequest != null) }
    var query by remember { mutableStateOf("") }
    var openDetail by remember(state.selected?.period?.periodId) { mutableStateOf(state.selected != null) }
    var exceptionsOnly by remember(state.selected?.period?.periodId) { mutableStateOf(false) }
    var periodCode by remember { mutableStateOf(retainedCreateRequest?.periodCode.orEmpty()) }
    var startDate by remember { mutableStateOf(retainedCreateRequest?.startDate.orEmpty()) }
    var endDate by remember { mutableStateOf(retainedCreateRequest?.endDate.orEmpty()) }
    var cutoffDate by remember { mutableStateOf(retainedCreateRequest?.cutoffDate.orEmpty()) }
    var targetPayment by remember { mutableStateOf(retainedCreateRequest?.targetPaymentDate.orEmpty()) }
    val validDates = payrollPeriodDatesValid(startDate, endDate, cutoffDate, targetPayment.takeIf(String::isNotBlank))

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
                TextButton(onClick = onRefresh, enabled = !blocked) { Text(hiltechText("تحديث", "Refresh")) }
            }
            if (state.loading) CircularProgressIndicator()
            state.errorCode?.let { Text(hiltechText("تعذر تنفيذ العملية: ", "Unable to complete operation: ") + it) }

            if (!openDetail || state.selected == null) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(hiltechText("ابحث برمز الفترة أو التاريخ", "Find a period or date")) })
                val matching = state.periods?.items.orEmpty().filter { period ->
                    query.isBlank() || listOf(period.periodCode, period.startDate, period.endDate).any { it.contains(query.trim(), true) }
                }
                if (!blocked && state.periods != null && matching.isEmpty()) Text(hiltechText("لا توجد فترات مطابقة.", "No matching periods."))
                matching.forEach { period ->
                    Surface(onClick = { openDetail = true; onSelect(period.periodId) },
                        enabled = !blocked, modifier = Modifier.fillMaxWidth(),
                        color = colors.surface, contentColor = colors.primaryText, shape = HiltechShape.medium) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(period.periodCode, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                HiltechStateTag(payrollLifecycleLabel(period.state), HiltechStateTone.NEUTRAL)
                            }
                            Text(payrollDisplayDate(period.startDate) + " — " + payrollDisplayDate(period.endDate), color = colors.secondaryText)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                HiltechKeyValue(hiltechText("الموظفون", "Employees"), period.employeeCount.toString(), Modifier.weight(1f))
                                HiltechKeyValue(hiltechText("ملاحظات اللقطة", "Snapshot notes"), period.exceptionCount.toString(), Modifier.weight(1f))
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
                HiltechDateField(hiltechText("بداية الفترة", "Period start"), startDate, { startDate = it }, optional = false, enabled = !blocked)
                HiltechDateField(hiltechText("نهاية الفترة", "Period end"), endDate, { endDate = it; if (cutoffDate.isBlank()) cutoffDate = it }, optional = false, enabled = !blocked)
                HiltechDateField(hiltechText("آخر يوم للمدخلات", "Input cutoff"), cutoffDate, { cutoffDate = it }, optional = false, enabled = !blocked)
                HiltechDateField(hiltechText("موعد الصرف المستهدف", "Target payment date"), targetPayment, { targetPayment = it }, enabled = !blocked)
                OutlinedTextField(value = periodCode, onValueChange = { periodCode = it.uppercase() }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(hiltechText("رمز الفترة، اختياري", "Period code, optional")) }, enabled = !blocked,
                    supportingText = { Text(hiltechText("يُنشأ تلقائيًا من تاريخي الفترة إن تركته فارغًا.", "Generated from the period dates when left blank.")) })
                if (startDate.isNotBlank() && endDate.isNotBlank() && cutoffDate.isNotBlank() && !validDates) Text(
                    hiltechText("راجع التواريخ: النهاية بعد البداية، وآخر يوم للمدخلات داخل الفترة، والصرف لا يسبق البداية.",
                        "Check the dates: end follows start, cutoff falls within the period, and payment is on or after the start."), color = MaterialTheme.colorScheme.error)
                Button(
                    enabled = !blocked && validDates && (periodCode.isBlank() || Regex("^[A-Z][A-Z0-9_-]{0,63}$").matches(periodCode)),
                    onClick = {
                        onCreatePeriod(
                            periodCode.ifBlank { "PAY_${startDate.replace("-", "")}_${endDate.replace("-", "")}" },
                            startDate,
                            endDate,
                            cutoffDate,
                            targetPayment.ifBlank { null },
                        )
                    },
                ) { Text(hiltechText("إنشاء الفترة", "Create period")) }
            }

            state.selected?.takeIf { openDetail }?.let { detail ->
                TextButton(onClick = { openDetail = false; onBackToPeriods() }, enabled = !blocked) { Text(hiltechText("العودة إلى الفترات", "Back to periods")) }
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Text(detail.period.periodCode, style = MaterialTheme.typography.headlineSmall)
                    Text(payrollDisplayDate(detail.period.startDate) + " — " + payrollDisplayDate(detail.period.endDate), color = colors.secondaryText)
                    HiltechStateTag(payrollLifecycleLabel(detail.period.state), HiltechStateTone.NEUTRAL)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !exceptionsOnly, onClick = { exceptionsOnly = false }, label = { Text(hiltechText("كل الموظفين", "All employees")) })
                    FilterChip(selected = exceptionsOnly, onClick = { exceptionsOnly = true }, label = { Text(hiltechText("الاستثناءات", "Exceptions")) })
                }
                if (detail.period.state == "OPEN" && state.canPrepare) {
                    Button(onClick = { onCaptureInputs(detail.period.periodId) }, enabled = !blocked) {
                        Text(hiltechText("تثبيت لقطة البيانات الحالية", "Capture current input snapshot"))
                    }
                }
                Text(hiltechText("تثبت اللقطة هوية الموظفين وتوظيفهم. سجّل مصادر القيم واعتمدها من قسم مصادر المرتبات قبل تجهيز الحساب.", "The snapshot fixes employee and employment facts. Record and approve monetary sources in Payroll sources before calculation."))
                if (detail.employees.isEmpty()) {
                    Text(hiltechText("لا توجد لقطة موظفين لهذه الفترة.", "No employee snapshot for this period."))
                }
                detail.employees.filter { !exceptionsOnly || it.sourceChanged || it.exceptions.any { exception -> exception.blocksCalculation } }.forEach { employee ->
                    Text(
                        employee.displayName + " · " + employee.employeeCode,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(hiltechText("حالة التوظيف: ", "Employment status: ") + payrollLifecycleLabel(employee.employeeState))
                    if (employee.sourceChanged) {
                        Text(hiltechText("تغيّرت بيانات المصدر بعد أخذ اللقطة — يجب مراجعة التغيير", "Source data changed after the snapshot; review the change."))
                    }
                    employee.exceptions.forEach { exception ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (exception.blocksCalculation) payrollMessage(exception.code) else
                                hiltechText("تم تسجيل المصدر واعتماده.", "The source has been recorded and approved."))
                            Text(hiltechText("المصدر: ", "Source: ") + exception.sourceDomain)
                            if (exception.reviews.isNotEmpty()) {
                                Text(hiltechText("مراجعات موثقة: ", "Recorded reviews: ") + exception.reviews.size)
                            }
                            if (state.canReview) {
                                var reviewNote by remember(exception.exceptionId, exception.reviews.size) { mutableStateOf("") }
                                OutlinedTextField(
                                    value = reviewNote,
                                    onValueChange = { reviewNote = it },
                                    label = { Text(hiltechText("ملاحظة مراجعة، لا تُزيل الاستثناء", "Review note (does not clear the exception)")) },
                                    enabled = !blocked,
                                )
                                OutlinedButton(
                                    enabled = reviewNote.isNotBlank() && !blocked,
                                    onClick = {
                                        onReviewException(
                                            detail.period.periodId,
                                            exception.exceptionId,
                                            reviewNote,
                                        )
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
            Text(hiltechText("يظهر كشفك بعد اعتماد نسخة المرتبات. تظهر حالة الصرف بشكل مستقل عند تسجيل نتيجتها من المالية.", "Your payslip becomes available after the payroll version is released. Payment status is shown separately when Finance records its outcome."))
        }
    }
}
