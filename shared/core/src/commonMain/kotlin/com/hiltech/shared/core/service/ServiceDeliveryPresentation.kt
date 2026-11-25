package com.hiltech.shared.core.service

import com.hiltech.shared.core.design.HiltechLanguage
import com.hiltech.shared.core.design.hiltechDateToUtcMillis

fun serviceDeliveryLabel(code: String, language: HiltechLanguage): String = when (code) {
    "HANDOVER_RELEASE" -> language.text("إصدار حزمة التسليم", "Issue handover package")
    "HANDOVER_CLIENT_RESPONSE" -> language.text("تسجيل استجابة العميل", "Record client response")
    "HANDOVER_WITHDRAW" -> language.text("سحب الإصدار السابق", "Withdraw prior release")
    "WARRANTY_TERMS" -> language.text("شروط ونطاق الضمان", "Warranty terms and scope")
    "WARRANTY_ASSESSMENT" -> language.text("قرار تغطية بلاغ", "Incident coverage decision")
    "AGREEMENT" -> language.text("العقد أو اتفاق الشروط", "Contract or agreed terms")
    "CLIENT_RESPONSE" -> language.text("استجابة العميل", "Client response")
    "WARRANTY_SUPPORT" -> language.text("مستند مساند للضمان", "Warranty supporting document")
    "INCIDENT" -> language.text("دليل البلاغ", "Incident evidence")
    "WORK_DOCUMENT" -> language.text("مستند تنفيذ معتمد", "Approved work document")
    "SITE" -> language.text("موقع", "Site")
    "WORK" -> language.text("أمر عمل", "Work order")
    "ACCEPTED", "CLIENT_ACCEPTED" -> language.text("قبول العميل مسجّل", "Client acceptance recorded")
    "REJECTED", "CLIENT_REJECTED" -> language.text("مرفوض", "Rejected")
    "ACCEPTED_WITH_RESERVATIONS", "CLIENT_ACCEPTED_WITH_RESERVATIONS" -> language.text("قبول بتحفظات مسجّل", "Acceptance with reservations recorded")
    "COVERED" -> language.text("مشمول بالضمان", "Covered by warranty")
    "NOT_COVERED" -> language.text("غير مشمول بالضمان", "Not covered by warranty")
    "INSUFFICIENT_INFORMATION" -> language.text("معلومات غير كافية للحسم", "Insufficient information to decide")
    "NOT_ISSUED" -> language.text("لم تصدر حزمة تسليم", "No handover issued")
    "ISSUED" -> language.text("حزمة التسليم صادرة", "Handover package issued")
    "WITHDRAWN" -> language.text("الإصدار مسحوب", "Release withdrawn")
    "PENDING" -> language.text("بانتظار المراجعة", "Awaiting review")
    "APPROVED" -> language.text("معتمد — يحتاج تثبيت النتيجة", "Approved — record the result")
    "RECORDED" -> language.text("مسجّل ومعتمد", "Approved and recorded")
    "STALE" -> language.text("تغيّر المصدر — يحتاج إغلاق الطلب", "Source changed — close this request")
    "SUPERSEDED" -> language.text("أُغلق بعد تغيّر المصدر", "Closed after a source change")
    "REQUEST_CHANGE", "CHANGES_REQUESTED" -> language.text("مطلوب تصحيح", "Changes requested")
    "CHECK_STARTED" -> language.text("بدء فحص المستندات", "Start document check")
    "FINDING_RECORDED" -> language.text("تسجيل ملاحظة فحص", "Record a check finding")
    "RECHECK_REQUESTED" -> language.text("طلب إعادة الفحص", "Request another check")
    else -> language.text("راجع حالة السجل", "Review record status")
}

