package com.hiltech.shared.core.payroll

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechKeyValue
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechRecordChoice
import com.hiltech.shared.core.design.HiltechRecordPicker
import com.hiltech.shared.core.design.HiltechRecordTable
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.HiltechTableColumn
import com.hiltech.shared.core.design.HiltechTableRecord
import com.hiltech.shared.core.design.hiltechText

internal fun payrollAmountText(value: String): String = value.trim().map { c ->
    when (c) {
        in '٠'..'٩' -> '0' + (c - '٠')
        in '۰'..'۹' -> '0' + (c - '۰')
        '٫' -> '.'
        else -> c
    }
}.joinToString("")

internal fun validPayrollAmount(value: String): Boolean =
    Regex("^(0|[1-9][0-9]{0,13})(\\.[0-9]{1,6})?$").matches(value) &&
        value.count { it in '0'..'9' } <= 18 && value.any { it in '1'..'9' }

internal fun validPayrollReference(value: String?): Boolean = value != null && value.trim() == value &&
    value.length in 3..240 && value.none { it == '|' || it == '\n' || it == '\r' || it.code < 32 }

internal fun validPayrollSource(request: PayrollSourceRequestDto): Boolean {
    if (request.domain !in setOf("POLICY", "COMPENSATION", "APPROVED_TIME", "FINANCE") ||
        !validPayrollReference(request.sourceReference) || !Regex("^[A-Z]{3}$").matches(request.currency)) return false
    if (request.domain == "POLICY") return request.employeeId == null && !request.noInputs && request.components.isEmpty() &&
        request.fractionalDigits != null && request.fractionalDigits in 0..6 &&
        request.roundingMode in setOf("HALF_UP", "HALF_EVEN", "HALF_DOWN", "UP", "DOWN", "CEILING", "FLOOR", "UNNECESSARY") &&
        validPayrollReference(request.companyAuthorizationReference) && validPayrollReference(request.statutoryPolicyReference) &&
        (request.deductionPolicyReference == null || validPayrollReference(request.deductionPolicyReference))
    if (request.employeeId == null || request.fractionalDigits != null || request.roundingMode != null ||
        request.companyAuthorizationReference != null || request.statutoryPolicyReference != null || request.deductionPolicyReference != null) return false
    if (request.noInputs) return request.domain != "COMPENSATION" && request.components.isEmpty()
    val kinds = if (request.domain == "FINANCE") setOf("DEDUCTION", "REIMBURSEMENT") else setOf("EARNING")
    return request.components.isNotEmpty() && request.components.size <= 100 &&
        request.components.map { it.code }.distinct().size == request.components.size && request.components.all {
        validPayrollReference(it.description) && validPayrollAmount(it.amount) &&
            it.kind in kinds && Regex("^[A-Z][A-Z0-9_-]{0,79}$").matches(it.code) &&
            (if (it.kind == "DEDUCTION") validPayrollReference(it.deductionPlanReference) else it.deductionPlanReference == null)
    }
}

@Composable
internal fun payrollSourceDomainLabel(domain: String): String = when (domain) {
    "POLICY" -> hiltechText("سياسة الحساب", "Calculation policy")
    "COMPENSATION" -> hiltechText("الأجر والاستحقاقات الثابتة", "Compensation")
    "APPROVED_TIME" -> hiltechText("البدلات والمتغيرات المعتمدة", "Approved allowances and variable pay")
    "FINANCE" -> hiltechText("الخصومات والمستردات", "Deductions and reimbursements")
    else -> domain
}

@Composable
internal fun payrollSourceStateLabel(state: String): String = when (state) {
    "APPROVED" -> hiltechText("معتمد", "Approved")
    "PENDING" -> hiltechText("بانتظار المراجع", "Awaiting reviewer")
    "REJECTED" -> hiltechText("مرفوض", "Rejected")
    "CHANGE_REQUESTED" -> hiltechText("تعديل مطلوب", "Changes requested")
    "POLICY_REVIEW_REQUIRED" -> hiltechText("سياسة المراجعة تغيّرت", "Review policy changed")
    else -> hiltechText("مسودة", "Draft")
}

