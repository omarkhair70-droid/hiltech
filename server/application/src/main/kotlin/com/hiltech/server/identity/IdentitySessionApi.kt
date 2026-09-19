package com.hiltech.server.identity

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.security.core.annotation.AuthenticationPrincipal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ReauthenticationCompletionRequest(
    val idToken: String,
)

data class IdentitySessionResponse(
    val sessionId: String,
    val deviceId: String,
    val createdAt: String,
    val lastSeenAt: String,
    val expiresAt: String,
    val revokedAt: String?,
    val authenticationStrength: String,
    val reauthSatisfiedUntil: String?,
    val current: Boolean,
    val version: Long,
)

data class IdentityDeviceSecurityResponse(
    val deviceId: String,
    val installationId: String,
    val platform: String,
    val deviceName: String?,
    val appVersion: String,
    val osVersion: String?,
    val lastSeenAt: String?,
    val revokedAt: String?,
    val current: Boolean,
    val version: Long,
)

@Component
class OidcIdTokenProofVerifier(
    private val properties: HiltechOidcProperties,
) {
    private val decoder: JwtDecoder by lazy {
        properties.validateEnabledConfiguration()

        val nimbus = NimbusJwtDecoder
            .withIssuerLocation(properties.issuerUri)
            .build()

        nimbus.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(
                    properties.issuerUri,
                ),
                JwtAudienceValidator(
                    properties.nativeClientId,
                ),
            ),
        )
        nimbus
    }

    fun verify(
        rawIdToken: String,
    ): Jwt {
        if (rawIdToken.isBlank()) {
            throw invalidProof()
        }

        return runCatching {
            decoder.decode(rawIdToken)
        }.getOrElse {
            throw invalidProof()
        }
    }

    private fun invalidProof() =
        IdentityAccessException(
            code = "REAUTH_PROOF_INVALID",
            message =
                "The authentication proof is invalid.",
            status = HttpStatus.UNAUTHORIZED,
        )
}

