package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * Optional native Finance-only surface for company accounting document review.
 * Actual financial posting and payment have no controls in this component.
 */
@Composable
fun FinanceSupplierAccountingReviewControl(
    candidateId: String,
    installationId: String,
    reviews: FinanceSupplierAccountingReviewApiClient,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(candidateId) { mutableStateOf(false) }
    var operationId by remember(candidateId) { mutableStateOf<String?>(null) }
    var status by remember(candidateId) {
        mutableStateOf<FinanceSupplierAccountingReviewStatusDto?>(null)
    }
    var error by remember(candidateId) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Supplier invoice accounting review", style = MaterialTheme.typography.titleSmall)
        Text(
            "Match and invoice documents are checked; no liability or payment is booked.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            enabled = !busy &&
                (status?.reviewState == null || status?.reviewState == "NOT_REQUESTED"),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val op = operationId ?: newOperationId().also { operationId = it }
                        val result = reviews.request(candidateId, op, installationId)
                        check(
                            !result.bookedPayable && !result.taxCredited &&
                                !result.paymentApproved && !result.bankPaymentSent &&
                                !result.paymentReconciled
                        ) { "Unexpected monetary transaction from review endpoint." }
                        status = result
                        error = null
                    } catch (failure: Exception) {
                        error = failure.message ?: "Accounting review unavailable."
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) "Submitting…" else "Request invoice accounting review") }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val result = reviews.status(candidateId, installationId)
                    check(
                        !result.bookedPayable && !result.taxCredited &&
                            !result.paymentApproved && !result.bankPaymentSent &&
                            !result.paymentReconciled
                    ) { "Unexpected monetary transaction from review endpoint." }
                    status = result
                    if (result.reviewState == "NOT_REQUESTED") operationId = null
                    error = null
                } catch (failure: Exception) {
                    error = failure.message ?: "Unable to refresh invoice review."
                } finally {
                    busy = false
                }
            }
        }) { Text("Refresh accounting review") }
        status?.let { result ->
            Text("Invoice " + result.invoiceNumber + " · " +
                result.amount + " " + result.currency)
            Text(
                when (result.reviewState) {
                    "NOT_REQUESTED" -> "Accounting document review not requested."
                    "PENDING" -> "Pending company's accounting reviewer."
                    "APPROVED" -> "Accounting evidence reviewed; NOT a booked payable."
                    "REJECTED" -> "Accounting evidence rejected; no payment."
                    "CHANGE_REQUESTED" -> "Accountant requested documentation changes."
                    "SUPERSEDED" -> "Invoice source or reviewer authority changed."
                    else -> "Accounting review: " + result.reviewState
                },
            )
            result.approvalRequestId?.let { Text("Approval #" + it.take(8)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
