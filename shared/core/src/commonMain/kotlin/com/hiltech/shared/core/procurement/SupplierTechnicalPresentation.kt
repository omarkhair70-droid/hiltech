package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.design.HiltechLanguage

fun supplierTechnicalLabel(code: String, language: HiltechLanguage): String = when (code) {
    "COMPLIANT" -> language.text("مطابق للمطلوب", "Meets the requirement")
    "NON_COMPLIANT" -> language.text("غير مطابق", "Does not meet the requirement")
    "NEEDS_CLARIFICATION" -> language.text("يحتاج إيضاحًا", "Needs clarification")
    "NOT_ASSESSED" -> language.text("بانتظار المراجعة الفنية", "Awaiting technical review")
    "WORK_ORDER" -> language.text("احتياج مشروع", "Project requirement")
    "WAREHOUSE_REPLENISHMENT" -> language.text("استكمال مخزون", "Stock replenishment")
    "ASSET_MAINTENANCE_OR_REPLACEMENT" -> language.text("صيانة أو استبدال معدة", "Equipment maintenance or replacement")
    "OFFICE_TEAM_STOCK" -> language.text("احتياج فريق أو مكتب", "Team or office requirement")
    else -> language.text("مراجعة فنية", "Technical review")
}

fun supplierTechnicalProblem(code: String?, language: HiltechLanguage): String = when (code) {
    "REAUTH_REQUIRED", "UNAUTHENTICATED" -> language.text("جدّد تسجيل الدخول ثم أعد نفس الطلب.", "Sign in again, then retry the same request.")
    "SUPPLIER_TECHNICAL_NOT_VISIBLE", "OBJECT_NOT_VISIBLE", "PROCUREMENT_OBJECT_NOT_VISIBLE" ->
        language.text("لم يعد هذا الملف متاحًا لمسؤوليات حسابك الحالية. حدّث الصفحة أو راجع مسؤول الشركة.", "This file is unavailable to your current responsibilities. Refresh or contact the company administrator.")
    "SUPPLIER_TECHNICAL_SOURCE_CHANGED", "PROCUREMENT_SOURCE_NOT_CURRENT", "SUPPLIER_TECHNICAL_PACKET_REQUIRED" ->
        language.text("تغيّر العرض أو الاحتياج أو البيان الفني. حدّث الملف وراجع النسخة الحالية قبل الإرسال.", "The quote, requirement or technical packet changed. Refresh and review the current version before submitting.")
    "SUPPLIER_TECHNICAL_ORIGINAL_REVIEW_REQUIRED" -> language.text("افتح المرفقات الفنية المرتبطة بالنسخة الحالية قبل تسجيل المطابقة.", "Open the current packet’s technical attachments before recording compliance.")
    "SUPPLIER_TECHNICAL_REVIEW_CONFIRMATION_REQUIRED" -> language.text("أكّد مراجعة المواصفات والفروق والمصدر الفني قبل تسجيل النتيجة.", "Confirm that you reviewed the specification, deviations and technical source before recording the result.")
    "SUPPLIER_TECHNICAL_RESULT_UNCONFIRMED" -> language.text("لم تصل نتيجة مؤكدة. أعد نفس الطلب للتحقق من تسجيله؛ تبقى بياناته محفوظة أثناء هذه الجلسة.", "The result is unconfirmed. Retry the same request to confirm it; its data stays in this session.")
    "SUPPLIER_TECHNICAL_ORIGINAL_UNAVAILABLE", "SUPPLIER_TECHNICAL_ORIGINAL_CHANGED", "SUPPLIER_TECHNICAL_PREVIEW_UNAVAILABLE" ->
        language.text("تعذّر التحقق من هذا المستند الفني أو عرضه. اختر مستندًا فنيًا صالحًا في تصحيح جديد للبيان؛ لم يُسجّل فتحه للمراجعة.", "This technical original could not be verified or displayed. Select a valid original in a corrected packet; no review opening was recorded.")
    "SUPPLIER_TECHNICAL_STORAGE_UNAVAILABLE", "EVIDENCE_STORAGE_UNAVAILABLE" -> language.text("تخزين المستندات غير متاح مؤقتًا. احتفظ بنفس الطلب وأعد المحاولة.", "Document storage is temporarily unavailable. Keep the same request and retry.")
    "SUPPLIER_TECHNICAL_RESELECT_SAME_FILE" -> language.text("اختر نفس الملف الأصلي لاستكمال الطلب المحفوظ؛ يجب أن يتطابق محتواه وحجمه.", "Select the same original file to resume the saved request; its content and size must match.")
    "EVIDENCE_UPLOAD_SESSION_EXPIRED" -> language.text("انتهت مهلة رفع الملف. لم يُنشر هذا المستند في البيان الفني. ألغِ محاولة الرفع هذه ثم اختر الملف مجددًا.", "The upload session expired. This document was not published in the technical packet. End this upload attempt, then select the file again.")
    "FORM_INCOMPLETE", "SUPPLIER_TECHNICAL_INPUT_INVALID" -> language.text("أكمل الحقول المطلوبة والمصدر الفني وسبب التسجيل أو التصحيح.", "Complete the required fields, technical source and reason for recording or correcting this packet.")
    "SUPPLIER_TECHNICAL_FILE_INVALID" -> language.text("اختر مستندًا فنيًا PDF أو صورة PNG أو JPEG لا تتجاوز ١٦ ميجابايت، دون أسعار أو شروط دفع.", "Choose a technical PDF, PNG or JPEG up to 16 MiB, without prices or payment terms.")
    "OPERATION_PREVIOUSLY_FAILED" -> language.text("تأكد فشل الطلب السابق. راجع الحالة الحالية ثم جهّز طلبًا مصححًا.", "The previous request is confirmed failed. Review the current state, then prepare a corrected request.")
    else -> language.text("تعذّر إكمال العملية. حدّث الملف أو أعد نفس الطلب المحفوظ.", "The operation could not be completed. Refresh the file or retry the same saved request.")
}

internal fun supplierTechnicalFieldsValid(d: SupplierTechnicalPacketFields): Boolean =
    d.offeredSpecification.trim().length in 8..8000 && d.deviations.trim().length in 1..4000 &&
        d.supplierSource.trim().length in 1..240 && d.sourceReference.trim().length in 1..1000 && d.correctionReason.trim().length in 8..1000 &&
        d.manufacturer.trim().length <= 160 && d.model.trim().length <= 240 && d.evidenceIds.size <= 10 && d.nonFinancialConfirmed
