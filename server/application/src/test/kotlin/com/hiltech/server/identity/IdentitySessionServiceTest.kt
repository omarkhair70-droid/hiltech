package com.hiltech.server.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class IdentitySessionServiceTest {
    private val now =
        Instant.parse("2026-09-19T06:00:00Z")
    private val identityId = UUID.randomUUID()
    private val deviceId = UUID.randomUUID()
    private val installationId = UUID.randomUUID()

    @Test
    fun registerBindsIdentityDeviceAndProviderSessionWithoutTrustingRoles() {
        val sessions = FakeSessionRepository()
        val service = service(sessions)

        val created = service.registerOrTouch(
            jwt = jwt(
                claims = mapOf(
                    "sid" to "provider-session-42",
                    "acr" to "1",
                    "roles" to listOf("admin"),
                ),
            ),
            installationId = installationId,
        )

        assertEquals(identityId, created.userIdentityId)
        assertEquals(deviceId, created.deviceId)
        assertEquals(
            "provider-session-42",
            sessions.lastProviderSessionRef,
        )
        assertEquals("1", created.authenticationStrength)
        assertTrue(created.expiresAt.isAfter(now))
    }

    @Test
    fun revokedDeviceAndMissingProviderSessionFailClosed() {
        val revoked = service(
            FakeSessionRepository(),
            device = activeDevice().copy(
                revokedAt = now.minusSeconds(1),
            ),
        )

        assertEquals(
            "DEVICE_REVOKED",
            assertThrows<IdentityAccessException> {
                revoked.registerOrTouch(
                    jwt = jwt(
                        mapOf(
                            "sid" to "provider-session",
                        ),
                    ),
                    installationId = installationId,
                )
            }.code,
        )

        val noSid = service(FakeSessionRepository())
        val failure =
            assertThrows<IdentityAccessException> {
                noSid.registerOrTouch(
                    jwt = jwt(emptyMap()),
                    installationId = installationId,
                )
            }
        assertEquals(
            "PROVIDER_SESSION_REFERENCE_MISSING",
            failure.code,
        )
        assertEquals(
            HttpStatus.UNAUTHORIZED,
            failure.status,
        )
    }

    @Test
    fun recentReauthenticationIsServerSessionStateNotClientRoleState() {
        val sessions = FakeSessionRepository()
        val service = service(sessions)
        val current = service.registerOrTouch(
            jwt = jwt(mapOf("sid" to "session")),
            installationId = installationId,
        )

        val context = IdentityAccessContext(
            identity = activeIdentity(),
            device = activeDevice(),
            session = current.copy(
                reauthSatisfiedUntil =
                    now.plusSeconds(60),
            ),
        )
        service.requireRecentReauthentication(context)

        val expired = context.copy(
            session = context.session.copy(
                reauthSatisfiedUntil =
                    now.minusSeconds(1),
            ),
        )
        assertEquals(
            "REAUTH_REQUIRED",
            assertThrows<IdentityAccessException> {
                service.requireRecentReauthentication(
                    expired,
                )
            }.code,
        )
    }

    private fun service(
        sessions: FakeSessionRepository,
        identity: UserIdentityRuntime = activeIdentity(),
        device: DeviceRuntime = activeDevice(),
    ): IdentitySessionService =
        IdentitySessionService(
            identityRepository =
                FakeIdentityRepository(
                    identity = identity,
                    device = device,
                ),
            sessionRepository = sessions,
            properties =
                HiltechIdentitySessionProperties(
                    ttlSeconds = 3_600,
                    reauthMaxAgeSeconds = 300,
                    reauthWindowSeconds = 900,
                ),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun activeIdentity() =
        UserIdentityRuntime(
            id = identityId,
            status = "ACTIVE",
            primaryOrganizationId = UUID.randomUUID(),
            version = 1,
        )

    private fun activeDevice() =
        DeviceRuntime(
            id = deviceId,
            userIdentityId = identityId,
            installationId = installationId,
            platform = "ANDROID",
            deviceName = "Phase 1",
            appVersion = "1",
            osVersion = "16",
            lastSeenAt = now,
            revokedAt = null,
            version = 1,
        )

    private fun jwt(
        claims: Map<String, Any>,
    ): Jwt {
        val merged = mutableMapOf<String, Any>(
            "iss" to
                "https://id.hiltech.test/realms/hiltech",
            "sub" to "subject-1",
        )
        merged.putAll(claims)

        return Jwt.withTokenValue("token")
            .header("alg", "none")
            .claims { it.putAll(merged) }
            .issuedAt(now.minusSeconds(10))
            .expiresAt(now.plusSeconds(300))
            .build()
    }

    private class FakeSessionRepository :
        IdentitySessionRepository {
        var lastProviderSessionRef: String? = null
        private var stored: IdentitySessionRuntime? = null

        override fun registerOrTouch(
            identityId: UUID,
            deviceId: UUID,
            providerSessionRef: String,
            authenticationStrength: String,
            seenAt: Instant,
            expiresAt: Instant,
        ): IdentitySessionRuntime {
            lastProviderSessionRef =
                providerSessionRef
            val value = IdentitySessionRuntime(
                id = UUID.randomUUID(),
                userIdentityId = identityId,
                deviceId = deviceId,
                createdAt = seenAt,
                lastSeenAt = seenAt,
                expiresAt = expiresAt,
                revokedAt = null,
                authenticationStrength =
                    authenticationStrength,
                reauthSatisfiedUntil = null,
                version = 1,
            )
            stored = value
            return value
        }

        override fun findCurrent(
            identityId: UUID,
            deviceId: UUID,
            providerSessionRef: String,
            at: Instant,
        ): IdentitySessionRuntime? = stored

        override fun findOwned(
            identityId: UUID,
            sessionId: UUID,
        ): IdentitySessionRuntime? = stored

        override fun listOwned(
            identityId: UUID,
            limit: Int,
        ): List<IdentitySessionRuntime> =
            listOfNotNull(stored)

        override fun revokeOwned(
            identityId: UUID,
            sessionId: UUID,
            revokedAt: Instant,
        ): Boolean = true

        override fun revokeForDevice(
            identityId: UUID,
            deviceId: UUID,
            revokedAt: Instant,
        ): Int = 1

        override fun markReauthenticationSatisfied(
            identityId: UUID,
            sessionId: UUID,
            satisfiedUntil: Instant,
            at: Instant,
        ): IdentitySessionRuntime? =
            stored?.copy(
                reauthSatisfiedUntil =
                    satisfiedUntil,
            )
    }

    private class FakeIdentityRepository(
        private val identity: UserIdentityRuntime,
        private val device: DeviceRuntime,
    ) : IdentityRuntimeRepository {
        override fun findByOidcSubject(
            issuer: String,
            subject: String,
        ): UserIdentityRuntime = identity

        override fun markAuthenticated(
            identityId: UUID,
            authenticatedAt: Instant,
        ) = Unit

        override fun findDevice(
            installationId: UUID,
        ): DeviceRuntime = device

        override fun registerOrTouchDevice(
            identityId: UUID,
            registration: DeviceRegistration,
            seenAt: Instant,
        ): DeviceRegistrationOutcome =
            DeviceRegistrationOutcome.Active(
                device,
            )
    }
}
