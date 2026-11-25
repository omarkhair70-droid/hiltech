package com.hiltech.shared.core.design

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class HiltechDatePlatformTest {
    @Test
    fun conversionAgreesWithIndependentJavaTimeCalendar() {
        for (year in listOf(1, 1582, 1800, 1900, 1969, 1970, 2000, 2024, 2026, 2100, 2400, 9999)) {
            for (month in 1..12) {
                val start = LocalDate.of(year, month, 1)
                for (day in 1..start.lengthOfMonth()) {
                    val date = start.withDayOfMonth(day)
                    val expected = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                    assertEquals(expected, hiltechDateToUtcMillis(date.toString()), date.toString())
                    assertEquals(date.toString(), hiltechDateFromUtcMillis(expected), date.toString())
                }
            }
        }
    }

    @Test
    fun displayAndStoredDateDoNotChangeWithDeviceTimeZone() {
        val original = TimeZone.getDefault()
        val dateMillis = hiltechDateToUtcMillis("2026-11-25")!!
        try {
            val expectedArabic = hiltechLocalizedDate(dateMillis, "ar-EG")
            val expectedEnglish = hiltechLocalizedDate(dateMillis, "en-GB")
            for (zone in listOf("Africa/Cairo", "Pacific/Kiritimati", "America/Los_Angeles", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals("2026-11-25", hiltechDateFromUtcMillis(dateMillis), zone)
                assertEquals(expectedArabic, hiltechLocalizedDate(dateMillis, "ar-EG"), zone)
                assertEquals(expectedEnglish, hiltechLocalizedDate(dateMillis, "en-GB"), zone)
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