fun serviceDeliveryProblem(code: String?, language: HiltechLanguage): String = when (code) {
    "REAUTH_REQUIRED" -> language.text("أكد تسجيل الدخول قبل تسجيل القرار، ثم أعد نفس الطلب المحفوظ.", "Confirm sign-in before recording this decision, then retry the saved request.")
    "SERVICE_DELIVERY_REVIEW_SETUP_REQUIRED" -> language.text("حدّد مراجع التسليم أو الضمان من مركز إدارة الشركة، ثم أعد تحميل المصدر.", "Choose the handover or warranty reviewer in Company administration, then refresh.")
    "SERVICE_DELIVERY_INDEPENDENT_REVIEWER_REQUIRED" -> language.text("معدّ الطلب لا يعتمد طلبه. اختر مراجعًا آخر من إدارة الشركة.", "The preparer cannot approve their own request. Choose another reviewer in Company administration.")
    "SERVICE_DELIVERY_ORIGINAL_REVIEW_REQUIRED" -> language.text("افتح المستندات المطلوبة داخل الطلب أولًا، ثم سجّل قرارك.", "Open the required documents in this request before deciding.")
    "SERVICE_DELIVERY_PACKAGE_NOT_READY" -> language.text("حزمة التسليم تحتاج مستندات مكتملة وأعمالًا مقبولة ونسخة حديثة. راجع أسباب الجاهزية.", "The package needs completed documents, accepted work and a current version. Review readiness details.")
    "SERVICE_DELIVERY_PACKAGE_USE_INDIVIDUAL_DOCUMENTS" -> language.text("الحزمة كبيرة للتصدير دفعة واحدة. صدّر المستندات الأصلية منفردة من الإصدار نفسه.", "This package is large. Export its original documents individually from the same release.")
    "SERVICE_DELIVERY_WITHDRAW_PREVIOUS_RELEASE" -> language.text("يوجد إصدار قائم. اسحب الإصدار السابق مع بيان السبب قبل إصدار بديل.", "A release is already active. Withdraw it with a reason before issuing a replacement.")
    "SERVICE_DELIVERY_RESELECT_SAME_FILE" -> language.text("اختر نفس الملف لإكمال الرفع المحفوظ. سيُتحقق من المحتوى قبل المتابعة.", "Choose the same file to resume the saved upload. Its content will be verified.")
    "SERVICE_DELIVERY_INPUT_INVALID", "FORM_INCOMPLETE" -> language.text("أكمل الاختيارات والتواريخ والسبب والتأكيدات المطلوبة قبل المتابعة.", "Complete the required selections, dates, reason and confirmations.")
    "SUPPORT_INVALID_TEXT", "SUPPORT_INVALID_CONFIGURED_CODE" -> language.text("راجع عنوان البلاغ ووصفه والتأثير والأولوية المحددين.", "Review the incident title, description, impact and priority.")
    "SUPPORT_PROJECT_STALE", "SUPPORT_INVALID_PROJECT_VERSION" -> language.text("تغيّرت بيانات المشروع. حدّث العرض وراجع البلاغ المحفوظ قبل تأكيده مجددًا.", "The project changed. Refresh and review the saved incident before confirming again.")
    "SERVICE_DELIVERY_RESPONSE_DATE_INVALID" -> language.text("يجب أن يكون تاريخ استجابة العميل بعد إصدار الحزمة، وألا يكون في المستقبل.", "The client response date must follow the package release and cannot be in the future.")
    "SERVICE_DELIVERY_RESPONSE_PROOF_ALREADY_LINKED" -> language.text("هذا الرد مسجّل لإصدار سابق. أرفق استجابة تخص الإصدار الحالي؛ لا يُنقل قبول العميل تلقائيًا إلى حزمة بديلة.", "This response is recorded against an earlier release. Attach a response for the current release; acceptance does not transfer automatically to a replacement package.")
    "SERVICE_DELIVERY_COVERAGE_CONTRADICTS_TERMS" -> language.text("تاريخ الواقعة أو النطاق أو الاستثناء لا يتفق مع قرار التغطية. راجع الشروط الأصلية.", "The incident date, scope or exclusion conflicts with coverage. Review the original terms.")
    "SERVICE_DELIVERY_SOURCE_CHANGED", "SERVICE_DELIVERY_REFERENCE_CHANGED", "SERVICE_DELIVERY_CORRECTION_SOURCE_REQUIRED", "SERVICE_DELIVERY_RELEASE_CHANGED", "SERVICE_DELIVERY_WARRANTY_TERMS_CHANGED", "SERVICE_DELIVERY_SCOPE_CHANGED" ->
        language.text("تغيّر السجل منذ فتحه. حمّل النسخة الحالية ثم جهّز الطلب عليها؛ التاريخ السابق محفوظ.", "The record changed. Refresh and prepare against the current version; previous history is retained.")
    "SERVICE_DELIVERY_PROOF_NOT_READY", "SERVICE_DELIVERY_PROOF_PURPOSE_MISMATCH", "SERVICE_DELIVERY_EVIDENCE_BYTES_CHANGED" -> language.text("المستند المحدد غير جاهز أو لا يطابق هذا المشروع والغرض. اختر الدليل الصحيح.", "The selected document is not ready or does not match this project and purpose. Choose the correct evidence.")
    "SERVICE_DELIVERY_DOCUMENT_DISCLOSURE_BLOCKED" -> language.text("تحتوي الحزمة على مستند لا تسمح قواعده بتسليمه للعميل. راجع المستند قبل إصدار الحزمة.", "A package document cannot be disclosed to the client under its current rules. Review it before release.")
    "SERVICE_DELIVERY_NOT_FOUND", "SERVICE_DELIVERY_EVIDENCE_NOT_FOUND", "SUPPORT_PROJECT_NOT_FOUND", "SUPPORT_FORBIDDEN", "FORBIDDEN", "UNAUTHENTICATED" -> language.text("لم يعد هذا السجل متاحًا لهذا الحساب. تحقق من تسجيل الدخول والمسؤولية الحالية.", "This record is no longer available to this account. Check sign-in and current responsibility.")
    "SERVICE_DELIVERY_PREVIEW_UNAVAILABLE", "SERVICE_DELIVERY_PREVIEW_FORMAT_UNSUPPORTED" -> language.text("تعذّر عرض هذا المستند. استخدم PDF أو صورة سليمة ويمكن قراءتها.", "This document could not be previewed. Use a readable PDF or image.")
    else -> language.text("تعذّر تأكيد النتيجة من المصدر. أعد المحاولة؛ الطلب المحفوظ سيستخدم المرجع نفسه.", "The result could not be confirmed from the source. Retry; the saved request keeps the same reference.")
}

