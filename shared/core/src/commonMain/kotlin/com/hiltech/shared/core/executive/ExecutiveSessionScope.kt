package com.hiltech.shared.core.executive

/**
 * The authenticated shell supplies both values from its CURRENT verified
 * session. A delayed response from a previous account or organization is
 * never eligible for rendering.
 */
fun executiveSnapshotMatchesSession(
    response: ExecutiveHomeDto,
    expectedIdentityId: String,
    expectedOrganizationId: String,
): Boolean =
    response.viewerIdentityId.equals(expectedIdentityId, ignoreCase = true) &&
        response.organizationId.equals(expectedOrganizationId, ignoreCase = true)
