package com.hiltech.shared.core.identity.auth

/** A cached read is never an authorization source. This lease only gates stale local viewing. */
data class OfflineFieldReadLeaseProof(
    val obtainedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val nowEpochMs: Long,
    val tokenMatchesLastVerifiedBootstrap: Boolean,
    val installationMatches: Boolean,
    val identityStatus: String,
    val hasOrganizationMembership: Boolean,
)

object OfflineFieldReadLeasePolicy {
    private const val EXPIRY_SKEW_MS = 30_000L

    fun canRead(proof: OfflineFieldReadLeaseProof): Boolean =
        proof.tokenMatchesLastVerifiedBootstrap &&
            proof.installationMatches &&
            proof.identityStatus == "ACTIVE" &&
            proof.hasOrganizationMembership &&
            proof.obtainedAtEpochMs > 0 &&
            proof.nowEpochMs >= proof.obtainedAtEpochMs &&
            proof.expiresAtEpochMs > proof.obtainedAtEpochMs &&
            proof.nowEpochMs < proof.expiresAtEpochMs - EXPIRY_SKEW_MS
}
