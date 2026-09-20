package com.hiltech.server

import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionIntent
import com.hiltech.server.security.AuthorizationProjectionRetryPolicy
import com.hiltech.server.security.HiltechOpenFgaProperties
import com.hiltech.server.security.JdbcAuthorizationProjectionGuard
import com.hiltech.server.security.JdbcAuthorizationProjectionIntentWriter
import com.hiltech.server.security.JdbcAuthorizationProjectionStore
import com.hiltech.server.security.OpenFgaTuple
import com.hiltech.server.security.ProjectionGuardDecision
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
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

        assertEquals(21, result.migrationsExecuted)

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
                        'authorization_relation_projection','authorization_projection_outbox',
                        'identity_session','activity_event','event_publication',
                        'approval_authority_binding','approval_policy_version','approval_request',
                        'approval_step','approval_assignment','approval_decision',
                        'inbox_item','inbox_user_state',
                        'notification_intent','notification_delivery_attempt',
                        'person','employee','employment',
                        'people_authority_binding',
                        'project_authority_binding','project_responsibility',
                        'workforce_assignment',
                        'team_membership_authority_aggregate',
                        'employee_document',
                        'certification',
                        'onboarding_policy',
                        'onboarding_policy_requirement',
                        'onboarding_case',
                        'onboarding_manual_requirement_resolution',
                        'employee_identity_invitation',
                        'offboarding_case',
                        'offboarding_clearance'
                      )
                    """.trimIndent(),
                )
                assertTrue(tables.next())
                assertEquals(41, tables.getInt(1))
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

            val ownerBindingId = UUID.randomUUID()
            connection.prepareStatement(
                """
                INSERT INTO approval_authority_binding
                    (id, organization_id, authority_key, principal_type, principal_user_id,
                     effective_from, active, created_by_user_id, created_at, version)
                VALUES (?, ?, 'OWNER_FINAL', 'USER', ?, ?, true, ?, ?, 1)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, ownerBindingId)
                ps.setObject(2, orgId)
                ps.setObject(3, userId)
                ps.setObject(4, now)
                ps.setObject(5, userId)
                ps.setObject(6, now)
                ps.executeUpdate()
            }

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO approval_authority_binding
                        (id, organization_id, authority_key, principal_type, principal_user_id,
                         effective_from, active, created_by_user_id, created_at, version)
                    VALUES (?, ?, 'OWNER_FINAL', 'USER', ?, ?, true, ?, ?, 1)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, orgId)
                    ps.setObject(3, userId)
                    ps.setObject(4, now)
                    ps.setObject(5, userId)
                    ps.setObject(6, now)
                    ps.executeUpdate()
                }
            }

            val inboxItemId = UUID.randomUUID()
            connection.prepareStatement(
                """
                INSERT INTO inbox_item (
                    id,
                    organization_id,
                    producer_key,
                    source_type,
                    source_id,
                    source_version,
                    action_key,
                    attention_class,
                    target_principal_type,
                    target_user_id,
                    state,
                    safe_title_code,
                    source_event_id,
                    created_at,
                    updated_at,
                    correlation_id,
                    version
                )
                VALUES (
                    ?, ?,
                    'approval:contract:decide',
                    'APPROVAL_REQUEST',
                    ?, 1,
                    'DECIDE_APPROVAL',
                    'ACTION_REQUIRED',
                    'USER', ?,
                    'OPEN',
                    'APPROVAL_DECISION_REQUIRED',
                    ?, ?, ?,
                    'corr-inbox-contract',
                    1
                )
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, inboxItemId)
                ps.setObject(2, orgId)
                ps.setObject(3, UUID.randomUUID())
                ps.setObject(4, userId)
                ps.setObject(5, UUID.randomUUID())
                ps.setObject(6, now)
                ps.setObject(7, now)
                ps.executeUpdate()
            }

            connection.prepareStatement(
                """
                INSERT INTO inbox_user_state (
                    inbox_item_id,
                    user_identity_id,
                    read_at,
                    updated_at,
                    version
                )
                VALUES (?, ?, NULL, ?, 1)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, inboxItemId)
                ps.setObject(2, userId)
                ps.setObject(3, now)
                ps.executeUpdate()
            }

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO inbox_item (
                        id,
                        organization_id,
                        producer_key,
                        source_type,
                        source_id,
                        source_version,
                        action_key,
                        attention_class,
                        target_principal_type,
                        target_user_id,
                        target_team_id,
                        state,
                        safe_title_code,
                        source_event_id,
                        created_at,
                        updated_at,
                        version
                    )
                    VALUES (
                        ?, ?,
                        'invalid:principal:shape',
                        'APPROVAL_REQUEST',
                        ?, 1,
                        'DECIDE_APPROVAL',
                        'ACTION_REQUIRED',
                        'USER',
                        ?, ?,
                        'OPEN',
                        'APPROVAL_DECISION_REQUIRED',
                        ?, ?, ?, 1
                    )
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, orgId)
                    ps.setObject(3, UUID.randomUUID())
                    ps.setObject(4, userId)
                    ps.setObject(5, UUID.randomUUID())
                    ps.setObject(6, UUID.randomUUID())
                    ps.setObject(7, now)
                    ps.setObject(8, now)
                    ps.executeUpdate()
                }
            }

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO inbox_item (
                        id,
                        organization_id,
                        producer_key,
                        source_type,
                        source_id,
                        source_version,
                        action_key,
                        attention_class,
                        target_principal_type,
                        target_user_id,
                        state,
                        safe_title_code,
                        source_event_id,
                        created_at,
                        updated_at,
                        resolved_at,
                        version
                    )
                    VALUES (
                        ?, ?,
                        'invalid:open:resolved',
                        'APPROVAL_REQUEST',
                        ?, 1,
                        'DECIDE_APPROVAL',
                        'ACTION_REQUIRED',
                        'USER',
                        ?,
                        'OPEN',
                        'APPROVAL_DECISION_REQUIRED',
                        ?, ?, ?, ?, 1
                    )
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, orgId)
                    ps.setObject(3, UUID.randomUUID())
                    ps.setObject(4, userId)
                    ps.setObject(5, UUID.randomUUID())
                    ps.setObject(6, now)
                    ps.setObject(7, now)
                    ps.setObject(8, now)
                    ps.executeUpdate()
                }
            }

            assertConstraintRejects(connection) {
                prepareStatement(
                    """
                    INSERT INTO approval_policy_version
                        (id, organization_id, policy_key, version_number, lifecycle_state,
                         approval_required, step_mode, authority_key, reason_required,
                         effective_from, created_by_user_id, created_at, version)
                    VALUES (?, ?, 'ROUTINE_FINANCE_ADMIN', 1, 'ACTIVE',
                            false, 'SINGLE', 'OWNER_FINAL', false,
                            ?, ?, ?, 1)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, orgId)
                    ps.setObject(3, now)
                    ps.setObject(4, userId)
                    ps.setObject(5, now)
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

        val dataSource = DriverManagerDataSource(url, user, password)
        val jdbc = JdbcTemplate(dataSource)
        val transactionManager = DataSourceTransactionManager(dataSource)
        val transaction = TransactionTemplate(transactionManager)
        val openFgaProperties = HiltechOpenFgaProperties(
            enabled = true,
            apiUrl = "http://openfga-contract",
            storeId = "store-contract",
            authorizationModelId = "model-contract",
        )
        val intentWriter = JdbcAuthorizationProjectionIntentWriter(
            jdbc = jdbc,
            properties = openFgaProperties,
        )
        val retryPolicy = AuthorizationProjectionRetryPolicy()
        val projectionStore = JdbcAuthorizationProjectionStore(
            jdbc = jdbc,
            transactionManager = transactionManager,
            properties = openFgaProperties,
            retryPolicy = retryPolicy,
        )
        val guard = JdbcAuthorizationProjectionGuard(jdbc)

        val tuple = OpenFgaTuple(
            subjectType = "user",
            subjectId = "contract-user",
            relation = "assigned_user",
            objectType = "work_order",
            objectId = "contract-work",
        )
        val projectionStartedAt = Instant.parse("2026-09-19T00:00:00Z")

        transaction.executeWithoutResult {
            intentWriter.write(
                AuthorizationProjectionIntent(
                    eventId = UUID.randomUUID(),
                    tuple = tuple,
                    desiredState = AuthorizationDesiredState.PRESENT,
                    sourceType = "WorkAssignment",
                    sourceId = "contract-assignment",
                    sourceVersion = 1,
                    occurredAt = projectionStartedAt,
                ),
            )
        }

        assertEquals(
            ProjectionGuardDecision.DENY_FAIL_CLOSED,
            guard.evaluate(tuple),
            "Pending grants must fail closed before OpenFGA projection is APPLIED.",
        )

        val grantWork = projectionStore.claimNext(
            projectionStartedAt.plusSeconds(1),
        )
        assertTrue(grantWork != null, "Expected pending grant outbox work.")
        assertEquals(
            AuthorizationDesiredState.PRESENT,
            grantWork!!.projection.desiredState,
        )

        projectionStore.markApplied(
            work = grantWork,
            now = projectionStartedAt.plusSeconds(1),
        )

        assertEquals(
            ProjectionGuardDecision.PROCEED_TO_OPENFGA,
            guard.evaluate(tuple),
            "Applied grants may proceed to the pinned OpenFGA decision.",
        )

        transaction.executeWithoutResult {
            intentWriter.write(
                AuthorizationProjectionIntent(
                    eventId = UUID.randomUUID(),
                    tuple = tuple,
                    desiredState = AuthorizationDesiredState.ABSENT,
                    sourceType = "WorkAssignment",
                    sourceId = "contract-assignment",
                    sourceVersion = 2,
                    occurredAt = projectionStartedAt.plusSeconds(2),
                ),
            )
        }

        assertEquals(
            ProjectionGuardDecision.DENY_FAIL_CLOSED,
            guard.evaluate(tuple),
            "Pending revokes must deny immediately while stale OpenFGA tuples may still exist.",
        )

        val revokeWork = projectionStore.claimNext(
            projectionStartedAt.plusSeconds(3),
        )
        assertTrue(revokeWork != null, "Expected pending revoke outbox work.")
        assertEquals(
            AuthorizationDesiredState.ABSENT,
            revokeWork!!.projection.desiredState,
        )

        projectionStore.markApplied(
            work = revokeWork,
            now = projectionStartedAt.plusSeconds(3),
        )

        assertEquals(
            ProjectionGuardDecision.PROCEED_TO_OPENFGA,
            guard.evaluate(tuple),
            "Once revoke projection is APPLIED, normal OpenFGA evaluation may resume.",
        )

        val projectionState = jdbc.queryForMap(
            """
            SELECT desired_state, projection_state, authorization_model_id
            FROM authorization_relation_projection
            WHERE relation_key = ?
            """.trimIndent(),
            tuple.relationKey,
        )
        assertEquals("ABSENT", projectionState["desired_state"])
        assertEquals("APPLIED", projectionState["projection_state"])
        assertEquals("model-contract", projectionState["authorization_model_id"])

        val completedOutbox = jdbc.queryForObject(
            """
            SELECT count(*)
            FROM authorization_projection_outbox
            WHERE relation_key = ?
              AND completed_at IS NOT NULL
            """.trimIndent(),
            Int::class.java,
            tuple.relationKey,
        )
        assertEquals(2, completedOutbox)
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
