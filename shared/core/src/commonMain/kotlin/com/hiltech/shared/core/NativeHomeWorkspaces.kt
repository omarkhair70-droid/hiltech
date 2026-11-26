package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechShape
import com.hiltech.shared.core.design.LocalHiltechLanguage
import com.hiltech.shared.core.design.LocalHiltechOperationalColors
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.people.EmployeeDetailDto

/** Navigation is derived from successful current source reads, never job labels or inferred grants. */
internal fun nativeHomeOperationalGroups(
    workspaces: List<NativeOperationalWorkspace>,
    availability: Map<String, WorkspaceAvailability>,
): Map<NativeWorkspaceGroup, List<NativeOperationalWorkspace>> =
    uniqueNativeWorkspaces(workspaces)
        .filter { availability[it.id] == WorkspaceAvailability.AVAILABLE &&
            it.id != "executive-home" && nativeWorkspaceGroup(it.id) != NativeWorkspaceGroup.Personal }
        .groupBy { nativeWorkspaceGroup(it.id) }
        .toList().sortedBy { it.first.ordinal }.toMap()

internal fun nativeHomePersonalWorkspaces(
    workspaces: List<NativeOperationalWorkspace>,
    availability: Map<String, WorkspaceAvailability>,
): List<NativeOperationalWorkspace> =
    uniqueNativeWorkspaces(workspaces).filter {
        nativeWorkspaceGroup(it.id) == NativeWorkspaceGroup.Personal &&
            availability[it.id] == WorkspaceAvailability.AVAILABLE
    }

internal fun nativePeopleDirectoryAvailable(people: HiltechPeopleState?): Boolean =
    people?.directory != null

internal fun nativeOwnEmployeeProfile(
    people: HiltechPeopleState?,
    identityId: String,
    organizationId: String?,
): EmployeeDetailDto? =
    people?.takeUnless { it.loading }?.ownProfile?.takeIf {
        organizationId != null && it.organizationId == organizationId &&
            (it.linkedIdentityId == null || it.linkedIdentityId == identityId)
    }

internal enum class HomeFieldStatus { Loading, Unavailable, Cached, Current }
internal data class HomeFieldOverview(val status: HomeFieldStatus, val count: Int? = null)

internal fun homeFieldOverview(field: HiltechFieldState): HomeFieldOverview = when {
    field.loading -> HomeFieldOverview(HomeFieldStatus.Loading)
    field.errorMessage != null -> HomeFieldOverview(HomeFieldStatus.Unavailable)
    field.cachedTodayAt != null -> HomeFieldOverview(HomeFieldStatus.Cached, field.today.size)
    else -> HomeFieldOverview(HomeFieldStatus.Current, field.today.size)
}

@Composable
internal fun homeFieldDetail(field: HiltechFieldState): String {
    val overview = homeFieldOverview(field)
    return when (overview.status) {
        HomeFieldStatus.Loading -> hiltechText("جارٍ تحديث تكليفاتك…", "Refreshing your assignments…")
        HomeFieldStatus.Unavailable -> hiltechText("لم يتأكد المصدر الحالي. افتح العمل لإعادة المحاولة.", "Current source not confirmed. Open work to retry.")
        HomeFieldStatus.Cached -> hiltechText("تكليفات محفوظة على الجهاز: ", "Locally cached assignments: ") + overview.count
        HomeFieldStatus.Current -> if (overview.count == 0)
            hiltechText("لا توجد تكليفات في القائمة الحالية.", "No assignments in the current list.")
        else hiltechText("تكليفاتك الحالية: ", "Current assignments: ") + overview.count
    }
}

@Composable
internal fun NativeHomeOperationalWorkspaces(
    workspaces: List<NativeOperationalWorkspace>,
    session: NativeWorkspaceSession,
    onOpen: (String) -> Unit,
) {
    val groups = nativeHomeOperationalGroups(workspaces, session.availability)
    if (groups.isEmpty()) return
    val language = LocalHiltechLanguage.current
    Text(hiltechText("مساحات عملي", "My operational workspaces"),
        style = MaterialTheme.typography.titleLarge)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 720.dp) 2 else 1
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            groups.entries.toList().chunked(columns).forEach { entries ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    entries.forEach { (group, items) ->
                        HiltechPanel(Modifier.weight(1f)) {
                            Text(group.title(language), style = MaterialTheme.typography.titleMedium)
                            items.forEach { workspace ->
                                NativeHomeActionRow(workspace, session.pendingId == null, onOpen)
                            }
                        }
                    }
                    repeat(columns - entries.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun NativeHomePersonalWorkspaces(
    workspaces: List<NativeOperationalWorkspace>,
    session: NativeWorkspaceSession,
    onOpen: (String) -> Unit,
) {
    nativeHomePersonalWorkspaces(workspaces, session.availability).forEach { workspace ->
        NativeHomeActionRow(workspace, session.pendingId == null, onOpen)
    }
}

@Composable
private fun NativeHomeActionRow(
    workspace: NativeOperationalWorkspace,
    enabled: Boolean,
    onOpen: (String) -> Unit,
) {
    val colors = LocalHiltechOperationalColors.current
    val language = LocalHiltechLanguage.current
    Surface(onClick = { onOpen(workspace.id) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), color = colors.surfaceElevated,
        contentColor = colors.primaryText, shape = HiltechShape.small) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(nativeWorkspaceTitle(workspace, language), Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge)
            Text(hiltechText("فتح", "Open"), color = colors.accent, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun NativeHomeRouteFeedback(session: NativeWorkspaceSession) {
    val language = LocalHiltechLanguage.current
    if (session.pendingId == null && session.availability.values.any { it == WorkspaceAvailability.CHECKING }) {
        Text(hiltechText("جارٍ التحقق من المساحات المتصلة…", "Checking connected workspaces…"))
    }
    if (session.pendingId == null && session.message == null &&
        session.availability.values.any { it == WorkspaceAvailability.UNAVAILABLE }) {
        Text(hiltechText("لم يتأكد الاتصال ببعض المصادر.", "Some sources could not be confirmed."),
            color = LocalHiltechOperationalColors.current.secondaryText)
        TextButton(onClick = session::refresh) { Text(hiltechText("تحديث المساحات", "Refresh workspaces")) }
    }
    if (session.pendingId != null) {
        Text(hiltechText("جارٍ التحقق من الوصول وفتح العمل…", "Checking access and opening workspace…"))
    }
    session.message?.let {
        Text(when (it) {
            "Access is no longer available." -> language.text("لم يعد الوصول متاحًا.", it)
            "The source could not be reached. Refresh and try again." ->
                language.text("تعذر الوصول للمصدر. حدّث وحاول مجددًا.", it)
            else -> language.text("تعذر فتح مساحة العمل الحالية.", "Could not open the current workspace.")
        }, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = session::refresh) { Text(hiltechText("تحديث المساحات", "Refresh workspaces")) }
    }
}
