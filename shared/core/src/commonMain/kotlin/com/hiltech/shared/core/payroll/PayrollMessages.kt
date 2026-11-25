package com.hiltech.shared.core.payroll

import androidx.compose.runtime.Composable
import com.hiltech.shared.core.design.hiltechDateToUtcMillis
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.design.LocalHiltechLanguage
import com.hiltech.shared.core.design.HiltechLanguage
import com.hiltech.shared.core.design.hiltechLocalizedDate

internal fun payrollPeriodDatesValid(start: String, end: String, cutoff: String, payment: String?): Boolean {
    val first = hiltechDateToUtcMillis(start) ?: return false
    val last = hiltechDateToUtcMillis(end) ?: return false
    val closed = hiltechDateToUtcMillis(cutoff) ?: return false
    val paid = payment?.let { hiltechDateToUtcMillis(it) ?: return false }
    return last >= first && closed in first..last && (paid == null || paid >= first)
}

@Composable
internal fun payrollMessage(code: String): String = when (code) {
    "PAYROLL_PERIODS_PAUSED" -> hiltechText("إدارة فترات المرتبات متوقفة مؤقتًا على الخادم. مسؤوليتك محفوظة؛ أعد التحديث بعد استئناف الخدمة.", "Payroll periods are temporarily paused on the server. Your responsibility is retained; refresh when service resumes.")
    "PAYROLL_RUNS_PAUSED" -> hiltechText("حساب المرتبات متوقف مؤقتًا على الخادم.", "Payroll calculation is temporarily paused on the server.")
    "PAYROLL_RUN_REVIEW_PAUSED" -> hiltechText("إرسال نسخ المرتبات للمراجعة أو تثبيت اعتمادها متوقف مؤقتًا على الخادم.", "Payroll review requests or release are temporarily paused on the server.")
    "PAYROLL_FINANCE_STATUS_PAUSED" -> hiltechText("قراءة نتائج المالية متوقفة مؤقتًا. لا يمكن تأكيد الصرف من هذه الحالة.", "Finance result access is temporarily paused. Payment cannot be confirmed from this state.")
    "PAYROLL_FINANCE_HANDOFF_PAUSED" -> hiltechText("خدمة تسليم المرتبات للمراجعة المالية غير متاحة حاليًا.", "The payroll handoff to Finance review is currently unavailable.")
    "PAYROLL_RUN_REVIEW_NOT_CONFIGURED" -> hiltechText("عيّن مراجع نسخة المرتبات من إعداد مراجعات الشركة قبل الإرسال والاعتماد.", "Assign a payroll run reviewer in company review setup before submitting or releasing a run.")
    "PAYROLL_INPUT_SNAPSHOT_REQUIRED" -> hiltechText("ثبّت لقطة الموظفين لهذه الفترة أولًا.", "Capture this period's employee snapshot first.")
    "PAYROLL_POLICY_NOT_VERIFIED", "COMPENSATION_POLICY_NOT_CONNECTED" -> hiltechText("يلزم تسجيل سياسة الحساب والأجر واعتماد مصادرهما.", "Record and approve the calculation policy and compensation sources.")
    "PAYROLL_COMPENSATION_COVERAGE_REQUIRED" -> hiltechText("الأجر لم يُعتمد لكل الموظفين بعد.", "Compensation is not yet approved for every employee.")
    "PAYROLL_APPROVED_TIME_COVERAGE_REQUIRED", "APPROVED_TIME_SOURCE_NOT_CONNECTED" -> hiltechText("راجع مصدر الوقت والبدلات لكل موظف، بما فيه إقرار عدم وجود قيم عند انطباقه.", "Review each employee's time and allowances, including an explicit no-input attestation where applicable.")
    "PAYROLL_FINANCE_COVERAGE_REQUIRED", "FINANCE_DEDUCTION_SOURCE_NOT_CONNECTED" -> hiltechText("راجع الخصومات والمستردات لكل موظف، بما فيه إقرار عدم وجود قيم عند انطباقه.", "Review each employee's deductions and reimbursements, including an explicit no-input attestation where applicable.")
    "PAYROLL_SOURCE_CHANGED_AFTER_SNAPSHOT", "PAYROLL_SOURCE_REVISION_CHANGED", "PAYROLL_APPROVAL_SOURCE_STALE" -> hiltechText("تغيرت بيانات المصدر. حدّث الصفحة وراجع النسخة الحالية قبل تسجيل نسخة أخرى.", "Source data changed. Refresh and review the current revision before recording another.")
    "PAYROLL_SOURCE_REVIEW_NOT_CONFIGURED", "PAYROLL_INDEPENDENT_SOURCE_REVIEW_REQUIRED" -> hiltechText("يجب إعداد مراجع مستقل لمصادر المرتبات من إدارة الشركة.", "Configure an independent payroll source reviewer in company administration.")
    "PAYROLL_SOURCE_REVIEW_NOT_REQUESTED" -> hiltechText("أرسل المصدر للمراجعة أولًا.", "Submit this source for review first.")
    "APPROVAL_REASON_REQUIRED" -> hiltechText("اكتب سبب طلب التعديل أو الرفض قبل تسجيل القرار.", "Enter a reason for requesting changes or rejecting before recording the decision.")
    "PAYROLL_INDEPENDENT_RUN_REVIEW_REQUIRED" -> hiltechText("يجب أن يراجع الحساب شخص مستقل عن مُعدّ النسخة ومُرسل طلبها.", "The reviewer must be independent of both the run preparer and the person who submitted it.")
    "PAYROLL_REVIEW_REQUEST_CHANGED", "PAYROLL_REVIEW_NOT_AVAILABLE" -> hiltechText("لم يعد القرار متاحًا على هذه النسخة. حدّث الصفحة للتحقق من المصادر والمراجع المعيّن.", "A decision is no longer available for this version. Refresh to check its sources and assigned reviewer.")
    "PAYROLL_SOURCE_CURRENCY_MISMATCH" -> hiltechText("عملات المصادر يجب أن تتطابق مع سياسة الفترة.", "Source currencies must match the period policy.")
    "PAYROLL_DEDUCTION_POLICY_REQUIRED", "PAYROLL_DEDUCTION_PLAN_REQUIRED" -> hiltechText("الخصم يحتاج سياسة معتمدة ومرجع خطة الخصم.", "A deduction requires an approved policy and deduction plan reference.")
    "PAYROLL_FINAL_PAY_SCOPE_REQUIRED", "OFFBOARDING_REQUIRES_REVIEW" -> hiltechText("التسوية النهائية تحتاج مراجعة نطاق المستحقات المرتبط بإخلاء الطرف.", "Final pay needs a reviewed entitlement scope linked to offboarding.")
    "WORKFORCE_ASSIGNMENT_MISSING" -> hiltechText("لم يظهر تكليف وظيفي خلال الفترة؛ راجع سجل الموظف.", "No workforce assignment was captured for this period; review the employee record.")
    "PAYROLL_PERIOD_CODE_CONFLICT" -> hiltechText("يوجد رمز فترة مماثل. افتح الفترة الموجودة أو اختر رمزًا آخر.", "This period code already exists. Open that period or choose another code.")
    "PAYROLL_FORBIDDEN", "APPROVAL_FORBIDDEN", "PAYROLL_FINANCE_REVIEW_FORBIDDEN", "PAYROLL_SOURCE_DENIED_OR_UNAVAILABLE" -> hiltechText("لم يعد الوصول لهذه العملية متاحًا. حدّث مسؤوليات حسابك أو راجع إدارة الشركة.", "This action is no longer available to your account. Refresh your responsibilities or contact company administration.")
    "PAYROLL_APPROVAL_NOT_CONFIGURED" -> hiltechText("راجع إعداد سياسة اعتماد المرتبات ومسؤوليتك عن المراجعة.", "Check the payroll approval policy and your review responsibility.")
    "PAYROLL_INVALID_NET" -> hiltechText("الصافي غير صالح. راجع الاستحقاقات والخصومات في مصادر الفترة.", "The net amount is invalid. Review this period's earnings and deductions.")
    "PAYROLL_UNSUPPORTED_PRECISION", "PAYROLL_SOURCE_COMPONENT_INVALID", "PAYROLL_INVALID_AMOUNT" -> hiltechText("راجع المبلغ والدقة المسموحة في سياسة الحساب.", "Check the amount and the calculation policy's precision.")
    "PAYROLL_COMMAND_UNCERTAIN", "PAYROLL_RESPONSE_SCOPE_MISMATCH" -> hiltechText("لم تصل نتيجة مؤكدة. احتفظنا بنفس العملية؛ أعد التحقق دون إنشاء طلب جديد.", "No confirmed result was received. The same operation is retained; retry without creating a new request.")
    "PAYROLL_SOURCE_READ_UNAVAILABLE" -> hiltechText("تعذر تحميل مصادر الفترة. حدّث الصفحة للمحاولة مرة أخرى.", "The period sources could not be loaded. Refresh to try again.")
    "SOURCE_READ_DENIED_OR_UNAVAILABLE" -> hiltechText("تعذر تحميل كشوفك الآن. حدّث الصفحة، أو راجع ارتباط حسابك بملف الموظف لدى إدارة الشركة.", "Your payslips could not be loaded. Refresh, or ask company administration to check your employee account link.")
    "PAYROLL_RUN_READ_UNAVAILABLE" -> hiltechText("تعذر تحميل سجل الحساب أو حالة المالية. حدّث الصفحة للمحاولة مرة أخرى.", "Run history or Finance status could not be loaded. Refresh to try again.")
    else -> hiltechText("تعذر إتمام العملية. راجع البيانات والصلاحيات ثم أعد المحاولة.", "The action could not be completed. Review the data and permissions, then try again.")
}

