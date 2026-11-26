package com.hiltech.shared.core.procurement

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiltech.shared.core.network.HiltechApiException

internal sealed interface PurchaseCandidateCommand {
    val rfqId: String
    val quoteId: String
    data class Propose(override val rfqId: String, override val quoteId: String,
        val payload: ProposePurchaseOrderCandidateDto) : PurchaseCandidateCommand
    data class Request(override val rfqId: String, override val quoteId: String, val candidateId: String,
        val payload: RequestPurchaseCandidateApprovalDto) : PurchaseCandidateCommand
}

/** Kept with the source-bound client through navigation. Uncertain requests never acquire a new key. */
internal class PurchaseCandidateCommandSession {
    var pending by mutableStateOf<PurchaseCandidateCommand?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var errorCode by mutableStateOf<String?>(null)
        private set
    val receipts = mutableStateMapOf<String, PurchaseOrderCandidateReceiptDto>()
    val approvals = mutableStateMapOf<String, PurchaseCandidateApprovalReceiptDto>()
    private var unknown = false

    fun begin(command: PurchaseCandidateCommand): Boolean {
        if (pending != null || busy) return false
        pending = command; busy = true; errorCode = null; unknown = false
        return true
    }
    fun retry(): PurchaseCandidateCommand? = if (busy || !unknown) null else pending?.also { busy = true; errorCode = null }
    fun succeeded(command: PurchaseCandidateCommand) {
        if (pending != command || !busy) return
        pending = null; busy = false; errorCode = null; unknown = false
    }
    fun failed(command: PurchaseCandidateCommand, failure: Throwable) {
        if (pending != command || !busy) return
        val api = failure as? HiltechApiException
        val definite = api?.httpStatus?.let { it in 400..499 && it != 408 } == true && api.envelope?.code == api.code && !api.retryable
        if (definite && (!unknown || api?.code == "OPERATION_PREVIOUSLY_FAILED")) {
            pending = null; unknown = false; errorCode = api?.code
        } else { unknown = true; errorCode = "PURCHASE_REVIEW_COMMAND_UNCERTAIN" }
        busy = false
    }
}
