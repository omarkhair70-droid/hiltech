package com.hiltech.server

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

class DatabaseMigrationContractTest {
    private val enabled = System.getenv("HILTECH_DB_CONTRACT_TEST") == "1"
    private val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
    private val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
    private val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
    private val migrationPath = System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations"

    @Test
    fun emptyPostgresMigratesThroughFirstSliceAndRejectsInvalidStates() {
        assumeTrue(enabled)

        val result = Flyway.configure()
            .dataSource(url, user, password)
            .locations("filesystem:$migrationPath")
            .load()
            .migrate()

        assertEquals(9, result.migrationsExecuted)

        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                val tables = statement.executeQuery(
                    """
                    SELECT count(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name IN (
                        'organization','user_identity','config_revision','project','site',
                        'work_order','asset','stock_balance','evidence',
                        'authorization_relation_projection','authorization_projection_outbox'
                      )
                    """.trimIndent(),
                )
                assertTrue(tables.next())
                assertEquals(11, tables.getInt(1))
            }

            val orgId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val now = OffsetDateTime.now()

            connection.prepareStatement(
                """
                INSERT INTO organization
                    (id, organization_code, legal_name, display_name, organization_type, status, created_at, version)
                VALUES (?, ?, ?, ?, 'HILTECH', 'ACTIVE', ?, 1)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, orgId)
                ps.setString(2, "HILTECH-CONTRACT")
                ps.setString(3, "HILTECH Contract Test")
                ps.setString(4, "HILTECH")
                ps.setObject(5, now)
                ps.executeUpdate()
            }

            connection.prepareStatement(
                """
                INSERT INTO user_identity
                    (id, auth_provider, auth_subject, status, primary_organization_id, created_at, version)
                VALUES (?, 'test', ?, 'ACTIVE', ?, ?, 1)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, "subject-${UUID.randomUUID()}")
                ps.setObject(3, orgId)
                ps.setObject(4, now)
                ps.executeUpdate()
            }

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO organization
                        (id, organization_code, legal_name, display_name, organization_type, status, created_at, version)
                    VALUES (?, ?, 'Invalid', 'Invalid', 'HILTECH', 'BROKEN', ?, 1)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setString(2, "INVALID-${UUID.randomUUID()}")
                    ps.setObject(3, now)
                    ps.executeUpdate()
                }
            }

            val activeConfigId = UUID.randomUUID()
            connection.prepareStatement(
                """
                INSERT INTO config_revision
                    (id, scope_type, scope_organization_id, family, code, name, lifecycle_state,
                     revision_number, created_at, created_by, activated_at, activated_by, version)
                VALUES (?, 'SYSTEM', NULL, 'work-types', 'CONTRACT_DUP', 'Contract', 'ACTIVE',
                        1, ?, ?, ?, ?, 1)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, activeConfigId)
                ps.setObject(2, now)
                ps.setObject(3, userId)
                ps.setObject(4, now)
                ps.setObject(5, userId)
                ps.executeUpdate()
            }

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO config_revision
                        (id, scope_type, scope_organization_id, family, code, name, lifecycle_state,
                         revision_number, created_at, created_by, activated_at, activated_by, version)
                    VALUES (?, 'SYSTEM', NULL, 'work-types', 'CONTRACT_DUP', 'Contract 2', 'ACTIVE',
                            2, ?, ?, ?, ?, 1)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, now)
                    ps.setObject(3, userId)
                    ps.setObject(4, now)
                    ps.setObject(5, userId)
                    ps.executeUpdate()
                }
            }

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO evidence
                        (id, organization_id, target_type, target_id, evidence_type_code, content_type,
                         size_bytes, sha256, captured_at, captured_by_user_id, storage_state,
                         classification_code, client_visibility_mode, created_at, version)
                    VALUES (?, ?, 'CONTRACT_TEST', ?, 'PHOTO', 'image/jpeg',
                            16777217, ?, ?, ?, 'RESERVED', 'INTERNAL', 'INTERNAL', ?, 1)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, orgId)
                    ps.setObject(3, UUID.randomUUID())
                    ps.setString(4, "a".repeat(64))
                    ps.setObject(5, now)
                    ps.setObject(6, userId)
                    ps.setObject(7, now)
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun assertConstraintRejects(
        connection: java.sql.Connection,
        block: java.sql.Connection.() -> Unit,
    ) {
        var rejected = false
        try {
            connection.block()
        } catch (_: SQLException) {
            rejected = true
        }
        assertTrue(rejected, "Expected PostgreSQL to reject contract-invalid data")
    }
}
