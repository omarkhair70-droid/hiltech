package com.hiltech.shared.core.identity.auth

import com.hiltech.shared.core.identity.IdentityBootstrapDto
import kotlinx.serialization.Serializable

/** Keystore-encrypted Android payload; never a grant of server-side authority. */
@Serializable
data class VerifiedOfflineFieldIdentity(
    val identity: IdentityBootstrapDto,
    val installationId: String,
    val accessTokenDigest: String,
)
