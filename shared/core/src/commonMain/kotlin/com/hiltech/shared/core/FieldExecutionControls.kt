package com.hiltech.shared.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.field.FieldExecutionIntent
import com.hiltech.shared.core.field.FieldQueuedIntent
import com.hiltech.shared.core.work.TechnicianJobBundleDto

@Composable
internal fun FieldExecutionControls(
    bundle: TechnicianJobBundleDto,
    enabled: Boolean,
    pending: FieldQueuedIntent?,
    feedback: String?,
    onQueue: (FieldExecutionIntent, String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("تنفيذ الشغل", style = MaterialTheme.typography.titleMedium)
        pending?.let {
            Text("أمر " + it.commandType + " محفوظ محليًا: " + it.state + " — لم يُعتمد من السيرفر.")
        }
        feedback?.let { Text(it) }
        if (!enabled) {
            Text("نسخة للقراءة فقط؛ يلزم تحقق مباشر من التكليف قبل تسجيل أمر جديد.")
        } else if (pending != null) {
            Text("يوجد أمر لم تتم تسويته بعد. راجع حالة المزامنة أولًا.")
        } else {
            val action = when {
                bundle.lifecycleState == "ASSIGNED" &&
                    bundle.executionCapabilities.startOffline -> FieldExecutionIntent.START
                bundle.lifecycleState == "IN_PROGRESS" &&
                    bundle.executionCapabilities.blockOffline -> FieldExecutionIntent.BLOCK
                bundle.lifecycleState == "BLOCKED" &&
                    bundle.executionCapabilities.resumeOffline -> FieldExecutionIntent.RESUME
                else -> null
            }
            if (action == null) {
                Text("مفيش أمر تنفيذ مسموح به في الحالة الحالية.")
            } else {
                var explanation by remember(bundle.workOrderId, action) { mutableStateOf("") }
                if (action != FieldExecutionIntent.START) {
                    OutlinedTextField(
                        value = explanation,
                        onValueChange = { explanation = it.take(2000) },
                        label = { Text(if (action == FieldExecutionIntent.BLOCK) "سبب إيقاف العمل" else "شرح حل العائق") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedButton(
                    onClick = { onQueue(action, explanation.takeIf { it.isNotBlank() }) },
                    enabled = action == FieldExecutionIntent.START || explanation.isNotBlank(),
                ) {
                    Text(when (action) {
                        FieldExecutionIntent.START -> "حفظ أمر بدء العمل"
                        FieldExecutionIntent.BLOCK -> "حفظ أمر إيقاف العمل"
                        FieldExecutionIntent.RESUME -> "حفظ أمر استكمال العمل"
                    })
                }
                Text("الحفظ على الجهاز Pending، والسيرفر وحده يطبق التغيير بعد التحقق والمزامنة.")
            }
        }
    }
}
