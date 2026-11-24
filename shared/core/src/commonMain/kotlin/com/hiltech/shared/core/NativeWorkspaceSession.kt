package com.hiltech.shared.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.hiltechRevealModifier
import com.hiltech.shared.core.design.HiltechLanguage
import com.hiltech.shared.core.design.LocalHiltechLanguage
import com.hiltech.shared.core.design.hiltechText
import com.hiltech.shared.core.design.HiltechPanel
import com.hiltech.shared.core.design.HiltechSectionHeading
import com.hiltech.shared.core.design.HiltechShape
import com.hiltech.shared.core.design.LocalHiltechOperationalColors
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal enum class WorkspaceAvailability { CHECKING, AVAILABLE, DENIED, UNAVAILABLE }

internal fun workspaceFailureAvailability(status: Int?): WorkspaceAvailability =
    if (status == 401 || status == 403) WorkspaceAvailability.DENIED
    else WorkspaceAvailability.UNAVAILABLE

internal fun uniqueNativeWorkspaces(workspaces: List<NativeOperationalWorkspace>): List<NativeOperationalWorkspace> =
    workspaces.groupBy { it.id }.values.filter { it.size == 1 && it.single().id.isNotBlank() }.map { it.single() }

internal class NativeWorkspaceSession {
    val availability = mutableStateMapOf<String, WorkspaceAvailability>()
    var selectedId by mutableStateOf<String?>(null)
        private set
    var pendingId by mutableStateOf<String?>(null)
        private set
    var revision by mutableStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun open(id: String) {
        if (availability[id] != WorkspaceAvailability.AVAILABLE) return
        selectedId = null
        message = null
        pendingId = id
    }

    fun close() {
        pendingId = null
        selectedId = null
        message = null
    }

    fun refresh() {
        close()
        availability.clear()
        revision += 1
    }

    fun completeOpen(id: String, result: WorkspaceAvailability) {
        if (pendingId != id) return
        availability[id] = result
        selectedId = id.takeIf { result == WorkspaceAvailability.AVAILABLE }
        message = when (result) {
            WorkspaceAvailability.AVAILABLE -> null
            WorkspaceAvailability.DENIED -> "Access is no longer available."
            else -> "The source could not be reached. Refresh and try again."
        }
        pendingId = null
    }
}

private suspend fun checkNativeWorkspace(workspace: NativeOperationalWorkspace): WorkspaceAvailability =
    try {
        if (workspace.canReadSource()) WorkspaceAvailability.AVAILABLE else WorkspaceAvailability.DENIED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: HiltechApiException) {
        workspaceFailureAvailability(failure.httpStatus)
    } catch (_: Exception) {
        WorkspaceAvailability.UNAVAILABLE
    }

@Composable
internal fun rememberNativeWorkspaceSession(
    scope: String,
    authorityRevision: String,
    workspaces: List<NativeOperationalWorkspace>,
): NativeWorkspaceSession {
    val session = remember(scope, authorityRevision) { NativeWorkspaceSession() }
    val eligible = uniqueNativeWorkspaces(workspaces)
    val ids = eligible.map { it.id }
    LaunchedEffect(session, ids, session.revision) {
        session.close()
        session.availability.clear()
        if (scope.isBlank()) return@LaunchedEffect
        eligible.forEach { session.availability[it.id] = WorkspaceAvailability.CHECKING }
        coroutineScope {
            eligible.forEach { workspace ->
                launch { session.availability[workspace.id] = checkNativeWorkspace(workspace) }
            }
        }
    }
    LaunchedEffect(session, ids, session.pendingId) {
        val id = session.pendingId ?: return@LaunchedEffect
        val workspace = eligible.firstOrNull { it.id == id }
        val result = if (workspace == null || scope.isBlank()) WorkspaceAvailability.DENIED
            else checkNativeWorkspace(workspace)
        session.completeOpen(id, result)
    }
    return session
}

