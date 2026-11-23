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

/** Native employee request/status only; no funding or payroll deduction UI. */
@Composable
fun FinanceAdvanceImprestPolicyReviewControl(
    organizationId: String,
    kind: String,
    draftId: String,
    installationId: String,
    reviews: FinanceMoneyPolicyReviewApiClient,
    newOperationId: () -> String,
) {
    require(kind == "advances" || kind == "imprests")
    val scope = rememberCoroutineScope()
    var busy by remember(kind, draftId) { mutableStateOf(false) }
    var pendingOperationId by remember(kind, draftId) { mutableStateOf<String?>(null) }
    var status by remember(kind, draftId) {
        mutableStateOf<FinanceMoneyPolicyReviewStatusDto?>(null)
    }
    var error by remember(kind, draftId) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (kind == "advances") "My advance request review"
            else "My company cash imprest review",
            style = MaterialTheme.typography.titleSmall)
        Text(
            if (kind == "advances")
                "Approval of a request does not issue an employee loan or authorize payroll deduction."
            else
                "Company cash custody is not a personal advance. This review does not deliver cash.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            enabled = !busy &&
                (status?.requestState == null || status?.requestState == "NOT_REQUESTED"),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val op = pendingOperationId ?: newOperationId().also {
                            pendingOperationId = it
                        }
                        val result = reviews.request(
                            organizationId, kind, draftId, op, installationId,
                        )
                        check(
                            !result.moneyIssued && !result.personalLoanRecognized &&
                                !result.cashCustodyRecorded &&
                                !result.payrollDeductionAuthorized &&
                                !result.settlementRecorded
                        ) { "Unexpected financial action reported by review." }
                        status = result
                        error = null
                    } catch (failure: Exception) {
                        error = failure.message ?: "Company review unavailable."
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) "Submitting…" else "Send request for company review") }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val actual = reviews.mine(organizationId, kind, draftId, installationId)
                    check(
                        !actual.moneyIssued && !actual.cashCustodyRecorded &&
                            !actual.personalLoanRecognized &&
                            !actual.payrollDeductionAuthorized &&
                            !actual.settlementRecorded
                    ) { "Unexpected finance outcome in policy-review-only data." }
                    status = actual
                    if (actual.requestState == "NOT_REQUESTED") pendingOperationId = null
                    error = null
                } catch (failure: Exception) {
                    error = failure.message ?: "Policy review status unavailable."
                } finally {
                    busy = false
                }
            }
        }) { Text("Refresh review status") }
        status?.let { result ->
            Text(
                when (result.requestState) {
                    "NOT_REQUESTED" -> "Not submitted for review."
                    "PENDING" -> "Waiting for company reviewer."
                    "APPROVED" -> "Request policy reviewed; no money issued."
                    "REJECTED" -> "Request rejected; no money issued."
                    "CHANGE_REQUESTED" -> "Company reviewer requested changes."
                    "SUPERSEDED" -> "Request source or policy changed."
                    else -> "Review: " + result.requestState
                },
            )
            result.approvalRequestId?.let {
                Text("Approval #" + it.take(8))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
