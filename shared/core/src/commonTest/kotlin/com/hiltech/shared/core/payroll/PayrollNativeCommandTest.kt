package com.hiltech.shared.core.payroll

import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.network.ErrorEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PayrollNativeCommandTest {
    @Test
    fun lostResponseKeepsTheExactOperationAndPayloadAcrossRetryDenial() {
        val session = PayrollCommandSession()
        val action = PayrollNativeAction.SaveSource("period", PayrollSourceRequestDto("", "COMPENSATION", "employee",
            sourceReference = "Synthetic source", currency = "EGP", components = listOf(PayrollSourceComponentDto("BASE", "EARNING", "125", description = "Synthetic pay"))))
        val first = assertNotNull(session.begin(action) { "fixed-operation" })
        assertNull(session.begin(action) { "different-operation" })
        session.fail(first, IllegalStateException("lost response"))
        assertTrue(session.locked)
        val retry = assertNotNull(session.retry())
        assertSame(first, retry)
        session.fail(retry, HiltechApiException("PAYROLL_FORBIDDEN", null, 403, false, null, "Denied"))
        assertTrue(session.locked)
        assertEquals("fixed-operation", session.pending?.operationId)
        assertSame(action, session.pending?.action)
        assertNotNull(session.retry())
        assertTrue(session.succeed(first))
        assertFalse(session.locked)
    }

    @Test
    fun definitiveFirstRejectionCanBeCorrectedButAnOldCallbackCannotCompleteANewCommand() {
        val session = PayrollCommandSession()
        val first = assertNotNull(session.begin(PayrollNativeAction.Capture("one")) { "operation-one" })
        session.fail(first, HiltechApiException("PAYROLL_FORBIDDEN", "synthetic", 403, false,
            ErrorEnvelope("PAYROLL_FORBIDDEN", "Denied", "synthetic"), "Denied"))
        assertFalse(session.locked)
        val second = assertNotNull(session.begin(PayrollNativeAction.Capture("two")) { "operation-two" })
        assertFalse(session.succeed(first))
        assertEquals(second, session.pending)
        assertNull(PayrollCommandSession().pending)
    }

    @Test
    fun calendarAndArabicAmountInputsHaveExactValidation() {
        assertTrue(payrollPeriodDatesValid("2026-11-01", "2026-11-30", "2026-11-25", "2026-12-01"))
        assertFalse(payrollPeriodDatesValid("2026-11-01", "2026-11-30", "2026-12-01", null))
        assertFalse(payrollPeriodDatesValid("2026-02-29", "2026-03-01", "2026-03-01", null))
        assertEquals("1250.25", payrollAmountText("١٢٥٠٫٢٥"))
        assertTrue(validPayrollAmount(payrollAmountText("١٢٥٠٫٢٥")))
        for (amount in listOf("-1", "0", "1,000", "1e3", "0.0000001")) assertFalse(validPayrollAmount(amount))
    }

    @Test
    fun proxyErrorDoesNotProveRejectionAndATerminalStoredFailureCanResolveIt() {
        val session = PayrollCommandSession()
        val command = assertNotNull(session.begin(PayrollNativeAction.Capture("period")) { "operation" })
        session.fail(command, HiltechApiException("HTTP_403", null, 403, false, null, "Proxy"))
        assertTrue(session.locked)
        assertNotNull(session.retry())
        session.fail(command, HiltechApiException("OPERATION_PREVIOUSLY_FAILED", "synthetic", 409, false,
            ErrorEnvelope("OPERATION_PREVIOUSLY_FAILED", "Stored rejection", "synthetic"), "Stored rejection"))
        assertFalse(session.locked)
    }
}
