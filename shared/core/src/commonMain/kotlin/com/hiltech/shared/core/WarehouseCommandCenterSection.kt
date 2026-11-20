package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.warehouse.WarehouseCommandCenterDto
import com.hiltech.shared.core.warehouse.WarehouseCommandCenterQueueDto
import com.hiltech.shared.core.warehouse.WarehouseProjectResourceReadinessQueueDto

@Composable
fun WarehouseCommandCenterSection(snapshot: WarehouseCommandCenterDto) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("مركز عمليات المخزن", style = MaterialTheme.typography.titleLarge)
        Text(snapshot.warehouseCode + " · " + snapshot.warehouseName + " · " + snapshot.asOf)
        val inventory = snapshot.inventory
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("أصناف بمخزون · " + inventory.stockItemCount)
            Text("مواقع رصيد · " + inventory.balanceLineCount)
            Text("رصيد صفري · " + inventory.zeroOnHandLineCount)
            Text("متاح صفري · " + inventory.zeroAvailableLineCount)
        }
        if (!inventory.lowStockPolicyConfigured) {
            Text("Low stock: غير محسوب — " + (inventory.lowStockPolicyReason ?: "POLICY_UNAVAILABLE"))
        }
        Queue("مرتجعات متوقعة / متأخرة", snapshot.dueReturns)
        Queue("حجوزات فعالة", snapshot.activeReservations)
        ProjectResourceReadiness(snapshot.projectResourceReadiness)
        Queue("أصول محجوبة / صيانة / معايرة", snapshot.assetExceptions)
        Queue("الاستلام", snapshot.receivingQueue)
        Queue("الجرد / التسويات", snapshot.stocktakeQueue)
        Queue("استثناءات الاستيراد", snapshot.importExceptions)
        Queue("آخر الحركات", snapshot.recentMovements)
    }
}

@Composable
private fun Queue(title: String, queue: WarehouseCommandCenterQueueDto) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (queue.items.isEmpty()) {
        Text("لا توجد عناصر حالية.")
    } else {
        queue.items.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.label + " · " + item.state)
                Text(item.reasonCodes.joinToString() + " · source " + item.sourceType + "/" + item.sourceId)
                item.dueAt?.let { Text("Due · " + it) }
                val context = listOfNotNull(
                    item.projectId?.let { "Project/$it" },
                    item.siteId?.let { "Site/$it" },
                    item.workOrderId?.let { "Work/$it" },
                )
                if (context.isNotEmpty()) Text(context.joinToString(" · "))
            }
        }
        if (queue.limited) Text("عرض أول 50 عنصرًا — افتح المصدر للتفاصيل الكاملة.")
    }
}


@Composable
private fun ProjectResourceReadiness(queue: WarehouseProjectResourceReadinessQueueDto) {
    Text("جاهزية موارد المشاريع / أوامر العمل", style = MaterialTheme.typography.titleMedium)
    if (!queue.visible) {
        Text("غير متاحة لهذه الهوية — صلاحية حجز موارد المخزن مطلوبة.")
        return
    }
    if (queue.items.isEmpty()) {
        Text("لا توجد متطلبات موارد غير مكتملة مرتبطة بهذا المخزن.")
        return
    }
    queue.items.forEach { item ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.workOrderCode + " · " + item.title + " · " + item.state)
            Text(
                item.resourceKind + " · " + item.requirementKey + " · " + item.reasonCode +
                    " · candidates " + item.candidateCount,
            )
            Text(
                "requirement " + item.requirementId +
                    (item.factSourceId?.let { " · fact source $it" } ?: "") +
                    (item.factSourceVersion?.let { " v$it" } ?: "") +
                    " · asOf " + item.sourceAsOf,
            )
            Text("Project/" + item.projectId + " · Site/" + item.siteId + " · Work/" + item.workOrderId)
        }
    }
    if (queue.limited) Text("عرض أول 50 متطلب موارد — افتح أمر العمل للتفاصيل الكاملة.")
}
