package com.hiltech.server.identity

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class UserIdentityRuntime(
    val id: UUID,
    val status: String,
    val primaryOrganizationId: UUID?,
    val version: Long,
)

data class DeviceRegistration(
    val installationId: UUID,
    val platform: String,
    val deviceName: String?,
    val appVersion: String,
    val osVersion: String?,
)

data class DeviceRuntime(
    val id: UUID,
    val userIdentityId: UUID,
    val installationId: UUID,
    val platform: String,
    val deviceName: String?,
    val appVersion: String,
    val osVersion: String?,
    val lastSeenAt: Instant?,
    val revokedAt: Instant?,
    val version: Long,
)

sealed interface DeviceRegistrationOutcome {
    data class Active(
        val device: DeviceRuntime,
    ) : DeviceRegistrationOutcome

    data object Revoked : DeviceRegistrationOutcome

    data object OwnedByAnotherIdentity : DeviceRegistrationOutcome
}

interface IdentityRuntimeRepository {
    fun findByOidcSubject(
        issuer: String,
        subject: String,
    ): UserIdentityRuntime?

    fun markAuthenticated(
        identityId: UUID,
        authenticatedAt: Instant,
    )

    fun findDevice(
        installationId: UUID,
    ): DeviceRuntime?

    fun registerOrTouchDevice(
        identityId: UUID,
        registration: DeviceRegistration,
        seenAt: Instant,
    ): DeviceRegistrationOutcome

    fun listDevices(
        identityId: UUID,
    ): List<DeviceRuntime> = emptyList()

    fun revokeDevice(
        identityId: UUID,
        deviceId: UUID,
        revokedAt: Instant,
    ): Boolean = false
}

