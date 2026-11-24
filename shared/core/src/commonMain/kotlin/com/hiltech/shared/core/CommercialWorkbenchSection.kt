package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hiltech.shared.core.design.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/** Commercial state is obtained only through the authenticated, role-filtered API. */
data class CommercialOpportunityTile(
    val id: String,
    val title: String,
    val state: String,
    val source: String,
    val clientOrganizationId: String,
    val deadline: String? = null,
)
data class CommercialWorkbenchState(
    val loading: Boolean = false,
    val enabled: Boolean = false,
    val canWrite: Boolean = false,
    val canRelease: Boolean = false,
    val opportunities: List<CommercialOpportunityTile> = emptyList(),
    val selectedOpportunityId: String? = null,
    val selectedQuoteId: String? = null,
    val errorCode: String? = null,
)

/**
 * Native Compose surface shared by the Windows workbench and bounded Android visits.
 * Integration owner mounts the feature under an explicit default-OFF runtime gate;
 * never place a general employee route into the global shell without a role check.
 */
@Composable
fun CommercialWorkbenchSection(
    state: CommercialWorkbenchState,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onCreate: (title: String, clientOrganizationId: String, source: String) -> Unit,
    onPlanVisit: (opportunityId: String, purpose: String, scheduledAt: String) -> Unit,
    onRequestQuoteApproval: (quoteId: String) -> Unit,
    onReleaseApprovedQuote: (quoteId: String) -> Unit,
) {
    if (!state.enabled) {
        Text(hiltechText("عمليات المبيعات غير مفعّلة.", "Commercial operations are not enabled."), style = MaterialTheme.typography.bodyMedium)
        return
    }
    var title by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var stateFilter by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val colors = LocalHiltechOperationalColors.current
    var clientId by remember { mutableStateOf("") }
    var visitPurpose by remember { mutableStateOf("") }
    var visitAt by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                HiltechSectionHeading("COMMERCIAL", hiltechText("المبيعات والمناقصات", "Sales & tenders"),
                    hiltechText("الفرص وعروض الأسعار والعملاء ومسار التعاقد.", "Opportunities, quotations, clients and contract journeys."))
            }
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text(hiltechText("تحديث", "Refresh")) }
        }
        if (state.loading) Text(hiltechText("جارٍ تحميل الفرص…", "Loading commercial records…"))
        state.errorCode?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.canWrite) TextButton(onClick = { creating = !creating }) { Text(if (creating) hiltechText("إغلاق", "Close") else hiltechText("+ فرصة", "+ Opportunity")) }
        if (state.canWrite && creating) {
            Text(hiltechText("فرصة جديدة", "New opportunity"), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text(hiltechText("اسم الفرصة", "Opportunity title")) },
            )
            OutlinedTextField(
                value = clientId, onValueChange = { clientId = it },
                label = { Text(hiltechText("معرّف العميل الحالي", "Existing client organization ID")) },
            )
            Button(
                enabled = title.isNotBlank() && clientId.isNotBlank(),
                onClick = { onCreate(title.trim(), clientId.trim(), "DIRECT") },
            ) { Text(hiltechText("إنشاء فرصة", "Create opportunity")) }
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(hiltechText("ابحث عن فرصة", "Find an opportunity")) })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = stateFilter == null, onClick = { stateFilter = null }, label = { Text(hiltechText("الكل", "All")) })
            state.opportunities.map { it.state }.distinct().forEach { stage ->
                FilterChip(selected = stateFilter == stage, onClick = { stateFilter = stage }, label = { Text(stage) })
            }
        }
        val matching = state.opportunities.filter { opportunity ->
            (stateFilter == null || opportunity.state == stateFilter) &&
                (query.isBlank() || opportunity.title.contains(query.trim(), true))
        }
        Text(hiltechText("الفرص المعروضة: ", "Visible opportunities: ") + matching.size,
            color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
        if (!state.loading && matching.isEmpty()) Text(hiltechText("لا توجد فرص مطابقة.", "No matching opportunities."))
        matching.forEach { opportunity ->
            Surface(onClick = { onSelect(opportunity.id) }, enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(), shape = HiltechShape.medium,
                color = colors.surface, contentColor = colors.primaryText) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(opportunity.title, style = MaterialTheme.typography.titleMedium)
                        Text(opportunity.source, color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                        opportunity.deadline?.let { Text(hiltechText("القرار المتوقع: ", "Expected decision: ") + it) }
                        HiltechStateTag(opportunity.state, HiltechStateTone.NEUTRAL)
                    }
                    Text(hiltechText("فتح", "Open"), color = colors.accent)
                }
            }
        }
        if (state.canWrite && state.selectedOpportunityId != null) {
            Text(hiltechText("استكشاف وزيارة الموقع", "Discovery / site visit"), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = visitPurpose, onValueChange = { visitPurpose = it },
                label = { Text(hiltechText("الغرض", "Purpose")) },
            )
            OutlinedTextField(
                value = visitAt, onValueChange = { visitAt = it },
                label = { Text(hiltechText("موعد الزيارة UTC (ISO-8601)", "Scheduled UTC (ISO-8601)")) },
            )
            Button(
                enabled = visitPurpose.isNotBlank() && visitAt.isNotBlank(),
                onClick = {
                    onPlanVisit(state.selectedOpportunityId, visitPurpose.trim(), visitAt.trim())
                },
            ) { Text(hiltechText("تسجيل زيارة الموقع", "Record site visit")) }
        }
        if (state.canWrite && state.selectedQuoteId != null) {
            OutlinedButton(onClick = { onRequestQuoteApproval(state.selectedQuoteId) }) {
                Text(hiltechText("طلب مراجعة مستقلة للعرض", "Request independent quote approval"))
            }
        }
        if (state.canRelease && state.selectedQuoteId != null) {
            Button(onClick = { onReleaseApprovedQuote(state.selectedQuoteId) }) {
                Text(hiltechText("إصدار العرض المعتمد", "Release approved quote"))
            }
        }
        Text(
            "Internal costing and supplier quotations are never shown in the customer quote preview.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
