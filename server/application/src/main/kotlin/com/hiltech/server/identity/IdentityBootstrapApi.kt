package com.hiltech.server.identity

import com.hiltech.server.organizations.OrganizationIdentityContextPort
import com.hiltech.server.platform.ProductApiException
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

data class IdentityBootstrapResponse(
    val identityId: String,
    val identityStatus: String,
    val identityVersion: Long,
    val primaryOrganizationId: String?,
    val organizations: List<IdentityOrganizationResponse>,
    val teams: List<IdentityTeamResponse>,
    val device: IdentityDeviceResponse?,
)

data class IdentityOrganizationResponse(
    val membershipId: String,
    val organizationId: String,
    val organizationCode: String,
    val displayName: String,
    val organizationType: String,
    val membershipType: String,
    val roleLabel: String?,
    val primary: Boolean,
    val membershipVersion: Long,
)

data class IdentityTeamResponse(
    val teamMembershipId: String,
    val teamId: String,
    val organizationId: String,
    val code: String,
    val name: String,
    val roleInTeam: String?,
)

data class DeviceRegistrationRequest(
    val installationId: String,
    val platform: String,
    val deviceName: String?,
    val appVersion: String,
    val osVersion: String?,
)

data class IdentityDeviceResponse(
    val deviceId: String,
    val installationId: String,
    val platform: String,
    val deviceName: String?,
    val appVersion: String,
    val osVersion: String?,
    val lastSeenAt: String?,
    val version: Long,
)

class IdentityAccessException(
    code: String,
    message: String,
    status: HttpStatus = HttpStatus.FORBIDDEN,
) : ProductApiException(
    code = code,
    message = message,
    status = status,
)

class IdentityBootstrapService(
    private val identityRepository: IdentityRuntimeRepository,
    private val organizationContext: OrganizationIdentityContextPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun bootstrap(
        subject: AuthenticatedOidcSubject,
        installationId: UUID?,
    ): IdentityBootstrapResponse {
        val now = clock.instant()
        val identity = resolveActiveIdentity(subject)
        val memberships = organizationContext.activeMemberships(
            identity.id,
            now,
        )

        if (memberships.isEmpty()) {
            throw IdentityAccessException(
                code = "NO_ACTIVE_ORGANIZATION_MEMBERSHIP",
                message = "No active organization membership is available.",
            )
        }

        val device = installationId?.let { id ->
            val current = identityRepository.findDevice(id)
            when {
                current == null ->
                    null

                current.userIdentityId != identity.id ->
                    throw IdentityAccessException(
                        code = "DEVICE_INSTALLATION_CONFLICT",
                        message = "This device installation belongs to another identity.",
                        status = HttpStatus.CONFLICT,
                    )

                current.revokedAt != null ->
                    throw IdentityAccessException(
                        code = "DEVICE_REVOKED",
                        message = "This device installation has been revoked.",
                    )

                else ->
                    current
            }
        }

        identityRepository.markAuthenticated(
            identityId = identity.id,
            authenticatedAt = now,
        )

        val teams = organizationContext.activeTeams(
            identity.id,
            now,
        )

        return IdentityBootstrapResponse(
            identityId = identity.id.toString(),
            identityStatus = identity.status,
            identityVersion = identity.version,
            primaryOrganizationId =
                identity.primaryOrganizationId?.toString(),
            organizations = memberships.map { membership ->
                IdentityOrganizationResponse(
                    membershipId =
                        membership.membershipId.toString(),
                    organizationId =
                        membership.organizationId.toString(),
                    organizationCode =
                        membership.organizationCode,
                    displayName =
                        membership.organizationDisplayName,
                    organizationType =
                        membership.organizationType,
                    membershipType =
                        membership.membershipType,
                    roleLabel = membership.roleLabel,
                    primary =
                        membership.organizationId ==
                            identity.primaryOrganizationId,
                    membershipVersion =
                        membership.membershipVersion,
                )
            },
            teams = teams.map { team ->
                IdentityTeamResponse(
                    teamMembershipId =
                        team.teamMembershipId.toString(),
                    teamId = team.teamId.toString(),
                    organizationId =
                        team.organizationId.toString(),
                    code = team.code,
                    name = team.name,
                    roleInTeam = team.roleInTeam,
                )
            },
            device = device?.toResponse(),
        )
    }

    fun registerDevice(
        subject: AuthenticatedOidcSubject,
        request: DeviceRegistrationRequest,
    ): IdentityDeviceResponse {
        val identity = resolveActiveIdentity(subject)
        val installationId = request.installationId.toUuidOrBadRequest(
            "installationId",
        )
        val platform = request.platform.trim().uppercase()

        if (platform !in setOf("ANDROID", "WINDOWS", "IOS")) {
            throw IdentityAccessException(
                code = "INVALID_DEVICE_PLATFORM",
                message = "Unsupported device platform.",
                status = HttpStatus.BAD_REQUEST,
            )
        }
        if (request.appVersion.isBlank()) {
            throw IdentityAccessException(
                code = "INVALID_DEVICE_METADATA",
                message = "appVersion must not be blank.",
                status = HttpStatus.BAD_REQUEST,
            )
        }

        return when (
            val outcome = identityRepository.registerOrTouchDevice(
                identityId = identity.id,
                registration = DeviceRegistration(
                    installationId = installationId,
                    platform = platform,
                    deviceName =
                        request.deviceName?.trim()?.takeIf {
                            it.isNotEmpty()
                        },
                    appVersion = request.appVersion.trim(),
                    osVersion =
                        request.osVersion?.trim()?.takeIf {
                            it.isNotEmpty()
                        },
                ),
                seenAt = clock.instant(),
            )
        ) {
            is DeviceRegistrationOutcome.Active ->
                outcome.device.toResponse()

            DeviceRegistrationOutcome.Revoked ->
                throw IdentityAccessException(
                    code = "DEVICE_REVOKED",
                    message = "This device installation has been revoked.",
                )

            DeviceRegistrationOutcome.OwnedByAnotherIdentity ->
                throw IdentityAccessException(
                    code = "DEVICE_INSTALLATION_CONFLICT",
                    message = "This device installation belongs to another identity.",
                    status = HttpStatus.CONFLICT,
                )
        }
    }

    private fun resolveActiveIdentity(
        subject: AuthenticatedOidcSubject,
    ): UserIdentityRuntime {
        val identity = identityRepository.findByOidcSubject(
            issuer = subject.issuer,
            subject = subject.subject,
        ) ?: throw IdentityAccessException(
            code = "IDENTITY_NOT_PROVISIONED",
            message = "Authenticated identity is not provisioned in HILTECH.",
        )

        if (identity.status != "ACTIVE") {
            throw IdentityAccessException(
                code = "IDENTITY_NOT_ACTIVE",
                message = "HILTECH identity is not active.",
            )
        }

        return identity
    }

    private fun String.toUuidOrBadRequest(
        field: String,
    ): UUID =
        runCatching { UUID.fromString(this) }
            .getOrElse {
                throw IdentityAccessException(
                    code = "INVALID_${field.uppercase()}",
                    message = "$field must be a UUID.",
                    status = HttpStatus.BAD_REQUEST,
                )
            }

    private fun DeviceRuntime.toResponse(): IdentityDeviceResponse =
        IdentityDeviceResponse(
            deviceId = id.toString(),
            installationId = installationId.toString(),
            platform = platform,
            deviceName = deviceName,
            appVersion = appVersion,
            osVersion = osVersion,
            lastSeenAt = lastSeenAt?.toString(),
            version = version,
        )
}

