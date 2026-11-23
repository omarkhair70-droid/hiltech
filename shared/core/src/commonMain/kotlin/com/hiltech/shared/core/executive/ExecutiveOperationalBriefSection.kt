package com.hiltech.shared.core.executive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * No new network request and no durable cache: the brief is a compact lens
 * on the currently rendered, already actor/org-checked Home response.
 */
@Composable
fun ExecutiveOperationalBriefSection(
    home: ExecutiveHomeDto,
    onOpenSource: (ExecutiveDestination) -> Unit,
) {
    val brief = remember(home) { buildExecutiveOperationalBrief(home) }
    var expanded by remember(home.viewerIdentityId, home.organizationId, home.correlationId, home.asOf) {
        mutableStateOf(false)
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("موجز التشغيل", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "قراءة محدودة من نفس المصادر والصلاحيات — بلا إجماليات شركة أو تنبؤات.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "إخفاء" else "عرض")
                }
            }
            if (expanded) {
                Text(
                    "القرارات تخص المستخدم الحالي عبر العضويات المسموح بها، وليست قرارات الشركة كلها. " +
                        "كل بطاقة تحتاج إعادة تحقق من المصدر عند فتحها.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                brief.lanes.forEach { lane ->
                    HorizontalDivider()
                    Text(lane.label, style = MaterialTheme.typography.titleSmall)
                    lane.sourceAsOf?.let { at ->
                        Text(
                            "وقت لقطة المصدر: " + at,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (lane.sourceStatus !in setOf("CURRENT", "PARTIAL")) {
                        Text(
                            "مصدر هذه البيانات غير متاح حاليًا؛ لا نعرض نتائج محفوظة أو صفرًا مفترضًا.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        if (lane.entries.isEmpty()) {
                            Text(
                                "لم تظهر عناصر في نافذة القراءة الحالية؛ لا يعني ذلك خلو الشركة منها.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        lane.entries.forEach { item ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                                if (item.priority == ExecutiveBriefPriority.SOURCE_CRITICAL) {
                                    Text(
                                        "حرج وفق تقييم المصدر",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else if (item.priority == ExecutiveBriefPriority.UNVERIFIED) {
                                    Text(
                                        "حالة المصدر غير مؤكدة",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(item.detail, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "المصدر: " + item.sourceType + " / " + item.sourceId +
                                        " · النسخة: " + (item.sourceVersion?.toString() ?: "غير متاحة") +
                                        " · تاريخ المصدر: " + (item.observedAt ?: "غير متاح"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                item.destination?.let { destination ->
                                    OutlinedButton(onClick = { onOpenSource(destination) }) {
                                        Text("راجع في النظام الأصلي")
                                    }
                                }
                            }
                        }
                    }
                    if (lane.extraVisibleItems) {
                        Text(
                            "هناك عناصر أخرى ظاهرة في الصفحة الأصلية؛ هذا الموجز يعرض أول ثلاث فقط.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (lane.incomplete) {
                        Text(
                            "تغطية المصدر جزئية أو غير مؤكدة — لا تُفسّر غياب العناصر على أنه لا توجد مخاطر.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "آخر تجميع: " + brief.assembledAt + " · لا يتم إرسال هذا الموجز تلقائيًا.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
