package com.hiltech.shared.core.ux

/**
 * UX-128 experimental icon-language and density specimens.
 * This does not import an icon library or select a final product appearance.
 */
enum class HuxIconClass { ACTION, OBJECT, STATUS }

enum class HuxIconIntent(
    val iconClass: HuxIconClass,
    val mirrorsInRtl: Boolean,
    val requiresVisibleLabel: Boolean,
    private val arabicLabel: String,
    private val englishLabel: String,
) {
    BACK(HuxIconClass.ACTION, true, false, "رجوع", "Back"),
    FORWARD(HuxIconClass.ACTION, true, false, "التالي", "Next"),
    SEARCH(HuxIconClass.ACTION, false, false, "بحث", "Search"),
    CODE_LOOKUP(HuxIconClass.ACTION, false, false, "بحث بكود الأصل", "Look up by asset code"),
    FILTER(HuxIconClass.ACTION, false, false, "تصفية", "Filter"),
    SORT(HuxIconClass.ACTION, false, false, "ترتيب", "Sort"),
    OPEN_SOURCE(HuxIconClass.ACTION, false, false, "عرض المصدر", "Open source record"),
    ATTACH_EVIDENCE(HuxIconClass.ACTION, false, false, "إرفاق دليل", "Attach evidence"),
    RETRY(HuxIconClass.ACTION, false, false, "إعادة المحاولة", "Retry"),
    NOTIFICATIONS(HuxIconClass.ACTION, false, false, "التنبيهات", "Notifications"),
    SETTINGS(HuxIconClass.ACTION, false, false, "الإعدادات", "Settings"),

    WORK_ORDER(HuxIconClass.OBJECT, false, true, "أمر عمل", "Work order"),
    PROJECT_SITE(HuxIconClass.OBJECT, false, true, "موقع المشروع", "Project site"),
    RACK(HuxIconClass.OBJECT, false, true, "راك", "Rack"),
    ODF(HuxIconClass.OBJECT, false, true, "لوحة توزيع الألياف ODF", "Optical distribution frame"),
    FIBER_LINK(HuxIconClass.OBJECT, false, true, "مسار ألياف ضوئية", "Fiber link"),
    SPLICE(HuxIconClass.OBJECT, false, true, "لحام ألياف", "Fiber splice"),
    OTDR(HuxIconClass.OBJECT, false, true, "اختبار OTDR", "OTDR test"),
    FLUKE_TEST(HuxIconClass.OBJECT, false, true, "اختبار Fluke", "Fluke test"),
    ASSET_PASSPORT(HuxIconClass.OBJECT, false, true, "سجل الأصل", "Asset passport"),
    CUSTODY(HuxIconClass.OBJECT, false, true, "عهدة", "Custody"),
    CALIBRATION(HuxIconClass.OBJECT, false, true, "معايرة", "Calibration"),

    SYNC_PENDING(HuxIconClass.STATUS, false, true, "بانتظار المزامنة", "Sync pending"),
    SYNC_CONFLICT(HuxIconClass.STATUS, false, true, "تعارض المزامنة", "Sync conflict"),
    UNVERIFIED(HuxIconClass.STATUS, false, true, "غير مُتحقق", "Unverified");

    /** Pair every icon with accessible copy, including icon-only generic actions. */
    fun accessibleLabel(locale: HuxLocale): String =
        if (locale == HuxLocale.AR) arabicLabel else englishLabel
}

/**
 * Measurable candidate profiles, not frozen global spacing or a final platform theme.
 * Renderer must preserve platform hit targets and support scalable fonts.
 */
enum class HuxDensityCandidate(
    val targetMinDp: Int,
    val rowMinDp: Int,
    val glyphDp: Int,
    val baseGapDp: Int,
    val touchSurface: Boolean,
) {
    DESKTOP_COMPACT(40, 44, 20, 8, false),
    COMFORTABLE(48, 56, 24, 12, true),
    FIELD_TOUCH(56, 64, 24, 16, true);
}

enum class HuxThemeCandidate { LIGHT, DARK, HIGH_CONTRAST }
enum class HuxMotionPreference { STANDARD, REDUCED }

fun HuxMotionPreference.shouldAnimateFeedback(): Boolean =
    this == HuxMotionPreference.STANDARD
