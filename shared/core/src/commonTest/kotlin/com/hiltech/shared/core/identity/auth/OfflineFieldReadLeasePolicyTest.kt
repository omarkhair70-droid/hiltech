package com.hiltech.shared.core.identity.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineFieldReadLeasePolicyTest {
    private val valid = OfflineFieldReadLeaseProof(
        obtainedAtEpochMs = 1_000_000L,
        expiresAtEpochMs = 4_600_000L,
        nowEpochMs = 1_100_000L,
        tokenMatchesLastVerifiedBootstrap = true,
        installationMatches = true,
        identityStatus = "ACTIVE",
        hasOrganizationMembership = true,
    )

    @Test fun permitsOnlyUnexpiredSameSessionRead() {
        assertTrue(OfflineFieldReadLeasePolicy.canRead(valid))
        assertFalse(OfflineFieldReadLeasePolicy.canRead(valid.copy(tokenMatchesLastVerifiedBootstrap = false)))
        assertFalse(OfflineFieldReadLeasePolicy.canRead(valid.copy(installationMatches = false)))
        assertFalse(OfflineFieldReadLeasePolicy.canRead(valid.copy(identityStatus = "REVOKED")))
        assertFalse(OfflineFieldReadLeasePolicy.canRead(valid.copy(hasOrganizationMembership = false)))
        assertFalse(OfflineFieldReadLeasePolicy.canRead(valid.copy(nowEpochMs = 900_000L)))
        assertFalse(OfflineFieldReadLeasePolicy.canRead(valid.copy(nowEpochMs = 4_580_000L)))
        assertFalse(OfflineFieldReadLeasePolicy.canRead(valid.copy(nowEpochMs = 4_600_001L)))
    }
}
