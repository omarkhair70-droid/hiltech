package com.hiltech.shared.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Host-provided, source-authorized route. This is neither a new role grant nor
 * a domain API: the caller performs a current authenticated source read in
 * [canReadSource], and [content] uses its original API and server permissions.
 *
 * Host-owned scrolling is for legacy non-scrolling sections only. Sections
 * with their own .verticalScroll must remain the sole scroll owner.
 */
data class NativeOperationalWorkspace(
    val id: String,
    val label: String,
    val canReadSource: suspend () -> Boolean,
    val hostOwnsScroll: Boolean = false,
    val content: @Composable (onExit: () -> Unit) -> Unit,
)

/** A flag or successful sign-in alone must never reveal a restricted route. */
fun nativeWorkspaceMayBeShown(
    featureEnabled: Boolean,
    authenticated: Boolean,
    currentSourceGrant: Boolean,
): Boolean = featureEnabled && authenticated && currentSourceGrant

/**
 * A bounded dialog is an independent layout root, NOT a vertically nested
 * child of HiltechShell's existing unbounded scrolling Column. This avoids
 * the P9/P10/P11/P13 Compose infinite-height nested-scroll failure.
 *
 * The workspace is only visible after a successful, current actor/org source
 * preflight. Revocation or a source failure hides the route on the next read.
 * Original business operations still recheck server authority independently.
 */
@Composable
fun NativeOperationalWorkspaceHub(
    scopeKey: String,
    authorityRevisionKey: String,
    workspaces: List<NativeOperationalWorkspace>,
) {
    // An ambiguous route key must never select a different privileged pane.
    val eligible = workspaces.groupBy { it.id }
        .filter { (id, entries) -> id.isNotBlank() && entries.size == 1 }
        .values.map { it.single() }
    val granted = remember(scopeKey, authorityRevisionKey) {
        mutableStateMapOf<String, Boolean>()
    }
    var selectedId by remember(scopeKey, authorityRevisionKey) {
        mutableStateOf<String?>(null)
    }
    var revision by remember(scopeKey, authorityRevisionKey) { mutableStateOf(0) }
    var pendingOpen by remember(scopeKey, authorityRevisionKey) {
        mutableStateOf<String?>(null)
    }
    var checking by remember(scopeKey, authorityRevisionKey) { mutableStateOf(true) }
    var accessMessage by remember(scopeKey, authorityRevisionKey) {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(scopeKey, authorityRevisionKey, eligible.map { it.id }, revision) {
        checking = true
        accessMessage = null
        granted.clear()
        // Independent source reads: a slow P11 project/contract check
        // must not block an already-authorized Payroll, P7 or Executive tab.
        coroutineScope {
            eligible.map { workspace ->
                launch {
                    val allowed = try {
                        workspace.canReadSource()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    granted[workspace.id] = allowed
                }
            }.joinAll()
        }
        checking = false
    }

    LaunchedEffect(scopeKey, authorityRevisionKey, pendingOpen) {
        val requested = pendingOpen ?: return@LaunchedEffect
        val workspace = eligible.firstOrNull { it.id == requested }
        val allowed = if (workspace == null) false else try {
            workspace.canReadSource()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        granted[requested] = allowed
        selectedId = if (allowed) requested else null
        accessMessage = if (allowed) null else
            "تعذر تأكيد الصلاحية الحالية لهذه المساحة؛ راجع حسابك أو حدّث المصادر."
        pendingOpen = null
    }

    val visible = eligible.filter {
        nativeWorkspaceMayBeShown(true, scopeKey.isNotBlank(), granted[it.id] == true)
    }
    val selected = visible.firstOrNull { it.id == selectedId }

    if (eligible.isNotEmpty()) {
        Text("مساحات متخصصة / Specialist workspaces",
            style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visible.forEach { workspace ->
                OutlinedButton(enabled = pendingOpen == null, onClick = {
                    // A current source read is mandatory even if the button
                    // was visible from an earlier successful read.
                    selectedId = null
                    pendingOpen = workspace.id
                }) { Text(workspace.label) }
            }
            OutlinedButton(enabled = !checking && pendingOpen == null, onClick = {
                selectedId = null
                pendingOpen = null
                accessMessage = null
                revision += 1
            }) {
                Text(if (checking) "جارٍ التحقق" else "تحديث الصلاحيات")
            }
        }
        when {
            checking || pendingOpen != null ->
                Text("جارٍ التحقق من المساحات والصلاحيات الحالية…",
                    style = MaterialTheme.typography.bodySmall)
            visible.isEmpty() ->
                Text("لا توجد مساحات متخصصة متاحة لهذا الحساب الآن؛ يمكن تحديث المصدر.",
                    style = MaterialTheme.typography.bodySmall)
        }
        accessMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (selected != null) {
        Dialog(
            onDismissRequest = { selectedId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 1000.dp).fillMaxWidth(0.97f).fillMaxHeight(0.95f),
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            selected.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedButton(onClick = { selectedId = null }) {
                            Text("رجوع")
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        key(scopeKey, selected.id) {
                            if (selected.hostOwnsScroll) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    selected.content { selectedId = null }
                                }
                            } else {
                                selected.content { selectedId = null }
                            }
                        }
                    }
                }
            }
        }
    }
}