@Composable
internal fun NativeWorkspaceDirectory(
    workspaces: List<NativeOperationalWorkspace>,
    session: NativeWorkspaceSession,
    onOpen: (String) -> Unit,
) {
    val colors = LocalHiltechOperationalColors.current
    val eligible = uniqueNativeWorkspaces(workspaces)
    val visible = eligible.filter { session.availability[it.id] == WorkspaceAvailability.AVAILABLE }
    val checking = eligible.any { session.availability[it.id] == null || session.availability[it.id] == WorkspaceAvailability.CHECKING }
    val unavailable = eligible.count { session.availability[it.id] == WorkspaceAvailability.UNAVAILABLE }
    val language = LocalHiltechLanguage.current
    HiltechSectionHeading("OPERATIONS", language.text("مساحات الشركة", "Company workspaces"),
        language.text("انتقل إلى المجال الذي تحتاجه.", "Go to the area you need."))
    visible.groupBy { nativeWorkspaceGroup(it.id) }.forEach { (group, entries) ->
        Text(group.title(language), style = MaterialTheme.typography.titleMedium)
        HiltechPanel(Modifier.fillMaxWidth()) {
            entries.forEach { workspace ->
                Surface(
                    onClick = { onOpen(workspace.id) },
                    enabled = session.pendingId == null,
                    shape = HiltechShape.small,
                    color = colors.surface,
                    contentColor = colors.primaryText,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(nativeWorkspaceTitle(workspace, language), style = MaterialTheme.typography.titleMedium)
                            if (session.pendingId == workspace.id) Text(language.text("جارٍ فتح المصدر…", "Opening source…"),
                                color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(language.text("فتح", "Open"), color = colors.accent, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
    if (checking || unavailable > 0 || visible.isEmpty() || session.message != null) {
        HiltechPanel(Modifier.fillMaxWidth()) {
            if (checking) Text(hiltechText("جارٍ تحميل المساحات المتاحة…", "Checking connected sources…"))
            if (!checking && visible.isEmpty()) Text(hiltechText("لا توجد مساحات متخصصة متاحة لهذا الحساب.", "No connected workspaces are available to this account."))
            if (unavailable > 0) Text(hiltechText("بعض المصادر غير متاحة الآن. حدّث لإعادة المحاولة.", "Some sources are currently unavailable. Refresh to retry."))
            session.message?.let { message ->
                Text(when (message) {
                    "Access is no longer available." -> language.text("لم تعد صلاحية الوصول متاحة.", message)
                    "The source could not be reached. Refresh and try again." -> language.text("تعذر الاتصال بالمصدر. حدّث وحاول مرة أخرى.", message)
                    else -> message
                })
            }
            TextButton(onClick = session::refresh, enabled = !checking && session.pendingId == null) {
                Text(hiltechText("تحديث المساحات", "Refresh workspaces"))
            }
        }
    }
}

@Composable
internal fun NativeWorkspacePane(
    workspace: NativeOperationalWorkspace,
    scope: String,
    onExit: () -> Unit,
) {
    val colors = LocalHiltechOperationalColors.current
    Column(Modifier.fillMaxSize()) {
        Surface(color = colors.surface, contentColor = colors.primaryText, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onExit) { Text(hiltechText("استكشف", "‹ Explore")) }
                Text(nativeWorkspaceTitle(workspace, LocalHiltechLanguage.current), modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().then(hiltechRevealModifier(scope to workspace.id)).padding(horizontal = 12.dp)) {
            key(scope, workspace.id) {
                if (workspace.hostOwnsScroll) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { workspace.content(onExit) }
                } else {
                    workspace.content(onExit)
                }
            }
        }
    }
}

/** Host supplies only configured routes and retains the original source-owned
 * API checks. Selecting a route never grants access or performs a command. */
data class NativeOperationArea(
    val id: String,
    val title: String,
    val description: String,
    val content: @Composable () -> Unit,
)

@Composable
fun NativeOperationAreas(title: String, description: String, areas: List<NativeOperationArea>) {
    val colors = LocalHiltechOperationalColors.current
    val language = LocalHiltechLanguage.current
    val sectionTitle = language.text(title, operationSectionEnglish(title))
    val sectionDescription = language.text(description, operationSectionEnglish(description))
    var requested by remember { mutableStateOf<String?>(null) }
    val current = areas.firstOrNull { it.id == requested }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (current == null) {
            HiltechSectionHeading("OPERATIONS", sectionTitle, sectionDescription)
            if (areas.isEmpty()) Text(hiltechText("لا توجد عمليات متاحة في إعدادات التشغيل الحالية.", "No operations are available in the current configuration."))
            areas.forEach { area ->
                Surface(onClick = { requested = area.id }, modifier = Modifier.fillMaxWidth(),
                    color = colors.surface, contentColor = colors.primaryText, shape = HiltechShape.medium) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(language.text(area.title, operationEnglish(area.id, area.title)), style = MaterialTheme.typography.titleMedium)
                            Text(language.text(area.description, operationEnglishDescription(area.id, area.description)), color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(hiltechText("فتح", "Open"), color = colors.accent, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(sectionTitle, color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                    Text(language.text(current.title, operationEnglish(current.id, current.title)), style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = { requested = null }) { Text(hiltechText("كل العمليات", "All operations")) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                areas.forEach { area ->
                    FilterChip(selected = current.id == area.id, onClick = { requested = area.id }, label = { Text(language.text(area.title, operationEnglish(area.id, area.title))) })
                }
            }
            Box(Modifier.fillMaxWidth().then(hiltechRevealModifier(current.id))) {
                key(current.id) { current.content() }
            }
        }
    }
}

internal enum class NativeWorkspaceGroup(val ar: String, val en: String) {
    Management("الإدارة", "Management"),
    Finance("المالية والمحاسبة", "Finance & accounting"),
    Payroll("الرواتب", "Payroll"),
    Sales("المبيعات", "Sales"),
    Delivery("التسليم والخدمة", "Delivery & service"),
    Purchasing("المشتريات", "Purchasing"),
    Other("مساحات أخرى", "Other workspaces");
    fun title(language: HiltechLanguage): String = language.text(ar, en)
}

internal fun nativeWorkspaceGroup(id: String): NativeWorkspaceGroup = when {
    id.startsWith("executive") -> NativeWorkspaceGroup.Management
    id.contains("payroll") || id.contains("payslip") -> NativeWorkspaceGroup.Payroll
    id.startsWith("finance") -> NativeWorkspaceGroup.Finance
    id.startsWith("commercial") -> NativeWorkspaceGroup.Sales
    id.startsWith("handover") || id.startsWith("service-operations") -> NativeWorkspaceGroup.Delivery
    id.contains("purchase") || id.contains("procurement") -> NativeWorkspaceGroup.Purchasing
    else -> NativeWorkspaceGroup.Other
}

internal fun nativeWorkspaceTitle(workspace: NativeOperationalWorkspace, language: HiltechLanguage): String {
    val names = when (workspace.id) {
        "executive-home" -> "نظرة عامة على الشركة" to "Company overview"
        "my-payslips" -> "كشوف مرتباتي" to "My payslips"
        "payroll-admin-read" -> "مراجعة فترات المرتبات" to "Payroll periods"
        "commercial-read" -> "المبيعات والمناقصات" to "Sales & tenders"
        "handover-read" -> "تسليم المشاريع" to "Project handover"
        "service-operations-read" -> "الدعم والصيانة" to "Support & maintenance"
        "finance-evidence-read" -> "المراجعة المالية" to "Financial review"
        "finance-bank-statement" -> "كشف البنك والتحصيلات" to "Bank statements & collections"
        "finance-invoice-read" -> "فواتير الموردين" to "Supplier invoices"
        "finance-completion-operator" -> "إتمام العمليات المالية" to "Complete financial operations"
        "finance-cashbox-opening" -> "فتح خزنة" to "Open cashbox"
        else -> return workspace.label
    }
    return language.text(names.first, names.second)
}

private fun operationSectionEnglish(label: String): String = when (label) {
    "المخازن" -> "Warehouses"
    "المشتريات" -> "Purchasing"
    "العمليات اليومية" -> "Daily operations"
    "اختر ما تحتاج إليه: تنفيذ حركة أو مراجعة المخزون أو البحث عن معدة." -> "Choose a movement, inventory review or asset lookup."
    "اختر العملية المطلوبة وتابعها في مساحتها المستقلة." -> "Choose an operation and continue in its dedicated workspace."
    "اختر نوع الحركة أو الطلب الذي تريد تنفيذه." -> "Choose the movement or supply request you need."
    else -> label
}

private fun operationEnglish(id: String, fallback: String): String = when (id) {
    "services" -> "Service purchasing"
    "requirements" -> "Project requirements"
    "sourcing" -> "Suppliers & quotations"
    "orders" -> "Purchase orders"
    "asset-parts", "parts" -> "Equipment spare parts"
    "office-stock", "office" -> "Office supplies"
    "replenishment" -> "Stock replenishment"
    "receiving" -> "Purchase receiving"
    "invoices" -> "Supplier invoices"
    "physical" -> "Daily operations"
    "movements" -> "Warehouse movements"
    "scan" -> "Find equipment"
    "inventory" -> "Inventory management"
    else -> fallback
}

private fun operationEnglishDescription(id: String, fallback: String): String = when (id) {
    "services" -> "Service requests, quotes and service orders"
    "requirements" -> "Material requirements linked to projects and work"
    "sourcing" -> "Compare supplier quotes and prepare a purchase order"
    "orders" -> "Issue, amend, cancel and communicate purchase orders"
    "asset-parts", "parts" -> "Equipment and maintenance requirements"
    "office-stock", "office" -> "Internal supply requests"
    "replenishment" -> "Stock replenishment requests"
    "receiving" -> "Receive deliveries and verify supporting documents"
    "invoices" -> "Record and review supplier invoices"
    "physical" -> "Issue, receive, return and review resource readiness"
    "movements" -> "Issue, return and record actual consumption"
    "scan" -> "Open an asset passport using its AssetTag"
    "inventory" -> "Stock, equipment, reservations, locations and permissions"
    else -> fallback
}
