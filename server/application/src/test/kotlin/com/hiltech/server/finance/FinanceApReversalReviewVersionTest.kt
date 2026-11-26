package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FinanceApReversalReviewVersionTest {
    private val org=UUID.randomUUID()
    private val original=UUID.randomUUID()
    private val target=UUID.randomUUID()
    private val reviewer=UUID.randomUUID()
    private val line=FinanceApReversalSourceLine(
        UUID.randomUUID(),UUID.randomUUID(),
        FinanceJournalSide.DEBIT,BigDecimal("120.000000"),
    )
    private fun version(
        targetPeriod:UUID=target,reason:String="DUPLICATE-INVOICE",
        sourceHash:String="a".repeat(64),lineAmount:BigDecimal=line.amount,
        reviewerVersion:Long=2,
    )=FinanceApReversalReviewVersion.derive(
        org,original,targetPeriod,sourceHash,"EGP",
        BigDecimal("120.000000"),reason,
        listOf(line.copy(amount=lineAmount)),3,reviewer,reviewerVersion,
    )
    @Test fun stableExactInputReplaysSameVersion() {
        assertEquals(version(),version())
    }
    @Test fun changedReasonPeriodSourceLinesOrAuthorityInvalidatesReview() {
        val v=version()
        assertNotEquals(v,version(reason="PRICE-CORRECTION"))
        assertNotEquals(v,version(targetPeriod=UUID.randomUUID()))
        assertNotEquals(v,version(sourceHash="b".repeat(64)))
        assertNotEquals(v,version(lineAmount=BigDecimal("119.000000")))
        assertNotEquals(v,version(reviewerVersion=3))
    }
}
