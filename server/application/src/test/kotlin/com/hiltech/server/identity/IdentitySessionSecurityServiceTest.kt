package com.hiltech.server.identity

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class IdentitySessionSecurityServiceTest {
    private val now =
        Instant.parse("2026-09-19T07:00:00Z")
    private val identityId = UUID.randomUUID()
    private val deviceId = UUID.randomUUID()
    private val installationId = UUID.randomUUID()
    private val currentSessionId = UUID.randomUUID()

    @Test
    fun freshSignedProofMarksOnlyCurrentBoundSessionReauthenticated() {
        val sessions = FakeSessionRepository()
        val auditEvents =
            mutableListOf<AuditEventRecord>()
        val identityRepository = FakeIdentityRepository()
        val sessionService = sessionService(
            identityRepository,
            sessions,
        )
        val security = IdentitySessionSecurityService(
            sessionService = sessionService,
            sessionRepository = sessions,
            identityRepository = identityRepository,
            proofVerifier = IdTokenProofVerifier {
                proofJwt(
                    sid = "provider-session",
                    authTime = now.minusSeconds(10),
                )
            },
            sessionProperties = properties(),
            audit = AuditEventWriter {
                auditEvents += it
            },
            clock = fixedClock(),
        )

        val updated =
            security.completeReauthentication(
                accessJwt = accessJwt(),
                installationId = installationId,
                rawIdToken = "signed-proof",
                correlationId = "corr-reauth",
            )

        assertEquals(
            currentSessionId,
            updated.id,
        )
        assertEquals(
            now.plusSeconds(
                properties().reauthWindowSeconds,
            ),
            updated.reauthSatisfiedUntil,
        )
        assertNotNull(
            sessions.current.reauthSatisfiedUntil,
        )
        assertEquals(
            "IDENTITY_REAUTH_COMPLETED",
            auditEvents.single().action,
        )
        assertEquals(
            "corr-reauth",
            auditEvents.single().correlationId,
        )
        assertEquals(
            currentSessionId,
            auditEvents.single().targetId,
        )
    }

    @Test
    fun staleOrDifferentProviderSessionProofFailsClosed() {
        val sessions = FakeSessionRepository()
        val identityRepository = FakeIdentityRepository()
        val sessionService =
            sessionService(
                identityRepository,
                sessions,
            )

        val stale = IdentitySessionSecurityService(
            sessionService = sessionService,
            sessionRepository = sessions,
            identityRepository = identityRepository,
            proofVerifier = IdTokenProofVerifier {
                proofJwt(
                    sid = "provider-session",
                    authTime = now.minusSeconds(301),
                )
            },
            sessionProperties = properties(),
            clock = fixedClock(),
        )

        assertEquals(
            "REAUTH_PROOF_STALE",
            assertThrows<IdentityAccessException> {
                stale.completeReauthentication(
                    accessJwt = accessJwt(),
                    installationId = installationId,
                    rawIdToken = "stale",
                )
            }.code,
        )

        val differentSession =
            IdentitySessionSecurityService(
                sessionService = sessionService,
                sessionRepository = sessions,
                identityRepository = identityRepository,
                proofVerifier = IdTokenProofVerifier {
                    proofJwt(
                        sid = "other-provider-session",
                        authTime = now.minusSeconds(5),
                    )
                },
                sessionProperties = properties(),
                clock = fixedClock(),
            )

        assertEquals(
            "REAUTH_SESSION_MISMATCH",
            assertThrows<IdentityAccessException> {
                differentSession.completeReauthentication(
                    accessJwt = accessJwt(),
                    installationId = installationId,
                    rawIdToken = "wrong-session",
                )
            }.code,
        )
    }

    @Test
    fun remoteSessionRevokeRequiresFreshReauthButSelfRevokeDoesNot() {
        val sessions = FakeSessionRepository()
        val auditEvents =
            mutableListOf<AuditEventRecord>()
        val identityRepository = FakeIdentityRepository()
        val sessionService =
            sessionService(
                identityRepository,
                sessions,
            )
        val security = IdentitySessionSecurityService(
            sessionService = sessionService,
            sessionRepository = sessions,
            identityRepository = identityRepository,
            proofVerifier = IdTokenProofVerifier {
                error("unused")
            },
            sessionProperties = properties(),
            audit = AuditEventWriter {
                auditEvents += it
            },
            clock = fixedClock(),
        )

        val otherSession = UUID.randomUUID()
        sessions.owned +=
            sessions.current.copy(
                id = otherSession,
            )

        assertEquals(
            "REAUTH_REQUIRED",
            assertThrows<IdentityAccessException> {
                security.revokeSession(
                    jwt = accessJwt(),
                    installationId = installationId,
                    targetSessionId = otherSession,
                )
            }.code,
        )

        val self = security.revokeSession(
            jwt = accessJwt(),
            installationId = installationId,
            targetSessionId = currentSessionId,
            correlationId = "corr-self-revoke",
        )
        assertNotNull(self.revokedAt)
        assertEquals(
            1,
            auditEvents.size,
        )
        assertEquals(
            "IDENTITY_SESSION_REVOKED",
            auditEvents.single().action,
        )
        assertEquals(
            "corr-self-revoke",
            auditEvents.single().correlationId,
        )
        assertEquals(
            "SELF_REVOKE",
            auditEvents.single().reason,
        )
    }

    private fun sessionService(
        identityRepository: FakeIdentityRepository,
        sessions: FakeSessionRepository,
    ) =
        IdentitySessionService(
            identityRepository = identityRepository,
            sessionRepository = sessions,
            properties = properties(),
            clock = fixedClock(),
        )

    private fun properties() =
        HiltechIdentitySessionProperties(
            ttlSeconds = 3_600,
            reauthMaxAgeSeconds = 300,
            reauthWindowSeconds = 900,
        )

    private fun fixedClock() =
        Clock.fixed(now, ZoneOffset.UTC)

    private fun accessJwt() =
        jwt(
            sid = "provider-session",
            authTime = now.minusSeconds(60),
        )

    private fun proofJwt(
        sid: String,
        authTime: Instant,
    ) =
        jwt(
            sid = sid,
            authTime = authTime,
        )

    private fun jwt(
        sid: String,
        authTime: Instant,
    ): Jwt =
        Jwt.withTokenValue(
            "token-" + UUID.randomUUID(),
        )
            .header("alg", "RS256")
            .issuer(
                "https://id.hiltech.test/realms/hiltech",
            )
            .subject("subject-1")
            .claim("sid", sid)
            .claim(
                "auth_time",
                authTime.epochSecond,
            )
            .audience(listOf("hiltech-native"))
            .issuedAt(now.minusSeconds(30))
            .expiresAt(now.plusSeconds(300))
            .build()

    private inner class FakeIdentityRepository :
        IdentityRuntimeRepository {
        private var device = DeviceRuntime(
            id = deviceId,
            userIdentityId = identityId,
            installationId = installationId,
            platform = "ANDROID",
            deviceName = "Phone",
            appVersion = "1",
            osVersion = "16",
            lastSeenAt = now,
            revokedAt = null,
            version = 1,
        )

        override fun findByOidcSubject(
            issuer: String,
            subject: String,
        ) =
            UserIdentityRuntime(
                id = identityId,
                status = "ACTIVE",
                primaryOrganizationId =
                    UUID.randomUUID(),
                version = 1,
            )

        override fun markAuthenticated(
            identityId: UUID,
            authenticatedAt: Instant,
        ) = Unit

        override fun findDevice(
            installationId: UUID,
        ) = device

        override fun registerOrTouchDevice(
            identityId: UUID,
            registration: DeviceRegistration,
            seenAt: Instant,
        ) =
            DeviceRegistrationOutcome.Active(
                device,
            )

        override fun listDevices(
            identityId: UUID,
        ) = listOf(device)

        override fun revokeDevice(
            identityId: UUID,
            deviceId: UUID,
            revokedAt: Instant,
        ): Boolean {
            if (
                device.id != deviceId ||
                device.revokedAt != null
            ) {
                return false
            }
            device = device.copy(
                revokedAt = revokedAt,
                version = device.version + 1,
            )
            return true
        }
    }

    private inner class FakeSessionRepository :
        IdentitySessionRepository {
        var current =
            IdentitySessionRuntime(
                id = currentSessionId,
                userIdentityId = identityId,
                deviceId = deviceId,
                createdAt = now.minusSeconds(100),
                lastSeenAt = now.minusSeconds(10),
                expiresAt = now.plusSeconds(3_000),
                revokedAt = null,
                authenticationStrength = "1",
                reauthSatisfiedUntil = null,
                version = 1,
            )
        val owned =
            mutableListOf(current)

        override fun registerOrTouch(
            identityId: UUID,
            deviceId: UUID,
            providerSessionRef: String,
            authenticationStrength: String,
            seenAt: Instant,
            expiresAt: Instant,
        ) = current

        override fun findCurrent(
            identityId: UUID,
            deviceId: UUID,
            providerSessionRef: String,
            at: Instant,
        ) =
            current.takeIf {
                it.revokedAt == null &&
                    it.expiresAt.isAfter(at)
            }

        override fun findOwned(
            identityId: UUID,
            sessionId: UUID,
        ) =
            owned.firstOrNull {
                it.id == sessionId
            }

        override fun listOwned(
            identityId: UUID,
            limit: Int,
        ) =
            owned.take(limit)

        override fun revokeOwned(
            identityId: UUID,
            sessionId: UUID,
            revokedAt: Instant,
        ): Boolean {
            val index = owned.indexOfFirst {
                it.id == sessionId &&
                    it.revokedAt == null
            }
            if (index < 0) return false

            val updated =
                owned[index].copy(
                    revokedAt = revokedAt,
                    version =
                        owned[index].version + 1,
                )
            owned[index] = updated
            if (sessionId == current.id) {
                current = updated
            }
            return true
        }

        override fun revokeForDevice(
            identityId: UUID,
            deviceId: UUID,
            revokedAt: Instant,
        ): Int {
            var count = 0
            owned.indices.forEach { index ->
                val value = owned[index]
                if (
                    value.deviceId == deviceId &&
                    value.revokedAt == null
                ) {
                    val updated =
                        value.copy(
                            revokedAt = revokedAt,
                            version =
                                value.version + 1,
                        )
                    owned[index] = updated
                    if (updated.id == current.id) {
                        current = updated
                    }
                    count += 1
                }
            }
            return count
        }

        override fun markReauthenticationSatisfied(
            identityId: UUID,
            sessionId: UUID,
            satisfiedUntil: Instant,
            at: Instant,
        ): IdentitySessionRuntime? {
            if (
                current.id != sessionId ||
                current.revokedAt != null
            ) {
                return null
            }
            current = current.copy(
                lastSeenAt = at,
                reauthSatisfiedUntil =
                    satisfiedUntil,
                version = current.version + 1,
            )
            val index =
                owned.indexOfFirst {
                    it.id == current.id
                }
            if (index >= 0) {
                owned[index] = current
            }
            return current
        }
    }
}
