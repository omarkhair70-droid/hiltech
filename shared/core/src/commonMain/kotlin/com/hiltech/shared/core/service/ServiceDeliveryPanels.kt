@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.hiltech.shared.core.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.*

@Composable
internal fun DeliveryDocumentList(documents: List<HandoverDocumentDto>) {
    val language = LocalHiltechLanguage.current
    HiltechRecordTable(listOf(
        HiltechTableColumn(hiltechText("المستند", "Document"), 1.5f),
        HiltechTableColumn(hiltechText("المراجعة", "Revision"), .65f),
        HiltechTableColumn(hiltechText("أمر العمل", "Work order"), 1.3f)), documents.map { d ->
        HiltechTableRecord(listOf(d.documentTitle.ifBlank { language.text("مستند تسليم", "Handover document") } + d.documentCode.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            d.revisionLabel, listOf(d.workOrderCode, d.workOrderTitle).filter { it.isNotBlank() }.joinToString(" · ")))
    }, hiltechText("لا توجد مستندات في هذه النسخة.", "No documents are included in this version."))
}

@Composable
internal fun ServiceDeliveryReadiness(source: ServiceDeliveryWorkspaceDto) {
    val language = LocalHiltechLanguage.current
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(hiltechText("جاهزية التسليم", "Handover readiness"), style = MaterialTheme.typography.titleLarge)
        Text(hiltechText("أوامر العمل: ", "Work orders: ") + source.readiness.workOrderCount + " · " +
            hiltechText("المستندات المعتمدة: ", "Approved documents: ") + source.readiness.documentCount)
        if (source.readiness.missingReasons.isEmpty()) Text(hiltechText("مصادر التنفيذ مكتملة لتجهيز الحزمة. يراجع المختص الاتفاق والنطاق قبل الإصدار.",
            "Work sources are complete for package preparation. The assigned reviewer checks the agreement and scope before release."))
        source.readiness.missingReasons.forEach { raw ->
            val code = raw.substringBefore(':')
            val workId = raw.substringAfter(':', "").substringBefore(':')
            val work = source.scopeChoices.firstOrNull { it.id == workId && it.kind == "WORK" }
            val label = when (code) {
                "PROJECT_WORK_ORDERS_MISSING" -> language.text("لم تُسجّل أوامر عمل للمشروع بعد.", "No work orders have been recorded for the project.")
                "WORK_NOT_ACCEPTED" -> language.text("يحتاج العمل إلى قبول التنفيذ.", "Work execution still needs acceptance.")
                "APPROVED_DOCUMENT_MISSING" -> language.text("ينقص مستند مطلوب بنسخة معتمدة.", "A required approved document is missing.")
                "HANDOVER_DOCUMENT_REQUIREMENT_NOT_SATISFIED" -> language.text("متطلب مستندات لم يُستوفَ بعد.", "A document requirement is not yet satisfied.")
                "HANDOVER_DOCUMENT_WAIVER_NOT_APPROVED" -> language.text("إعفاء من مستند يحتاج مراجعة صلاحيته للتسليم ضمن الاتفاق.", "A document waiver needs review against the handover agreement.")
                "HANDOVER_DOCUMENT_EXCLUSION_NOT_APPROVED" -> language.text("استبعاد مستند يحتاج مراجعة نطاق التسليم.", "A document exclusion needs review against the delivery scope.")
                else -> language.text("راجع متطلبات هذه الحزمة قبل الإصدار.", "Review this package's requirements before issuing it.")
            }
            Text(listOfNotNull(work?.let { it.code + " · " + it.title }, label).joinToString(" — "))
        }
        source.policies.forEach { policy ->
            val name = if (policy.workflow == "HANDOVER") language.text("مراجع التسليم", "Handover reviewer") else language.text("مراجع الضمان", "Warranty reviewer")
            Text(name + ": " + if (policy.configured) policy.reviewerName.orEmpty() else language.text("يُحدد من إدارة الشركة", "Choose in Company administration"))
        }
        ServiceDeliveryFindings(source.readiness.sourceFindings)
    }
}

@Composable
internal fun DeliveryProposalRow(proposal: ServiceDeliveryProposalDto, enabled: Boolean, onOpen: () -> Unit) {
    val language = LocalHiltechLanguage.current
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(serviceDeliveryLabel(proposal.action, language), style = MaterialTheme.typography.titleMedium)
        Text(proposal.projectName + " · " + proposal.projectCode)
        Text(proposal.preparedByName + " · " + hiltechDateText(proposal.preparedAt.take(10)), style = MaterialTheme.typography.bodySmall)
        HiltechStateTag(serviceDeliveryLabel(proposal.state, language), when (proposal.state) {
            "RECORDED" -> HiltechStateTone.CURRENT; "STALE", "REJECTED" -> HiltechStateTone.ATTENTION; else -> HiltechStateTone.NEUTRAL
        })
        TextButton(enabled = enabled, onClick = onOpen) { Text(hiltechText("فتح الطلب والمستندات", "Open request and documents")) }
    }
}