@Component
class JdbcIdentityRuntimeRepository(
    private val jdbc: JdbcTemplate,
) : IdentityRuntimeRepository {
    override fun findByOidcSubject(
        issuer: String,
        subject: String,
    ): UserIdentityRuntime? =
        jdbc.query(
            """
            SELECT id, status, primary_organization_id, version
            FROM user_identity
            WHERE auth_provider = ?
              AND auth_subject = ?
            """.trimIndent(),
            { rs, _ ->
                UserIdentityRuntime(
                    id = rs.getObject("id", UUID::class.java),
                    status = rs.getString("status"),
                    primaryOrganizationId = rs.getObject(
                        "primary_organization_id",
                        UUID::class.java,
                    ),
                    version = rs.getLong("version"),
                )
            },
            issuer,
            subject,
        ).singleOrNull()

    override fun markAuthenticated(
        identityId: UUID,
        authenticatedAt: Instant,
    ) {
        jdbc.update(
            """
            UPDATE user_identity
            SET last_authenticated_at = ?
            WHERE id = ?
            """.trimIndent(),
            authenticatedAt.atOffset(ZoneOffset.UTC),
            identityId,
        )
    }

    override fun findDevice(
        installationId: UUID,
    ): DeviceRuntime? =
        jdbc.query(
            """
            SELECT
                id,
                user_identity_id,
                installation_id,
                platform,
                device_name,
                app_version,
                os_version,
                last_seen_at,
                revoked_at,
                version
            FROM device
            WHERE installation_id = ?
            """.trimIndent(),
            { rs, _ ->
                DeviceRuntime(
                    id = rs.getObject("id", UUID::class.java),
                    userIdentityId = rs.getObject(
                        "user_identity_id",
                        UUID::class.java,
                    ),
                    installationId = rs.getObject(
                        "installation_id",
                        UUID::class.java,
                    ),
                    platform = rs.getString("platform"),
                    deviceName = rs.getString("device_name"),
                    appVersion = rs.getString("app_version"),
                    osVersion = rs.getString("os_version"),
                    lastSeenAt = rs.getObject(
                        "last_seen_at",
                        OffsetDateTime::class.java,
                    )?.toInstant(),
                    revokedAt = rs.getObject(
                        "revoked_at",
                        OffsetDateTime::class.java,
                    )?.toInstant(),
                    version = rs.getLong("version"),
                )
            },
            installationId,
        ).singleOrNull()

    override fun listDevices(
        identityId: UUID,
    ): List<DeviceRuntime> =
        jdbc.query(
            """
            SELECT
                id,
                user_identity_id,
                installation_id,
                platform,
                device_name,
                app_version,
                os_version,
                last_seen_at,
                revoked_at,
                version
            FROM device
            WHERE user_identity_id = ?
            ORDER BY
                (revoked_at IS NULL) DESC,
                last_seen_at DESC NULLS LAST,
                created_at DESC
            LIMIT 100
            """.trimIndent(),
            { rs, _ ->
                DeviceRuntime(
                    id = rs.getObject(
                        "id",
                        UUID::class.java,
                    ),
                    userIdentityId = rs.getObject(
                        "user_identity_id",
                        UUID::class.java,
                    ),
                    installationId = rs.getObject(
                        "installation_id",
                        UUID::class.java,
                    ),
                    platform = rs.getString("platform"),
                    deviceName = rs.getString("device_name"),
                    appVersion = rs.getString("app_version"),
                    osVersion = rs.getString("os_version"),
                    lastSeenAt = rs.getObject(
                        "last_seen_at",
                        OffsetDateTime::class.java,
                    )?.toInstant(),
                    revokedAt = rs.getObject(
                        "revoked_at",
                        OffsetDateTime::class.java,
                    )?.toInstant(),
                    version = rs.getLong("version"),
                )
            },
            identityId,
        )

    override fun revokeDevice(
        identityId: UUID,
        deviceId: UUID,
        revokedAt: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE device
            SET revoked_at = ?,
                version = version + 1
            WHERE id = ?
              AND user_identity_id = ?
              AND revoked_at IS NULL
            """.trimIndent(),
            revokedAt.atOffset(ZoneOffset.UTC),
            deviceId,
            identityId,
        ) == 1

    override fun registerOrTouchDevice(
        identityId: UUID,
        registration: DeviceRegistration,
        seenAt: Instant,
    ): DeviceRegistrationOutcome {
        require(
            registration.platform in setOf(
                "ANDROID",
                "WINDOWS",
                "IOS",
            ),
        ) {
            "Unsupported device platform: ${registration.platform}"
        }
        require(registration.appVersion.isNotBlank()) {
            "appVersion must not be blank."
        }

        val existing = findDevice(registration.installationId)
        if (existing != null) {
            if (existing.userIdentityId != identityId) {
                return DeviceRegistrationOutcome.OwnedByAnotherIdentity
            }
            if (existing.revokedAt != null) {
                return DeviceRegistrationOutcome.Revoked
            }
        }

        val rows = jdbc.query(
            """
            INSERT INTO device (
                id,
                user_identity_id,
                platform,
                device_name,
                installation_id,
                app_version,
                os_version,
                last_seen_at,
                revoked_at,
                created_at,
                version
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 1)
            ON CONFLICT (installation_id) DO UPDATE SET
                platform = EXCLUDED.platform,
                device_name = EXCLUDED.device_name,
                app_version = EXCLUDED.app_version,
                os_version = EXCLUDED.os_version,
                last_seen_at = EXCLUDED.last_seen_at,
                version = device.version + 1
            WHERE device.user_identity_id = EXCLUDED.user_identity_id
              AND device.revoked_at IS NULL
            RETURNING
                id,
                user_identity_id,
                installation_id,
                platform,
                device_name,
                app_version,
                os_version,
                last_seen_at,
                revoked_at,
                version
            """.trimIndent(),
            { rs, _ ->
                DeviceRuntime(
                    id = rs.getObject("id", UUID::class.java),
                    userIdentityId = rs.getObject(
                        "user_identity_id",
                        UUID::class.java,
                    ),
                    installationId = rs.getObject(
                        "installation_id",
                        UUID::class.java,
                    ),
                    platform = rs.getString("platform"),
                    deviceName = rs.getString("device_name"),
                    appVersion = rs.getString("app_version"),
                    osVersion = rs.getString("os_version"),
                    lastSeenAt = rs.getObject(
                        "last_seen_at",
                        OffsetDateTime::class.java,
                    )?.toInstant(),
                    revokedAt = rs.getObject(
                        "revoked_at",
                        OffsetDateTime::class.java,
                    )?.toInstant(),
                    version = rs.getLong("version"),
                )
            },
            UUID.randomUUID(),
            identityId,
            registration.platform,
            registration.deviceName,
            registration.installationId,
            registration.appVersion,
            registration.osVersion,
            seenAt.atOffset(ZoneOffset.UTC),
            seenAt.atOffset(ZoneOffset.UTC),
        )

        val device = rows.singleOrNull()
        if (device != null) {
            return DeviceRegistrationOutcome.Active(device)
        }

        val current = findDevice(registration.installationId)
        return when {
            current == null ->
                error(
                    "Device upsert returned no row and no current installation record.",
                )

            current.userIdentityId != identityId ->
                DeviceRegistrationOutcome.OwnedByAnotherIdentity

            current.revokedAt != null ->
                DeviceRegistrationOutcome.Revoked

            else ->
                error(
                    "Device upsert returned no row for an active owned installation.",
                )
        }
    }
}
