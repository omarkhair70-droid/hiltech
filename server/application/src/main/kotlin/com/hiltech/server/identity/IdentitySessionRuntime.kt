package com.hiltech.server.identity

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ConfigurationProperties(prefix = "hiltech.identity.session")
data class HiltechIdentitySessionProperties(
    var ttlSeconds: Long = 43_200,
    var reauthMaxAgeSeconds: Long = 300,
    var reauthWindowSeconds: Long = 900,
) {
    fun validate() {
        require(ttlSeconds > 0) {
            "hiltech.identity.session.ttl-seconds must be positive."
        }
        require(reauthMaxAgeSeconds > 0) {
            "hiltech.identity.session.reauth-max-age-seconds must be positive."
        }
        require(reauthWindowSeconds > 0) {
            "hiltech.identity.session.reauth-window-seconds must be positive."
        }
    }
}

data class IdentitySessionRuntime(
    val id: UUID,
    val userIdentityId: UUID,
    val deviceId: UUID,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val authenticationStrength: String,
    val reauthSatisfiedUntil: Instant?,
    val version: Long,
)

interface IdentitySessionRepository {
    fun registerOrTouch(
        identityId: UUID,
        deviceId: UUID,
        providerSessionRef: String,
        authenticationStrength: String,
        seenAt: Instant,
        expiresAt: Instant,
    ): IdentitySessionRuntime

    fun findCurrent(
        identityId: UUID,
        deviceId: UUID,
        providerSessionRef: String,
        at: Instant,
    ): IdentitySessionRuntime?

    fun findOwned(
        identityId: UUID,
        sessionId: UUID,
    ): IdentitySessionRuntime?

    fun listOwned(
        identityId: UUID,
        limit: Int = 50,
    ): List<IdentitySessionRuntime>

    fun revokeOwned(
        identityId: UUID,
        sessionId: UUID,
        revokedAt: Instant,
    ): Boolean

    fun revokeForDevice(
        identityId: UUID,
        deviceId: UUID,
        revokedAt: Instant,
    ): Int

    fun markReauthenticationSatisfied(
        identityId: UUID,
        sessionId: UUID,
        satisfiedUntil: Instant,
        at: Instant,
    ): IdentitySessionRuntime?
}

