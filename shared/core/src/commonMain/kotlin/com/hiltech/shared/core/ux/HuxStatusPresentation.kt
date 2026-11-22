package com.hiltech.shared.core.ux

/**
 * UX-128 experimental display grammar, not a source of business authority.
 *
 * Callers must supply a status that their authoritative domain state explicitly supports.
 * This contract never derives permissions, approvals, server confirmation or attention
 * priority from a colour, a role name, an optimistic local action or an HTTP response.
 * It is deliberately not wired into the production HiltechShell.
 */
enum class HuxLocale { AR, EN }

enum class HuxStatusAxis(val ar: String, val en: String) {
    LIFECYCLE("دورة الحياة", "Lifecycle"),
    HEALTH("حالة التشغيل", "Health"),
    ACTION("الإجراء المطلوب", "Action"),
    SYNC("حالة المزامنة", "Sync"),
    RESULT("نتيجة العملية", "Result");

    fun label(locale: HuxLocale): String = if (locale == HuxLocale.AR) ar else en
}

/** Semantic signal, not a palette token. Colour is optional supporting evidence. */
enum class HuxVisualIntent {
    NEUTRAL, INFORMATION, ATTENTION, CAUTION, CRITICAL, POSITIVE, UNKNOWN
}

/** Candidate icon meaning. No icon font, stroke, colour or library is frozen here. */
enum class HuxIconRole {
    DOCUMENT, CURRENT, FINISHED, VERIFIED, WARNING, BLOCKED,
    ACTION_REQUIRED, WAITING, LOCAL, TRANSFER, PROGRESS, QUESTION
}

data class HuxStatusPresentation(
    val key: String,
    val axis: HuxStatusAxis,
    val visualIntent: HuxVisualIntent,
    val iconRole: HuxIconRole,
    val visibleLabel: String,
    val accessibleLabel: String,
)

/**
 * UI labels for representative _explicit_ states. No status-to-status inference.
 *
 * E.g. SYNC_CONFIRMED means a caller already knows the server confirmed a sync;
 * it does NOT mean a purchase, approval, inspection or work order was accepted.
 */
enum class HuxStatus(
    val axis: HuxStatusAxis,
    val visualIntent: HuxVisualIntent,
    val iconRole: HuxIconRole,
    private val arabic: String,
    private val english: String,
) {
    LIFECYCLE_DRAFT(HuxStatusAxis.LIFECYCLE, HuxVisualIntent.NEUTRAL, HuxIconRole.DOCUMENT, "مسودة", "Draft"),
    LIFECYCLE_ACTIVE(HuxStatusAxis.LIFECYCLE, HuxVisualIntent.INFORMATION, HuxIconRole.CURRENT, "نشط", "Active"),
    LIFECYCLE_CLOSED(HuxStatusAxis.LIFECYCLE, HuxVisualIntent.NEUTRAL, HuxIconRole.FINISHED, "مغلق", "Closed"),

    HEALTH_HEALTHY(HuxStatusAxis.HEALTH, HuxVisualIntent.POSITIVE, HuxIconRole.VERIFIED, "سليم", "Healthy"),
    HEALTH_AT_RISK(HuxStatusAxis.HEALTH, HuxVisualIntent.CAUTION, HuxIconRole.WARNING, "معرّض للخطر", "At risk"),
    HEALTH_CRITICAL(HuxStatusAxis.HEALTH, HuxVisualIntent.CRITICAL, HuxIconRole.BLOCKED, "حرج", "Critical"),
    HEALTH_UNKNOWN(HuxStatusAxis.HEALTH, HuxVisualIntent.UNKNOWN, HuxIconRole.QUESTION, "الحالة غير معروفة", "Unknown health"),

    ACTION_WAITING_ON_YOU(HuxStatusAxis.ACTION, HuxVisualIntent.ATTENTION, HuxIconRole.ACTION_REQUIRED, "يحتاج إجراء منك", "Action required from you"),
    ACTION_WAITING_EXTERNAL(HuxStatusAxis.ACTION, HuxVisualIntent.INFORMATION, HuxIconRole.WAITING, "بانتظار طرف آخر", "Waiting on another party"),
    ACTION_NONE(HuxStatusAxis.ACTION, HuxVisualIntent.NEUTRAL, HuxIconRole.FINISHED, "لا يوجد إجراء مطلوب حاليًا", "No action currently required"),

    SYNC_LOCAL_ONLY(HuxStatusAxis.SYNC, HuxVisualIntent.INFORMATION, HuxIconRole.LOCAL, "محفوظ محليًا فقط", "Saved locally only"),
    SYNC_QUEUED(HuxStatusAxis.SYNC, HuxVisualIntent.ATTENTION, HuxIconRole.TRANSFER, "بانتظار المزامنة", "Queued for sync"),
    SYNC_IN_PROGRESS(HuxStatusAxis.SYNC, HuxVisualIntent.INFORMATION, HuxIconRole.PROGRESS, "تجري المزامنة", "Sync in progress"),
    SYNC_CONFIRMED(HuxStatusAxis.SYNC, HuxVisualIntent.POSITIVE, HuxIconRole.VERIFIED, "تأكدت المزامنة من الخادم", "Sync confirmed by server"),
    SYNC_CONFLICT(HuxStatusAxis.SYNC, HuxVisualIntent.CRITICAL, HuxIconRole.BLOCKED, "تعارض يحتاج مراجعة", "Sync conflict needs review"),
    SYNC_STALE(HuxStatusAxis.SYNC, HuxVisualIntent.CAUTION, HuxIconRole.WARNING, "البيانات قديمة", "Data may be stale"),
    SYNC_PROVIDER_UNAVAILABLE(HuxStatusAxis.SYNC, HuxVisualIntent.UNKNOWN, HuxIconRole.QUESTION, "مصدر البيانات غير متاح", "Data provider unavailable"),
    SYNC_UNKNOWN(HuxStatusAxis.SYNC, HuxVisualIntent.UNKNOWN, HuxIconRole.QUESTION, "حالة المزامنة غير معروفة", "Sync state unknown"),

    RESULT_SUCCESS(HuxStatusAxis.RESULT, HuxVisualIntent.POSITIVE, HuxIconRole.VERIFIED, "نجحت العملية", "Operation succeeded"),
    RESULT_FAILURE(HuxStatusAxis.RESULT, HuxVisualIntent.CRITICAL, HuxIconRole.BLOCKED, "فشلت العملية", "Operation failed"),
    RESULT_PARTIAL(HuxStatusAxis.RESULT, HuxVisualIntent.CAUTION, HuxIconRole.WARNING, "اكتملت العملية جزئيًا", "Operation partially completed"),
    RESULT_UNVERIFIED(HuxStatusAxis.RESULT, HuxVisualIntent.UNKNOWN, HuxIconRole.QUESTION, "النتيجة لم تُتحقق بعد", "Result not yet verified");

    fun present(locale: HuxLocale): HuxStatusPresentation {
        val label = if (locale == HuxLocale.AR) arabic else english
        return HuxStatusPresentation(
            key = name,
            axis = axis,
            visualIntent = visualIntent,
            iconRole = iconRole,
            visibleLabel = label,
            accessibleLabel = "${axis.label(locale)}: $label",
        )
    }
}
