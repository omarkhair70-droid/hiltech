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
