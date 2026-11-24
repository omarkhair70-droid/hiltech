package com.hiltech.shared.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechShape
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.design.LocalHiltechOperationalColors

internal enum class ShellWorkspace(val label: String, val displayLabel: String) {
    Home("Home", "الرئيسية"),
    Work("Work", "أعمالي"),
    Inbox("Inbox", "الوارد"),
    Explore("Explore", "استكشف"),
    Projects("Projects", "المشاريع"),
    People("People", "الأفراد"),
    Warehouse("Warehouse", "المخازن"),
    Purchasing("Purchasing", "المشتريات"),
    Account("Account", "حسابي"),
}

private val primaryDestinations = listOf(ShellWorkspace.Home, ShellWorkspace.Work, ShellWorkspace.Inbox, ShellWorkspace.Explore)

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
    if (selected == null) {
        Column(
            Modifier.fillMaxSize().background(colors.canvas).verticalScroll(scroll).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { CompositionLocalProvider(LocalContentColor provides colors.primaryText) { content() } }
        return
    }

    val selectedTab = selected.takeIf(primaryDestinations::contains) ?: ShellWorkspace.Explore
    if (compact) {
        Column(Modifier.fillMaxSize().background(colors.canvas)) {
            Surface(color = colors.surface, contentColor = colors.primaryText) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    HiltechWordmark()
                    Spacer(Modifier.weight(1f))
                    Text(hiltechText(selected.displayLabel, selected.label), style = MaterialTheme.typography.labelLarge, color = colors.secondaryText)
                    if (ShellWorkspace.Account in destinations) {
                        TextButton(onClick = { onSelect(ShellWorkspace.Account) }) {
                            WorkspaceGlyph(ShellWorkspace.Account, colors.accent)
                            Spacer(Modifier.width(6.dp))
                            Text(hiltechText("حسابي", "Account"))
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.divider)
            if (boundedContent != null) {
                Box(Modifier.weight(1f).fillMaxWidth()) { boundedContent() }
            } else Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides colors.primaryText) { content() }
                if (selected == ShellWorkspace.Explore) {
                    val areas = destinations.filterNot(primaryDestinations::contains).filterNot { it == ShellWorkspace.Account }
                    if (areas.isNotEmpty()) {
                        Text(hiltechText("مساحات العمل", "Workspaces"), style = MaterialTheme.typography.titleMedium)
                        areas.forEach { workspace ->
                            WorkspaceDirectoryEntry(workspace) { onSelect(workspace) }
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.divider)
            NavigationBar(containerColor = colors.surface, contentColor = colors.primaryText) {
                primaryDestinations.filter(destinations::contains).forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { onSelect(tab) },
                        icon = { WorkspaceGlyph(tab, if (selectedTab == tab) colors.accent else colors.secondaryText) },
                        label = { Text(hiltechText(tab.displayLabel, tab.label)) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = colors.selected,
                            selectedTextColor = colors.primaryText,
                            unselectedTextColor = colors.secondaryText,
                        ),
                    )
                }
            }
        }
        return
    }

    Row(Modifier.fillMaxSize().background(colors.canvas)) {
        Surface(
            modifier = Modifier.width(212.dp).fillMaxHeight(),
            color = colors.surfaceElevated,
            contentColor = colors.primaryText,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
                HiltechWordmark(Modifier.padding(start = 8.dp, bottom = 18.dp))
                onFind?.let { open ->
                    Surface(
                        onClick = open,
                        color = colors.surface,
                        shape = HiltechShape.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            SearchGlyph(colors.secondaryText)
                            Text(hiltechText("بحث / أمر", "Search / Command"), Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.labelLarge)
                            Text("Ctrl K", color = colors.secondaryText, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("HILTECH OS", Modifier.padding(start = 10.dp, bottom = 5.dp),
                        color = colors.secondaryText, style = MaterialTheme.typography.labelSmall)
                    primaryDestinations.filter(destinations::contains).forEach { destination ->
                        WorkspaceRailEntry(destination, selected == destination, onSelect)
                    }
                    val areas = destinations.filterNot(primaryDestinations::contains).filterNot { it == ShellWorkspace.Account }
                    if (areas.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(hiltechText("مساحات العمل", "Workspaces"), Modifier.padding(start = 10.dp, bottom = 4.dp),
                            color = colors.secondaryText, style = MaterialTheme.typography.labelSmall)
                        areas.forEach { destination -> WorkspaceRailEntry(destination, selected == destination, onSelect) }
                    }
                }
                if (ShellWorkspace.Account in destinations) {
                    HorizontalDivider(color = colors.divider)
                    Spacer(Modifier.height(8.dp))
                    WorkspaceRailEntry(ShellWorkspace.Account, selected == ShellWorkspace.Account, onSelect)
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Surface(color = colors.surface, contentColor = colors.primaryText) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("HILTECH", style = MaterialTheme.typography.labelMedium, color = colors.secondaryText)
                    Text(" / ", color = colors.secondaryText)
                    Text(hiltechText(selected.displayLabel, selected.label), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    onFind?.let { TextButton(onClick = it) { SearchGlyph(colors.secondaryText); Spacer(Modifier.width(8.dp)); Text(hiltechText("بحث", "Search")) } }
                }
            }
            HorizontalDivider(color = colors.divider)
            if (boundedContent != null) {
                Box(Modifier.weight(1f).fillMaxWidth()) { boundedContent() }
            } else Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides colors.primaryText) { content() }
            }
        }
    }
}

