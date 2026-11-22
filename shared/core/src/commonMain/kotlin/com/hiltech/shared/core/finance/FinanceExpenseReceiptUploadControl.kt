package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The host platform supplies a real chooser. No fake file or demo upload. */
data class FinanceChosenExpenseReceipt(
    val contentType: String,
    val originalFileName: String?,
    val bytes: ByteArray,
    val sha256: String,
    val capturedAt: String,
)

/**
 * Attachable native control for Android/Windows. It cannot perform payment
 * or mark an expense approved. The app must supply its REAL platform picker
 * and UUID factory after authorized routing; no shell ownership taken here.
 */
@Composable
fun FinanceExpenseReceiptUploadControl(
    expenseDraftId: String,
    installationId: String,
    uploader: FinanceExpenseReceiptUploader,
    chooseFile: suspend () -> FinanceChosenExpenseReceipt?,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(expenseDraftId) { mutableStateOf(false) }
    var evidenceId by remember(expenseDraftId) { mutableStateOf<String?>(null) }
    var uploadState by remember(expenseDraftId) { mutableStateOf<String?>(null) }
    var error by remember(expenseDraftId) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Expense receipt", style = MaterialTheme.typography.titleSmall)
        Text("Private PDF/PNG/JPEG. Uploading does not authorize any reimbursement.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val selected = chooseFile()
                    if (selected != null) {
                        val reserve = newOperationId()
                        val finalize = newOperationId()
                        require(reserve != finalize) { "Unique receipt operation IDs required" }
                        val result = uploader.upload(
                            FinanceSelectedExpenseReceipt(
                                expenseDraftId = expenseDraftId,
                                reserveOperationId = reserve,
                                finalizeOperationId = finalize,
                                contentType = selected.contentType,
                                originalFileName = selected.originalFileName,
                                bytes = selected.bytes,
                                sha256 = selected.sha256,
                                capturedAt = selected.capturedAt,
                            ),
                            installationId,
                        )
                        evidenceId = result.evidenceId
                        uploadState = result.storageState
                        error = null
                    }
                } catch (failure: Exception) {
                    error = failure.message ?: "Receipt upload unavailable."
                } finally {
                    busy = false
                }
            }
        }) { Text(if (busy) "Uploading…" else "Choose and upload receipt") }

        evidenceId?.let { id ->
            Text("Evidence " + id.take(8) + " / " + (uploadState ?: "unknown"))
            if (uploadState == "QUARANTINED") {
                Text("Security scan pending — do not submit for payment.")
            }
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        uploadState = uploader.refresh(id, expenseDraftId, installationId)
                            .storageState
                        error = null
                    } catch (failure: Exception) {
                        error = failure.message ?: "Evidence status unavailable."
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Refresh Evidence verification") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
