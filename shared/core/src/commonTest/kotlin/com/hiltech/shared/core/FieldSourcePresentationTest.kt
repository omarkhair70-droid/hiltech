package com.hiltech.shared.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldSourcePresentationTest {
    @Test
    fun currentlySelectedAssignmentDisplaysMatchingServerBundle() {
        assertTrue(fieldSourceAllowsSelectedBundle(
            selectedWorkOrderId = "work-2",
            bundleWorkOrderId = "work-2",
            todayWorkOrderIds = listOf("work-1", "work-2"),
        ))
    }

    @Test
    fun staleBundleIsHiddenAfterSelectingDifferentWorkOrder() {
        assertFalse(fieldSourceAllowsSelectedBundle(
            selectedWorkOrderId = "work-2",
            bundleWorkOrderId = "work-1",
            todayWorkOrderIds = listOf("work-1", "work-2"),
        ))
    }

    @Test
    fun removedAssignmentImmediatelyStopsPresentingOldDetail() {
        assertFalse(fieldSourceAllowsSelectedBundle(
            selectedWorkOrderId = "work-1",
            bundleWorkOrderId = "work-1",
            todayWorkOrderIds = listOf("work-2"),
        ))
    }

    @Test
    fun emptyOrMissingSelectionCannotRenderPreviouslyLoadedBundle() {
        assertFalse(fieldSourceAllowsSelectedBundle(
            selectedWorkOrderId = null,
            bundleWorkOrderId = "work-1",
            todayWorkOrderIds = listOf("work-1"),
        ))
        assertFalse(fieldSourceAllowsSelectedBundle(
            selectedWorkOrderId = "",
            bundleWorkOrderId = "work-1",
            todayWorkOrderIds = listOf("work-1"),
        ))
    }

    @Test
    fun offlineSnapshotWithoutItsTodayEntryIsNotPresentedAsCurrent() {
        assertFalse(fieldSourceAllowsSelectedBundle(
            selectedWorkOrderId = "work-1",
            bundleWorkOrderId = "work-1",
            todayWorkOrderIds = emptyList(),
        ))
    }

    @Test
    fun reorderingDoesNotBreakSourceIdentityMatch() {
        assertTrue(fieldSourceAllowsSelectedBundle(
            selectedWorkOrderId = "work-2",
            bundleWorkOrderId = "work-2",
            todayWorkOrderIds = listOf("work-3", "work-2", "work-1"),
        ))
    }
    @Test
    fun focusIsClearedAfterRemovalAndDoesNotReopenOnLaterRegrant() {
        var focusedId: String? = "work-2"
        assertTrue(fieldFocusStillAssigned(focusedId, listOf("work-1", "work-2")))
        if (!fieldFocusStillAssigned(focusedId, listOf("work-1"))) {
            focusedId = null
        }
        assertFalse(fieldFocusStillAssigned(focusedId, listOf("work-1", "work-2")))
    }

    @Test
    fun focusIgnoresReorderingButRejectsBlankAndOtherAssignments() {
        assertTrue(fieldFocusStillAssigned("work-2", listOf("work-3", "work-2", "work-1")))
        assertFalse(fieldFocusStillAssigned("work-4", listOf("work-3", "work-2")))
        assertFalse(fieldFocusStillAssigned(null, listOf("work-2")))
        assertFalse(fieldFocusStillAssigned("", listOf("work-2")))
    }

}
