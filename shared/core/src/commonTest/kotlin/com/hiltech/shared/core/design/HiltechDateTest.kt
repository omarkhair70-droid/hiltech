package com.hiltech.shared.core.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HiltechDateTest {
    @Test
    fun civilDatesUseMidnightUtcWithoutDeviceZoneOffset() {
        assertEquals(0L, hiltechDateToUtcMillis("1970-01-01"))
        assertEquals(-86_400_000L, hiltechDateToUtcMillis("1969-12-31"))
        assertEquals(951_782_400_000L, hiltechDateToUtcMillis("2000-02-29"))
        assertEquals("1969-12-31", hiltechDateFromUtcMillis(-1))
        assertEquals("1970-01-01", hiltechDateFromUtcMillis(86_399_999))
    }

    @Test
    fun leapYearsAndMonthLengthsAreValidatedRatherThanNormalized() {
        listOf("1900-02-29", "2100-02-29", "2025-02-29", "2026-04-31", "2026-00-01", "2026-13-01", "2026-01-00").forEach {
            assertNull(parseHiltechDate(it), it)
        }
        listOf("2000-02-29", "2024-02-29", "2026-04-30", "2026-12-31").forEach {
            assertEquals(it, parseHiltechDate(it)?.toIso(), it)
        }
    }

    @Test
    fun wireFormatIsStrictAndDoesNotAcceptPartialOrTimestampInput() {
        listOf("2026-1-02", "26-01-02", "2026-01-02T00:00:00Z", "2026/01/02", "2026-01-02 ", "202X-01-02", "0000-01-01", "10000-01-01").forEach {
            assertNull(parseHiltechDate(it), it)
        }
    }

    @Test
    fun optionalDatesCanBeAbsentAndEqualRangeEndsAreAllowed() {
        assertTrue(isValidOptionalHiltechDate(""))
        assertNull(hiltechDateRangeProblem("", ""))
        assertNull(hiltechDateRangeProblem("2026-11-25", ""))
        assertNull(hiltechDateRangeProblem("", "2026-11-25"))
        assertNull(hiltechDateRangeProblem("2026-11-25", "2026-11-25"))
        assertNull(hiltechDateRangeProblem("2026-11-25", "2026-11-26"))
        assertFalse(isValidOptionalHiltechDate("invalid"))
    }

    @Test
    fun reversedAndInvalidRangesCannotBeSubmitted() {
        assertEquals(HiltechDateRangeProblem.END_BEFORE_START, hiltechDateRangeProblem("2026-11-26", "2026-11-25"))
        assertEquals(HiltechDateRangeProblem.INVALID_START, hiltechDateRangeProblem("2026-02-30", "2026-11-25"))
        assertEquals(HiltechDateRangeProblem.INVALID_END, hiltechDateRangeProblem("2026-11-25", "2026-13-25"))
    }

    @Test
    fun completeGregorianCycleRoundTripsIncludingCenturyBoundaries() {
        val start = hiltechDateToUtcMillis("1800-01-01")!!
        val end = hiltechDateToUtcMillis("2200-01-01")!!
        var utcMillis = start
        while (utcMillis < end) {
            val iso = hiltechDateFromUtcMillis(utcMillis)!!
            assertEquals(utcMillis, hiltechDateToUtcMillis(iso), iso)
            utcMillis += 86_400_000L
        }
    }

    @Test
    fun supportedBoundsDoNotOverflowOrWrap() {
        listOf("0001-01-01", "1582-10-15", "9999-12-31").forEach { iso ->
            assertEquals(iso, hiltechDateFromUtcMillis(hiltechDateToUtcMillis(iso)!!))
        }
        assertNull(hiltechDateFromUtcMillis(Long.MIN_VALUE))
        assertNull(hiltechDateFromUtcMillis(Long.MAX_VALUE))
    }
}
