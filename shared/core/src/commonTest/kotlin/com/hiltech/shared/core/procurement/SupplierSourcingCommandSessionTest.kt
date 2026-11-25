package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.ErrorEnvelope
import com.hiltech.shared.core.network.HiltechApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SupplierSourcingCommandSessionTest {
    private val open = SupplierSourcingCommand.Open("requirement", OpenSupplierRfqDto("operation", 3))

    @Test fun openingARevisedRequirementCannotReplaceAnUnconfirmedOriginalOperation() {
        val state = SupplierSourcingCommandSession()
        assertTrue(state.begin(open))
        state.failed(open, IllegalStateException("Lost response after commit"))
        assertFalse(state.begin(open.copy(payload = OpenSupplierRfqDto("another-operation", 4))))
        assertSame(open, state.retry())
        assertEquals(3L, (state.pending as SupplierSourcingCommand.Open).payload.expectedRequirementVersion)
    }

    @Test fun aLaterAuthorizationRejectionDoesNotExplainAnEarlierLostResponse() {
        val state = SupplierSourcingCommandSession()
        state.begin(open); state.failed(open, IllegalStateException("Lost response")); state.retry()
        state.failed(open, rejection("OBJECT_NOT_VISIBLE", 404))
        assertSame(open, state.pending)
        assertSame(open, state.retry())
        state.succeeded(open)
        assertNull(state.pending)
    }

    @Test fun aConfirmedInitialRejectionAllowsCorrectingTheSource() {
        val state = SupplierSourcingCommandSession()
        state.begin(open); state.failed(open, rejection("SOURCING_SOURCE_STALE", 409))
        assertNull(state.pending)
        assertTrue(state.begin(open.copy(payload = OpenSupplierRfqDto("another-operation", 4))))
    }

    @Test fun reusedIdempotencyKeyIsNotTreatedAsProofThatTheRequestFailed() {
        val state = SupplierSourcingCommandSession()
        state.begin(open); state.failed(open, rejection("IDEMPOTENCY_KEY_REUSED", 409))
        assertSame(open, state.retry())
    }

    @Test fun anUnrelatedReceiptCannotUnlockAPendingOperation() {
        val state = SupplierSourcingCommandSession()
        state.begin(open)
        state.succeeded(open.copy(requirementId = "different-requirement"))
        assertTrue(state.busy)
        assertSame(open, state.pending)
        assertNull(SupplierSourcingCommandSession().pending)
    }

    @Test fun localNumericEntryAcceptsArabicDigitsWithoutChangingPrecisionOrAcceptingNegativeAmounts() {
        assertEquals("100.125", quoteNumericInput("١٠٠٫١٢٥"))
        assertEquals("200.50", quoteNumericInput("۲۰۰.۵۰"))
        assertTrue(quoteDecimalValid("100.125", true))
        assertTrue(quoteDecimalValid("0.000001", true))
        assertTrue(quoteDecimalValid("0", false))
        assertFalse(quoteDecimalValid("0", true))
        assertFalse(quoteDecimalValid("-1", false))
        assertFalse(quoteDecimalValid("1.0000001", true))
        assertFalse(quoteDecimalValid("1e6", true))
    }

    private fun rejection(code: String, status: Int) = HiltechApiException(code, "synthetic", status, false,
        ErrorEnvelope(code, "Synthetic source rejection", "synthetic", false), "Synthetic source rejection")
}
