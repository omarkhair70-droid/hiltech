package com.hiltech.shared.core

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.LocalHiltechOperationalColors

internal enum class ShellWorkspace(val label: String) {
    Home("Home"),
    Work("Work"),
    Inbox("Inbox"),
    Explore("Explore"),
    Projects("Projects"),
    People("People"),
    Warehouse("Warehouse"),
    Purchasing("Purchasing"),
    Account("Account"),
}

@Composable
internal fun NativeWorkspaceNavigation(
    selected: ShellWorkspace?,
    destinations: List<ShellWorkspace>,
    scroll: ScrollState,
    compact: Boolean,
    onSelect: (ShellWorkspace) -> Unit,
    onFind: (() -> Unit)? = null,
    boundedContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalHiltechOperationalColors.current
    if (compact && selected != null) {
        val primary = listOf(ShellWorkspace.Home, ShellWorkspace.Work, ShellWorkspace.Inbox, ShellWorkspace.Explore)
        val selectedTab = selected.takeIf(primary::contains) ?: ShellWorkspace.Explore
        Column(Modifier.fillMaxSize().background(colors.canvas)) {
            if (boundedContent != null) {
                Box(Modifier.weight(1f).fillMaxWidth()) { boundedContent() }
            } else Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides colors.primaryText) { content() }
                if (selected == ShellWorkspace.Explore) {
                    destinations.filterNot(primary::contains).forEach { workspace ->
                        TextButton(onClick = { onSelect(workspace) }, modifier = Modifier.fillMaxWidth()) {
                            Text(workspace.label)
                        }
                    }
                }
            }
            NavigationBar(containerColor = colors.surfaceElevated, contentColor = colors.primaryText) {
                primary.filter(destinations::contains).forEach { tab ->
                    if (tab == selectedTab) {
                        FilledTonalButton(onClick = { onSelect(tab) }, modifier = Modifier.weight(1f)) { Text(tab.label) }
                    } else {
                        TextButton(onClick = { onSelect(tab) }, modifier = Modifier.weight(1f)) { Text(tab.label) }
                    }
                }
            }
        }
        return
    }

    Row(Modifier.fillMaxSize().background(colors.canvas)) {
        if (selected != null) {
            Surface(
                modifier = Modifier.width(176.dp).fillMaxHeight(),
                color = colors.surfaceElevated,
                contentColor = colors.primaryText,
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "HILTECH",
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 16.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    onFind?.let { open ->
                        TextButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                            Text("Find · Ctrl+K")
                        }
                    }
                    destinations.forEach { destination ->
                        if (destination == selected) {
                            FilledTonalButton(
                                onClick = { onSelect(destination) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(destination.label) }
                        } else {
                            TextButton(
                                onClick = { onSelect(destination) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(destination.label) }
                        }
                    }
                }
            }
        }
        if (boundedContent != null) {
            Box(Modifier.weight(1f).fillMaxHeight()) { boundedContent() }
        } else Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides colors.primaryText) { content() }
        }
    }
}
