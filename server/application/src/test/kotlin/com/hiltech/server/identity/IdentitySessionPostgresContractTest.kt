package com.hiltech.server.identity

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class IdentitySessionPostgresContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_IDENTITY_SESSION_CONTRACT_TEST",
        ) == "1"
    private val url =
        System.getenv("HILTECH_DB_URL")
            ?: "jdbc:postgresql://localhost:5432/hiltech"
    private val user =
        System.getenv("HILTECH_DB_USER") ?: "hiltech"
    private val password =
        System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
    private val migrationPath =
        System.getenv("HILTECH_MIGRATIONS_PATH")
            ?: "database/migrations"

    @Test
    fun sessionBindingUsesHashedProviderReferenceAndRevocationCannotResurrect() {
        assumeTrue(enabled)

        Flyway.configure()
            .dataSource(url, user, password)
            .locations("filesystem:$migrationPath")
            .load()
            .migrate()

        val jdbc = JdbcTemplate(
            DriverManagerDataSource(
                url,
                user,
                password,
            ),
        )
        val identityId = UUID.randomUUID()
        val orgId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val installationId = UUID.randomUUID()
        val now =
            Instant.parse("2026-09-19T06:30:00Z")
        val nowOffset =
            now.atOffset(ZoneOffset.UTC)

        jdbc.update(
            """
            INSERT INTO organization (
                id, organization_code, legal_name,
                display_name, organization_type,
                status, created_at, version
            )
            VALUES (?, ?, 'Session Contract',
                'Session Contract', 'HILTECH',
                'ACTIVE', ?, 1)
            """.trimIndent(),
            orgId,
            "SESSION-" + UUID.randomUUID(),
            nowOffset,
        )
        jdbc.update(
            """
            INSERT INTO user_identity (
                id, auth_provider, auth_subject,
                status, primary_organization_id,
                created_at, version
            )
            VALUES (?, 'test', ?, 'ACTIVE', ?, ?, 1)
            """.trimIndent(),
            identityId,
            "session-" + UUID.randomUUID(),
            orgId,
            nowOffset,
        )
        jdbc.update(
            """
            INSERT INTO device (
                id, user_identity_id, platform,
                device_name, installation_id,
                app_version, os_version,
                last_seen_at, revoked_at,
                created_at, version
            )
            VALUES (?, ?, 'ANDROID', 'Phone', ?,
                '1', '16', ?, NULL, ?, 1)
            """.trimIndent(),
            deviceId,
            identityId,
            installationId,
            nowOffset,
            nowOffset,
        )

        val repository =
            JdbcIdentitySessionRepository(jdbc)
        val providerRef =
            "keycloak-session-secret-value"

        val first = repository.registerOrTouch(
            identityId = identityId,
            deviceId = deviceId,
            providerSessionRef = providerRef,
            authenticationStrength = "1",
            seenAt = now,
            expiresAt = now.plusSeconds(3_600),
        )

        val storedHash = jdbc.queryForObject(
            """
            SELECT provider_session_ref_hash
            FROM identity_session
            WHERE id = ?
            """.trimIndent(),
            String::class.java,
            first.id,
        )
        assertNotEquals(providerRef, storedHash)
        assertEquals(64, storedHash?.length)

        val current = repository.findCurrent(
            identityId = identityId,
            deviceId = deviceId,
            providerSessionRef = providerRef,
            at = now.plusSeconds(1),
        )
        assertEquals(first.id, current?.id)

        assertTrue(
            repository.revokeOwned(
                identityId = identityId,
                sessionId = first.id,
                revokedAt = now.plusSeconds(2),
            ),
        )
        assertNull(
            repository.findCurrent(
                identityId = identityId,
                deviceId = deviceId,
                providerSessionRef = providerRef,
                at = now.plusSeconds(3),
            ),
        )

        val failure =
            org.junit.jupiter.api.assertThrows<
                IdentityAccessException
            > {
                repository.registerOrTouch(
                    identityId = identityId,
                    deviceId = deviceId,
                    providerSessionRef = providerRef,
                    authenticationStrength = "1",
                    seenAt = now.plusSeconds(4),
                    expiresAt =
                        now.plusSeconds(3_604),
                )
            }
        assertEquals("SESSION_REVOKED", failure.code)
    }
}