@Composable
private fun HiltechWordmark(modifier: Modifier = Modifier) {
    val colors = LocalHiltechOperationalColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(color = colors.accent, shape = HiltechShape.small) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(16.dp)) {
                    val ink = colors.canvas
                    val w = size.width
                    val h = size.height
                    drawLine(ink, Offset(w*.16f,h*.12f),Offset(w*.16f,h*.88f),2.dp.toPx())
                    drawLine(ink, Offset(w*.84f,h*.12f),Offset(w*.84f,h*.88f),2.dp.toPx())
                    drawLine(ink, Offset(w*.16f,h*.5f),Offset(w*.84f,h*.5f),2.dp.toPx())
                    drawCircle(ink, 2.dp.toPx(), Offset(w*.5f,h*.5f))
                }
            }
        }
        Text("HILTECH", color = colors.primaryText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun WorkspaceRailEntry(destination: ShellWorkspace, selected: Boolean, onSelect: (ShellWorkspace) -> Unit) {
    val colors = LocalHiltechOperationalColors.current
    Surface(color = if (selected) colors.selected else colors.surfaceElevated, shape = HiltechShape.small) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination) })
                .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WorkspaceGlyph(destination, if (selected) colors.accent else colors.secondaryText)
            Text(hiltechText(destination.displayLabel, destination.label), Modifier.weight(1f), color = colors.primaryText,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium)
            if (selected) Box(Modifier.width(3.dp).height(18.dp).background(colors.accent, HiltechShape.small))
        }
    }
}

@Composable
private fun WorkspaceDirectoryEntry(destination: ShellWorkspace, onClick: () -> Unit) {
    val colors = LocalHiltechOperationalColors.current
    Surface(onClick = onClick, color = colors.surface, shape = HiltechShape.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkspaceGlyph(destination, colors.accent)
            Text(hiltechText(destination.displayLabel, destination.label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text("‹", color = colors.secondaryText)
        }
    }
}

@Composable
private fun SearchGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        drawCircle(color, w*.27f, Offset(w*.4f,w*.4f), style = Stroke(1.5.dp.toPx()))
        drawLine(color, Offset(w*.6f,w*.6f), Offset(w*.86f,w*.86f), 1.5.dp.toPx())
    }
}

@Composable
internal fun WorkspaceGlyph(workspace: ShellWorkspace, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        fun line(x1: Float,y1: Float,x2: Float,y2: Float) = drawLine(color,Offset(w*x1,h*y1),Offset(w*x2,h*y2),stroke)
        fun rect(x: Float,y: Float,ww: Float,hh: Float) = drawRect(color,Offset(w*x,h*y),Size(w*ww,h*hh),style=Stroke(stroke))
        fun dot(x: Float,y: Float,r: Float) = drawCircle(color,w*r,Offset(w*x,h*y),style=Stroke(stroke))
        when (workspace) {
            ShellWorkspace.Home -> { line(.1f,.45f,.5f,.12f);line(.5f,.12f,.9f,.45f);rect(.22f,.45f,.56f,.45f) }
            ShellWorkspace.Work -> { rect(.14f,.22f,.72f,.66f);line(.35f,.22f,.35f,.12f);line(.65f,.22f,.65f,.12f);line(.35f,.12f,.65f,.12f);line(.14f,.48f,.86f,.48f) }
            ShellWorkspace.Inbox -> { rect(.12f,.18f,.76f,.66f);line(.12f,.53f,.34f,.53f);line(.34f,.53f,.42f,.67f);line(.42f,.67f,.58f,.67f);line(.58f,.67f,.66f,.53f);line(.66f,.53f,.88f,.53f) }
            ShellWorkspace.Explore -> { rect(.13f,.13f,.28f,.28f);rect(.59f,.13f,.28f,.28f);rect(.13f,.59f,.28f,.28f);rect(.59f,.59f,.28f,.28f) }
            ShellWorkspace.Projects -> { rect(.12f,.28f,.76f,.54f);line(.12f,.28f,.12f,.17f);line(.12f,.17f,.42f,.17f);line(.42f,.17f,.53f,.28f) }
            ShellWorkspace.People -> { dot(.38f,.3f,.16f);dot(.75f,.35f,.12f);line(.13f,.84f,.13f,.64f);line(.13f,.64f,.63f,.64f);line(.63f,.64f,.63f,.84f);line(.7f,.65f,.88f,.65f);line(.88f,.65f,.88f,.84f) }
            ShellWorkspace.Warehouse -> { line(.1f,.4f,.5f,.14f);line(.5f,.14f,.9f,.4f);rect(.16f,.4f,.68f,.46f);rect(.34f,.56f,.32f,.3f);line(.34f,.71f,.66f,.71f) }
            ShellWorkspace.Purchasing -> { line(.08f,.16f,.22f,.16f);line(.22f,.16f,.32f,.62f);line(.32f,.62f,.8f,.62f);line(.8f,.62f,.88f,.3f);line(.25f,.3f,.88f,.3f);dot(.38f,.82f,.08f);dot(.75f,.82f,.08f) }
            ShellWorkspace.Account -> { dot(.5f,.3f,.18f);line(.2f,.87f,.2f,.7f);line(.2f,.7f,.35f,.56f);line(.35f,.56f,.65f,.56f);line(.65f,.56f,.8f,.7f);line(.8f,.7f,.8f,.87f) }
        }
    }
}
