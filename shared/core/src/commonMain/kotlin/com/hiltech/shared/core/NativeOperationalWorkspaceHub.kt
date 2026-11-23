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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException

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
    val content: @Composable () -> Unit,
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
    var checking by remember(scopeKey, authorityRevisionKey) { mutableStateOf(true) }

    LaunchedEffect(scopeKey, authorityRevisionKey, eligible.map { it.id }, revision) {
        checking = true
        granted.clear()
        for (workspace in eligible) {
            val allowed = try {
                workspace.canReadSource()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            granted[workspace.id] = allowed
        }
        checking = false
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
                OutlinedButton(onClick = {
                    // On open, immediately revoke the previous grant and redo
                    // source preflight; stale navigation cannot grant access.
                    selectedId = null
                    revision += 1
                    // Route selection is set only after the new read below.
                }) { Text(workspace.label) }
            }
            OutlinedButton(enabled = !checking, onClick = { selectedId = null; revision += 1 }) {
                Text(if (checking) "جارٍ التحقق" else "تحديث الصلاحيات")
            }
        }
    }

    if (selected != null) {
        Dialog(
            onDismissRequest = { selectedId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.95f),
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { selectedId = null }) {
                            Text("الرجوع لمساحات العمل")
                        }
                        Text(selected.label, style = MaterialTheme.typography.titleMedium)
                    }
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        key(scopeKey, selected.id) {
                            if (selected.hostOwnsScroll) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    selected.content()
                                }
                            } else {
                                selected.content()
                            }
                        }
                    }
                }
            }
        }
    }
}
