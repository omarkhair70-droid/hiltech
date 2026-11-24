package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechStateTag
import com.hiltech.shared.core.design.HiltechStateTone
import com.hiltech.shared.core.design.HiltechShape
import com.hiltech.shared.core.design.LocalHiltechOperationalColors
import com.hiltech.shared.core.executive.ExecutivePulseItem
import com.hiltech.shared.core.executive.ExecutiveDestination
import com.hiltech.shared.core.executive.ExecutiveHomeDto
import com.hiltech.shared.core.executive.ExecutiveSectionDto
import com.hiltech.shared.core.executive.ExecutiveSourceReferenceDto
import com.hiltech.shared.core.executive.resolveExecutiveDestination
import com.hiltech.shared.core.executive.resolveExecutiveProjectSignalDestination
import com.hiltech.shared.core.executive.executiveSnapshotMatchesSession
import com.hiltech.shared.core.executive.orderExecutiveProjectsByHealth
import com.hiltech.shared.core.executive.executiveVisiblePulse
import com.hiltech.shared.core.executive.ExecutivePulseState
import com.hiltech.shared.core.executive.ExecutiveOperationalBriefSection
import com.hiltech.shared.core.executive.executiveSectionTrusted
import com.hiltech.shared.core.executive.executiveDecisionsTrusted
import com.hiltech.shared.core.executive.EXECUTIVE_PROJECT_SOURCE
import com.hiltech.shared.core.executive.EXECUTIVE_ASSET_SOURCE

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
    canOpenDestination: (ExecutiveDestination) -> Boolean = { true },
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val colors = LocalHiltechOperationalColors.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                HiltechSectionHeading(
                    "HILTECH / COMPANY",
                    "Mohamed Home",
                    "القرارات والمشاريع والمعدات التي يمكنك متابعتها الآن",
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
            "آخر تجميع: " + home.asOf,
            style = MaterialTheme.typography.labelMedium,
            color = colors.secondaryText,
        )
        ExecutiveSourceDetails("مرجع الطلب: " + home.correlationId)
        ExecutivePulseStrip(executiveVisiblePulse(home))

        // Phase 13B: optional source-bound operating brief over this already
        // validated snapshot; no extra endpoint, cache, digest delivery or
        // cross-tenant access.
        ExecutiveOperationalBriefSection(
            home = home,
            onOpenSource = onOpenSource,
            canOpenDestination = canOpenDestination,
        )

        val mainLane: @Composable () -> Unit = {
            ExecutiveSectionHeader(
                "يحتاج قرارك",
                home.needsYou,
                "هذه قائمة قرارات الشخص المسجل عبر عضوياته المصرح بها؛ ليست إجمالي الشركة.",
                trusted = executiveDecisionsTrusted(home),
            )
            if (executiveDecisionsTrusted(home)) {
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
                        canOpenDestination = canOpenDestination,
                    )
                }
            } else {
                Text(
                    "هذا المصدر غير متاح أو لا يطابق نطاق القراءة المعتمد؛ لا نعرض عناصره.",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider()

            ExecutiveSectionHeader(
                "المشاريع والصحة التشغيلية",
                home.projects,
                "المشاريع المتاحة لك، مرتبة وفق حالة الصحة المسجلة في المصدر: الحرجة أولًا. UNKNOWN لا تعني سليمًا، والنافذة ليست إجمالي الشركة.",
                trusted = executiveSectionTrusted(home.projects, EXECUTIVE_PROJECT_SOURCE),
            )
            if (executiveSectionTrusted(home.projects, EXECUTIVE_PROJECT_SOURCE)) {
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
                                val healthDestination = resolveExecutiveProjectSignalDestination(
                                    signal.reference, project.projectId,
                                )
                                if (healthDestination != null && canOpenDestination(healthDestination)) {
                                    OutlinedButton(onClick = { onOpenSource(healthDestination) }) {
                                        Text("راجع سبب الخطر في المشروع")
                                    }
                                }
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
                            if (destination != null && canOpenDestination(destination)) {
                                OutlinedButton(onClick = { onOpenSource(destination) }) {
                                    Text("افتح المشروع")
                                }
                            } else {
                                Text("رابط المشروع غير مدعوم؛ افتحه من قائمة المشاريع.")
                            }
                        }
                    }
                }
            } else {
                Text(
                    "هذا المصدر غير متاح أو لا يطابق نطاق القراءة المعتمد؛ لا نعرض عناصره.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val supportLane: @Composable () -> Unit = {
            ExecutiveSectionHeader(
                "استثناءات المعدات",
                home.assetExceptions,
                "قائمة الخدمة الحالية للمخازن المسموح برؤيتها فقط؛ ليست رصيدًا أو إجماليًا.",
                trusted = executiveSectionTrusted(home.assetExceptions, EXECUTIVE_ASSET_SOURCE),
            )
            if (executiveSectionTrusted(home.assetExceptions, EXECUTIVE_ASSET_SOURCE)) {
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
                        canOpenDestination = canOpenDestination,
                    )
                }
            } else {
                Text(
                    "هذا المصدر غير متاح أو لا يطابق نطاق القراءة المعتمد؛ لا نعرض عناصره.",
                    color = MaterialTheme.colorScheme.error,
                )
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
        // Wide screens keep decisions and projects in focus with equipment
        // and coverage alongside. Phone uses the same live sources in order.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 860.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1.55f), verticalArrangement = Arrangement.spacedBy(16.dp)) { mainLane() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { supportLane() }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    mainLane()
                    HorizontalDivider()
                    supportLane()
                }
            }
        }
    }
}

