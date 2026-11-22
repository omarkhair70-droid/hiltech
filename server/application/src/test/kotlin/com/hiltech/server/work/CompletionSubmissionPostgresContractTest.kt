package com.hiltech.server.work

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class CompletionSubmissionPostgresContractTest {
    private val enabled = System.getenv("HILTECH_DB_CONTRACT_TEST") == "1"
    private val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
    private val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
    private val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
    private val migrations = System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations"

    @Test
    fun v0038TransitionsWorkAndPreservesImmutableSubmissionSource() {
        assumeTrue(enabled)
        Flyway.configure()
            .dataSource(url, user, password)
            .locations("filesystem:$migrations")
            .load()
            .migrate()

        val jdbc = JdbcTemplate(DriverManagerDataSource(url, user, password))
        val org = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val project = UUID.randomUUID()
        val site = UUID.randomUUID()
        val projectSite = UUID.randomUUID()
        val work = UUID.randomUUID()
        val assignment = UUID.randomUUID()
        val binding = UUID.randomUUID()
        val instruction = UUID.randomUUID()
        val operation = UUID.randomUUID()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val refs = (0 until 5).map { UUID.randomUUID() }

        jdbc.update(
            """
            INSERT INTO organization
              (id, organization_code, legal_name, display_name, organization_type,
               status, created_at, version)
            VALUES (?, ?, 'Completion contract', 'Completion contract', 'HILTECH',
                    'ACTIVE', ?, 1)
            """.trimIndent(),
            org, "CMP-" + org.toString().take(8), now,
        )
        jdbc.update(
            """
            INSERT INTO user_identity
              (id, auth_provider, auth_subject, status, primary_organization_id,
               created_at, version)
            VALUES (?, 'test', ?, 'ACTIVE', ?, ?, 1)
            """.trimIndent(),
            actor, "completion-" + actor, org, now,
        )
        jdbc.update(
            """
            INSERT INTO organization_membership
              (id, organization_id, user_identity_id, membership_type,
               role_label, state, valid_from, version)
            VALUES (?, ?, ?, 'EMPLOYEE', 'Assigned field technician',
                    'ACTIVE', ?, 1)
            """.trimIndent(),
            UUID.randomUUID(), org, actor, now.minusDays(1),
        )
        jdbc.update(
            """
            INSERT INTO project
              (id, organization_id, project_code, name, client_organization_id,
               lifecycle_state, baseline_version, created_at, created_by, updated_at,
               version, source_type)
            VALUES (?, ?, ?, 'Completion project', ?, 'PLANNING', 1, ?, ?, ?, 1, 'INTERNAL')
            """.trimIndent(),
            project, org, "PROJECT-" + project.toString().take(8), org, now, actor, now,
        )
        jdbc.update(
            """
            INSERT INTO site
              (id, client_organization_id, site_code, name, status, created_at,
               created_by, updated_at, version, organization_id)
            VALUES (?, ?, ?, 'Completion site', 'ACTIVE', ?, ?, ?, 1, ?)
            """.trimIndent(),
            site, org, "SITE-" + site.toString().take(8), now, actor, now, org,
        )
        jdbc.update(
            """
            INSERT INTO project_site
              (id, project_id, site_id, project_site_code, lifecycle_state,
               version, organization_id)
            VALUES (?, ?, ?, ?, 'PLANNED', 1, ?)
            """.trimIndent(),
            projectSite, project, site, "PS-" + projectSite.toString().take(8), org,
        )
        jdbc.update(
            """
            INSERT INTO work_order
              (id, work_order_code, project_id, site_id, project_site_id, title,
               lifecycle_state, readiness_state, priority_code, created_at,
               created_by, updated_at, version, organization_id, baseline_version)
            VALUES (?, ?, ?, ?, ?, 'Fiber route', 'DRAFT', 'NOT_EVALUATED',
                    'NORMAL', ?, ?, ?, 1, ?, 1)
            """.trimIndent(),
            work, "WO-" + work.toString().take(8), project, site, projectSite,
            now, actor, now, org,
        )
        jdbc.update(
            """
            INSERT INTO work_assignment
              (id, work_order_id, target_type, target_id, lead, assigned_at,
               assigned_by, valid_from, state, source_operation_id, version,
               organization_id, project_id)
            VALUES (?, ?, 'USER', ?, true, ?, ?, ?, 'ACTIVE', ?, 3, ?, ?)
            """.trimIndent(),
            assignment, work, actor, now, actor, now, UUID.randomUUID(), org, project,
        )
        val families = listOf(
            "work-types",
            "assignment-policies",
            "readiness-policies",
            "evidence-policies",
            "review-policies",
        )
        refs.forEachIndexed { i, ref ->
            jdbc.update(
                """
                INSERT INTO config_revision
                  (id, scope_type, scope_organization_id, family, code, name,
                   lifecycle_state, revision_number, created_at, created_by,
                   activated_at, activated_by, version)
                VALUES (?, 'ORGANIZATION', ?, ?, ?, 'Completion config',
                        'ACTIVE', 1, ?, ?, ?, ?, 1)
                """.trimIndent(),
                ref, org, families[i], "CMP-" + ref.toString().take(8),
                now, actor, now, actor,
            )
        }
        jdbc.update(
            """
            INSERT INTO work_policy_binding
              (id, work_order_id, binding_revision,
               work_type_definition_id, work_type_revision,
               assignment_policy_id, assignment_policy_revision,
               readiness_policy_id, readiness_policy_revision,
               evidence_policy_id, evidence_policy_revision,
               review_policy_id, review_policy_revision,
               binding_created_at, binding_created_by)
            VALUES (?, ?, 2, ?, 1, ?, 1, ?, 1, ?, 1, ?, 1, ?, ?)
            """.trimIndent(),
            binding, work, refs[0], refs[1], refs[2], refs[3], refs[4], now, actor,
        )
        jdbc.update(
            """
            INSERT INTO work_instruction_revision
              (id, work_order_id, instruction_revision, payload_schema_version,
               structured_payload_json, created_at, created_by, correlation_id)
            VALUES (?, ?, 1, 1, '{}'::jsonb, ?, ?, 'completion-contract')
            """.trimIndent(),
            instruction, work, now, actor,
        )
        jdbc.update(
            """
            UPDATE work_order
            SET current_policy_binding_id = ?, current_instruction_revision_id = ?,
                lifecycle_state = 'IN_PROGRESS', readiness_state = 'READY', version = 11
            WHERE id = ?
            """.trimIndent(),
            binding, instruction, work,
        )
        val persistence = JdbcCompletionSubmissionPersistence(jdbc)
        val record = WorkCompletionSubmissionRecord(
            operationId = operation,
            organizationId = org,
            workOrderId = work,
            submittedByUserId = actor,
            assignmentId = assignment,
            assignmentVersion = 3,
            baseWorkOrderVersion = 11,
            submittedWorkOrderVersion = 12,
            policyBindingId = binding,
            policyBindingRevision = 2,
            evidencePolicyId = refs[3],
            evidencePolicyRevision = 1,
            reviewPolicyId = refs[4],
            reviewPolicyRevision = 1,
            sourceLifecycleState = "IN_PROGRESS",
            dependencySnapshotJson = """{"sourceWorkVersion":11,"passed":true}""",
            clientOccurredAt = now.toInstant(),
            submittedAt = now.toInstant(),
            correlationId = "completion-contract",
        )
        assertEquals(
            12L,
            persistence.submit(record, 11, WorkOrderLifecycle.IN_PROGRESS, now.toInstant()),
        )
        assertEquals(
            null,
            persistence.submit(
                record.copy(operationId = UUID.randomUUID()),
                11, WorkOrderLifecycle.IN_PROGRESS, now.toInstant(),
            ),
        )
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.prepareStatement(
                """
                SELECT lifecycle_state, version, submitted_review_version
                FROM work_order WHERE id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, work)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("SUBMITTED_FOR_REVIEW", rs.getString(1))
                    assertEquals(12L, rs.getLong(2))
                    assertEquals(12L, rs.getLong(3))
                }
            }
            fun rejects(sql: String, vararg values: Any?) {
                assertThrows<SQLException> {
                    connection.prepareStatement(sql).use { ps ->
                        values.forEachIndexed { index, value ->
                            ps.setObject(index + 1, value)
                        }
                        ps.executeUpdate()
                    }
                }
            }
            rejects(
                "UPDATE work_completion_submission SET dependency_snapshot_json = '{}'::jsonb WHERE operation_id = ?",
                operation,
            )
            rejects("DELETE FROM work_completion_submission WHERE operation_id = ?", operation)
            rejects(
                """
                INSERT INTO work_completion_submission
                SELECT ?, organization_id, work_order_id, submitted_by_user_id,
                       assignment_id, assignment_version, base_work_order_version,
                       submitted_work_order_version, policy_binding_id,
                       policy_binding_revision, evidence_policy_id,
                       evidence_policy_revision, review_policy_id,
                       review_policy_revision, source_lifecycle_state,
                       dependency_snapshot_json, client_occurred_at,
                       submitted_at, correlation_id, version
                FROM work_completion_submission WHERE operation_id = ?
                """.trimIndent(),
                UUID.randomUUID(), operation,
            )
            rejects(
                """
                INSERT INTO work_completion_submission
                SELECT ?, organization_id, work_order_id, submitted_by_user_id,
                       assignment_id, assignment_version, base_work_order_version,
                       submitted_work_order_version + 1, policy_binding_id,
                       policy_binding_revision + 1, evidence_policy_id,
                       evidence_policy_revision, review_policy_id,
                       review_policy_revision, source_lifecycle_state,
                       dependency_snapshot_json, client_occurred_at,
                       submitted_at, correlation_id, version
                FROM work_completion_submission WHERE operation_id = ?
                """.trimIndent(),
                UUID.randomUUID(), operation,
            )
        }
    }
}
