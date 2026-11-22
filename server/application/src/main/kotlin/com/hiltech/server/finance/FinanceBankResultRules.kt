package com.hiltech.server.finance

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Standalone future-facing result reducer only. This cannot originate or
 * transmit a payment, reserve cash, link a bank account or post to any ledger.
 *
 * Future provider adapters must additionally verify source, signatures,
 * transaction IDs, booking and authority before calling this reducer.
 */
enum class FinanceBankOutcome {
    SUBMITTED,
    UNKNOWN,
    CONFIRMED_PAID,
    CONFIRMED_FAILED,
    RETURNED,
    REVERSED,
}

data class FinanceObservedBankResult(
    val eventId: UUID,
    val instructionId: UUID,
    val instructionRevision: Long,
    val attemptId: UUID,
    val sourceSha256: String,
    val amount: BigDecimal,
    val currency: String,
    val outcome: FinanceBankOutcome,
    val providerProofSha256: String? = null,
    val receivedAt: Instant,
)

data class FinanceBankAttemptSnapshot(
    val instructionId: UUID,
    val instructionRevision: Long,
    val attemptId: UUID,
    val sourceSha256: String,
    val amount: BigDecimal,
    val currency: String,
    val outcome: FinanceBankOutcome,
    val lastEventId: UUID? = null,
    val lastEventFingerprint: String? = null,
    val lastReceivedAt: Instant? = null,
)

/**
 * Fail-closed for retries, stale versions, changed amounts and unknown outcome.
 * No new bank attempt is created in this module.
 */
object FinanceBankResultRules {
    fun record(
        current: FinanceBankAttemptSnapshot,
        incoming: FinanceObservedBankResult,
    ): FinanceBankAttemptSnapshot {
        require(current.instructionId == incoming.instructionId) {
            "Different source payment instruction"
        }
        require(current.instructionRevision > 0 &&
            current.instructionRevision == incoming.instructionRevision) {
            "Stale or different source payment revision"
        }
        require(current.attemptId == incoming.attemptId) {
            "Never substitute or silently retry a bank attempt"
        }
        require(current.sourceSha256 == incoming.sourceSha256 &&
            shaIsValid(incoming.sourceSha256)) {
            "Different or unverified source digest"
        }
        require(current.amount.signum() > 0 && incoming.amount.signum() > 0 &&
            current.amount.compareTo(incoming.amount) == 0) {
            "Result amount must equal immutable submitted amount"
        }
        require(current.currency == incoming.currency &&
            Regex("^[A-Z]{3}$").matches(current.currency)) {
            "Result currency must equal immutable submitted currency"
        }

        val fingerprint = fingerprint(incoming)
        if (current.lastEventId == incoming.eventId) {
            require(current.lastEventFingerprint == fingerprint) {
                "Same result event ID with conflicting content"
            }
            return current // Exact last event replay must never send money.
        }

        require(current.lastReceivedAt == null ||
            !incoming.receivedAt.isBefore(current.lastReceivedAt)) {
            "Out of order provider result; reconcile explicitly"
        }
        val allowedNext = when (current.outcome) {
            FinanceBankOutcome.SUBMITTED ->
                setOf(
                    FinanceBankOutcome.UNKNOWN,
                    FinanceBankOutcome.CONFIRMED_PAID,
                    FinanceBankOutcome.CONFIRMED_FAILED,
                )
            FinanceBankOutcome.UNKNOWN ->
                setOf(
                    FinanceBankOutcome.CONFIRMED_PAID,
                    FinanceBankOutcome.CONFIRMED_FAILED,
                )
            FinanceBankOutcome.CONFIRMED_PAID ->
                setOf(FinanceBankOutcome.RETURNED, FinanceBankOutcome.REVERSED)
            FinanceBankOutcome.CONFIRMED_FAILED,
            FinanceBankOutcome.RETURNED,
            FinanceBankOutcome.REVERSED -> emptySet()
        }
        require(incoming.outcome in allowedNext) {
            "Illegal bank result transition; do not issue a new payment"
        }

        // Confirmed outcomes must have independently validated proof, not
        // an operator assertion or a timeout. UNKNOWN is never PAID.
        if (incoming.outcome != FinanceBankOutcome.UNKNOWN) {
            require(incoming.providerProofSha256?.let(::shaIsValid) == true) {
                "Terminal external result requires provider proof digest"
            }
        }
        return current.copy(
            outcome = incoming.outcome,
            lastEventId = incoming.eventId,
            lastEventFingerprint = fingerprint,
            lastReceivedAt = incoming.receivedAt,
        )
    }

    private fun shaIsValid(sha: String): Boolean =
        Regex("^[0-9a-f]{64}$").matches(sha)

    private fun fingerprint(event: FinanceObservedBankResult): String {
        val canonical = listOf(
            event.eventId,
            event.instructionId,
            event.instructionRevision,
            event.attemptId,
            event.sourceSha256,
            event.amount.stripTrailingZeros().toPlainString(),
            event.currency,
            event.outcome.name,
            event.providerProofSha256 ?: "<missing>",
            event.receivedAt,
        ).joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
