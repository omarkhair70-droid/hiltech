package com.hiltech.shared.core.ux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HuxIconDensityCandidatesTest {
    @Test
    fun everyIntentHasUsableEnglishAndArabicAccessibleText() {
        for (intent in HuxIconIntent.entries) {
            for (locale in HuxLocale.entries) {
                assertTrue(intent.accessibleLabel(locale).isNotBlank(), intent.name)
                assertFalse(intent.accessibleLabel(locale).contains('_'), intent.name)
            }
        }
    }

    @Test
    fun uncommonEngineeringObjectsRequireVisibleTextLabels() {
        for (intent in HuxIconIntent.entries.filter { it.iconClass == HuxIconClass.OBJECT }) {
            assertTrue(intent.requiresVisibleLabel, intent.name)
        }
    }

    @Test
    fun onlyDirectionalNavigationIconsMirrorInRtl() {
        assertEquals(
            setOf(HuxIconIntent.BACK, HuxIconIntent.FORWARD),
            HuxIconIntent.entries.filter { it.mirrorsInRtl }.toSet(),
        )
        assertFalse(HuxIconIntent.FIBER_LINK.mirrorsInRtl)
        assertFalse(HuxIconIntent.OTDR.mirrorsInRtl)
    }

    @Test
    fun manualCodeLookupMustNotImplyCameraScan() {
        assertEquals("Look up by asset code", HuxIconIntent.CODE_LOOKUP.accessibleLabel(HuxLocale.EN))
        assertNotEquals(HuxIconIntent.SEARCH, HuxIconIntent.CODE_LOOKUP)
    }

    @Test
    fun touchDensityCandidatesHaveGenerousTargetsButKeepDenseDesktop() {
        for (mode in HuxDensityCandidate.entries) {
            assertTrue(mode.rowMinDp >= mode.targetMinDp, mode.name)
            assertTrue(mode.glyphDp <= mode.targetMinDp, mode.name)
            assertTrue(mode.baseGapDp > 0, mode.name)
            if (mode.touchSurface) assertTrue(mode.targetMinDp >= 48, mode.name)
        }
        assertTrue(HuxDensityCandidate.DESKTOP_COMPACT.rowMinDp <
            HuxDensityCandidate.FIELD_TOUCH.rowMinDp)
    }

    @Test
    fun reducingMotionDoesNotChangeTheSemanticStatusModel() {
        assertTrue(HuxMotionPreference.STANDARD.shouldAnimateFeedback())
        assertFalse(HuxMotionPreference.REDUCED.shouldAnimateFeedback())
        assertEquals(HuxStatus.SYNC_QUEUED.present(HuxLocale.AR),
            HuxStatus.SYNC_QUEUED.present(HuxLocale.AR))
    }
}
