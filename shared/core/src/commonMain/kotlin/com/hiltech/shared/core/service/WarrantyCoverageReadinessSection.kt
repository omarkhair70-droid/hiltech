package com.hiltech.shared.core.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.*

data class WarrantyCoverageReadinessUiState(
    val ticketTitle: String,
    val assessment: WarrantyCoverageReadinessDto? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Current recorded coverage and outstanding source checks; the record does not send a client response. */
@Composable
fun WarrantyCoverageReadinessSection(
    state: WarrantyCoverageReadinessUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalHiltechLanguage.current
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(hiltechText("حالة تغطية الضمان", "Warranty coverage status"), style = MaterialTheme.typography.titleLarge)
        Text(state.ticketTitle, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
            Text(hiltechText("تحديث حالة القرار", "Refresh decision status"))
        }
        if (state.loading) Text(hiltechText("جارٍ قراءة الاتفاق والبلاغ والقرارات المسجلة…", "Reading the agreement, incident and recorded decisions…"))
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.assessment?.let { result ->
            val recorded = result.approvalState == "APPROVED_RECORDED"
            HiltechStateTag(if (recorded) serviceDeliveryLabel(result.coverageDecisionState, language)
                else hiltechText("لم يُسجل قرار تغطية بعد", "No coverage decision recorded"),
                if (result.warrantyApproved) HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL)
            Text(if (recorded) hiltechText("القرار مسجّل بعد المراجعة المستقلة؛ تفاصيله وتصحيحاته محفوظة في ملف التسليم والضمان.",
                "This decision was recorded after independent review. Its details and corrections are retained in Handover and warranty.")
                else hiltechText("افتح ملف التسليم والضمان للمشروع لتجهيز القرار وأدلته وإرساله إلى المراجع.",
                    "Open the project's Handover and warranty workspace to prepare the decision and its evidence for review."))
            Text(if (result.writtenWarrantyTermsPresent) hiltechText("شروط الضمان المعتمدة متاحة.", "Approved warranty terms are available.")
                else hiltechText("لم تُربط شروط ضمان معتمدة بهذا التقييم.", "No approved warranty terms are linked to this assessment."))
            Text(when (result.coveredAssetOrSiteState) {
                "VERIFIED_FOR_INCIDENT" -> hiltechText("تم التحقق من نطاق المعدة أو الموقع لهذه الواقعة.", "Equipment or site scope was verified for this incident.")
                "APPROVED_TERMS_AVAILABLE" -> hiltechText("نطاق الشروط معتمد؛ يلزم مطابقته مع الواقعة.", "The terms' scope is approved; it still needs to be matched to the incident.")
                else -> hiltechText("يلزم التحقق من ارتباط الواقعة بالنطاق المشمول.", "The incident's link to the covered scope needs verification.")
            })
            if (result.blockerCodes.isNotEmpty()) Text(hiltechText("ما يحتاج الاستكمال", "What still needs attention"), style = MaterialTheme.typography.titleSmall)
            result.blockerCodes.forEach { Text("• " + warrantyReadinessRequirement(it, language)) }
            Text(
                hiltechText("تسجيل القرار لا يرسل ردًا للعميل ولا ينشئ موعد استجابة أو مطالبة مالية. تُدار هذه الإجراءات وفق الاتفاق ومسؤولياتها.",
                    "Recording the decision does not send a client response or create a response deadline or charge. Those actions follow the agreement and their assigned responsibilities."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun warrantyReadinessRequirement(code: String, language: HiltechLanguage): String = when (code) {
    "APPROVED_WARRANTY_TERMS_REQUIRED", "COMPANY_WARRANTY_POLICY_NOT_APPROVED", "WRITTEN_WARRANTY_TERMS_MISSING", "WARRANTY_START_AND_END_NOT_APPROVED" ->
        language.text("سجّل شروط الضمان ونطاقه وتواريخه من الاتفاق الأصلي وأرسلها للمراجعة.", "Record the warranty scope, terms and dates from the original agreement for review.")
    "WARRANTY_REVIEW_SETUP_REQUIRED" -> language.text("حدّد مراجع الضمان من مركز إدارة الشركة.", "Choose the warranty reviewer in Company administration.")
    "INCIDENT_AND_EXCLUSIONS_NOT_ASSESSED", "INCIDENT_INFORMATION_REQUIRED" -> language.text("استكمل دليل الواقعة ومطابقتها مع النطاق والتواريخ والاستثناءات.", "Complete incident evidence and check scope, dates and exclusions.")
    "COVERED_ASSET_OR_SITE_SCOPE_NOT_VERIFIED" -> language.text("تحقق من المعدة أو الموقع الذي يشمله الاتفاق.", "Verify the equipment or site covered by the agreement.")
    "SIGNED_PROJECT_CONTRACT_MISSING", "SIGNED_CONTRACT_AND_PO_EVIDENCE_NOT_VERIFIED" -> language.text("أرفق أصل الاتفاق وتحقق من صلاحيته لهذا المشروع.", "Attach and verify the original agreement for this project.")
    else -> language.text("راجع مصدر الاتفاق وحالة المستندات في ملف المشروع.", "Review the agreement source and document status in the project workspace.")
}
