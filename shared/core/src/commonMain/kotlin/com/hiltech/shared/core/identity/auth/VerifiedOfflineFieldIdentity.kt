package com.hiltech.shared.core.identity.auth

import kotlinx.serialization.Serializable

/** Keystore-encrypted Android payload; never a grant of server-side authority. */
@Serializable
data class VerifiedOfflineFieldIdentity(
    val identityId: String,
    val identityStatus: String,
    val hasOrganizationMembership: Boolean,
    val installationId: String,
    val accessTokenDigest: String,
)
