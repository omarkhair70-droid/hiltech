package com.hiltech.server.audit

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class AuditEventWriterPostgresContractTest {
    private val enabled =
        System.getenv("HILTECH_AUDIT_CONTRACT_TEST") == "1"
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
    fun securityAuditEventPersistsSafeAppendOnlyContext() {
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
        val now =
            Instant.parse("2026-09-19T08:00:00Z")
        val orgId = UUID.randomUUID()
        val identityId = UUID.randomUUID()
        val targetSessionId = UUID.randomUUID()

        jdbc.update(
            """
            INSERT INTO organization (
                id, organization_code, legal_name,
                display_name, organization_type,
                status, created_at, version
            )
            VALUES (?, ?, 'Audit Contract',
                'Audit Contract', 'HILTECH',
                'ACTIVE', ?, 1)
            """.trimIndent(),
            orgId,
            "AUDIT-" + UUID.randomUUID(),
            now.atOffset(ZoneOffset.UTC),
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
            "audit-" + UUID.randomUUID(),
            orgId,
            now.atOffset(ZoneOffset.UTC),
        )

        val correlationId =
            "audit-contract-" + UUID.randomUUID()
        val writer = JdbcAuditEventWriter(jdbc)
        writer.append(
            AuditEventRecord(
                actorUserId = identityId,
                action = "IDENTITY_SESSION_REVOKED",
                targetType = "IdentitySession",
                targetId = targetSessionId,
                previousStateRef = "session:active",
                newStateRef = "session:revoked",
                safeDiffJson =
                    """{"category":"security","transition":"revoke"}""",
                occurredAt = now,
                correlationId = correlationId,
                reason = "REMOTE_SESSION_REVOKE",
            ),
        )

        val row = jdbc.queryForMap(
            """
            SELECT
                actor_user_id,
                action,
                target_type,
                target_id,
                previous_state_ref,
                new_state_ref,
                safe_diff::text AS safe_diff,
                occurred_at,
                correlation_id,
                reason
            FROM audit_event
            WHERE correlation_id = ?
            """.trimIndent(),
            correlationId,
        )

        assertEquals(
            identityId,
            row["actor_user_id"],
        )
        assertEquals(
            "IDENTITY_SESSION_REVOKED",
            row["action"],
        )
        assertEquals(
            "IdentitySession",
            row["target_type"],
        )
        assertEquals(
            targetSessionId,
            row["target_id"],
        )
        assertEquals(
            "session:active",
            row["previous_state_ref"],
        )
        assertEquals(
            "session:revoked",
            row["new_state_ref"],
        )
        assertEquals(
            correlationId,
            row["correlation_id"],
        )
        assertEquals(
            "REMOTE_SESSION_REVOKE",
            row["reason"],
        )
        assertEquals(
            now,
            (row["occurred_at"] as OffsetDateTime)
                .toInstant(),
        )
        val safeDiff =
            row["safe_diff"] as String
        assertNotNull(safeDiff)
        assertFalse(
            safeDiff.contains(
                "token",
                ignoreCase = true,
            ),
        )
        assertFalse(
            safeDiff.contains(
                "password",
                ignoreCase = true,
            ),
        )
    }
}