@Composable
private fun ExecutiveSectionHeader(
    title: String,
    section: ExecutiveSectionDto<*>,
    explanation: String,
    trusted: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(explanation, style = MaterialTheme.typography.bodySmall)
        Text(
            "المصدر " + section.source + " · " + when {
                !trusted -> "العقد أو النطاق غير مطابق — البيانات محجوبة"
                section.status == "CURRENT" -> "استجاب المصدر لنافذة القراءة؛ حداثة كل سجل موضحة بوقته"
                section.status == "PARTIAL" -> "قراءة جزئية — لا تفسرها كإجمالي"
                else -> "المصدر غير متاح — لا توجد قيمة صفرية مفترضة"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (!trusted) MaterialTheme.colorScheme.error
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
            Text(
                "النافذة غير مكتملة: قد توجد عناصر أخرى أو تعذر قراءة جزء من المصدر.",
                style = MaterialTheme.typography.labelSmall,
            )
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
    canOpenDestination: (ExecutiveDestination) -> Boolean = { true },
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            ExecutiveSourceDetails(sourceLine)
            val destination = resolveExecutiveDestination(reference)
            if (destination != null && canOpenDestination(destination)) {
                OutlinedButton(onClick = { onOpenSource(destination) }) {
                    Text(actionLabel)
                }
            } else {
                Text("تفاصيل المصدر غير متاحة للتنقل من هنا.")
            }
        }
    }
}

@Composable
private fun ExecutiveSourceDetails(sourceLine: String) {
    var expanded by remember(sourceLine) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "إخفاء تفاصيل المصدر" else "تفاصيل المصدر")
        }
        if (expanded) Text(
            sourceLine,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExecutivePulseStrip(items: List<ExecutivePulseItem>) {
    val colors = LocalHiltechOperationalColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("إشارات التشغيل", style = MaterialTheme.typography.titleLarge)
        Text("نافذة البيانات المتاحة لك الآن، وليست إجمالي الشركة.", color = colors.secondaryText,
            style = MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 720.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items.forEach { item -> ExecutivePulseTile(item, Modifier.weight(1f)) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { item -> ExecutivePulseTile(item, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

@Composable
private fun ExecutivePulseTile(item: ExecutivePulseItem, modifier: Modifier) {
    val colors = LocalHiltechOperationalColors.current
    val (status, tone) = when (item.state) {
        ExecutivePulseState.CRITICAL_VISIBLE -> "حرج" to HiltechStateTone.BLOCKED
        ExecutivePulseState.ATTENTION_VISIBLE -> "يحتاج انتباهًا" to HiltechStateTone.ATTENTION
        ExecutivePulseState.UNVERIFIED -> "غير مؤكد" to HiltechStateTone.ATTENTION
        ExecutivePulseState.WINDOW_EMPTY -> "لا توجد إشارة ظاهرة" to HiltechStateTone.NEUTRAL
        ExecutivePulseState.SOURCE_UNAVAILABLE -> "المصدر غير متاح" to HiltechStateTone.BLOCKED
    }
    Surface(modifier = modifier, shape = HiltechShape.medium, color = colors.surface,
        contentColor = colors.primaryText) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.label, style = MaterialTheme.typography.titleMedium)
            HiltechStateTag(status, tone)
            Text(item.statement, style = MaterialTheme.typography.bodyMedium)
            if (item.incomplete) Text("التغطية جزئية أو غير مؤكدة.", color = colors.secondaryText,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
