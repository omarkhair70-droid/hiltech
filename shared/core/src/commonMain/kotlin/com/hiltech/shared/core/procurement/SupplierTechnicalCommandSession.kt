package com.hiltech.shared.core.procurement

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiltech.shared.core.network.HiltechApiException

internal sealed interface SupplierTechnicalCommand {
    val organizationId: String
    val quoteId: String
    val title: String
    data class Publish(override val organizationId: String, override val quoteId: String, override val title: String,
        val payload: SupplierTechnicalPublishRequest) : SupplierTechnicalCommand
    data class Assess(override val organizationId: String, override val quoteId: String, override val title: String,
        val payload: SupplierTechnicalAssessmentCommand) : SupplierTechnicalCommand
}

/** Owned by a full-session-bound client so closing a dialog never forgets an uncertain command. */
internal class SupplierTechnicalCommandSession {
    var pending by mutableStateOf<SupplierTechnicalCommand?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var errorCode by mutableStateOf<String?>(null)
        private set
    private var unknown = false
    var upload by mutableStateOf<SupplierTechnicalUploadIntent?>(null)
    var uploadFile by mutableStateOf<ServiceSelectedFile?>(null)
    var uploadBusy by mutableStateOf(false)
    var uploadError by mutableStateOf<String?>(null)

    fun begin(command: SupplierTechnicalCommand): Boolean {
        if (pending != null || busy || upload != null) return false
        pending = command; busy = true; unknown = false; errorCode = null
        return true
    }
    fun retry(): SupplierTechnicalCommand? = if (busy) null else pending?.also { busy = true; errorCode = null }
    fun succeeded(command: SupplierTechnicalCommand) {
        if (pending != command || !busy) return
        pending = null; busy = false; unknown = false; errorCode = null
    }
    fun failed(command: SupplierTechnicalCommand, failure: Throwable) {
        if (pending != command || !busy) return
        val api = failure as? HiltechApiException
        val rejected = api?.httpStatus?.let { it in 400..499 && it != 408 } == true && api.envelope?.code == api.code && !api.retryable &&
            api.code !in setOf("IDEMPOTENCY_KEY_REUSED", "REAUTH_REQUIRED", "UNAUTHENTICATED")
        if (rejected && (!unknown || api?.code == "OPERATION_PREVIOUSLY_FAILED")) {
            pending = null; unknown = false; errorCode = api?.code
        } else {
            unknown = true; errorCode = if (api?.code in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED")) api?.code else "SUPPLIER_TECHNICAL_RESULT_UNCONFIRMED"
        }
        busy = false
    }
}
