package com.hiltech.shared.core.executive

/**
 * Compact, strictly window-scoped read of already-authorized producer data.
 *
 * This is NOT company-wide search, an executive risk score or a total count.
 * In particular, Project and Inbox reads can be bounded before the source's
 * access filtering and cannot prove that "nothing needs attention."
 */
enum class ExecutivePulseState {
    CRITICAL_VISIBLE,
    ATTENTION_VISIBLE,
    UNVERIFIED,
    WINDOW_EMPTY,
    SOURCE_UNAVAILABLE,
}

data class ExecutivePulseItem(
    val label: String,
    val state: ExecutivePulseState,
    val statement: String,
    val incomplete: Boolean,
)

fun executiveVisiblePulse(home: ExecutiveHomeDto): List<ExecutivePulseItem> {
    val decisions = home.needsYou
    val projects = home.projects
    val assets = home.assetExceptions

    val decisionsState = when {
        decisions.status == "UNAVAILABLE" -> ExecutivePulseState.SOURCE_UNAVAILABLE
        decisions.items.isNotEmpty() -> ExecutivePulseState.ATTENTION_VISIBLE
        decisions.partial || decisions.status != "CURRENT" -> ExecutivePulseState.UNVERIFIED
        else -> ExecutivePulseState.WINDOW_EMPTY
    }

    val projectStates = projects.items.map { it.healthState }
    val projectState = when {
        projects.status == "UNAVAILABLE" -> ExecutivePulseState.SOURCE_UNAVAILABLE
        "CRITICAL" in projectStates -> ExecutivePulseState.CRITICAL_VISIBLE
        "ON_HOLD" in projectStates || "ATTENTION" in projectStates -> ExecutivePulseState.ATTENTION_VISIBLE
        projectStates.any { it != "HEALTHY" } || projects.partial || projects.status != "CURRENT" ->
            ExecutivePulseState.UNVERIFIED
        else -> ExecutivePulseState.WINDOW_EMPTY
    }

    val assetState = when {
        assets.status == "UNAVAILABLE" -> ExecutivePulseState.SOURCE_UNAVAILABLE
        assets.items.isNotEmpty() -> ExecutivePulseState.ATTENTION_VISIBLE
        assets.partial || assets.status != "CURRENT" -> ExecutivePulseState.UNVERIFIED
        else -> ExecutivePulseState.WINDOW_EMPTY
    }

    return listOf(
        ExecutivePulseItem(
            label = "قراراتك",
            state = decisionsState,
            statement = when (decisionsState) {
                ExecutivePulseState.ATTENTION_VISIBLE -> "توجد قرارات ظاهرة لك تحتاج مراجعة."
                ExecutivePulseState.SOURCE_UNAVAILABLE -> "تعذر التحقق من قائمة قراراتك."
                ExecutivePulseState.UNVERIFIED -> "القراءة جزئية؛ لا يمكن الجزم باكتمال القرارات."
                else -> "لا توجد قرارات ظاهرة في نافذة القراءة الحالية."
            },
            incomplete = decisions.partial || decisions.status != "CURRENT",
        ),
        ExecutivePulseItem(
            label = "صحة المشاريع",
            state = projectState,
            statement = when (projectState) {
                ExecutivePulseState.CRITICAL_VISIBLE -> "توجد مشاريع بحالة حرجة في النافذة المتاحة."
                ExecutivePulseState.ATTENTION_VISIBLE -> "توجد مشاريع متوقفة أو تحتاج انتباهًا."
                ExecutivePulseState.SOURCE_UNAVAILABLE -> "تعذر التحقق من مصدر صحة المشاريع."
                ExecutivePulseState.UNVERIFIED -> "بيانات الصحة أو تغطية المشاريع غير مكتملة."
                else -> "لم تظهر مشكلة في نافذة المشاريع المقروءة."
            },
            incomplete = projects.partial || projects.status != "CURRENT" ||
                projectStates.any { it != "HEALTHY" && it != "CRITICAL" && it != "ON_HOLD" && it != "ATTENTION" },
        ),
        ExecutivePulseItem(
            label = "خدمة المعدات",
            state = assetState,
            statement = when (assetState) {
                ExecutivePulseState.ATTENTION_VISIBLE -> "توجد استثناءات معدات في المخازن الظاهرة لصلاحياتك."
                ExecutivePulseState.SOURCE_UNAVAILABLE -> "تعذر التحقق من استثناءات المعدات."
                ExecutivePulseState.UNVERIFIED -> "بيانات المعدات غير مكتملة؛ لا يمكن إثبات خلوها من الاستثناءات."
                else -> "لا توجد استثناءات ظاهرة في نافذة المعدات الحالية."
            },
            incomplete = assets.partial || assets.status != "CURRENT",
        ),
    )
}