/** Convenient client feedback only. The source validates actual dates, current scope, evidence, authority and approval again. */
fun serviceDeliveryFormComplete(action: String, d: ServiceDeliveryDetails): Boolean {
    fun present(vararg values: String?) = values.all { !it.isNullOrBlank() }
    fun date(value: String?) = value?.let(::hiltechDateToUtcMillis) != null
    if (d.reason.trim().length !in 8..4000) return false
    return when (action) {
        "HANDOVER_RELEASE" -> present(d.packageVersionId, d.agreementEvidenceId, d.contractReference) && d.scopeConfirmed && d.disclosureConfirmed
        "HANDOVER_CLIENT_RESPONSE" -> present(d.releaseEventId, d.proofEvidenceId, d.representativeName, d.representativeCapacity, d.representativeAuthoritySource) &&
            d.responseOutcome in setOf("ACCEPTED", "REJECTED", "ACCEPTED_WITH_RESERVATIONS") && date(d.responseOn) && d.representativeVerified
        "HANDOVER_WITHDRAW" -> present(d.releaseEventId)
        "WARRANTY_TERMS" -> present(d.agreementEvidenceId, d.contractReference, d.scopeId, d.coveredItem, d.writtenTerms) && d.scopeType in setOf("SITE", "WORK") &&
            date(d.startsOn) && date(d.endsOn) && requireNotNull(d.startsOn) <= requireNotNull(d.endsOn) && d.scopeConfirmed
        "WARRANTY_ASSESSMENT" -> present(d.ticketId, d.incidentDescription) && d.coverageDecision in setOf("COVERED", "NOT_COVERED", "INSUFFICIENT_INFORMATION") &&
            (d.coverageDecision == "INSUFFICIENT_INFORMATION" || (present(d.termsEventId, d.proofEvidenceId) && date(d.incidentOn))) &&
            (d.coverageDecision != "COVERED" || (d.scopeConfirmed && !d.exclusionApplies)) && (!d.exclusionApplies || present(d.exclusionReason))
        else -> false
    }
}
