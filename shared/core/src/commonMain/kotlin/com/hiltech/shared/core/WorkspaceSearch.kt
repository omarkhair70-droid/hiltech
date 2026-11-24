package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Alignment
import androidx.compose.material3.TextButton
import com.hiltech.shared.core.design.hiltechText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hiltech.shared.core.design.HiltechShape
import com.hiltech.shared.core.design.LocalHiltechOperationalColors

internal data class WorkspaceSearchResult(
    val key: String,
    val title: String,
    val context: String,
    val open: () -> Unit,
)

@Composable
internal fun WorkspaceSearch(
    commands: List<WorkspaceSearchResult>,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var highlighted by remember { mutableIntStateOf(0) }
    val term = query.trim()
    val matching = commands.filter { result ->
        if (term.isEmpty()) result.key.startsWith("workspace:") || result.key.startsWith("specialist:")
        else result.title.contains(term, ignoreCase = true) || result.context.contains(term, ignoreCase = true)
    }
    val results = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val colors = LocalHiltechOperationalColors.current

    fun choose(result: WorkspaceSearchResult) {
        onDismiss()
        result.open()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        LaunchedEffect(highlighted, matching) {
            if (highlighted in matching.indices) results.scrollToItem(highlighted)
        }
        Surface(
            modifier = Modifier.widthIn(max = 580.dp).fillMaxWidth(0.92f),
            shape = HiltechShape.large,
            color = colors.surface,
            contentColor = colors.primaryText,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || matching.isEmpty()) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            highlighted = (highlighted + 1).coerceAtMost(matching.lastIndex)
                            true
                        }
                        Key.DirectionUp -> {
                            highlighted = (highlighted - 1).coerceAtLeast(0)
                            true
                        }
                        Key.Enter -> matching.getOrNull(highlighted)?.let {
                            choose(it)
                            true
                        } ?: false
                        else -> false
                    }
                },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(hiltechText("انتقل إلى أي مساحة", "Go to a workspace"), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Esc") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        highlighted = 0
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = true,
                    label = { Text(hiltechText("ابحث عن مساحة أو مشروع أو موظف", "Find a workspace, project or employee")) },
                )
                if (matching.isEmpty()) Text(hiltechText("لا توجد نتائج لهذا البحث", "No results for this search"))
                Text(hiltechText("النتائج المتاحة: ", "Available results: ") + matching.size,
                    style = MaterialTheme.typography.labelMedium, color = colors.secondaryText)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    state = results,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    itemsIndexed(matching, key = { _, item -> item.key }) { index, item ->
                        Surface(
                            onClick = { choose(item) },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (highlighted == index) colors.selected else colors.surface,
                            contentColor = colors.primaryText,
                            shape = HiltechShape.small,
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                                    Text(item.context, style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                                }
                                if (highlighted == index) Text("Enter", color = colors.accent, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
