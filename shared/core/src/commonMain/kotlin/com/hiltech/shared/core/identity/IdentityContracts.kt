package com.hiltech.shared.core.identity

import kotlinx.serialization.Serializable

@Serializable
data class IdentityBootstrapDto(
    val identityId: String,
    val identityStatus: String,
    val identityVersion: Long,
    val primaryOrganizationId: String? = null,
    val organizations: List<IdentityOrganizationDto>,
    val teams: List<IdentityTeamDto>,
    val device: IdentityDeviceDto? = null,
)

@Serializable
data class IdentityOrganizationDto(
    val membershipId: String,
    val organizationId: String,
    val organizationCode: String,
    val displayName: String,
    val organizationType: String,
    val membershipType: String,
    val roleLabel: String? = null,
    val primary: Boolean,
    val membershipVersion: Long,
)

@Serializable
data class IdentityTeamDto(
    val teamMembershipId: String,
    val teamId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val roleInTeam: String? = null,
)

@Serializable
data class DeviceRegistrationDto(
    val installationId: String,
    val platform: String,
    val deviceName: String? = null,
    val appVersion: String,
    val osVersion: String? = null,
)

@Serializable
data class IdentityDeviceDto(
    val deviceId: String,
    val installationId: String,
    val platform: String,
    val deviceName: String? = null,
    val appVersion: String,
    val osVersion: String? = null,
    val lastSeenAt: String? = null,
    val version: Long,
)
