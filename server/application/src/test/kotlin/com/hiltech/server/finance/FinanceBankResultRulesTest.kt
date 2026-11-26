package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class FinanceBankResultRulesTest {
    private val t0 = Instant.parse("2026-11-23T08:00:00Z")
    private val source = "a".repeat(64)
    private val proof = "c".repeat(64)
    private val base = FinanceBankAttemptSnapshot(
        instructionId = UUID.randomUUID(), instructionRevision = 4,
        attemptId = UUID.randomUUID(), sourceSha256 = source,
        amount = BigDecimal("410.50"), currency = "EGP",
        outcome = FinanceBankOutcome.SUBMITTED,
    )

    private fun result(
        state: FinanceBankOutcome,
        previous: FinanceBankAttemptSnapshot = base,
        eventId: UUID = UUID.randomUUID(),
        amount: BigDecimal = previous.amount,
        attemptId: UUID = previous.attemptId,
        currency: String = previous.currency,
        providerProof: String? =
            if (state == FinanceBankOutcome.UNKNOWN) null else proof,
        observed: Instant = t0,
    ) = FinanceObservedBankResult(
        eventId = eventId,
        instructionId = previous.instructionId,
        instructionRevision = previous.instructionRevision,
        attemptId = attemptId,
        sourceSha256 = previous.sourceSha256,
        amount = amount,
        currency = currency,
        outcome = state,
        providerProofSha256 = providerProof,
        receivedAt = observed,
    )

    @Test fun unknownExternalResultNeverBecomesPaidOrAutomaticallyRetried() {
        val unknownEvent = result(FinanceBankOutcome.UNKNOWN)
        val unknown = FinanceBankResultRules.record(base, unknownEvent)
        assertEquals(FinanceBankOutcome.UNKNOWN, unknown.outcome)
        assertNull(unknownEvent.providerProofSha256)
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankResultRules.record(
                unknown, result(FinanceBankOutcome.UNKNOWN, previous = unknown,
                    observed = t0.plusSeconds(1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankResultRules.record(
                unknown, result(FinanceBankOutcome.CONFIRMED_PAID, previous = unknown,
                    attemptId = UUID.randomUUID(), observed = t0.plusSeconds(1)),
            )
        }
    }

    @Test fun providerProofCanResolveUnknownWithoutNewBankAttempt() {
        val unknown = FinanceBankResultRules.record(base, result(FinanceBankOutcome.UNKNOWN))
        val paid = FinanceBankResultRules.record(
            unknown, result(FinanceBankOutcome.CONFIRMED_PAID,
                previous = unknown, observed = t0.plusSeconds(10)),
        )
        assertEquals(FinanceBankOutcome.CONFIRMED_PAID, paid.outcome)
        assertEquals(base.attemptId, paid.attemptId)
    }

    @Test fun terminalPaymentProofIsMandatory() {
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankResultRules.record(
                base, result(FinanceBankOutcome.CONFIRMED_PAID, providerProof = null),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankResultRules.record(
                base, result(FinanceBankOutcome.CONFIRMED_FAILED, providerProof = "forged"),
            )
        }
    }

    @Test fun sameLastEventReplayIsExactlyIdempotent() {
        val confirmed = result(FinanceBankOutcome.CONFIRMED_PAID)
        val paid = FinanceBankResultRules.record(base, confirmed)
        assertEquals(paid, FinanceBankResultRules.record(paid, confirmed))
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankResultRules.record(paid,
                confirmed.copy(outcome = FinanceBankOutcome.REVERSED))
        }
    }

    @Test fun differentAmountCurrencyAndSourceVersionCannotAlterPayment() {
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankResultRules.record(base,
                result(FinanceBankOutcome.CONFIRMED_PAID,
                    amount = BigDecimal("410.51")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankResultRules.record(base,
                result(FinanceBankOutcome.CONFIRMED_PAID, currency = "USD"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankResultRules.record(base,
                result(FinanceBankOutcome.CONFIRMED_PAID)
                    .copy(instructionRevision = base.instructionRevision + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankResultRules.record(base,
                result(FinanceBankOutcome.CONFIRMED_PAID)
                    .copy(sourceSha256 = "b".repeat(64)))
        }
    }

    @Test fun failedAndReturnedAreTerminalAndPaidCanOnlyBeCorrectedWithProof() {
        val failed = FinanceBankResultRules.record(
            base, result(FinanceBankOutcome.CONFIRMED_FAILED))
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankResultRules.record(failed,
                result(FinanceBankOutcome.CONFIRMED_PAID,
                    previous = failed, observed = t0.plusSeconds(1)))
        }
        val paid = FinanceBankResultRules.record(
            base, result(FinanceBankOutcome.CONFIRMED_PAID))
        val returned = FinanceBankResultRules.record(paid,
            result(FinanceBankOutcome.RETURNED,
                previous = paid, observed = t0.plusSeconds(1)))
        assertEquals(FinanceBankOutcome.RETURNED, returned.outcome)
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankResultRules.record(returned,
                result(FinanceBankOutcome.CONFIRMED_PAID,
                    previous = returned, observed = t0.plusSeconds(2)))
        }
    }

    @Test fun earlierResultMustGoToExceptionReconciliation() {
        val paid = FinanceBankResultRules.record(base,
            result(FinanceBankOutcome.CONFIRMED_PAID, observed = t0.plusSeconds(5)))
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankResultRules.record(paid,
                result(FinanceBankOutcome.RETURNED, previous = paid,
                    observed = t0.plusSeconds(4)))
        }
    }
}