private val employeePayrollDomains = listOf("COMPENSATION", "APPROVED_TIME", "FINANCE")

/** Start with missing facts, then work needing preparation. Pending reviews are not new inputs. */
internal fun nextEmployeePayrollSource(records: List<PayrollSourceRecordDto>, employeeId: String): String? {
    val byDomain = records.filter { it.employeeId == employeeId }.groupBy { it.domain }
    return employeePayrollDomains.firstOrNull { byDomain[it].isNullOrEmpty() }
        ?: employeePayrollDomains.firstOrNull { domain ->
            byDomain[domain]?.singleOrNull()?.approvalState in setOf("DRAFT", "REJECTED", "CHANGE_REQUESTED", "POLICY_REVIEW_REQUIRED")
        }
}

/** One source editor and review view shared by Windows and Android. */
@Composable
fun PayrollSourcesSection(
    sources: PayrollSourcesDto,
    employees: List<PayrollEmployeeInputDto>,
    identityId: String,
    canPrepare: Boolean,
    canReview: Boolean,
    locked: Boolean,
    completedSourceSave: Int,
    onSave: (PayrollSourceRequestDto) -> Unit,
    onRequestApproval: (String) -> Unit,
    onDecide: (String, String, String?) -> Unit,
    retainedSourceRequest: PayrollSourceRequestDto? = null,
    retainedRecordId: String? = null,
    retainedDecisionComment: String? = null,
) {
    val retainedRecord = sources.records.singleOrNull { it.recordId == retainedRecordId }
    var domain by remember(sources.periodId) { mutableStateOf(retainedSourceRequest?.domain ?: retainedRecord?.domain ?: "POLICY") }
    var employeeId by remember(sources.periodId) { mutableStateOf(retainedSourceRequest?.employeeId ?: retainedRecord?.employeeId.orEmpty()) }
    var editing by remember(sources.periodId) { mutableStateOf(retainedSourceRequest) }
    var showCoverage by remember(sources.periodId) { mutableStateOf(false) }
    LaunchedEffect(completedSourceSave) { if (completedSourceSave > 0) editing = null }
    val selected = sources.records.singleOrNull { it.domain == domain && it.employeeId == employeeId.takeIf { domain != "POLICY" } }
    val readyCurrency = sources.records.singleOrNull { it.domain == "POLICY" }?.currency.orEmpty()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HiltechSectionHeading("INPUTS", hiltechText("مصادر المرتبات", "Payroll sources"),
            hiltechText("ابدأ بالموظف واستحقاقاته الفعلية لهذه الفترة. لا يُطلب سجل حضور لتسجيل الأجر أو البدلات؛ استخدم مصدر الاستحقاق المعتمد.",
                "Start with the employee and actual entitlements for this period. A time clock is not required to record pay or allowances; use the authorized entitlement source."))
        if (!sources.reviewPolicyReady) Text(hiltechText(
            "يمكن تجهيز المصادر الآن. يلزم تعيين مراجع مستقل لسياسة مصادر المرتبات من إدارة الشركة قبل الاعتماد.",
            "Sources can be prepared now. Assign an independent payroll source reviewer in company administration before approval."))
        if (employees.isNotEmpty()) {
            val complete = employees.count { employee -> employeePayrollDomains.all { owner ->
                sources.records.singleOrNull { it.domain == owner && it.employeeId == employee.employeeId }?.approvalState == "APPROVED"
            } }
            Text(hiltechText("موظفون اكتملت مراجعة مصادرهم: ", "Employees with reviewed sources: ") + complete + " / " + employees.size)
            HiltechRecordPicker(hiltechText("ملف استحقاقات موظف", "Employee pay sources"), employeeId,
                employees.map { employee -> HiltechRecordChoice(employee.employeeId, employee.displayName, employee.employeeCode) },
                onChange = { id ->
                    employeeId = id
                    domain = nextEmployeePayrollSource(sources.records, id) ?: "COMPENSATION"
                    editing = null
                }, allowKnownReference = false, allowClear = false, enabled = !locked && editing == null)
            if (employeeId.isNotBlank() && domain != "POLICY") {
                val next = nextEmployeePayrollSource(sources.records, employeeId)
                if (next != null && next != domain) TextButton(enabled = !locked && editing == null, onClick = { domain = next }) {
                    Text(hiltechText("انتقل للمصدر التالي: ", "Continue to next source: ") + payrollSourceDomainLabel(next))
                }
            }
            TextButton(onClick = { showCoverage = !showCoverage }) { Text(if (showCoverage)
                hiltechText("إخفاء جاهزية الموظفين", "Hide employee readiness") else hiltechText("عرض جاهزية جميع الموظفين", "View every employee's readiness")) }
            if (showCoverage) HiltechRecordTable(
                listOf(HiltechTableColumn(hiltechText("الموظف", "Employee")), HiltechTableColumn(hiltechText("الأجر", "Compensation")),
                    HiltechTableColumn(hiltechText("المتغيرات", "Variable pay")), HiltechTableColumn(hiltechText("المالية", "Finance"))),
                employees.map { employee -> HiltechTableRecord(listOf(employee.displayName) +
                    listOf("COMPENSATION", "APPROVED_TIME", "FINANCE").map { owner ->
                        sources.records.singleOrNull { it.domain == owner && it.employeeId == employee.employeeId }
                            ?.let { payrollSourceStateLabel(it.approvalState) } ?: hiltechText("غير مسجل", "Not recorded")
                    }) }, "")
        }
        if (canReview && editing == null) {
            val pending = sources.records.filter { it.approvalState == "PENDING" && it.preparedByIdentityId != identityId }
            if (pending.isNotEmpty()) HiltechRecordPicker(hiltechText("مصادر تنتظر الاعتماد", "Sources awaiting approval"),
                selected?.recordId?.takeIf { id -> pending.any { it.recordId == id } }.orEmpty(),
                pending.map { record -> HiltechRecordChoice(record.recordId, payrollSourceDomainLabel(record.domain),
                    employees.firstOrNull { it.employeeId == record.employeeId }?.displayName ?: record.sourceReference) },
                onChange = { id -> pending.singleOrNull { it.recordId == id }?.let { domain = it.domain; employeeId = it.employeeId.orEmpty() } },
                allowKnownReference = false, allowClear = false, enabled = !locked)
        }
        HiltechRecordPicker(hiltechText("المصدر", "Source"), domain,
            listOf("POLICY", "COMPENSATION", "APPROVED_TIME", "FINANCE").map { HiltechRecordChoice(it, payrollSourceDomainLabel(it)) },
            onChange = { domain = it; editing = null }, allowKnownReference = false, allowClear = false, enabled = !locked && editing == null)
        if (domain == "APPROVED_TIME") Text(hiltechText(
            "سجّل البدلات والاستحقاقات المتغيرة المقررة فعلًا، والعمل الإضافي إن وجد. عدم وجود بصمة حضور لا يعني عدم وجود أجر مستحق.",
            "Record actual authorized allowances and variable entitlements, including overtime when applicable. No clock-in record does not mean no earned pay."),
            style = MaterialTheme.typography.bodySmall)
        if (domain == "POLICY" || employeeId.isNotBlank()) {
            selected?.let { record ->
                HiltechPanel(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(hiltechText("النسخة ", "Revision ") + record.revision, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        HiltechStateTag(payrollSourceStateLabel(record.approvalState),
                            if (record.approvalState == "APPROVED") HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL)
                    }
                    HiltechKeyValue(hiltechText("المستند أو سجل المصدر", "Source document or register"), record.sourceReference)
                    if (record.domain == "POLICY") {
                        HiltechKeyValue(hiltechText("العملة والدقة", "Currency and precision"), record.currency + " · " + record.fractionalDigits)
                        HiltechKeyValue(hiltechText("التقريب", "Rounding"), roundingLabel(record.roundingMode.orEmpty()))
                        HiltechKeyValue(hiltechText("اعتماد الشركة", "Company authorization"), record.companyAuthorizationReference.orEmpty())
                        HiltechKeyValue(hiltechText("المراجعة النظامية", "Statutory review"), record.statutoryPolicyReference.orEmpty())
                        record.deductionPolicyReference?.let { HiltechKeyValue(hiltechText("ضوابط الخصم", "Deduction policy"), it) }
                    } else if (record.noInputs) Text(hiltechText(
                        "إقرار صريح: تمت مراجعة هذا المصدر ولا توجد قيم لهذه الفترة.",
                        "Explicit attestation: this source was checked and has no inputs for this period."))
                    else HiltechRecordTable(
                        listOf(HiltechTableColumn(hiltechText("البند", "Component")), HiltechTableColumn(hiltechText("القيمة", "Amount"), numeric = true)),
                        record.components.map { HiltechTableRecord(listOf(it.description, it.amount + " " + record.currency)) }, "")
                    record.components.mapNotNull { it.deductionPlanReference }.distinct().forEach {
                        HiltechKeyValue(hiltechText("خطة الخصم المعتمدة", "Approved deduction plan"), it)
                    }
                    if (canPrepare && record.preparedByIdentityId == identityId && record.approvalState == "DRAFT") {
                        OutlinedButton(onClick = { onRequestApproval(record.recordId) }, enabled = !locked && sources.reviewPolicyReady) {
                            Text(hiltechText("إرسال للمراجعة", "Submit for review"))
                        }
                    }
                    if (canReview && record.preparedByIdentityId != identityId && record.approvalState == "PENDING") {
                        var decisionComment by remember(record.recordId) { mutableStateOf(retainedDecisionComment.orEmpty()) }
                        Text(hiltechText("راجع القيم والمراجع أعلاه قبل اتخاذ القرار.", "Review the values and references above before deciding."))
                        OutlinedTextField(decisionComment, { decisionComment = it.take(1000) }, Modifier.fillMaxWidth(),
                            enabled = !locked, label = { Text(hiltechText("ملاحظة المراجعة", "Review note")) },
                            supportingText = { Text(hiltechText("السبب مطلوب لطلب تعديل أو رفض المصدر.", "A reason is required to request changes or reject this source.")) })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onDecide(record.recordId, "APPROVE", decisionComment.trim().takeIf(String::isNotBlank)) }, enabled = !locked) { Text(hiltechText("اعتماد المصدر", "Approve source")) }
                            OutlinedButton(onClick = { onDecide(record.recordId, "REQUEST_CHANGE", decisionComment.trim()) }, enabled = !locked && decisionComment.isNotBlank()) { Text(hiltechText("طلب تعديل", "Request changes")) }
                        }
                        TextButton(onClick = { onDecide(record.recordId, "REJECT", decisionComment.trim()) }, enabled = !locked && decisionComment.isNotBlank()) {
                            Text(hiltechText("رفض المصدر", "Reject source"))
                        }
                    }
                }
            } ?: Text(hiltechText("لم يُسجل هذا المصدر بعد. غياب السجل لا يعني أن القيمة صفر.", "This source has not been recorded. A missing record does not mean zero."))
            if (canPrepare && editing == null) OutlinedButton(enabled = !locked, onClick = {
                editing = selected?.let { r -> PayrollSourceRequestDto("", r.domain, r.employeeId, r.recordId,
                    r.sourceReference, r.currency, r.noInputs, r.components, r.fractionalDigits, r.roundingMode,
                    r.companyAuthorizationReference, r.statutoryPolicyReference, r.deductionPolicyReference) }
                    ?: PayrollSourceRequestDto("", domain, employeeId.takeIf { domain != "POLICY" }, sourceReference = "", currency = readyCurrency,
                        components = if (domain == "POLICY") emptyList() else listOf(newSourceComponent(domain, 1)))
            }) { Text(if (selected == null) hiltechText("تسجيل المصدر", "Record source") else hiltechText("إعداد نسخة مصححة", "Prepare corrected revision")) }
        }
        editing?.let { draft ->
            HiltechPanel(Modifier.fillMaxWidth()) {
                Text(if (draft.replacesRecordId == null) hiltechText("تسجيل مصدر جديد", "New source record") else hiltechText("نسخة مصححة — ستحتاج مراجعة جديدة", "Corrected revision — new review required"), style = MaterialTheme.typography.titleMedium)
                SourceText(hiltechText("مرجع المستند أو السجل", "Document or register reference"), draft.sourceReference, !locked) { editing = draft.copy(sourceReference = it) }
                if (draft.domain == "POLICY") {
                    SourceText(hiltechText("رمز العملة", "Currency code"), draft.currency, !locked) { editing = draft.copy(currency = it.uppercase().take(3)) }
                    HiltechRecordPicker(hiltechText("المنازل العشرية", "Decimal places"), draft.fractionalDigits?.toString().orEmpty(),
                        (0..6).map { HiltechRecordChoice(it.toString(), it.toString()) },
                        { editing = draft.copy(fractionalDigits = it.toIntOrNull()) }, allowKnownReference = false, allowClear = false, enabled = !locked)
                    HiltechRecordPicker(hiltechText("طريقة التقريب المعتمدة", "Approved rounding method"), draft.roundingMode.orEmpty(),
                        listOf("HALF_UP", "HALF_EVEN", "HALF_DOWN", "UP", "DOWN", "CEILING", "FLOOR", "UNNECESSARY").map { HiltechRecordChoice(it, roundingLabel(it)) },
                        { editing = draft.copy(roundingMode = it) }, allowKnownReference = false, allowClear = false, enabled = !locked)
                    SourceText(hiltechText("مرجع تفويض الشركة", "Company authorization reference"), draft.companyAuthorizationReference.orEmpty(), !locked) { editing = draft.copy(companyAuthorizationReference = it) }
                    SourceText(hiltechText("مرجع المراجعة النظامية للمحاسب", "Accountant statutory review reference"), draft.statutoryPolicyReference.orEmpty(), !locked) { editing = draft.copy(statutoryPolicyReference = it) }
                    SourceText(hiltechText("مرجع ضوابط الخصم، عند وجود خصومات", "Deduction policy reference, when deductions apply"), draft.deductionPolicyReference.orEmpty(), !locked) { editing = draft.copy(deductionPolicyReference = it.takeIf(String::isNotBlank)) }
                    Text(hiltechText("المرجع يثبت مراجعة السياسة؛ لا يحسب النظام ضرائب أو نسبًا قانونية تلقائيًا.", "The reference records policy review; the system does not invent tax or statutory rates."))
                } else {
                    SourceText(hiltechText("رمز العملة", "Currency code"), draft.currency, !locked) { editing = draft.copy(currency = it.uppercase().take(3)) }
                    if (draft.domain != "COMPENSATION") Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft.noInputs, onCheckedChange = { none -> editing = draft.copy(noInputs = none,
                            components = if (none) emptyList() else listOf(newSourceComponent(draft.domain, 1))) }, enabled = !locked)
                        Text(if (draft.domain == "APPROVED_TIME")
                            hiltechText("راجعت الاستحقاقات المتغيرة ولا توجد مبالغ إضافية لهذه الفترة", "Variable entitlements were reviewed; no additional amounts for this period")
                        else hiltechText("تمت مراجعة المصدر ولا توجد قيم لهذه الفترة", "This source was checked and has no inputs for this period"), Modifier.weight(1f))
                    }
                    draft.components.forEachIndexed { index, component ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            fun update(value: PayrollSourceComponentDto) { editing = draft.copy(components = draft.components.mapIndexed { i, old -> if (i == index) value else old }) }
                            SourceText(hiltechText("وصف البند", "Component description"), component.description, !locked) { update(component.copy(description = it)) }
                            if (draft.domain == "FINANCE") HiltechRecordPicker(hiltechText("نوع البند", "Component type"), component.kind,
                                listOf(HiltechRecordChoice("DEDUCTION", hiltechText("خصم", "Deduction")), HiltechRecordChoice("REIMBURSEMENT", hiltechText("استرداد مصروف", "Reimbursement"))),
                                { update(component.copy(kind = it, deductionPlanReference = null)) }, allowKnownReference = false, allowClear = false, enabled = !locked)
                            OutlinedTextField(component.amount, { update(component.copy(amount = payrollAmountText(it))) }, Modifier.fillMaxWidth(),
                                label = { Text(hiltechText("المبلغ المعتمد", "Approved amount")) }, singleLine = true, enabled = !locked,
                                isError = component.amount.isNotBlank() && !validPayrollAmount(component.amount),
                                supportingText = { Text(hiltechText("قيمة موجبة، حتى 6 منازل عشرية، دون فواصل آلاف.", "Positive amount, up to 6 decimal places, without thousands separators.")) })
                            if (component.kind == "DEDUCTION") SourceText(hiltechText("مرجع خطة الخصم", "Deduction plan reference"), component.deductionPlanReference.orEmpty(), !locked) { update(component.copy(deductionPlanReference = it)) }
                            if (draft.components.size > 1) TextButton(enabled = !locked, onClick = { editing = draft.copy(components = draft.components.filterIndexed { i, _ -> i != index }) }) {
                                Text(hiltechText("إزالة البند", "Remove component"))
                            }
                        }
                    }
                    if (!draft.noInputs && draft.components.size < 100) TextButton(enabled = !locked, onClick = {
                        val next = (draft.components.mapNotNull { it.code.substringAfterLast('_').toIntOrNull() }.maxOrNull() ?: 0) + 1
                        editing = draft.copy(components = draft.components + newSourceComponent(draft.domain, next))
                    }) { Text(hiltechText("+ إضافة بند", "+ Add component")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSave(draft) }, enabled = !locked && validPayrollSource(draft)) { Text(hiltechText("حفظ المصدر للمراجعة", "Save source for review")) }
                    TextButton(onClick = { editing = null }, enabled = !locked) { Text(hiltechText("إلغاء", "Cancel")) }
                }
            }
        }
    }
}

private fun newSourceComponent(domain: String, index: Int) = PayrollSourceComponentDto("INPUT_$index",
    if (domain == "FINANCE") "DEDUCTION" else "EARNING", "")

@Composable
private fun SourceText(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = enabled)
}

@Composable
private fun roundingLabel(code: String): String = when (code) {
    "HALF_UP" -> hiltechText("لأقرب قيمة؛ النصف لأعلى", "Nearest; ties away from zero")
    "HALF_EVEN" -> hiltechText("لأقرب قيمة؛ النصف للزوجي", "Nearest; ties to even")
    "HALF_DOWN" -> hiltechText("لأقرب قيمة؛ النصف لأسفل", "Nearest; ties toward zero")
    "UP" -> hiltechText("بعيدًا عن الصفر", "Away from zero")
    "DOWN" -> hiltechText("نحو الصفر", "Toward zero")
    "CEILING" -> hiltechText("نحو القيمة الأعلى", "Toward positive infinity")
    "FLOOR" -> hiltechText("نحو القيمة الأدنى", "Toward negative infinity")
    "UNNECESSARY" -> hiltechText("دون تقريب؛ رفض الدقة الزائدة", "Exact only; reject excess precision")
    else -> code
}
