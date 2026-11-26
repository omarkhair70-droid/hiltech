package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmployeeRefreshFailureTest {
    @Test
    fun anUnavailableReadDoesNotEraseAnAlreadySuccessfulHandoff() {
        assertTrue(canRetainEmployeeAfterRefreshFailure(api(null, "NETWORK_UNAVAILABLE")))
    }

    @Test
    fun serverFailurePreservesTheCurrentEmployee() {
        for (status in listOf(500, 502, 503, 504)) {
            assertTrue(canRetainEmployeeAfterRefreshFailure(api(status)))
        }
    }

    @Test
    fun timeoutOrThrottlingDoesNotAssertThatAccessWasRevoked() {
        for (status in listOf(408, 429)) {
            assertTrue(canRetainEmployeeAfterRefreshFailure(api(status)))
        }
    }

    @Test
    fun authoritativeDenialClearsEmployeeEvenWhenMarkedRetryable() {
        for (status in listOf(400, 401, 403, 404, 409, 422)) {
            assertFalse(canRetainEmployeeAfterRefreshFailure(api(status)))
        }
    }

    @Test
    fun reauthenticationClearsEmployeeWithoutAnHttpResponse() {
        assertFalse(canRetainEmployeeAfterRefreshFailure(api(null, "REAUTH_REQUIRED")))
    }

    @Test
    fun cancelledScopeCannotKeepAnEmployeeAlive() {
        assertFalse(canRetainEmployeeAfterRefreshFailure(CancellationException("Session ended")))
    }

    @Test
    fun unexpectedFailureDoesNotRetainAnUnverifiedSelection() {
        assertFalse(canRetainEmployeeAfterRefreshFailure(api(null)))
        assertFalse(canRetainEmployeeAfterRefreshFailure(IllegalArgumentException("Malformed response")))
    }

    private fun api(status: Int?, code: String = "SYNTHETIC_REFRESH_FAILURE") = HiltechApiException(
        code = code,
        correlationId = "synthetic-refresh",
        httpStatus = status,
        retryable = true,
        envelope = null,
        message = "Synthetic refresh failure",
    )
}
