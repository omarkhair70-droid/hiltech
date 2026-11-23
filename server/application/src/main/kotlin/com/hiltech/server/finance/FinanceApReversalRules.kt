package com.hiltech.server.finance

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Phase 8N: deterministic preflight for an append-only AP journal reversal.
 * This does NOT post a reversal or grant authority to one. A later transactional
 * command must re-read/lock the source, verify independent approval, and insert
 * an immutable reversing event in an OPEN period before reporting net AP.
 */
data class FinanceApReversalSourceLine(
    val sourceLineId: UUID,
    val glAccountId: UUID,
    val side: FinanceJournalSide,
    val amount: BigDecimal,
)

data class FinanceApReversalSnapshot(
    val organizationId: UUID,
    val sourceJournalId: UUID,
    val sourceCurrency: String,
    val sourceGross: BigDecimal,
    val sourcePostedDate: LocalDate,
    val sourceLines: List<FinanceApReversalSourceLine>,
    val sourcePeriodClosed: Boolean,
    val alreadyReversed: Boolean,
)

data class FinanceApReversalTarget(
    val organizationId: UUID,
    val fiscalPeriodId: UUID,
    val currency: String,
    val postingDate: LocalDate,
    val periodOpen: Boolean,
    val periodClosedByEvent: Boolean,
)

data class FinanceApReversalLine(
    val sourceLineId: UUID,
    val glAccountId: UUID,
    val side: FinanceJournalSide,
    val amount: BigDecimal,
)

object FinanceApReversalRules {
    /**
     * Fail closed. Identical source amounts are reversed, never edited,
     * reallocated, rounded, or inferred from an invoice.
     */
    fun plan(source: FinanceApReversalSnapshot, target: FinanceApReversalTarget): List<FinanceApReversalLine> {
        require(source.organizationId == target.organizationId) { "CROSS_ORGANIZATION_REVERSAL" }
        require(!source.alreadyReversed) { "SOURCE_ALREADY_REVERSED" }
        require(target.periodOpen && !target.periodClosedByEvent) { "TARGET_PERIOD_CLOSED" }
        // A closed source period stays untouched: only a NEW open period may receive correction.
        require(!source.sourcePeriodClosed || target.postingDate > source.sourcePostedDate) {
            "CLOSED_SOURCE_REQUIRES_LATER_PERIOD"
        }
        require(!target.postingDate.isBefore(source.sourcePostedDate)) { "REVERSAL_BEFORE_ORIGINAL" }
        require(source.sourceCurrency.matches(Regex("^[A-Z]{3}$")) &&
            source.sourceCurrency == target.currency) { "CURRENCY_MISMATCH" }
        require(source.sourceGross > BigDecimal.ZERO) { "INVALID_SOURCE_GROSS" }
        require(source.sourceLines.size in 2..32) { "INVALID_SOURCE_LINES" }
        require(source.sourceLines.map { it.sourceLineId }.toSet().size == source.sourceLines.size) {
            "DUPLICATE_SOURCE_LINES"
        }
        require(source.sourceLines.all { it.amount > BigDecimal.ZERO }) { "INVALID_LINE_AMOUNT" }
        val debit = source.sourceLines.filter { it.side == FinanceJournalSide.DEBIT }
            .fold(BigDecimal.ZERO) { acc, line -> acc + line.amount }
        val credit = source.sourceLines.filter { it.side == FinanceJournalSide.CREDIT }
            .fold(BigDecimal.ZERO) { acc, line -> acc + line.amount }
        require(debit.compareTo(credit) == 0 && credit.compareTo(source.sourceGross) == 0) {
            "UNBALANCED_ORIGINAL"
        }
        return source.sourceLines.map { line ->
            FinanceApReversalLine(
                sourceLineId = line.sourceLineId,
                glAccountId = line.glAccountId,
                side = if (line.side == FinanceJournalSide.DEBIT)
                    FinanceJournalSide.CREDIT else FinanceJournalSide.DEBIT,
                amount = line.amount,
            )
        }
    }
}