@Component
class JdbcIdentitySessionRepository(
    private val jdbc: JdbcTemplate,
) : IdentitySessionRepository {
    override fun registerOrTouch(
        identityId: UUID,
        deviceId: UUID,
        providerSessionRef: String,
        authenticationStrength: String,
        seenAt: Instant,
        expiresAt: Instant,
    ): IdentitySessionRuntime {
        val rows = jdbc.query(
            """
            INSERT INTO identity_session (
                id,
                user_identity_id,
                device_id,
                provider_session_ref_hash,
                created_at,
                last_seen_at,
                expires_at,
                revoked_at,
                authentication_strength,
                reauth_satisfied_until,
                version
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, 1)
            ON CONFLICT (
                user_identity_id,
                device_id,
                provider_session_ref_hash
            )
            DO UPDATE SET
                last_seen_at = EXCLUDED.last_seen_at,
                expires_at = GREATEST(
                    identity_session.expires_at,
                    EXCLUDED.expires_at
                ),
                authentication_strength =
                    EXCLUDED.authentication_strength,
                version = identity_session.version + 1
            WHERE identity_session.revoked_at IS NULL
            RETURNING
                id,
                user_identity_id,
                device_id,
                created_at,
                last_seen_at,
                expires_at,
                revoked_at,
                authentication_strength,
                reauth_satisfied_until,
                version
            """.trimIndent(),
            sessionRowMapper,
            UUID.randomUUID(),
            identityId,
            deviceId,
            providerSessionHash(providerSessionRef),
            seenAt.atOffset(ZoneOffset.UTC),
            seenAt.atOffset(ZoneOffset.UTC),
            expiresAt.atOffset(ZoneOffset.UTC),
            authenticationStrength,
        )

        return rows.singleOrNull()
            ?: throw IdentityAccessException(
                code = "SESSION_REVOKED",
                message = "This HILTECH session has been revoked.",
            )
    }

    override fun findCurrent(
        identityId: UUID,
        deviceId: UUID,
        providerSessionRef: String,
        at: Instant,
    ): IdentitySessionRuntime? =
        jdbc.query(
            """
            SELECT
                id,
                user_identity_id,
                device_id,
                created_at,
                last_seen_at,
                expires_at,
                revoked_at,
                authentication_strength,
                reauth_satisfied_until,
                version
            FROM identity_session
            WHERE user_identity_id = ?
              AND device_id = ?
              AND provider_session_ref_hash = ?
              AND revoked_at IS NULL
              AND expires_at > ?
            """.trimIndent(),
            sessionRowMapper,
            identityId,
            deviceId,
            providerSessionHash(providerSessionRef),
            at.atOffset(ZoneOffset.UTC),
        ).singleOrNull()

    override fun findOwned(
        identityId: UUID,
        sessionId: UUID,
    ): IdentitySessionRuntime? =
        jdbc.query(
            """
            SELECT
                id,
                user_identity_id,
                device_id,
                created_at,
                last_seen_at,
                expires_at,
                revoked_at,
                authentication_strength,
                reauth_satisfied_until,
                version
            FROM identity_session
            WHERE id = ?
              AND user_identity_id = ?
            """.trimIndent(),
            sessionRowMapper,
            sessionId,
            identityId,
        ).singleOrNull()

    override fun listOwned(
        identityId: UUID,
        limit: Int,
    ): List<IdentitySessionRuntime> =
        jdbc.query(
            """
            SELECT
                id,
                user_identity_id,
                device_id,
                created_at,
                last_seen_at,
                expires_at,
                revoked_at,
                authentication_strength,
                reauth_satisfied_until,
                version
            FROM identity_session
            WHERE user_identity_id = ?
            ORDER BY last_seen_at DESC
            LIMIT ?
            """.trimIndent(),
            sessionRowMapper,
            identityId,
            limit.coerceIn(1, 100),
        )

    override fun revokeOwned(
        identityId: UUID,
        sessionId: UUID,
        revokedAt: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE identity_session
            SET revoked_at = ?,
                version = version + 1
            WHERE id = ?
              AND user_identity_id = ?
              AND revoked_at IS NULL
            """.trimIndent(),
            revokedAt.atOffset(ZoneOffset.UTC),
            sessionId,
            identityId,
        ) == 1

    override fun revokeForDevice(
        identityId: UUID,
        deviceId: UUID,
        revokedAt: Instant,
    ): Int =
        jdbc.update(
            """
            UPDATE identity_session
            SET revoked_at = ?,
                version = version + 1
            WHERE user_identity_id = ?
              AND device_id = ?
              AND revoked_at IS NULL
            """.trimIndent(),
            revokedAt.atOffset(ZoneOffset.UTC),
            identityId,
            deviceId,
        )

    override fun markReauthenticationSatisfied(
        identityId: UUID,
        sessionId: UUID,
        satisfiedUntil: Instant,
        at: Instant,
    ): IdentitySessionRuntime? =
        jdbc.query(
            """
            UPDATE identity_session
            SET reauth_satisfied_until = ?,
                last_seen_at = ?,
                version = version + 1
            WHERE id = ?
              AND user_identity_id = ?
              AND revoked_at IS NULL
              AND expires_at > ?
            RETURNING
                id,
                user_identity_id,
                device_id,
                created_at,
                last_seen_at,
                expires_at,
                revoked_at,
                authentication_strength,
                reauth_satisfied_until,
                version
            """.trimIndent(),
            sessionRowMapper,
            satisfiedUntil.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
            sessionId,
            identityId,
            at.atOffset(ZoneOffset.UTC),
        ).singleOrNull()

    private val sessionRowMapper =
        { rs: java.sql.ResultSet, _: Int ->
            IdentitySessionRuntime(
                id = rs.getObject("id", UUID::class.java),
                userIdentityId = rs.getObject(
                    "user_identity_id",
                    UUID::class.java,
                ),
                deviceId = rs.getObject(
                    "device_id",
                    UUID::class.java,
                ),
                createdAt = rs.getObject(
                    "created_at",
                    OffsetDateTime::class.java,
                ).toInstant(),
                lastSeenAt = rs.getObject(
                    "last_seen_at",
                    OffsetDateTime::class.java,
                ).toInstant(),
                expiresAt = rs.getObject(
                    "expires_at",
                    OffsetDateTime::class.java,
                ).toInstant(),
                revokedAt = rs.getObject(
                    "revoked_at",
                    OffsetDateTime::class.java,
                )?.toInstant(),
                authenticationStrength =
                    rs.getString("authentication_strength"),
                reauthSatisfiedUntil = rs.getObject(
                    "reauth_satisfied_until",
                    OffsetDateTime::class.java,
                )?.toInstant(),
                version = rs.getLong("version"),
            )
        }

    private fun providerSessionHash(
        providerSessionRef: String,
    ): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                providerSessionRef.toByteArray(
                    StandardCharsets.UTF_8,
                ),
            )
            .joinToString("") { byte ->
                "%02x".format(byte)
            }
}

data class IdentityAccessContext(
    val identity: UserIdentityRuntime,
    val device: DeviceRuntime,
    val session: IdentitySessionRuntime,
)

@Component
class IdentitySessionService(
    private val identityRepository: IdentityRuntimeRepository,
    private val sessionRepository: IdentitySessionRepository,
    private val properties: HiltechIdentitySessionProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun registerOrTouch(
        jwt: Jwt,
        installationId: UUID,
    ): IdentitySessionRuntime {
        properties.validate()
        val now = clock.instant()
        val identity = resolveActiveIdentity(jwt)
        val device = resolveActiveDevice(
            identityId = identity.id,
            installationId = installationId,
        )

        return sessionRepository.registerOrTouch(
            identityId = identity.id,
            deviceId = device.id,
            providerSessionRef =
                providerSessionReference(jwt),
            authenticationStrength =
                jwt.getClaimAsString("acr")
                    ?.takeIf { it.isNotBlank() }
                    ?: "OIDC",
            seenAt = now,
            expiresAt = now.plusSeconds(
                properties.ttlSeconds,
            ),
        )
    }

    fun requireCurrentAccess(
        jwt: Jwt,
        installationId: UUID,
    ): IdentityAccessContext {
        val now = clock.instant()
        val identity = resolveActiveIdentity(jwt)
        val device = resolveActiveDevice(
            identityId = identity.id,
            installationId = installationId,
        )
        val session = sessionRepository.findCurrent(
            identityId = identity.id,
            deviceId = device.id,
            providerSessionRef =
                providerSessionReference(jwt),
            at = now,
        ) ?: throw IdentityAccessException(
            code = "SESSION_NOT_ACTIVE",
            message = "No active HILTECH session is available.",
        )

        return IdentityAccessContext(
            identity = identity,
            device = device,
            session = session,
        )
    }

    fun requireRecentReauthentication(
        context: IdentityAccessContext,
    ) {
        val now = clock.instant()
        val satisfiedUntil =
            context.session.reauthSatisfiedUntil
        if (
            satisfiedUntil == null ||
            !satisfiedUntil.isAfter(now)
        ) {
            throw IdentityAccessException(
                code = "REAUTH_REQUIRED",
                message =
                    "Fresh authentication is required for this action.",
                status =
                    org.springframework.http.HttpStatus.PRECONDITION_REQUIRED,
            )
        }
    }

    private fun resolveActiveIdentity(
        jwt: Jwt,
    ): UserIdentityRuntime {
        val subject = OidcSubjectResolver.from(jwt)
        val identity = identityRepository.findByOidcSubject(
            issuer = subject.issuer,
            subject = subject.subject,
        ) ?: throw IdentityAccessException(
            code = "IDENTITY_NOT_PROVISIONED",
            message =
                "Authenticated identity is not provisioned in HILTECH.",
        )

        if (identity.status != "ACTIVE") {
            throw IdentityAccessException(
                code = "IDENTITY_NOT_ACTIVE",
                message = "HILTECH identity is not active.",
            )
        }
        return identity
    }

    private fun resolveActiveDevice(
        identityId: UUID,
        installationId: UUID,
    ): DeviceRuntime {
        val device = identityRepository.findDevice(
            installationId,
        ) ?: throw IdentityAccessException(
            code = "DEVICE_NOT_REGISTERED",
            message = "This device is not registered.",
        )

        if (device.userIdentityId != identityId) {
            throw IdentityAccessException(
                code = "DEVICE_INSTALLATION_CONFLICT",
                message =
                    "This device installation belongs to another identity.",
                status =
                    org.springframework.http.HttpStatus.CONFLICT,
            )
        }
        if (device.revokedAt != null) {
            throw IdentityAccessException(
                code = "DEVICE_REVOKED",
                message =
                    "This device installation has been revoked.",
            )
        }
        return device
    }

    fun providerSessionReference(
        jwt: Jwt,
    ): String =
        sequenceOf(
            jwt.getClaimAsString("sid"),
            jwt.getClaimAsString("session_state"),
        ).firstOrNull {
            !it.isNullOrBlank()
        }?.trim()
            ?: throw IdentityAccessException(
                code = "PROVIDER_SESSION_REFERENCE_MISSING",
                message =
                    "The authenticated provider session is missing its session reference.",
                status =
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
            )
}
