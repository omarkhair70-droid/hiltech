package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.executive.ExecutiveDestination
import com.hiltech.shared.core.executive.ExecutiveHomeDto
import com.hiltech.shared.core.executive.ExecutiveSectionDto
import com.hiltech.shared.core.executive.ExecutiveSourceReferenceDto
import com.hiltech.shared.core.executive.resolveExecutiveDestination
import com.hiltech.shared.core.executive.executiveSnapshotMatchesSession
import com.hiltech.shared.core.executive.orderExecutiveProjectsByHealth
import com.hiltech.shared.core.executive.executiveVisiblePulse
import com.hiltech.shared.core.executive.ExecutivePulseState

/**
 * Native Windows + Android executive surface. Stateless by design:
 * the authenticated shell owns session changes, online refresh and navigation.
 *
 * No executive mutation endpoint exists. Open a source-owned detail, where the
 * original module re-checks permissions and performs its own commands.
 */
@Composable
fun ExecutiveHomeSection(
    home: ExecutiveHomeDto?,
    expectedOrganizationId: String,
    expectedIdentityId: String,
    loading: Boolean,
    loadError: String?,
    onRefresh: () -> Unit,
    onOpenSource: (ExecutiveDestination) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Mohamed Home",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "القرارات والمخاطر الظاهرة لصلاحياتك الحالية — من أنظمتها الأصلية",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onRefresh, enabled = !loading) {
                Text(if (loading) "جارٍ التحديث" else "تحديث")
            }
        }

        if (loadError != null) {
            Text(
                "تعذر تحميل بيانات الإدارة. لن نعرض لقطة قديمة أو معلومات خطأ غير موثوقة.",
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }
        if (loading) {
            Text("جارٍ قراءة الصلاحيات والبيانات الحالية. لا نعرض اللقطة السابقة أثناء التحديث.")
            return@Column
        }
        // Prevent a delayed response for organization A being rendered in B's
        // executive view. The shell must also clear on sign-out/revocation.
        if (home != null && !executiveSnapshotMatchesSession(
                home,
                expectedIdentityId,
                expectedOrganizationId,
            )) {
            Text("بيانات جلسة أو مؤسسة سابقة لا تُعرض هنا؛ يلزم تحديث الصلاحيات الحالية.")
            return@Column
        }
        if (home == null) {
            Text(if (loading) "جارٍ قراءة المصادر المصرح بها..." else "لا توجد قراءة حديثة معتمدة.")
            return@Column
        }

        Text(
            "وقت تجميع القراءة: " + home.asOf + " · مرجع الطلب: " + home.correlationId,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("ملخص القراءة — وليس إجمالي الشركة", style = MaterialTheme.typography.titleMedium)
                Text(
                    "من القرارات والمشاريع والمعدات المتاحة لصلاحياتك في نافذة القراءة الحالية فقط.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                executiveVisiblePulse(home).forEach { pulse ->
                    val color = when (pulse.state) {
                        ExecutivePulseState.CRITICAL_VISIBLE,
                        ExecutivePulseState.SOURCE_UNAVAILABLE -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(pulse.label + " · " + pulse.statement, color = color)
                    if (pulse.incomplete) {
                        Text(
                            "قراءة جزئية أو غير مؤكدة؛ افتح النظام الأصلي للتحقق.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        ExecutiveSectionHeader(
            "يحتاج قرارك",
            home.needsYou,
            "هذه قائمة قرارات الشخص المسجل عبر عضوياته المصرح بها؛ ليست إجمالي الشركة.",
        )
        if (home.needsYou.status != "UNAVAILABLE") {
            if (home.needsYou.items.isEmpty()) {
                EmptyExecutiveSection()
            }
            home.needsYou.items.forEach { item ->
                ExecutiveSourceCard(
                    title = item.safeTitleCode,
                    detail = item.safeSummary ?: "التفاصيل متاحة من طلب الموافقة الأصلي.",
                    sourceLine = item.attentionClass + " · نسخة المصدر " +
                        (item.reference.sourceVersion?.toString() ?: "غير متاحة") +
                        " · آخر تحديث " + item.updatedAt,
                    actionLabel = "افتح القرار",
                    reference = item.reference,
                    onOpenSource = onOpenSource,
                )
            }
        }

        HorizontalDivider()

        ExecutiveSectionHeader(
            "المشاريع والصحة التشغيلية",
            home.projects,
            "المشاريع المتاحة لك، مرتبة وفق حالة الصحة المسجلة في المصدر: الحرجة أولًا. UNKNOWN لا تعني سليمًا، والنافذة ليست إجمالي الشركة.",
        )
        if (home.projects.status != "UNAVAILABLE") {
            if (home.projects.items.isEmpty()) {
                EmptyExecutiveSection()
            }
            orderExecutiveProjectsByHealth(home.projects.items).forEach { project ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            project.projectCode + " · " + project.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "الحالة: " + project.lifecycleState +
                                " · تقييم الصحة: " + project.healthState,
                        )
                        if (project.healthState == "UNAVAILABLE" || project.healthState == "UNKNOWN") {
                            Text(
                                "تقييم الصحة غير مؤكد؛ راجع مصدر المشروع.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        project.signals.forEach { signal ->
                            Text(
                                "• " + signal.severity + " · " + signal.code + " · " + signal.summary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "مصدر الإشارة: " + signal.reference.sourceType +
                                    " / " + signal.reference.sourceId +
                                    " · إصدار " + (signal.reference.sourceVersion?.toString() ?: "غير متاح") +
                                    " · آخر رصد " + signal.lastObservedAt,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (project.reasonCodes.isNotEmpty()) {
                            Text(
                                "أسباب المصدر: " + project.reasonCodes.joinToString("، "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "إصدار المشروع " + project.projectVersion +
                                " · تعديل " + project.projectUpdatedAt +
                                " · تقييم " + (project.healthAsOf ?: "غير متاح"),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        val destination = resolveExecutiveDestination(project.reference)
                        if (destination != null) {
                            OutlinedButton(onClick = { onOpenSource(destination) }) {
                                Text("افتح المشروع")
                            }
                        } else {
                            Text("رابط المشروع غير مدعوم؛ افتحه من قائمة المشاريع.")
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        ExecutiveSectionHeader(
            "استثناءات المعدات",
            home.assetExceptions,
            "قائمة الخدمة الحالية للمخازن المسموح برؤيتها فقط؛ ليست رصيدًا أو إجماليًا.",
        )
        if (home.assetExceptions.status != "UNAVAILABLE") {
            if (home.assetExceptions.items.isEmpty()) {
                EmptyExecutiveSection()
            }
            home.assetExceptions.items.forEach { item ->
                ExecutiveSourceCard(
                    title = item.assetCode + (item.name?.let { " · " + it } ?: ""),
                    detail = "الإتاحة " + item.availabilityState +
                        " · المعايرة " + item.calibrationStatus +
                        " · الصيانة " + item.maintenanceStatus +
                        (if (item.reasonCodes.isEmpty()) "" else
                            " · الأسباب: " + item.reasonCodes.joinToString("، ")),
                    sourceLine = "مخزن " + item.warehouseId +
                        " · إصدار " + (item.reference.sourceVersion?.toString() ?: "غير متاح") +
                        " · تقييم المصدر " + item.evaluatedAt,
                    actionLabel = "افتح حالة المعدة",
                    reference = item.reference,
                    onOpenSource = onOpenSource,
                )
            }
        }

        HorizontalDivider()

        Text("مصادر لم يتم تفعيلها بعد", style = MaterialTheme.typography.titleMedium)
        Text(
            "لن تظهر مبالغ أو كشوف مرتبات أو مبيعات أو التزامات صيانة حتى يعتمد كل نظام عقد القراءة والصلاحيات.",
            style = MaterialTheme.typography.bodyMedium,
        )
        home.pendingDomains.forEach { domain ->
            Text(
                domain.domain + " · " + domain.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExecutiveSectionHeader(
    title: String,
    section: ExecutiveSectionDto<*>,
    explanation: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(explanation, style = MaterialTheme.typography.bodySmall)
        Text(
            "المصدر " + section.source + " · " + when (section.status) {
                "CURRENT" -> "قراءة حالية من المصدر"
                "PARTIAL" -> "قراءة جزئية — لا تفسرها كإجمالي"
                else -> "المصدر غير متاح — لا توجد قيمة صفرية مفترضة"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (section.status == "UNAVAILABLE") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (section.sourceAsOf != null) {
            Text("وقت لقطة المصدر: " + section.sourceAsOf, style = MaterialTheme.typography.labelSmall)
        }
        if (section.unavailableReason != null) {
            Text(
                "سبب نقص البيانات: " + section.unavailableReason,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (section.partial) {
            Text("هناك عناصر خارج نافذة القراءة الحالية.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyExecutiveSection() {
    Text(
        "لا توجد عناصر ظاهرة لك في نافذة القراءة الحالية؛ هذا لا يثبت انعدامها في الشركة.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ExecutiveSourceCard(
    title: String,
    detail: String,
    sourceLine: String,
    actionLabel: String,
    reference: ExecutiveSourceReferenceDto,
    onOpenSource: (ExecutiveDestination) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Text(
                sourceLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val destination = resolveExecutiveDestination(reference)
            if (destination != null) {
                OutlinedButton(onClick = { onOpenSource(destination) }) {
                    Text(actionLabel)
                }
            } else {
                Text("تفاصيل المصدر غير متاحة للتنقل من هنا.")
            }
        }
    }
}
