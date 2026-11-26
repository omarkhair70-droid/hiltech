package com.hiltech.shared.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeOperationalWorkspaceGateTest {
    @Test
    fun sourceGrantIsNecessaryButNotSufficientForNativeVisibility() {
        assertTrue(nativeWorkspaceMayBeShown(
            featureEnabled = true, authenticated = true, currentSourceGrant = true,
        ))
        assertFalse(nativeWorkspaceMayBeShown(true, true, false))
        assertFalse(nativeWorkspaceMayBeShown(true, false, true))
        assertFalse(nativeWorkspaceMayBeShown(false, true, true))
        assertFalse(nativeWorkspaceMayBeShown(false, false, false))
    }

    @Test
    fun staleOrRevokedSourceReadMustHideThePreviouslyVisibleRoute() {
        val grantBeforeRevocation = nativeWorkspaceMayBeShown(true, true, true)
        val grantAfterRevocation = nativeWorkspaceMayBeShown(true, true, false)
        assertTrue(grantBeforeRevocation)
        assertFalse(grantAfterRevocation)
    }
}
