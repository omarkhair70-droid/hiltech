package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Source-neutral presentation focus for the already-authenticated Warehouse operator.
 * This does not grant access, load new facts, issue stock or emulate a camera scanner.
 * The caller retains existing Phase 5 source API, permission, failure and drill handlers.
 *
 * REVIEW ONLY: called only when the explicit presentation experiment is enabled.
 * The production WarehousePhysicalOperatorSection still defaults to its old long-flow UI.
 */
enum class WarehouseOperatorFocus {
    TODAY,
    CODE_LOOKUP,
}

@Composable
fun WarehouseOperatorFocusFlow(
    warehouseId: String,
    busy: Boolean,
    initialFocus: WarehouseOperatorFocus = WarehouseOperatorFocus.TODAY,
    onFocusChange: (WarehouseOperatorFocus) -> Unit = {},
    today: @Composable () -> Unit,
    codeLookup: @Composable () -> Unit,
) {
    var focus by remember(warehouseId) { mutableStateOf(initialFocus) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "المخزن · مهام وبحث",
            style = MaterialTheme.typography.titleLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (focus == WarehouseOperatorFocus.TODAY) {
                Button(
                    enabled = !busy,
                    onClick = {
                        focus = WarehouseOperatorFocus.TODAY
                        onFocusChange(focus)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "مهام المخزن"
                        stateDescription = "القسم الحالي"
                    },
                ) { Text("المهام") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        focus = WarehouseOperatorFocus.CODE_LOOKUP
                        onFocusChange(focus)
                    },
                    modifier = Modifier.semantics { contentDescription = "الانتقال للبحث بكود الأصل أو المخزون" },
                ) { Text("بحث بالكود") }
            } else {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        focus = WarehouseOperatorFocus.TODAY
                        onFocusChange(focus)
                    },
                    modifier = Modifier.semantics { contentDescription = "العودة إلى مهام المخزن" },
                ) { Text("المهام") }
                Button(
                    enabled = !busy,
                    onClick = {
                        focus = WarehouseOperatorFocus.CODE_LOOKUP
                        onFocusChange(focus)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "البحث بالكود"
                        stateDescription = "القسم الحالي"
                    },
                ) { Text("بحث بالكود") }
            }
        }
        when (focus) {
            WarehouseOperatorFocus.TODAY -> today()
            WarehouseOperatorFocus.CODE_LOOKUP -> codeLookup()
        }
    }
}
