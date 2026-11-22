package com.hiltech.shared.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * UX #128 / product-wide navigation exploration.
 *
 * This is a presentation experiment over callers' EXISTING source-backed
 * composables, not a replacement authorization layer, route registry, new
 * backend domain or production shell. Nothing in the shipped app opts in.
 */
enum class HiltechNavigationCandidate {
    CONTEXT_FIRST,
    WORKBENCH_FIRST,
}

enum class HiltechNavigationArea {
    WORK,
    PROJECTS,
    OBJECTS,
    PEOPLE,
    ME,
}

data class HiltechNavigationEntry(
    val area: HiltechNavigationArea,
    val label: String,
)

/**
 * The caller must supply only destinations for which it has a current
 * server-authorized UI content projection. The presence of a cached
 * domain object or a team's display name is NOT proof of authorization.
 */
data class HiltechNavigationScope(
    val identityId: String,
    val organizationId: String,
    val contextName: String,
    // Exact project/site/warehouse context ID, not the human label.
    // A new object context must reset the previous selection/history.
    val contextId: String,
    val entries: List<HiltechNavigationEntry>,
    val preferredArea: HiltechNavigationArea,
) {
    val key: String
        get() = listOf(identityId, organizationId, contextId)
            .joinToString(separator = "") { "${it.length}:$it" }
}

data class HiltechNavigationTrace(
    val current: HiltechNavigationArea?,
    val history: List<HiltechNavigationArea> = emptyList(),
)

fun hiltechNavigationRevalidated(
    trace: HiltechNavigationTrace,
    available: Set<HiltechNavigationArea>,
    preferred: HiltechNavigationArea,
): HiltechNavigationTrace {
    val chosen = trace.current?.takeIf { it in available }
        ?: preferred.takeIf { it in available }
        ?: HiltechNavigationArea.entries.firstOrNull { it in available }
    return HiltechNavigationTrace(chosen, trace.history.filter { it in available && it != chosen })
}

fun hiltechNavigationSelect(
    trace: HiltechNavigationTrace,
    destination: HiltechNavigationArea,
    available: Set<HiltechNavigationArea>,
    preferred: HiltechNavigationArea,
): HiltechNavigationTrace {
    val valid = hiltechNavigationRevalidated(trace, available, preferred)
    if (destination !in available || destination == valid.current) return valid
    return HiltechNavigationTrace(
        current = destination,
        history = listOfNotNull(valid.current) + valid.history,
    )
}

fun hiltechNavigationBack(
    trace: HiltechNavigationTrace,
    available: Set<HiltechNavigationArea>,
    preferred: HiltechNavigationArea,
): HiltechNavigationTrace {
    val valid = hiltechNavigationRevalidated(trace, available, preferred)
    val previous = valid.history.firstOrNull() ?: return valid
    return HiltechNavigationTrace(previous, valid.history.drop(1))
}

@Composable
fun HiltechNavigationStudy(
    scope: HiltechNavigationScope,
    candidate: HiltechNavigationCandidate,
    modifier: Modifier = Modifier,
    onAreaChanged: (HiltechNavigationArea) -> Unit = {},
    content: @Composable (HiltechNavigationArea) -> Unit,
) {
    // Explicit scope key: a different principal or organization cannot
    // inherit the previously selected tab/history from this composition.
    var trace by remember(scope.key) {
        mutableStateOf(HiltechNavigationTrace(scope.preferredArea))
    }
    val permittedAreas = scope.entries.map { it.area }.toSet()
    // Always revalidate in the composition BEFORE displaying content.
    // A revoked destination must never remain visible for one extra frame.
    val valid = hiltechNavigationRevalidated(trace, permittedAreas, scope.preferredArea)
    val current = valid.current
    val ordered = if (candidate == HiltechNavigationCandidate.WORKBENCH_FIRST) {
        scope.entries.sortedWith(compareBy({ it.area != scope.preferredArea }, { it.area.ordinal }))
    } else {
        scope.entries.sortedBy { it.area.ordinal }
    }

    val select: (HiltechNavigationArea) -> Unit = { area ->
        val next = hiltechNavigationSelect(valid, area, permittedAreas, scope.preferredArea)
        trace = next
        if (next.current != valid.current) next.current?.let(onAreaChanged)
    }
    val goBack: () -> Unit = {
        val next = hiltechNavigationBack(valid, permittedAreas, scope.preferredArea)
        trace = next
        if (next.current != valid.current) next.current?.let(onAreaChanged)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                if (candidate == HiltechNavigationCandidate.CONTEXT_FIRST) "سياق العمل" else "مساحة عملك",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(scope.contextName, style = MaterialTheme.typography.bodyMedium)
            Text(
                "الوجهات الظاهرة مصدرها نطاق الوصول الحالي، وليست موافقة جديدة",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (current == null) {
            Text(
                "لا توجد مساحة عمل متاحة في بيانات الجلسة الحالية. تحقق من الاتصال والصلاحيات.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            BoxWithConstraints {
                val wide = maxWidth >= 760.dp
                if (wide && candidate == HiltechNavigationCandidate.CONTEXT_FIRST) {
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Column(
                            modifier = Modifier.width(186.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            NavigationEntries(ordered, current, select, vertical = true)
                            if (valid.history.isNotEmpty()) {
                                OutlinedButton(onClick = goBack) { Text("رجوع للسياق السابق") }
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(ordered.firstOrNull { it.area == current }?.label.orEmpty(),
                                style = MaterialTheme.typography.titleMedium)
                            HorizontalDivider()
                            content(current)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (candidate == HiltechNavigationCandidate.WORKBENCH_FIRST) {
                            Text(
                                "المساحة الحالية أولًا؛ باقي السياقات متاحة دون تغيير الصلاحيات",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        NavigationEntries(ordered, current, select, vertical = false)
                        if (valid.history.isNotEmpty()) {
                            OutlinedButton(onClick = goBack) { Text("رجوع للسياق السابق") }
                        }
                        HorizontalDivider()
                        content(current)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationEntries(
    entries: List<HiltechNavigationEntry>,
    selected: HiltechNavigationArea,
    onSelect: (HiltechNavigationArea) -> Unit,
    vertical: Boolean,
) {
    if (vertical) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entries.forEach { entry ->
                NavigationEntryButton(entry, entry.area == selected, onSelect)
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            entries.forEach { entry ->
                NavigationEntryButton(entry, entry.area == selected, onSelect)
            }
        }
    }
}

@Composable
private fun NavigationEntryButton(
    entry: HiltechNavigationEntry,
    selected: Boolean,
    onSelect: (HiltechNavigationArea) -> Unit,
) {
    val semanticsModifier = Modifier.semantics {
        contentDescription = entry.label
        stateDescription = if (selected) "القسم الحالي" else "قسم متاح"
    }
    if (selected) {
        Button(
            onClick = { onSelect(entry.area) },
            modifier = semanticsModifier,
        ) { Text(entry.label) }
    } else {
        OutlinedButton(
            onClick = { onSelect(entry.area) },
            modifier = semanticsModifier,
        ) { Text(entry.label) }
    }
}
