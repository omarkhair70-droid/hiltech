package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.local.LocalEvidenceEntity
import com.hiltech.shared.core.local.LocalEvidenceStates
import com.hiltech.shared.core.work.TechnicianJobBundleDto

/** No stale-cache or unauthorized capture affordance in the production UI. */
@Composable
internal fun FieldEvidenceCaptureControls(
    bundle: TechnicianJobBundleDto,
    enabled: Boolean,
    evidence: List<LocalEvidenceEntity>,
    feedback: String?,
    onFile: (String) -> Unit,
    onPhoto: (String) -> Unit,
) {
    if (bundle.evidenceRequirements.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("أدلة العمل", style = MaterialTheme.typography.titleMedium)
        feedback?.let { Text(it) }
        bundle.evidenceRequirements.forEach { requirement ->
            Text(requirement.requirementKey + " · " + requirement.evidenceTypeCode +
                " · " + requirement.stage)
            val captured = evidence.filter { it.requirementKey == requirement.requirementKey }
            captured.forEach { item ->
                Text(
                    if (item.localState == LocalEvidenceStates.READY) {
                        "تم التحقق على السيرفر (READY)"
                    } else if (item.localState == LocalEvidenceStates.REJECTED) {
                        "الدليل محفوظ، ويحتاج مراجعة: " + (item.lastUploadErrorCode ?: "REJECTED")
                    } else if (item.localState == LocalEvidenceStates.UPLOADED_UNVERIFIED) {
                        "الملف مرفوع، لكن لم يتم تأكيد READY بعد"
                    } else {
                        "محفوظ على الجهاز: " + item.localState + " — لم يؤكده السيرفر بعد"
                    },
                )
            }
            val count = captured.count {
                it.localState !in setOf(
                    LocalEvidenceStates.READY,
                    LocalEvidenceStates.REJECTED,
                    LocalEvidenceStates.DISCARDED_EXPLICITLY,
                )
            }
            val allowed = enabled &&
                bundle.executionCapabilities.evidenceCaptureOffline &&
                requirement.offlineCaptureAllowedByPolicy &&
                requirement.allowedContentTypes.isNotEmpty() &&
                (requirement.maxCount == null || count +
                    bundle.existingEvidence.count {
                        it.requirementKey == requirement.requirementKey && it.storageState == "READY"
                    } < requirement.maxCount)
            if (allowed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onFile(requirement.requirementKey) }) {
                        Text("اختيار ملف")
                    }
                    if ("image/jpeg" in requirement.allowedContentTypes) {
                        OutlinedButton(onClick = { onPhoto(requirement.requirementKey) }) {
                            Text("التقاط صورة")
                        }
                    }
                }
            } else if (!enabled) {
                Text("يلزم تأكيد التكليف أونلاين قبل التقاط دليل جديد.")
            } else {
                Text("إضافة دليل جديد غير متاحة حسب سياسة التكليف الحالية.")
            }
        }
        Text("المرفوع لا يُعتبر دليلًا مكتملًا حتى يؤكد السيرفر READY.")
    }
}
