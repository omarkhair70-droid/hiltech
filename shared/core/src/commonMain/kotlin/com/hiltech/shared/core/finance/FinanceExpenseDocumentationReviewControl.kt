package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Can be placed below actual employee receipt readiness inside authenticated
 * native shell. This REQUESTS review only; there is no finance payment action.
 */
@Composable
fun FinanceExpenseDocumentationReviewControl(
    organizationId: String,
    expenseDraftId: String,
    installationId: String,
    receiptReady: Boolean,
    reviews: FinanceExpenseDocumentationReviewApiClient,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var sending by remember(expenseDraftId) { mutableStateOf(false) }
    var pendingReviewId by remember(expenseDraftId) { mutableStateOf<String?>(null) }
    var error by remember(expenseDraftId) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Receipt documentation review",
            style = MaterialTheme.typography.titleSmall)
        Text(
            "Review checks supporting receipts, not whether company money can be paid.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!receiptReady) {
            Text("A verified private receipt is needed before requesting review.")
        }
        OutlinedButton(
            enabled = receiptReady && !sending && pendingReviewId == null,
            onClick = {
                sending = true
                scope.launch {
                    try {
                        val result = reviews.request(
                            organizationId = organizationId,
                            expenseDraftId = expenseDraftId,
                            operationId = newOperationId(),
                            installationId = installationId,
                        )
                        check(
                            result.documentationReviewState == "PENDING" &&
                                !result.expenseApproved &&
                                !result.reimbursementAuthorized &&
                                !result.cashMovementRecorded
                        ) { "Unexpected receipt documentation review response." }
                        pendingReviewId = result.documentationReviewRequestId
                        error = null
                    } catch (failure: Exception) {
                        error = failure.message ?: "Documentation review not available."
                    } finally {
                        sending = false
                    }
                }
            },
        ) { Text(if (sending) "Sending…" else "Submit receipts for review") }
        pendingReviewId?.let {
            Text(
                "Documentation review requested: " + it.take(8) +
                    " · Pending company reviewer · No payment approved",
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