@Composable
internal fun ServiceDeliveryProposalPanel(proposal: ServiceDeliveryProposalDto, enabled: Boolean, reason: String, onReason: (String) -> Unit,
    onOpen: (ServiceDeliveryProofDto) -> Unit, onDecision: (String) -> Unit) {
    val language = LocalHiltechLanguage.current
    val p = proposal
    HiltechPanel(Modifier.fillMaxWidth()) {
        Text(serviceDeliveryLabel(p.action, language), style = MaterialTheme.typography.headlineSmall)
        Text(p.projectName + " · " + p.projectCode + " · " + p.clientName)
        Text(hiltechText("إعداد: ", "Prepared by: ") + p.preparedByName + " · " + hiltechDateText(p.preparedAt.take(10)))
        HiltechStateTag(serviceDeliveryLabel(p.state, language), if (p.state == "RECORDED") HiltechStateTone.CURRENT else HiltechStateTone.NEUTRAL)
        ServiceDeliveryDetailsView(p.action, p.details, p.scopeName)
        if (p.packageDocuments.isNotEmpty()) DeliveryDocumentList(p.packageDocuments)
        ServiceDeliveryFindings(p.packageFindings)
        Text(hiltechText("المستندات المرتبطة بهذا الطلب", "Documents linked to this request"), style = MaterialTheme.typography.titleMedium)
        p.proofDocuments.forEach { proof ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(proof.title)
                    Text(serviceDeliveryLabel(proof.purpose, language), style = MaterialTheme.typography.bodySmall)
                    if (proof.evidenceId in p.requiredEvidenceIds) Text(if (proof.evidenceId in p.viewedEvidenceIds)
                        hiltechText("تم فتحه لهذا الطلب", "Opened for this request") else hiltechText("يلزم فتح الأصل قبل الاعتماد", "Open the original before approval"),
                        style = MaterialTheme.typography.labelMedium)
                }
                TextButton(enabled = enabled, onClick = { onOpen(proof) }) { Text(hiltechText("فتح الأصل", "Open original")) }
            }
        }
        if (p.requiredEvidenceIds.any { id -> p.proofDocuments.none { it.evidenceId == id } }) Text(
            hiltechText("أحد المستندات المطلوبة لم يعد متاحًا. حدّث المصدر قبل اتخاذ القرار.", "A required document is no longer available. Refresh the source before deciding."),
            color = MaterialTheme.colorScheme.error)
        if (p.canReview) {
            OutlinedTextField(reason, onReason, Modifier.fillMaxWidth(), enabled = enabled, minLines = 2,
                label = { Text(hiltechText("ملاحظات المراجع (مطلوبة للرفض)", "Reviewer notes (required for rejection)")) })
            val allOpened = p.requiredEvidenceIds.all { it in p.viewedEvidenceIds && p.proofDocuments.any { proof -> proof.evidenceId == it } }
            if (!allOpened) Text(hiltechText("افتح المستندات المطلوبة أعلاه، ثم اعتمد النتيجة إذا كانت مطابقة.", "Open the required documents above, then approve if the result matches them."))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = enabled && allOpened, onClick = { onDecision("APPROVE") }) { Text(hiltechText("اعتماد وتسجيل النتيجة", "Approve and record result")) }
                OutlinedButton(enabled = enabled && reason.trim().isNotBlank(), onClick = { onDecision("REJECT") }) { Text(hiltechText("رفض مع السبب", "Reject with reason")) }
            }
        } else if (p.canCloseStale) {
            Text(hiltechText("تغيّر المصدر. إغلاق الطلب يحفظ تاريخه ولا يعتمد النتيجة القديمة.", "The source changed. Closing retains this request's history without approving the old result."))
            OutlinedButton(enabled = enabled, onClick = { onDecision("CLOSE_STALE") }) { Text(hiltechText("إغلاق الطلب القديم", "Close outdated request")) }
        }
    }
}

@Composable
internal fun ServiceDeliveryFindings(findings: List<HandoverSourceFindingDto>) {
    if (findings.isEmpty()) return
    Text(hiltechText("متطلبات المستندات والاستثناءات المسجلة", "Recorded document requirements and exceptions"), style = MaterialTheme.typography.titleMedium)
    findings.forEach { finding ->
        HiltechPanel(Modifier.fillMaxWidth()) {
            Text(finding.workOrderTitle + " · " + finding.workOrderCode, style = MaterialTheme.typography.titleSmall)
            Text(hiltechText("مرجع المتطلب: ", "Requirement reference: ") + finding.requirementKey, style = MaterialTheme.typography.bodySmall)
            Text(when (finding.satisfactionState) {
                "WAIVED" -> hiltechText("يوجد إعفاء في سجل التنفيذ", "A waiver is recorded in execution")
                "NOT_APPLICABLE" -> hiltechText("مسجّل كغير منطبق في سجل التنفيذ", "Recorded as not applicable in execution")
                "BLOCKED" -> hiltechText("المتطلب متوقف", "Requirement blocked")
                else -> hiltechText("المتطلب لم يُستوفَ", "Requirement not satisfied")
            })
            Text(finding.waiverReason?.takeIf { it.isNotBlank() }
                ?: hiltechText("لا يوجد سبب إعفاء مسجّل لهذا المتطلب.", "No waiver reason is recorded for this requirement."))
        }
    }
}
