package com.hiltech.shared.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.warehouse.WarehouseCommandCenterDto
import com.hiltech.shared.core.warehouse.WarehouseCommandCenterItemDto
import com.hiltech.shared.core.warehouse.WarehouseCommandCenterQueueDto
import com.hiltech.shared.core.warehouse.WarehouseProjectResourceReadinessQueueDto

/**
 * Review-only presentation candidates over the existing Phase 5 authoritative read model.
 * BASELINE remains the production default in WarehouseCommandCenterSection.
 * No new command, mutation, permission or stock semantics live here.
 */
enum class WarehouseCommandCenterPresentation {
    BASELINE,
    ACTION_FIRST,
    OBJECT_FIRST,
}

private data class WorkGroup(
    val label: String,
    val queue: WarehouseCommandCenterQueueDto,
)

@Composable
internal fun WarehouseCommandCenterCandidate(
    snapshot: WarehouseCommandCenterDto,
    presentation: WarehouseCommandCenterPresentation,
    onOpenStorageLocations: () -> Unit,
    onDrill: (String, String) -> Unit,
    onOpenWorkResource: (String, String) -> Unit,
) {
    val actionFirst = presentation == WarehouseCommandCenterPresentation.ACTION_FIRST
    val inventory = snapshot.inventory
    val groups = listOf(
        WorkGroup("المرتجعات المتوقعة والمتأخرة", snapshot.dueReturns),
        WorkGroup("معدات تحتاج مراجعة أو معايرة", snapshot.assetExceptions),
        WorkGroup("الاستلام والفروقات", snapshot.receivingQueue),
        WorkGroup("الجرد والتسويات", snapshot.stocktakeQueue),
        WorkGroup("استثناءات الاستيراد", snapshot.importExceptions),
        WorkGroup("الحجوزات", snapshot.activeReservations),
        WorkGroup("آخر حركات المخزون", snapshot.recentMovements),
    )
    val ordered = if (actionFirst) groups else listOf(groups[5], groups[6], groups[0], groups[1], groups[2], groups[3], groups[4])

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (actionFirst) "ما يحتاج تدخلك" else "المخزن والأصول",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(snapshot.warehouseName, style = MaterialTheme.typography.titleMedium)
            Text(
                "موقع: ${snapshot.warehouseCode}  ·  آخر تحديث من المصدر: ${snapshot.asOf}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        BoxWithConstraints {
            val wide = maxWidth >= 760.dp
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (actionFirst) QueueGroups(ordered.take(4), onDrill)
                        else {
                            WarehouseInventoryFacts(snapshot, onOpenStorageLocations)
                            QueueGroups(ordered.take(3), onDrill)
                        }
                    }
                    Column(Modifier.width(290.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        if (actionFirst) WarehouseInventoryFacts(snapshot, onOpenStorageLocations)
                        QueueGroups(ordered.drop(if (actionFirst) 4 else 3), onDrill)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (!actionFirst) WarehouseInventoryFacts(snapshot, onOpenStorageLocations)
                    QueueGroups(ordered.take(if (actionFirst) 3 else 2), onDrill)
                    if (actionFirst) WarehouseInventoryFacts(snapshot, onOpenStorageLocations)
                    QueueGroups(ordered.drop(if (actionFirst) 3 else 2), onDrill)
                }
            }
        }
        if (snapshot.projectResourceReadiness.visible) {
            WorkResourceGroup(snapshot.projectResourceReadiness, onOpenWorkResource)
        }
    }
}

@Composable
private fun QueueGroups(
    groups: List<WorkGroup>,
    onDrill: (String, String) -> Unit,
) {
    groups.forEach { group ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(group.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (group.queue.limited) "${group.queue.items.size}+" else group.queue.items.size.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (group.queue.items.isEmpty()) {
                Text("لا توجد عناصر في هذا القسم", style = MaterialTheme.typography.bodySmall)
            } else {
                group.queue.items.forEach { item ->
                    WorkItem(item, onDrill)
                    HorizontalDivider()
                }
            }
            if (group.queue.limited) {
                Text("القائمة جزئية؛ راجع المصدر للاطلاع على الباقي", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WorkItem(
    item: WarehouseCommandCenterItemDto,
    onDrill: (String, String) -> Unit,
) {
    var provenanceOpen by remember(item.sourceId) { mutableStateOf(false) }
    val hasDetail = item.drillType != null && item.drillId != null
    val readableStatus = when (item.state) {
        "OVERDUE" -> "متأخر"
        "ACTIVE" -> "نشط"
        "BLOCKED_CALIBRATION" -> "محجوب بسبب المعايرة"
        "RECEIVED_WITH_DISCREPANCY" -> "مستلم مع فرق"
        "PENDING" -> "في انتظار المراجعة"
        "NEEDS_REVIEW" -> "يحتاج مراجعة"
        "ISSUE" -> "صرف"
        else -> item.state.replace('_', ' ')
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                readableStatus,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.dueAt?.let {
                Text("الموعد: $it", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasDetail) {
                    OutlinedButton(
                        onClick = { onDrill(requireNotNull(item.drillType), requireNotNull(item.drillId)) },
                    ) { Text("افتح ${item.label}") }
                }
                OutlinedButton(onClick = { provenanceOpen = !provenanceOpen }) {
                    Text(if (provenanceOpen) "إخفاء مصدر الحالة" else "مصدر الحالة")
                }
            }
            if (provenanceOpen) {
                Text(
                    "الحالة الأصلية: ${item.state}\nالسبب: ${item.reasonCodes.joinToString()}\nالمصدر: ${item.sourceType}/${item.sourceId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val context = listOfNotNull(
                    item.projectId?.let { "مشروع: $it" },
                    item.siteId?.let { "موقع: $it" },
                    item.workOrderId?.let { "أمر عمل: $it" },
                )
                if (context.isNotEmpty()) Text(context.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WarehouseInventoryFacts(
    snapshot: WarehouseCommandCenterDto,
    onOpenStorageLocations: () -> Unit,
) {
    val inventory = snapshot.inventory
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("حالة الأرصدة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("الأصناف: ${inventory.stockItemCount}  ·  مواقع برصيد: ${inventory.balanceLineCount}")
        Text("رصيد صفري: ${inventory.zeroOnHandLineCount}  ·  متاح صفري: ${inventory.zeroAvailableLineCount}")
        if (!inventory.lowStockPolicyConfigured) {
            Text(
                "المخزون المنخفض غير محسوب؛ سياسة الحد الأدنى غير مفعلة",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onOpenStorageLocations) { Text("استعرض مواقع التخزين") }
    }
}

@Composable
private fun WorkResourceGroup(
    queue: WarehouseProjectResourceReadinessQueueDto,
    onOpenWorkResource: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("جاهزية الموارد لأوامر العمل", style = MaterialTheme.typography.titleMedium)
        if (queue.items.isEmpty()) {
            Text("لا توجد متطلبات موارد غير مكتملة")
        } else {
            queue.items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.workOrderCode + " · " + item.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "الحالة: ${item.state} · المورد: ${item.resourceKind} · السبب: ${item.reasonCode}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { onOpenWorkResource(item.workOrderId, item.requirementId) }) {
                        Text("افتح متطلب أمر العمل")
                    }
                }
                HorizontalDivider()
            }
        }
        if (queue.limited) Text("النتائج جزئية — افتح مصدر العمل للقائمة الكاملة")
    }
}
