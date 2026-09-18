package com.hiltech.spike.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveRtlPolicyTest {
    @Test
    fun phone_tablet_desktop_have_explicit_adaptive_modes() {
        assertEquals(AdaptiveMode.STACKED, adaptiveMode(360))
        assertEquals(AdaptiveMode.SPLIT, adaptiveMode(800))
        assertEquals(AdaptiveMode.WIDE, adaptiveMode(1440))
    }

    @Test
    fun mixed_arabic_label_isolates_ltr_identifiers() {
        val label = mixedArabicProjectLabel()

        assertTrue(label.contains("\u2066WO-42\u2069"))
        assertTrue(label.contains("\u206610.20.30.4\u2069"))
        assertTrue(label.contains("\u2066Fluke-03\u2069"))
    }

    @Test
    fun boundary_widths_are_deterministic() {
        assertEquals(AdaptiveMode.STACKED, adaptiveMode(599))
        assertEquals(AdaptiveMode.SPLIT, adaptiveMode(600))
        assertEquals(AdaptiveMode.SPLIT, adaptiveMode(1099))
        assertEquals(AdaptiveMode.WIDE, adaptiveMode(1100))
    }
}