@Component
class IdentitySessionSecurityService(
    private val sessionService: IdentitySessionService,
    private val sessionRepository: IdentitySessionRepository,
    private val identityRepository: IdentityRuntimeRepository,
    private val proofVerifier: OidcIdTokenProofVerifier,
    private val sessionProperties: HiltechIdentitySessionProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun completeReauthentication(
        accessJwt: Jwt,
        installationId: UUID,
        rawIdToken: String,
    ): IdentitySessionRuntime {
        sessionProperties.validate()
        val context = sessionService.requireCurrentAccess(
            jwt = accessJwt,
            installationId = installationId,
        )
        val proof = proofVerifier.verify(rawIdToken)

        val accessSubject =
            OidcSubjectResolver.from(accessJwt)
        val proofSubject =
            OidcSubjectResolver.from(proof)

        if (proofSubject != accessSubject) {
            throw IdentityAccessException(
                code = "REAUTH_SUBJECT_MISMATCH",
                message =
                    "The authentication proof does not belong to the active identity.",
                status = HttpStatus.UNAUTHORIZED,
            )
        }

        val activeProviderSession =
            sessionService.providerSessionReference(
                accessJwt,
            )
        val proofProviderSession =
            sessionService.providerSessionReference(
                proof,
            )
        if (activeProviderSession != proofProviderSession) {
            throw IdentityAccessException(
                code = "REAUTH_SESSION_MISMATCH",
                message =
                    "The authentication proof does not belong to the active provider session.",
                status = HttpStatus.UNAUTHORIZED,
            )
        }

        val now = clock.instant()
        val authenticationTime =
            authenticationTime(proof)
                ?: throw IdentityAccessException(
                    code = "REAUTH_AUTH_TIME_MISSING",
                    message =
                        "The authentication proof does not include authentication time.",
                    status = HttpStatus.UNAUTHORIZED,
                )

        val earliestAccepted =
            now.minusSeconds(
                sessionProperties.reauthMaxAgeSeconds,
            )
        val latestAccepted =
            now.plusSeconds(60)

        if (
            authenticationTime.isBefore(
                earliestAccepted,
            ) ||
            authenticationTime.isAfter(
                latestAccepted,
            )
        ) {
            throw IdentityAccessException(
                code = "REAUTH_PROOF_STALE",
                message =
                    "Fresh authentication is required.",
                status =
                    HttpStatus.PRECONDITION_REQUIRED,
            )
        }

        return sessionRepository
            .markReauthenticationSatisfied(
                identityId = context.identity.id,
                sessionId = context.session.id,
                satisfiedUntil =
                    now.plusSeconds(
                        sessionProperties
                            .reauthWindowSeconds,
                    ),
                at = now,
            )
            ?: throw IdentityAccessException(
                code = "SESSION_NOT_ACTIVE",
                message =
                    "No active HILTECH session is available.",
            )
    }

    fun listSessions(
        jwt: Jwt,
        installationId: UUID,
    ): Pair<IdentityAccessContext, List<IdentitySessionRuntime>> {
        val context =
            sessionService.requireCurrentAccess(
                jwt = jwt,
                installationId = installationId,
            )
        return context to
            sessionRepository.listOwned(
                context.identity.id,
            )
    }

    fun listDevices(
        jwt: Jwt,
        installationId: UUID,
    ): Pair<IdentityAccessContext, List<DeviceRuntime>> {
        val context =
            sessionService.requireCurrentAccess(
                jwt = jwt,
                installationId = installationId,
            )
        return context to
            identityRepository.listDevices(
                context.identity.id,
            )
    }

    fun revokeSession(
        jwt: Jwt,
        installationId: UUID,
        targetSessionId: UUID,
    ): IdentitySessionRuntime {
        val context =
            sessionService.requireCurrentAccess(
                jwt = jwt,
                installationId = installationId,
            )

        if (targetSessionId != context.session.id) {
            sessionService
                .requireRecentReauthentication(
                    context,
                )
        }

        val target =
            sessionRepository.findOwned(
                identityId = context.identity.id,
                sessionId = targetSessionId,
            ) ?: throw notFound("SESSION_NOT_FOUND")

        if (target.revokedAt == null) {
            sessionRepository.revokeOwned(
                identityId = context.identity.id,
                sessionId = targetSessionId,
                revokedAt = clock.instant(),
            )
        }

        return sessionRepository.findOwned(
            identityId = context.identity.id,
            sessionId = targetSessionId,
        ) ?: target
    }

    @Transactional
    fun revokeDevice(
        jwt: Jwt,
        installationId: UUID,
        targetDeviceId: UUID,
    ): DeviceRuntime {
        val context =
            sessionService.requireCurrentAccess(
                jwt = jwt,
                installationId = installationId,
            )

        if (targetDeviceId != context.device.id) {
            sessionService
                .requireRecentReauthentication(
                    context,
                )
        }

        val target =
            identityRepository.listDevices(
                context.identity.id,
            ).firstOrNull {
                it.id == targetDeviceId
            } ?: throw notFound("DEVICE_NOT_FOUND")

        val now = clock.instant()
        if (target.revokedAt == null) {
            identityRepository.revokeDevice(
                identityId = context.identity.id,
                deviceId = targetDeviceId,
                revokedAt = now,
            )
            sessionRepository.revokeForDevice(
                identityId = context.identity.id,
                deviceId = targetDeviceId,
                revokedAt = now,
            )
        }

        return identityRepository.listDevices(
            context.identity.id,
        ).first {
            it.id == targetDeviceId
        }
    }

    private fun authenticationTime(
        jwt: Jwt,
    ): Instant? =
        when (
            val claim =
                jwt.claims["auth_time"]
        ) {
            is Instant -> claim
            is Number ->
                Instant.ofEpochSecond(
                    claim.toLong(),
                )
            is String ->
                claim.toLongOrNull()
                    ?.let(Instant::ofEpochSecond)
            else -> null
        }

    private fun notFound(
        code: String,
    ) =
        IdentityAccessException(
            code = code,
            message = "The requested security context was not found.",
            status = HttpStatus.NOT_FOUND,
        )
}

