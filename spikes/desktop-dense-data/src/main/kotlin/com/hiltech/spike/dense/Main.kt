package com.hiltech.spike.dense

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay

private const val ROW_COUNT = 50_000

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "HILTECH Dense Desktop Spike",
    ) {
        DensePayrollApp(
            onRendered = {
                if (System.getenv("HILTECH_SPIKE_AUTOCLOSE") == "1") {
                    println("HILTECH_DENSE_UI_RENDER_PASS rows=$ROW_COUNT")
                    exitApplication()
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DensePayrollApp(
    onRendered: () -> Unit,
) {
    val source = remember { generatePayrollRows(ROW_COUNT) }

    var query by remember { mutableStateOf("") }
    var exceptionsOnly by remember { mutableStateOf(false) }
    var sortVarianceDescending by remember { mutableStateOf(true) }
    var isRtl by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<Int?>(1) }
    var bulkSelected by remember { mutableStateOf<Set<Int>>(emptySet()) }

    val visible = remember(query, exceptionsOnly, sortVarianceDescending) {
        applyDenseQuery(
            source = source,
            query = query,
            exceptionsOnly = exceptionsOnly,
            sortVarianceDescending = sortVarianceDescending,
        )
    }

    val selected = remember(selectedId, visible) {
        selectedId?.let { id -> visible.firstOrNull { it.id == id } }
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(250)
        onRendered()
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        MaterialTheme {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || visible.isEmpty()) {
                            return@onPreviewKeyEvent false
                        }

                        val currentIndex = visible.indexOfFirst { it.id == selectedId }
                            .let { if (it < 0) 0 else it }

                        when (event.key) {
                            Key.DirectionDown -> {
                                selectedId = visible[(currentIndex + 1).coerceAtMost(visible.lastIndex)].id
                                true
                            }

                            Key.DirectionUp -> {
                                selectedId = visible[(currentIndex - 1).coerceAtLeast(0)].id
                                true
                            }

                            Key.Spacebar -> {
                                selectedId?.let { id ->
                                    bulkSelected = if (id in bulkSelected) {
                                        bulkSelected - id
                                    } else {
                                        bulkSelected + id
                                    }
                                }
                                true
                            }

                            else -> false
                        }
                    },
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        DenseToolbar(
                            query = query,
                            onQuery = { query = it },
                            exceptionsOnly = exceptionsOnly,
                            onExceptionsOnly = { exceptionsOnly = it },
                            sortVarianceDescending = sortVarianceDescending,
                            onSortVariance = { sortVarianceDescending = !sortVarianceDescending },
                            isRtl = isRtl,
                            onToggleRtl = { isRtl = !isRtl },
                            visibleCount = visible.size,
                            selectedCount = bulkSelected.size,
                        )

                        DenseHeader()

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(
                                items = visible,
                                key = { it.id },
                            ) { row ->
                                DenseRow(
                                    row = row,
                                    selected = row.id == selectedId,
                                    checked = row.id in bulkSelected,
                                    onClick = { selectedId = row.id },
                                    onCheck = {
                                        bulkSelected = if (row.id in bulkSelected) {
                                            bulkSelected - row.id
                                        } else {
                                            bulkSelected + row.id
                                        }
                                    },
                                )
                                Divider()
                            }
                        }
                    }

                    Inspector(
                        selected = selected,
                        bulkSelectedCount = bulkSelected.size,
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
private fun DenseToolbar(
    query: String,
    onQuery: (String) -> Unit,
    exceptionsOnly: Boolean,
    onExceptionsOnly: (Boolean) -> Unit,
    sortVarianceDescending: Boolean,
    onSortVariance: () -> Unit,
    isRtl: Boolean,
    onToggleRtl: () -> Unit,
    visibleCount: Int,
    selectedCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("HILTECH Payroll", fontWeight = FontWeight.Bold)

        Box(
            modifier = Modifier
                .width(260.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.isEmpty()) {
                Text("Search employee / project")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = exceptionsOnly,
                onCheckedChange = onExceptionsOnly,
            )
            Text("Exceptions")
        }

        Button(onClick = onSortVariance) {
            Text(if (sortVarianceDescending) "Variance ↓" else "Code ↑")
        }

        Button(onClick = onToggleRtl) {
            Text(if (isRtl) "LTR" else "RTL")
        }

        Text("$visibleCount rows")
        Text("$selectedCount selected")
    }
}

@Composable
private fun DenseHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✓", modifier = Modifier.width(42.dp))
        Text("Employee", modifier = Modifier.width(190.dp), fontWeight = FontWeight.Bold)
        Text("Project", modifier = Modifier.width(120.dp), fontWeight = FontWeight.Bold)
        Text("Base", modifier = Modifier.width(100.dp), fontWeight = FontWeight.Bold)
        Text("OT", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold)
        Text("Deduct", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold)
        Text("Net", modifier = Modifier.width(100.dp), fontWeight = FontWeight.Bold)
        Text("Variance", modifier = Modifier.width(100.dp), fontWeight = FontWeight.Bold)
        Text("State", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DenseRow(
    row: PayrollRow,
    selected: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onCheck: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheck() },
            modifier = Modifier.width(42.dp),
        )
        Column(modifier = Modifier.width(190.dp)) {
            Text(row.employeeName)
            Text(row.employeeCode, style = MaterialTheme.typography.labelSmall)
        }
        Text(row.project, modifier = Modifier.width(120.dp))
        Text(row.baseSalary.toString(), modifier = Modifier.width(100.dp))
        Text(row.overtime.toString(), modifier = Modifier.width(90.dp))
        Text(row.deductions.toString(), modifier = Modifier.width(90.dp))
        Text(row.netPay.toString(), modifier = Modifier.width(100.dp))
        Text(row.variance.toString(), modifier = Modifier.width(100.dp))
        Text(row.status, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Inspector(
    selected: PayrollRow?,
    bulkSelectedCount: Int,
) {
    Column(
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Inspector", style = MaterialTheme.typography.titleMedium)
        Text("$bulkSelectedCount bulk selected")

        if (selected == null) {
            Text("No employee selected")
            return
        }

        Text(selected.employeeName, fontWeight = FontWeight.Bold)
        Text(selected.employeeCode)
        Text(selected.project)
        Divider()
        Text("Base: ${selected.baseSalary}")
        Text("Overtime: ${selected.overtime}")
        Text("Deductions: ${selected.deductions}")
        Text("Net: ${selected.netPay}")
        Text("Variance: ${selected.variance}")
        Text("State: ${selected.status}")
    }
}
