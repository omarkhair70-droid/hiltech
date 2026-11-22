package com.hiltech.shared.core.ux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** UX research guards. No domain permissions are tested or modified here. */
class HuxStatusPresentationTest {
    @Test
    fun everyCandidateHasNonEmptyArabicAndEnglishVisualAndAccessibleLabels() {
        for (status in HuxStatus.entries) {
            for (locale in HuxLocale.entries) {
                val display = status.present(locale)
                assertEquals(status.name, display.key)
                assertTrue(display.visibleLabel.isNotBlank(), status.name)
                assertTrue(display.accessibleLabel.contains(display.visibleLabel), status.name)
                assertTrue(display.accessibleLabel.contains(status.axis.label(locale)), status.name)
                assertFalse(display.visibleLabel.contains('_'), status.name)
            }
        }
    }

    @Test
    fun attentionHealthLifecycleSyncAndResultRemainDistinctAxes() {
        assertEquals(HuxStatusAxis.LIFECYCLE, HuxStatus.LIFECYCLE_ACTIVE.axis)
        assertEquals(HuxStatusAxis.HEALTH, HuxStatus.HEALTH_CRITICAL.axis)
        assertEquals(HuxStatusAxis.ACTION, HuxStatus.ACTION_WAITING_ON_YOU.axis)
        assertEquals(HuxStatusAxis.SYNC, HuxStatus.SYNC_QUEUED.axis)
        assertEquals(HuxStatusAxis.RESULT, HuxStatus.RESULT_PARTIAL.axis)
        assertNotEquals(HuxStatus.SYNC_CONFIRMED.axis, HuxStatus.RESULT_SUCCESS.axis)
    }

    @Test
    fun optimisticOrOfflineStatesNeverPresentAsServerConfirmation() {
        val confirmed = HuxStatus.SYNC_CONFIRMED.present(HuxLocale.EN).visibleLabel
        for (status in listOf(
            HuxStatus.SYNC_LOCAL_ONLY,
            HuxStatus.SYNC_QUEUED,
            HuxStatus.SYNC_IN_PROGRESS,
            HuxStatus.SYNC_STALE,
            HuxStatus.SYNC_UNKNOWN,
        )) {
            val presentation = status.present(HuxLocale.EN)
            assertNotEquals(confirmed, presentation.visibleLabel)
            assertNotEquals(HuxIconRole.VERIFIED, presentation.iconRole)
            assertNotEquals(HuxVisualIntent.POSITIVE, presentation.visualIntent)
        }
    }

    @Test
    fun missingSourceAndUnknownDoNotPretendToBeFailures() {
        for (status in listOf(
            HuxStatus.SYNC_PROVIDER_UNAVAILABLE,
            HuxStatus.SYNC_UNKNOWN,
            HuxStatus.HEALTH_UNKNOWN,
            HuxStatus.RESULT_UNVERIFIED,
        )) {
            assertEquals(HuxVisualIntent.UNKNOWN, status.visualIntent)
            assertEquals(HuxIconRole.QUESTION, status.iconRole)
        }
        assertNotEquals(
            HuxStatus.SYNC_PROVIDER_UNAVAILABLE.present(HuxLocale.AR).visibleLabel,
            HuxStatus.RESULT_FAILURE.present(HuxLocale.AR).visibleLabel,
        )
    }

    @Test
    fun actionIsNotEqualToNotificationOrApprovalResult() {
        assertEquals(HuxVisualIntent.ATTENTION, HuxStatus.ACTION_WAITING_ON_YOU.visualIntent)
        assertEquals(HuxVisualIntent.INFORMATION, HuxStatus.ACTION_WAITING_EXTERNAL.visualIntent)
        assertEquals(HuxVisualIntent.NEUTRAL, HuxStatus.ACTION_NONE.visualIntent)
        assertNotEquals(HuxStatus.RESULT_SUCCESS.axis, HuxStatus.ACTION_NONE.axis)
    }

    @Test
    fun stableUniqueCandidateKeysAndAllFiveAxesAreCovered() {
        assertEquals(HuxStatus.entries.size, HuxStatus.entries.map { it.name }.toSet().size)
        assertEquals(HuxStatusAxis.entries.toSet(), HuxStatus.entries.map { it.axis }.toSet())
    }
}