@RestController
@RequestMapping("/v1/me")
class IdentitySessionSecurityController(
    private val service: IdentitySessionSecurityService,
) {
    @PostMapping("/reauth/complete")
    fun completeReauthentication(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader(
            name = "X-Device-Installation-Id",
        )
        installationId: String,
        @RequestBody
        request: ReauthenticationCompletionRequest,
    ): IdentitySessionResponse {
        val session =
            service.completeReauthentication(
                accessJwt = jwt,
                installationId =
                    installationId.toUuid(
                        "INVALID_DEVICE_INSTALLATION_ID",
                    ),
                rawIdToken = request.idToken,
            )
        return session.toResponse(
            currentSessionId = session.id,
        )
    }

    @GetMapping("/sessions")
    fun sessions(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader(
            name = "X-Device-Installation-Id",
        )
        installationId: String,
    ): List<IdentitySessionResponse> {
        val (context, sessions) =
            service.listSessions(
                jwt = jwt,
                installationId =
                    installationId.toUuid(
                        "INVALID_DEVICE_INSTALLATION_ID",
                    ),
            )
        return sessions.map {
            it.toResponse(
                currentSessionId =
                    context.session.id,
            )
        }
    }

    @GetMapping("/devices")
    fun devices(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader(
            name = "X-Device-Installation-Id",
        )
        installationId: String,
    ): List<IdentityDeviceSecurityResponse> {
        val (context, devices) =
            service.listDevices(
                jwt = jwt,
                installationId =
                    installationId.toUuid(
                        "INVALID_DEVICE_INSTALLATION_ID",
                    ),
            )
        return devices.map {
            it.toSecurityResponse(
                currentDeviceId =
                    context.device.id,
            )
        }
    }

    @PostMapping("/sessions/{sessionId}/revoke")
    fun revokeSession(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader(
            name = "X-Device-Installation-Id",
        )
        installationId: String,
        @PathVariable
        sessionId: String,
    ): IdentitySessionResponse {
        val target = service.revokeSession(
            jwt = jwt,
            installationId =
                installationId.toUuid(
                    "INVALID_DEVICE_INSTALLATION_ID",
                ),
            targetSessionId =
                sessionId.toUuid(
                    "INVALID_SESSION_ID",
                ),
        )
        return target.toResponse(
            currentSessionId = null,
        )
    }

    @PostMapping("/devices/{deviceId}/revoke")
    fun revokeDevice(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader(
            name = "X-Device-Installation-Id",
        )
        installationId: String,
        @PathVariable
        deviceId: String,
    ): IdentityDeviceSecurityResponse {
        val currentInstallationId =
            installationId.toUuid(
                "INVALID_DEVICE_INSTALLATION_ID",
            )
        val target = service.revokeDevice(
            jwt = jwt,
            installationId =
                currentInstallationId,
            targetDeviceId =
                deviceId.toUuid(
                    "INVALID_DEVICE_ID",
                ),
        )
        return target.toSecurityResponse(
            currentDeviceId =
                if (
                    target.installationId ==
                    currentInstallationId
                ) {
                    target.id
                } else {
                    null
                },
        )
    }

    private fun String.toUuid(
        code: String,
    ): UUID =
        runCatching {
            UUID.fromString(this)
        }.getOrElse {
            throw IdentityAccessException(
                code = code,
                message = "Expected UUID value.",
                status = HttpStatus.BAD_REQUEST,
            )
        }

    private fun IdentitySessionRuntime.toResponse(
        currentSessionId: UUID?,
    ) =
        IdentitySessionResponse(
            sessionId = id.toString(),
            deviceId = deviceId.toString(),
            createdAt = createdAt.toString(),
            lastSeenAt = lastSeenAt.toString(),
            expiresAt = expiresAt.toString(),
            revokedAt = revokedAt?.toString(),
            authenticationStrength =
                authenticationStrength,
            reauthSatisfiedUntil =
                reauthSatisfiedUntil?.toString(),
            current = id == currentSessionId,
            version = version,
        )

    private fun DeviceRuntime.toSecurityResponse(
        currentDeviceId: UUID?,
    ) =
        IdentityDeviceSecurityResponse(
            deviceId = id.toString(),
            installationId =
                installationId.toString(),
            platform = platform,
            deviceName = deviceName,
            appVersion = appVersion,
            osVersion = osVersion,
            lastSeenAt = lastSeenAt?.toString(),
            revokedAt = revokedAt?.toString(),
            current = id == currentDeviceId,
            version = version,
        )
}
