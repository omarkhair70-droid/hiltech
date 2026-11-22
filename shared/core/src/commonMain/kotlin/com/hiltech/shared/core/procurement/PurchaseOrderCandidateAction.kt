package com.hiltech.shared.core.procurement

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Explicit buyer approval-candidate step. Never uses a supplier-award button. */
@Composable
fun PurchaseOrderCandidateAction(
    api: PurchaseOrderCandidateApiClient,
    rfqId: String,
    quote: SupplierQuoteComparisonRowDto,
    installationId: String,
    newOperationId: () -> String,
) {
    val scope = rememberCoroutineScope()
    var preview by remember(rfqId, quote.quoteId) {
        mutableStateOf<PurchaseOrderCandidatePreviewDto?>(null)
    }
    var rationale by remember(rfqId, quote.quoteId) { mutableStateOf("") }
    var pendingOp by remember(rfqId, quote.quoteId) { mutableStateOf<String?>(null) }
    var busy by remember(rfqId, quote.quoteId) { mutableStateOf(false) }
    var error by remember(rfqId, quote.quoteId) { mutableStateOf<String?>(null) }
    var receipt by remember(rfqId, quote.quoteId) {
        mutableStateOf<PurchaseOrderCandidateReceiptDto?>(null)
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    preview = api.preview(rfqId, quote.quoteId, installationId)
                } catch (failure: Exception) {
                    preview = null
                    error = failure.message ?: "Candidate preflight unavailable."
                } finally {
                    busy = false
                }
            }
        }) { Text("Check before internal purchase proposal") }
        preview?.let { gate ->
            if (!gate.canPropose) {
                gate.blockers.forEach { blocker ->
                    Text("Not ready: $blocker", color = MaterialTheme.colorScheme.error)
                }
            } else if (receipt == null) {
                Text("Preflight passed. Purchase approval is still required by policy.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = rationale,
                    onValueChange = {
                        rationale = it
                        pendingOp = null
                    },
                    label = { Text("Why choose this supplier and quote?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
                OutlinedButton(
                    enabled = !busy && rationale.trim().length in 12..1000,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                // Never trust a previously displayed preflight.
                                val fresh = api.preview(rfqId, quote.quoteId, installationId)
                                preview = fresh
                                if (!fresh.canPropose) {
                                    error = "Supplier, quote, technical review or source changed."
                                } else {
                                    val operation = pendingOp ?: newOperationId().also {
                                        pendingOp = it
                                    }
                                    receipt = api.propose(
                                        rfqId,
                                        ProposePurchaseOrderCandidateDto(
                                            operationId = operation,
                                            quoteEvidenceId = quote.quoteId,
                                            expectedQuoteRevision = quote.revisionNumber,
                                            rationale = rationale.trim(),
                                        ),
                                        installationId,
                                    )
                                    pendingOp = null
                                }
                            } catch (failure: Exception) {
                                error = failure.message ?: "Candidate was not recorded."
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Record candidate for company approval") }
            }
        }
        receipt?.let {
            Text("Candidate recorded · " + it.state +
                ". No PO issued and no payment authorized.",
                style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
