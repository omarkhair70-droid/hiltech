package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException

/**
 * Keep an already displayed employee during a transient refresh failure so a
 * successful one-time account handoff is not destroyed by an unrelated read.
 * Callers must still match the employee, organization and active session.
 * An authoritative rejection clears the selection; this never grants an action.
 */
fun canRetainEmployeeAfterRefreshFailure(failure: Throwable): Boolean {
    if (failure is CancellationException) return false
    val api = failure as? HiltechApiException ?: return false
    if (api.code == "REAUTH_REQUIRED") return false
    return api.httpStatus?.let { it in 500..599 || it == 408 || it == 429 }
        ?: (api.code == "NETWORK_UNAVAILABLE")
}
