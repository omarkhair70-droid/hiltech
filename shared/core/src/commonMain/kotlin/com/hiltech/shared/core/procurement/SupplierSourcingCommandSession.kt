package com.hiltech.shared.core.procurement

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiltech.shared.core.network.HiltechApiException

internal sealed interface SupplierSourcingCommand {
    data class Open(val requirementId: String, val payload: OpenSupplierRfqDto) : SupplierSourcingCommand
    data class Quote(val rfqId: String, val payload: RecordSupplierQuoteDto, val requirementId: String) : SupplierSourcingCommand
}

/** Session memory survives navigation; an uncertain response never changes the original request. */
internal class SupplierSourcingCommandSession {
    var pending by mutableStateOf<SupplierSourcingCommand?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var errorCode by mutableStateOf<String?>(null)
        private set
    private var unknown = false
    val opened = mutableStateMapOf<String, SupplierRfqReceiptDto>()
    val recorded = mutableStateMapOf<String, SupplierQuoteReceiptDto>()

    fun begin(command: SupplierSourcingCommand): Boolean {
        if (pending != null || busy) return false
        pending = command; busy = true; unknown = false; errorCode = null
        return true
    }

    fun retry(): SupplierSourcingCommand? = if (busy || !unknown) null
        else pending?.also { busy = true; errorCode = null }

    fun succeeded(command: SupplierSourcingCommand) {
        if (pending != command || !busy) return
        pending = null; busy = false; unknown = false; errorCode = null
    }

    fun failed(command: SupplierSourcingCommand, failure: Throwable) {
        if (pending != command || !busy) return
        val api = failure as? HiltechApiException
        val rejected = api?.httpStatus?.let { it in 400..499 && it != 408 } == true &&
            api.envelope?.code == api.code && !api.retryable && api.code != "IDEMPOTENCY_KEY_REUSED"
        if (rejected && (!unknown || api?.code == "OPERATION_PREVIOUSLY_FAILED")) {
            pending = null; unknown = false; errorCode = api?.code
        } else { unknown = true; errorCode = "SOURCING_RESULT_UNCONFIRMED" }
        busy = false
    }
}
