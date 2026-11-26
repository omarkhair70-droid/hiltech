package com.hiltech.shared.core.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.*
import kotlinx.serialization.Serializable

/** Internal triage choices do not establish a contractual response time or warranty entitlement. */
@Serializable
data class ServiceDeliveryIncidentDraft(val title: String = "", val description: String = "", val severity: String = "", val priority: String = "") {
    fun complete(): Boolean = title.trim().length in 1..240 && description.trim().length in 1..8000 &&
        severity in setOf("CRITICAL", "MAJOR", "MINOR") && priority in setOf("P1", "P2", "P3", "P4")
}

internal fun serviceIncidentLabel(value: String, language: HiltechLanguage): String = when (value) {
    "CRITICAL" -> language.text("تأثير حرج", "Critical impact")
    "MAJOR" -> language.text("تأثير كبير", "Major impact")
    "MINOR" -> language.text("تأثير محدود", "Minor impact")
    "P1" -> language.text("عاجل", "Urgent")
    "P2" -> language.text("مرتفع", "High")
    "P3" -> language.text("عادي", "Normal")
    "P4" -> language.text("منخفض", "Low")
    else -> language.text("اختر التصنيف", "Choose classification")
}

@Composable
internal fun ServiceDeliveryIncidentForm(
    draft: ServiceDeliveryIncidentDraft, enabled: Boolean, attempted: Boolean,
    onChange: (ServiceDeliveryIncidentDraft) -> Unit, onSave: () -> Unit, onCancel: () -> Unit,
) {
    val language = LocalHiltechLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(hiltechText("سجّل الواقعة كما وردت. التأثير والأولوية لترتيب المتابعة الداخلية؛ قرار الضمان ومواعيد الاستجابة التعاقدية يُراجعان منفصلين.",
            "Record the reported incident. Impact and priority order internal follow-up; warranty coverage and contractual response times are reviewed separately."))
        OutlinedTextField(draft.title, { onChange(draft.copy(title = it)) }, Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text(hiltechText("عنوان واضح للبلاغ", "Clear incident title")) }, singleLine = true,
            isError = attempted && draft.title.trim().length !in 1..240)
        OutlinedTextField(draft.description, { onChange(draft.copy(description = it)) }, Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text(hiltechText("وصف الواقعة والموقع أو المعدة المتأثرة", "Incident and affected location or equipment")) }, minLines = 3,
            isError = attempted && draft.description.trim().length !in 1..8000)
        HiltechRecordPicker(hiltechText("تأثير الواقعة", "Incident impact"), draft.severity,
            listOf("CRITICAL", "MAJOR", "MINOR").map { HiltechRecordChoice(it, serviceIncidentLabel(it, language)) },
            { onChange(draft.copy(severity = it)) }, allowKnownReference = false, enabled = enabled)
        HiltechRecordPicker(hiltechText("أولوية المتابعة الداخلية", "Internal follow-up priority"), draft.priority,
            listOf("P1", "P2", "P3", "P4").map { HiltechRecordChoice(it, serviceIncidentLabel(it, language)) },
            { onChange(draft.copy(priority = it)) }, allowKnownReference = false, enabled = enabled)
        if (attempted && !draft.complete()) Text(hiltechText("أكمل العنوان والوصف واختر التأثير والأولوية.",
            "Complete the title and description, then choose impact and priority."), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = enabled, onClick = onSave) { Text(hiltechText("مراجعة البلاغ قبل الحفظ", "Review incident before saving")) }
            TextButton(enabled = enabled, onClick = onCancel) { Text(hiltechText("عودة", "Back")) }
        }
    }
}
