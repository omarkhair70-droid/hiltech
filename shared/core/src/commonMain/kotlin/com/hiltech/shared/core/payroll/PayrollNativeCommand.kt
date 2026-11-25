package com.hiltech.shared.core.payroll

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiltech.shared.core.network.HiltechApiException

internal sealed interface PayrollNativeAction {
    data class CreatePeriod(val request: CreatePayrollPeriodRequestDto) : PayrollNativeAction
    data class Capture(val periodId: String) : PayrollNativeAction
    data class ReviewException(val periodId: String, val exceptionId: String, val note: String) : PayrollNativeAction
    data class SaveSource(val periodId: String, val request: PayrollSourceRequestDto) : PayrollNativeAction
    data class RequestSourceReview(val recordId: String, val periodId: String) : PayrollNativeAction
    data class DecideSource(val recordId: String, val decision: String, val periodId: String, val comment: String? = null) : PayrollNativeAction
    data class PrepareRun(val periodId: String) : PayrollNativeAction
    data class RequestRunReview(val runId: String, val periodId: String) : PayrollNativeAction
    data class DecideRun(val runId: String, val periodId: String, val approvalRequestId: String, val decision: String, val comment: String?) : PayrollNativeAction
    data class ReleaseRun(val runId: String, val periodId: String) : PayrollNativeAction
    data class HandoffRun(val runId: String, val periodId: String) : PayrollNativeAction
}

internal val PayrollNativeAction.periodId: String?
    get() = when (this) {
        is PayrollNativeAction.CreatePeriod -> null
        is PayrollNativeAction.Capture -> periodId
        is PayrollNativeAction.ReviewException -> periodId
        is PayrollNativeAction.SaveSource -> periodId
        is PayrollNativeAction.RequestSourceReview -> periodId
        is PayrollNativeAction.DecideSource -> periodId
        is PayrollNativeAction.PrepareRun -> periodId
        is PayrollNativeAction.RequestRunReview -> periodId
        is PayrollNativeAction.DecideRun -> periodId
        is PayrollNativeAction.ReleaseRun -> periodId
        is PayrollNativeAction.HandoffRun -> periodId
    }

internal val PayrollNativeAction.runId: String?
    get() = when (this) {
        is PayrollNativeAction.RequestRunReview -> runId
        is PayrollNativeAction.DecideRun -> runId
        is PayrollNativeAction.ReleaseRun -> runId
        is PayrollNativeAction.HandoffRun -> runId
        else -> null
    }

internal data class PayrollNativeCommand(val operationId: String, val action: PayrollNativeAction)

/**
 * A timeout retains one immutable payload and operation ID. A rejection of a
 * later retry cannot prove that the earlier attempt did not commit. The
 * session is held by the identity-scoped workspace, beyond the active screen.
 */
internal class PayrollCommandSession {
    var pending by mutableStateOf<PayrollNativeCommand?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var errorCode by mutableStateOf<String?>(null)
        private set
    private var wasUnknown = false
    val locked: Boolean get() = pending != null

    fun begin(action: PayrollNativeAction, newOperationId: () -> String): PayrollNativeCommand? {
        if (pending != null) return null
        return PayrollNativeCommand(newOperationId(), action).also {
            pending = it
            busy = true
            wasUnknown = false
            errorCode = null
        }
    }

    fun retry(): PayrollNativeCommand? {
        if (busy || !wasUnknown) return null
        return pending?.also { busy = true; errorCode = null }
    }

    fun succeed(command: PayrollNativeCommand): Boolean {
        if (pending != command || !busy) return false
        pending = null
        busy = false
        wasUnknown = false
        errorCode = null
        return true
    }

    fun fail(command: PayrollNativeCommand, failure: Throwable) {
        if (pending != command || !busy) return
        val api = failure as? HiltechApiException
        val definite = api?.httpStatus?.let { it in 400..499 && it != 408 } == true &&
            api.envelope?.code == api.code && !api.retryable
        if (definite && (!wasUnknown || api?.code == "OPERATION_PREVIOUSLY_FAILED")) {
            pending = null
            wasUnknown = false
            errorCode = api?.code
        } else {
            wasUnknown = true
            errorCode = "PAYROLL_COMMAND_UNCERTAIN"
        }
        busy = false
    }
}
