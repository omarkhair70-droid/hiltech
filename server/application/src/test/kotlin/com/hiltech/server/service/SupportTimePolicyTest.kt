package com.hiltech.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SupportTimePolicyTest {
    private val opened = Instant.parse("2026-11-23T08:00:00Z")

    @Test
    fun firstAttentionIsNotTheClientContractualResponse() {
        val result = SupportTimePolicy.observe(
            opened, opened.plusSeconds(900),
            listOf(SupportTimeSourceEvent(
                "INTERNAL_ATTENTION_RECORDED", opened.plusSeconds(120),
            )),
        )
        assertEquals(opened.plusSeconds(120), result.firstAttentionAt)
        assertEquals(120L, result.firstAttentionElapsedSeconds)
        assertEquals(900L, result.wallSeconds)
        assertEquals(0L, result.notedHoldSeconds)
        assertNull(result.activeHoldStartedAt)
    }

    @Test
    fun holdPairsAreObservedWithoutSubtractingFromTheSlaClock() {
        val result = SupportTimePolicy.observe(
            opened, opened.plusSeconds(2400),
            listOf(
                SupportTimeSourceEvent("INTERNAL_HOLD_STARTED", opened.plusSeconds(100)),
                SupportTimeSourceEvent("INTERNAL_HOLD_ENDED", opened.plusSeconds(220)),
                SupportTimeSourceEvent("INTERNAL_ATTENTION_RECORDED", opened.plusSeconds(300)),
                SupportTimeSourceEvent("INTERNAL_HOLD_STARTED", opened.plusSeconds(1400)),
            ),
        )
        assertEquals(1120L, result.notedHoldSeconds)
        assertEquals(2400L, result.wallSeconds)
        assertEquals(opened.plusSeconds(1400), result.activeHoldStartedAt)
        assertEquals(300L, result.firstAttentionElapsedSeconds)
    }

    @Test
    fun firstAttentionCanBeRecordedOnceAndHoldsMustAlternate() {
        assertTrue(SupportTimePolicy.nextAllowed(
            "INTERNAL_ATTENTION_RECORDED", true, false, false))
        assertFalse(SupportTimePolicy.nextAllowed(
            "INTERNAL_ATTENTION_RECORDED", true, true, false))
        assertTrue(SupportTimePolicy.nextAllowed(
            "INTERNAL_HOLD_STARTED", true, false, false))
        assertFalse(SupportTimePolicy.nextAllowed(
            "INTERNAL_HOLD_STARTED", true, false, true))
        assertTrue(SupportTimePolicy.nextAllowed(
            "INTERNAL_HOLD_ENDED", true, false, true))
        assertFalse(SupportTimePolicy.nextAllowed(
            "INTERNAL_HOLD_ENDED", true, false, false))
        assertFalse(SupportTimePolicy.nextAllowed(
            "INTERNAL_HOLD_STARTED", false, false, false))
        assertFalse(SupportTimePolicy.nextAllowed(
            "SLA_BREACH_RECORDED", true, false, false))
    }

    @Test
    fun emptyOrFutureSourceDoesNotInventResponseOrNegativeWallTime() {
        val result = SupportTimePolicy.observe(opened, opened.minusSeconds(20), emptyList())
        assertNull(result.firstAttentionAt)
        assertNull(result.firstAttentionElapsedSeconds)
        assertEquals(0L, result.wallSeconds)
        assertEquals(0L, result.notedHoldSeconds)
    }
}
