package com.hiltech.shared.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechShape
import com.hiltech.shared.core.design.LocalHiltechOperationalColors
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal enum class WorkspaceAvailability { CHECKING, AVAILABLE, DENIED, UNAVAILABLE }

internal fun workspaceFailureAvailability(status: Int?): WorkspaceAvailability =
    if (status == 401 || status == 403) WorkspaceAvailability.DENIED
    else WorkspaceAvailability.UNAVAILABLE

internal fun uniqueNativeWorkspaces(workspaces: List<NativeOperationalWorkspace>): List<NativeOperationalWorkspace> =
    workspaces.groupBy { it.id }.values.filter { it.size == 1 && it.single().id.isNotBlank() }.map { it.single() }

internal class NativeWorkspaceSession {
    val availability = mutableStateMapOf<String, WorkspaceAvailability>()
    var selectedId by mutableStateOf<String?>(null)
        private set
    var pendingId by mutableStateOf<String?>(null)
        private set
    var revision by mutableStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun open(id: String) {
        if (availability[id] != WorkspaceAvailability.AVAILABLE) return
        selectedId = null
        message = null
        pendingId = id
    }

    fun close() {
        pendingId = null
        selectedId = null
        message = null
    }

    fun refresh() {
        close()
        availability.clear()
        revision += 1
    }

    fun completeOpen(id: String, result: WorkspaceAvailability) {
        if (pendingId != id) return
        availability[id] = result
        selectedId = id.takeIf { result == WorkspaceAvailability.AVAILABLE }
        message = when (result) {
            WorkspaceAvailability.AVAILABLE -> null
            WorkspaceAvailability.DENIED -> "Access is no longer available."
            else -> "The source could not be reached. Refresh and try again."
        }
        pendingId = null
    }
}

private suspend fun checkNativeWorkspace(workspace: NativeOperationalWorkspace): WorkspaceAvailability =
    try {
        if (workspace.canReadSource()) WorkspaceAvailability.AVAILABLE else WorkspaceAvailability.DENIED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: HiltechApiException) {
        workspaceFailureAvailability(failure.httpStatus)
    } catch (_: Exception) {
        WorkspaceAvailability.UNAVAILABLE
    }

@Composable
internal fun rememberNativeWorkspaceSession(
    scope: String,
    authorityRevision: String,
    workspaces: List<NativeOperationalWorkspace>,
): NativeWorkspaceSession {
    val session = remember(scope, authorityRevision) { NativeWorkspaceSession() }
    val eligible = uniqueNativeWorkspaces(workspaces)
    val ids = eligible.map { it.id }
    LaunchedEffect(session, ids, session.revision) {
        session.close()
        session.availability.clear()
        if (scope.isBlank()) return@LaunchedEffect
        eligible.forEach { session.availability[it.id] = WorkspaceAvailability.CHECKING }
        coroutineScope {
            eligible.forEach { workspace ->
                launch { session.availability[workspace.id] = checkNativeWorkspace(workspace) }
            }
        }
    }
    LaunchedEffect(session, ids, session.pendingId) {
        val id = session.pendingId ?: return@LaunchedEffect
        val workspace = eligible.firstOrNull { it.id == id }
        val result = if (workspace == null || scope.isBlank()) WorkspaceAvailability.DENIED
            else checkNativeWorkspace(workspace)
        session.completeOpen(id, result)
    }
    return session
}

@Composable
internal fun NativeWorkspaceDirectory(
    workspaces: List<NativeOperationalWorkspace>,
    session: NativeWorkspaceSession,
    onOpen: (String) -> Unit,
) {
    val colors = LocalHiltechOperationalColors.current
    val eligible = uniqueNativeWorkspaces(workspaces)
    val visible = eligible.filter { session.availability[it.id] == WorkspaceAvailability.AVAILABLE }
    val checking = eligible.any { session.availability[it.id] == null || session.availability[it.id] == WorkspaceAvailability.CHECKING }
    val unavailable = eligible.count { session.availability[it.id] == WorkspaceAvailability.UNAVAILABLE }
    HiltechSectionHeading("OPERATIONS", "Connected workspaces", "Open a live workspace to continue your work.")
    visible.forEach { workspace ->
        Surface(
            onClick = { onOpen(workspace.id) },
            enabled = session.pendingId == null,
            shape = HiltechShape.medium,
            color = colors.surface,
            contentColor = colors.primaryText,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(workspace.label, style = MaterialTheme.typography.titleMedium)
                    Text(if (session.pendingId == workspace.id) "Checking source…" else "Open workspace",
                        color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                }
                Text("›", color = colors.accent, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
    if (checking || unavailable > 0 || visible.isEmpty() || session.message != null) {
        HiltechPanel(Modifier.fillMaxWidth()) {
            if (checking) Text("Checking connected sources…")
            if (!checking && visible.isEmpty()) Text("No connected workspaces are available to this account.")
            if (unavailable > 0) Text("Some sources are currently unavailable. Refresh to retry.")
            session.message?.let { Text(it) }
            TextButton(onClick = session::refresh, enabled = !checking && session.pendingId == null) {
                Text("Refresh workspaces")
            }
        }
    }
}

@Composable
internal fun NativeWorkspacePane(
    workspace: NativeOperationalWorkspace,
    scope: String,
    onExit: () -> Unit,
) {
    val colors = LocalHiltechOperationalColors.current
    Column(Modifier.fillMaxSize()) {
        Surface(color = colors.surface, contentColor = colors.primaryText, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onExit) { Text("‹ Explore") }
                Text(workspace.label, modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            key(scope, workspace.id) {
                if (workspace.hostOwnsScroll) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { workspace.content(onExit) }
                } else {
                    workspace.content(onExit)
                }
            }
        }
    }
}

/** Host supplies only configured routes and retains the original source-owned
 * API checks. Selecting a route never grants access or performs a command. */
data class NativeOperationArea(
    val id: String,
    val title: String,
    val description: String,
    val content: @Composable () -> Unit,
)

@Composable
fun NativeOperationAreas(title: String, description: String, areas: List<NativeOperationArea>) {
    val colors = LocalHiltechOperationalColors.current
    var requested by remember { mutableStateOf<String?>(null) }
    val current = areas.firstOrNull { it.id == requested }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (current == null) {
            HiltechSectionHeading("OPERATIONS", title, description)
            if (areas.isEmpty()) Text("لا توجد عمليات متاحة في إعدادات التشغيل الحالية.")
            areas.forEach { area ->
                Surface(onClick = { requested = area.id }, modifier = Modifier.fillMaxWidth(),
                    color = colors.surface, contentColor = colors.primaryText, shape = HiltechShape.medium) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(area.title, style = MaterialTheme.typography.titleMedium)
                            Text(area.description, color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("فتح", color = colors.accent, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                    Text(current.title, style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = { requested = null }) { Text("كل العمليات") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                areas.forEach { area ->
                    FilterChip(selected = current.id == area.id, onClick = { requested = area.id }, label = { Text(area.title) })
                }
            }
            key(current.id) { current.content() }
        }
    }
}
