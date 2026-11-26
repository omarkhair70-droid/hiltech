package com.hiltech.shared.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeWorkspaceSessionTest {
    @Test
    fun unavailableSourcesAreNotMistakenForDeniedAccess() {
        assertEquals(WorkspaceAvailability.DENIED, workspaceFailureAvailability(403))
        assertEquals(WorkspaceAvailability.DENIED, workspaceFailureAvailability(401))
        assertEquals(WorkspaceAvailability.UNAVAILABLE, workspaceFailureAvailability(503))
        assertEquals(WorkspaceAvailability.UNAVAILABLE, workspaceFailureAvailability(null))
    }

    @Test
    fun openRequiresCurrentGrantAndRecheck() {
        val session = NativeWorkspaceSession()
        session.open("finance")
        assertNull(session.pendingId)
        session.availability["finance"] = WorkspaceAvailability.AVAILABLE
        session.open("finance")
        assertNull(session.selectedId)
        session.completeOpen("finance", WorkspaceAvailability.DENIED)
        assertNull(session.selectedId)
        session.availability["finance"] = WorkspaceAvailability.AVAILABLE
        session.open("finance")
        session.completeOpen("finance", WorkspaceAvailability.AVAILABLE)
        assertEquals("finance", session.selectedId)
        session.refresh()
        assertNull(session.selectedId)
        assertEquals(0, session.availability.size)
    }

    @Test
    fun closedRouteCannotBeReopenedByLateResult() {
        val session = NativeWorkspaceSession()
        session.availability["executive"] = WorkspaceAvailability.AVAILABLE
        session.open("executive")
        session.close()
        session.completeOpen("executive", WorkspaceAvailability.AVAILABLE)
        assertNull(session.selectedId)
    }
}