@RestController
@RequestMapping("/v1/me")
class IdentityBootstrapController(
    identityRepository: IdentityRuntimeRepository,
    organizationContext: OrganizationIdentityContextPort,
    private val sessionService: IdentitySessionService,
) {
    private val service = IdentityBootstrapService(
        identityRepository = identityRepository,
        organizationContext = organizationContext,
    )

    @GetMapping("/bootstrap")
    fun bootstrap(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader(
            name = "X-Device-Installation-Id",
            required = false,
        )
        installationId: String?,
    ): IdentityBootstrapResponse =
        service.bootstrap(
            subject = OidcSubjectResolver.from(jwt),
            installationId = installationId
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    runCatching { UUID.fromString(it) }
                        .getOrElse {
                            throw IdentityAccessException(
                                code = "INVALID_DEVICE_INSTALLATION_ID",
                                message = "X-Device-Installation-Id must be a UUID.",
                                status = HttpStatus.BAD_REQUEST,
                            )
                        }
                },
        )

    @PutMapping("/device")
    fun registerDevice(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: DeviceRegistrationRequest,
    ): IdentityDeviceResponse {
        val response = service.registerDevice(
            subject = OidcSubjectResolver.from(jwt),
            request = request,
        )
        sessionService.registerOrTouch(
            jwt = jwt,
            installationId =
                request.installationId.toUuidHeaderOrBadRequest(),
        )
        return response
    }

    private fun String.toUuidHeaderOrBadRequest(): UUID =
        runCatching { UUID.fromString(this) }
            .getOrElse {
                throw IdentityAccessException(
                    code = "INVALID_INSTALLATIONID",
                    message = "installationId must be a UUID.",
                    status = HttpStatus.BAD_REQUEST,
                )
            }
}
