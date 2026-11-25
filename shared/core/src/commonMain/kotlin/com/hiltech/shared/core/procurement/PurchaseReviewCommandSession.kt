package com.hiltech.shared.core.procurement

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiltech.shared.core.network.HiltechApiException

internal sealed interface PurchaseReviewCommand {
    val candidateId: String
    val requestId: String
    data class Open(override val candidateId: String, override val requestId: String,
        val payload: OpenPurchaseReviewQuoteDto) : PurchaseReviewCommand
    data class Decide(override val candidateId: String, override val requestId: String,
        val payload: DecidePurchaseReviewDto) : PurchaseReviewCommand
}

/** Owned by the identity-bound API client. No document bytes or credentials are retained. */
internal class PurchaseReviewCommandSession {
    var pending by mutableStateOf<PurchaseReviewCommand?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var errorCode by mutableStateOf<String?>(null)
        private set
    var requiresReauthentication by mutableStateOf(false)
        private set
    var receipt by mutableStateOf<PurchaseReviewDecisionReceiptDto?>(null)
        private set
    private var wasUnknown = false

    fun begin(command: PurchaseReviewCommand): Boolean {
        if (pending != null || busy) return false
        pending = command; busy = true; errorCode = null; wasUnknown = false; requiresReauthentication = false
        return true
    }
    fun retry(): PurchaseReviewCommand? {
        if (busy || !wasUnknown) return null
        return pending?.also { busy = true; errorCode = null }
    }
    fun succeeded(command: PurchaseReviewCommand, decisionReceipt: PurchaseReviewDecisionReceiptDto? = null) {
        if (pending != command || !busy) return
        if (decisionReceipt != null) receipt = decisionReceipt
        pending = null; busy = false; errorCode = null; wasUnknown = false; requiresReauthentication = false
    }
    fun failed(command: PurchaseReviewCommand, error: Throwable) {
        if (pending != command || !busy) return
        val api = error as? HiltechApiException
        requiresReauthentication = api?.code == "REAUTH_REQUIRED"
        val definite = api?.httpStatus?.let { it in 400..499 && it != 408 } == true &&
            api.envelope?.code == api.code && !api.retryable
        if (definite && (!wasUnknown || api?.code == "OPERATION_PREVIOUSLY_FAILED")) {
            pending = null; wasUnknown = false; errorCode = api?.code
        } else {
            wasUnknown = true; errorCode = "PURCHASE_REVIEW_COMMAND_UNCERTAIN"
        }
        busy = false
    }
}