@Composable
internal fun payrollLifecycleLabel(state: String): String = when (state) {
    "OPEN" -> hiltechText("فترة مفتوحة", "Open period")
    "INPUTS_SNAPSHOTTED" -> hiltechText("لقطة الموظفين مثبتة", "Employee snapshot captured")
    "ACTIVE" -> hiltechText("على رأس العمل", "Active")
    "OFFBOARDING" -> hiltechText("إجراءات مغادرة", "Offboarding")
    "FORMER" -> hiltechText("موظف سابق", "Former employee")
    "PREBOARDING" -> hiltechText("جارٍ التجهيز", "Preboarding")
    "RELEASED" -> hiltechText("نسخة معتمدة للمراجعة المالية", "Released for Finance review")
    "SUPERSEDED" -> hiltechText("استُبدلت بنسخة معتمدة أحدث", "Replaced by a newer released version")
    "NOT_RELEASED", "DRAFT" -> hiltechText("مسودة حساب", "Calculation draft")
    else -> state
}

@Composable
internal fun payrollRunReviewLabel(state: String): String = when (state) {
    "POLICY_NOT_CONFIGURED" -> hiltechText("إعداد المراجع مطلوب", "Reviewer setup required")
    "NOT_REQUESTED" -> hiltechText("لم تُرسل للمراجعة", "Not submitted for review")
    "PENDING" -> hiltechText("بانتظار قرار المراجع المستقل", "Awaiting the independent reviewer's decision")
    "APPROVED" -> hiltechText("اعتمد المراجع حساب النسخة", "Calculation approved by the reviewer")
    "RELEASED" -> hiltechText("اعتماد النسخة مثبت", "Version release recorded")
    "REJECTED" -> hiltechText("مرفوضة", "Rejected")
    "CHANGE_REQUESTED" -> hiltechText("تعديل الحساب مطلوب", "Calculation changes requested")
    "POLICY_REVIEW_REQUIRED" -> hiltechText("تغيّر إعداد المراجعة", "Review setup changed")
    "SOURCE_CHANGED", "SUPERSEDED" -> hiltechText("تغيّرت مصادر الحساب", "Calculation sources changed")
    "NO_APPROVAL_REQUIRED" -> hiltechText("السياسة المعتمدة تسمح بالتسليم المباشر", "The approved policy permits direct release")
    else -> hiltechText("حالة المراجعة غير متاحة", "Review status unavailable")
}

@Composable
internal fun payrollDisplayDate(value: String): String = hiltechDateToUtcMillis(value)?.let {
    hiltechLocalizedDate(it, if (LocalHiltechLanguage.current == HiltechLanguage.Arabic) "ar-EG" else "en-GB")
} ?: value
