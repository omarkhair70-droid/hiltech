package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class FinanceApReversalRulesTest {
    private val org = UUID.randomUUID()
    private val date = LocalDate.of(2026, 11, 23)
    private val source = FinanceApReversalSnapshot(
        organizationId = org,
        sourceJournalId = UUID.randomUUID(),
        sourceCurrency = "EGP",
        sourceGross = BigDecimal("100.000000"),
        sourcePostedDate = date,
        sourceLines = listOf(
            FinanceApReversalSourceLine(UUID.randomUUID(), UUID.randomUUID(),
                FinanceJournalSide.DEBIT, BigDecimal("80.000000")),
            FinanceApReversalSourceLine(UUID.randomUUID(), UUID.randomUUID(),
                FinanceJournalSide.DEBIT, BigDecimal("20.000000")),
            FinanceApReversalSourceLine(UUID.randomUUID(), UUID.randomUUID(),
                FinanceJournalSide.CREDIT, BigDecimal("100.000000")),
        ),
        sourcePeriodClosed = false,
        alreadyReversed = false,
    )
    private val target = FinanceApReversalTarget(
        organizationId=org, fiscalPeriodId=UUID.randomUUID(), currency="EGP",
        postingDate=date, periodOpen=true, periodClosedByEvent=false,
    )
    private fun rejects(s: FinanceApReversalSnapshot=source, t: FinanceApReversalTarget=target) {
        assertThrows(IllegalArgumentException::class.java) { FinanceApReversalRules.plan(s,t) }
    }

    @Test fun exactOppositeLinesPreserveAccountsAndAmounts() {
        val result = FinanceApReversalRules.plan(source,target)
        assertEquals(listOf(FinanceJournalSide.CREDIT, FinanceJournalSide.CREDIT,
            FinanceJournalSide.DEBIT), result.map { it.side })
        assertEquals(source.sourceLines.map { it.amount }, result.map { it.amount })
        assertEquals(source.sourceLines.map { it.glAccountId }, result.map { it.glAccountId })
        assertEquals(source.sourceLines.map { it.sourceLineId }, result.map { it.sourceLineId })
    }
    @Test fun noCrossTenantOrCurrencyReversal() {
        rejects(t=target.copy(organizationId=UUID.randomUUID()))
        rejects(t=target.copy(currency="USD"))
    }
    @Test fun noDuplicateOrClosedPeriodOrBackdatedReversal() {
        rejects(s=source.copy(alreadyReversed=true))
        rejects(t=target.copy(periodOpen=false))
        rejects(t=target.copy(periodClosedByEvent=true))
        rejects(t=target.copy(postingDate=date.minusDays(1)))
        rejects(s=source.copy(sourcePeriodClosed=true))
        assertEquals(3, FinanceApReversalRules.plan(source.copy(sourcePeriodClosed=true),
            target.copy(postingDate=date.plusDays(1))).size)
    }
    @Test fun noMalformedOrUnbalancedSource() {
        rejects(s=source.copy(sourceGross=BigDecimal("99")))
        rejects(s=source.copy(sourceLines=source.sourceLines.take(2)))
        rejects(s=source.copy(sourceLines=source.sourceLines +
            source.sourceLines.first()))
        rejects(s=source.copy(sourceLines=source.sourceLines.map {
            it.copy(amount=BigDecimal.ZERO)
        }))
    }
}
