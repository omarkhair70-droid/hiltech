package com.hiltech.server.work

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class FieldEngineeringTestPostgresContractTest {
    private val enabled = System.getenv("HILTECH_DB_CONTRACT_TEST") == "1"
    private val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
    private val user = System.getenv("HILTECH_DB_USER") ?: "hiltech"
    private val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
    private val migrationPath = System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations"

    @Test
    fun `v0036 binds result to exact work assignment and keeps retests append only`() {
        assumeTrue(enabled)

        Flyway.configure()
            .dataSource(url, user, password)
            .locations("filesystem:$migrationPath")
            .load()
            .migrate()

        DriverManager.getConnection(url, user, password).use { connection ->
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val organizationId = UUID.randomUUID()
            val actorId = UUID.randomUUID()
            val projectId = UUID.randomUUID()
            val siteId = UUID.randomUUID()
            val projectSiteId = UUID.randomUUID()
            val workOrderId = UUID.randomUUID()
            val otherWorkOrderId = UUID.randomUUID()
            val assignmentId = UUID.randomUUID()
            val evidencePolicyId = UUID.randomUUID()
            val schemaTemplateId = UUID.randomUUID()

            insertOrganization(connection, organizationId, now)
            insertActorAndMembership(connection, organizationId, actorId, now)
            insertProjectSiteAndWork(
                connection = connection,
                organizationId = organizationId,
                actorId = actorId,
                projectId = projectId,
                siteId = siteId,
                projectSiteId = projectSiteId,
                workOrderId = workOrderId,
                otherWorkOrderId = otherWorkOrderId,
                now = now,
            )
            insertAssignment(
                connection = connection,
                organizationId = organizationId,
                actorId = actorId,
                projectId = projectId,
                workOrderId = workOrderId,
                assignmentId = assignmentId,
                now = now,
            )
            insertEngineeringConfiguration(
                connection = connection,
                organizationId = organizationId,
                actorId = actorId,
                evidencePolicyId = evidencePolicyId,
                schemaTemplateId = schemaTemplateId,
                now = now,
            )

            val firstResultId = UUID.randomUUID()
            insertResult(
                connection = connection,
                operationId = firstResultId,
                organizationId = organizationId,
                actorId = actorId,
                workOrderId = workOrderId,
                assignmentId = assignmentId,
                evidencePolicyId = evidencePolicyId,
                schemaTemplateId = schemaTemplateId,
                evaluatedStatus = "FAIL",
                value = "0.50",
                supersedesResultId = null,
                now = now,
            )

            connection.prepareStatement(
                """
                SELECT evaluated_status, values_json ->> 'loss' AS loss
                FROM field_engineering_test_result
                WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, firstResultId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("FAIL", rows.getString("evaluated_status"))
                    assertEquals("0.50", rows.getString("loss"))
                }
            }

            assertThrows<SQLException> {
                insertResult(
                    connection = connection,
                    operationId = UUID.randomUUID(),
                    organizationId = organizationId,
                    actorId = actorId,
                    workOrderId = otherWorkOrderId,
                    assignmentId = assignmentId,
                    evidencePolicyId = evidencePolicyId,
                    schemaTemplateId = schemaTemplateId,
                    evaluatedStatus = "UNKNOWN",
                    value = "0.25",
                    supersedesResultId = null,
                    now = now,
                )
            }

            assertThrows<SQLException> {
                connection.prepareStatement(
                    """
                    UPDATE field_engineering_test_result
                    SET evaluated_status = 'PASS'
                    WHERE operation_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, firstResultId)
                    statement.executeUpdate()
                }
            }
            assertThrows<SQLException> {
                connection.prepareStatement(
                    "DELETE FROM field_engineering_test_result WHERE operation_id = ?",
                ).use { statement ->
                    statement.setObject(1, firstResultId)
                    statement.executeUpdate()
                }
            }

            val retestId = UUID.randomUUID()
            insertResult(
                connection = connection,
                operationId = retestId,
                organizationId = organizationId,
                actorId = actorId,
                workOrderId = workOrderId,
                assignmentId = assignmentId,
                evidencePolicyId = evidencePolicyId,
                schemaTemplateId = schemaTemplateId,
                evaluatedStatus = "PASS",
                value = "0.20",
                supersedesResultId = firstResultId,
                now = now.plusSeconds(60),
            )

            assertThrows<SQLException> {
                insertResult(
                    connection = connection,
                    operationId = UUID.randomUUID(),
                    organizationId = organizationId,
                    actorId = actorId,
                    workOrderId = workOrderId,
                    assignmentId = assignmentId,
                    evidencePolicyId = evidencePolicyId,
                    schemaTemplateId = schemaTemplateId,
                    evaluatedStatus = "PASS",
                    value = "0.18",
                    supersedesResultId = firstResultId,
                    now = now.plusSeconds(120),
                )
            }

            connection.prepareStatement(
                """
                SELECT operation_id, evaluated_status, supersedes_result_id
                FROM field_engineering_test_result
                WHERE work_order_id = ?
                ORDER BY created_at, operation_id
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, workOrderId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(firstResultId, rows.getObject("operation_id", UUID::class.java))
                    assertEquals("FAIL", rows.getString("evaluated_status"))
                    assertEquals(null, rows.getObject("supersedes_result_id", UUID::class.java))
                    assertTrue(rows.next())
                    assertEquals(retestId, rows.getObject("operation_id", UUID::class.java))
                    assertEquals("PASS", rows.getString("evaluated_status"))
                    assertEquals(firstResultId, rows.getObject("supersedes_result_id", UUID::class.java))
                    assertEquals(false, rows.next())
                }
            }
        }
    }

    private fun insertOrganization(
        connection: Connection,
        organizationId: UUID,
        now: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO organization
                (id, organization_code, legal_name, display_name, organization_type, status, created_at, version)
            VALUES (?, ?, 'Engineering Test Contract', 'Engineering Test Contract', 'HILTECH', 'ACTIVE', ?, 1)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, organizationId)
            statement.setString(2, "ENG-TEST-${organizationId.toString().take(8)}")
            statement.setObject(3, now)
            statement.executeUpdate()
        }
    }

    private fun insertActorAndMembership(
        connection: Connection,
        organizationId: UUID,
        actorId: UUID,
        now: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO user_identity
                (id, auth_provider, auth_subject, status, primary_organization_id, created_at, version)
            VALUES (?, 'test', ?, 'ACTIVE', ?, ?, 1)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, actorId)
            statement.setString(2, "engineering-${UUID.randomUUID()}")
            statement.setObject(3, organizationId)
            statement.setObject(4, now)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO organization_membership
                (id, organization_id, user_identity_id, membership_type, role_label, state, valid_from, version)
            VALUES (?, ?, ?, 'EMPLOYEE', 'Field technician', 'ACTIVE', ?, 1)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, organizationId)
            statement.setObject(3, actorId)
            statement.setObject(4, now.minusDays(1))
            statement.executeUpdate()
        }
    }

    private fun insertProjectSiteAndWork(
        connection: Connection,
        organizationId: UUID,
        actorId: UUID,
        projectId: UUID,
        siteId: UUID,
        projectSiteId: UUID,
        workOrderId: UUID,
        otherWorkOrderId: UUID,
        now: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO project
                (id, organization_id, project_code, name, client_organization_id, lifecycle_state,
                 baseline_version, created_at, created_by, updated_at, version, source_type)
            VALUES (?, ?, ?, 'Engineering contract project', ?, 'PLANNING',
                    1, ?, ?, ?, 1, 'INTERNAL')
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, projectId)
            statement.setObject(2, organizationId)
            statement.setString(3, "ENG-${projectId.toString().take(8)}")
            statement.setObject(4, organizationId)
            statement.setObject(5, now)
            statement.setObject(6, actorId)
            statement.setObject(7, now)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO site
                (id, client_organization_id, site_code, name, status, created_at, created_by, updated_at, version,
                 organization_id)
            VALUES (?, ?, ?, 'Engineering contract site', 'ACTIVE', ?, ?, ?, 1, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, siteId)
            statement.setObject(2, organizationId)
            statement.setString(3, "SITE-${siteId.toString().take(8)}")
            statement.setObject(4, now)
            statement.setObject(5, actorId)
            statement.setObject(6, now)
            statement.setObject(7, organizationId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO project_site
                (id, project_id, site_id, project_site_code, lifecycle_state, version, organization_id)
            VALUES (?, ?, ?, ?, 'PLANNED', 1, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, projectSiteId)
            statement.setObject(2, projectId)
            statement.setObject(3, siteId)
            statement.setString(4, "PS-${projectSiteId.toString().take(8)}")
            statement.setObject(5, organizationId)
            statement.executeUpdate()
        }

        listOf(workOrderId, otherWorkOrderId).forEachIndexed { index, id ->
            connection.prepareStatement(
                """
                INSERT INTO work_order
                    (id, work_order_code, project_id, site_id, project_site_id, title,
                     lifecycle_state, readiness_state, priority_code, created_at, created_by,
                     updated_at, version, organization_id, baseline_version)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', 'NOT_EVALUATED', 'NORMAL', ?, ?, ?, 1, ?, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, "WO-ENG-${index + 1}-${id.toString().take(6)}")
                statement.setObject(3, projectId)
                statement.setObject(4, siteId)
                statement.setObject(5, projectSiteId)
                statement.setString(6, "Engineering result contract ${index + 1}")
                statement.setObject(7, now)
                statement.setObject(8, actorId)
                statement.setObject(9, now)
                statement.setObject(10, organizationId)
                statement.executeUpdate()
            }
        }
    }

    private fun insertAssignment(
        connection: Connection,
        organizationId: UUID,
        actorId: UUID,
        projectId: UUID,
        workOrderId: UUID,
        assignmentId: UUID,
        now: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO work_assignment
                (id, work_order_id, target_type, target_id, lead, assigned_at, assigned_by,
                 valid_from, state, source_operation_id, version, organization_id, project_id)
            VALUES (?, ?, 'USER', ?, false, ?, ?, ?, 'ACTIVE', ?, 1, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, assignmentId)
            statement.setObject(2, workOrderId)
            statement.setObject(3, actorId)
            statement.setObject(4, now)
            statement.setObject(5, actorId)
            statement.setObject(6, now)
            statement.setObject(7, UUID.randomUUID())
            statement.setObject(8, organizationId)
            statement.setObject(9, projectId)
            statement.executeUpdate()
        }
    }

    private fun insertEngineeringConfiguration(
        connection: Connection,
        organizationId: UUID,
        actorId: UUID,
        evidencePolicyId: UUID,
        schemaTemplateId: UUID,
        now: OffsetDateTime,
    ) {
        fun config(id: UUID, family: String, code: String, name: String) {
            connection.prepareStatement(
                """
                INSERT INTO config_revision
                    (id, scope_type, scope_organization_id, family, code, name, lifecycle_state,
                     revision_number, created_at, created_by, activated_at, activated_by, version)
                VALUES (?, 'ORGANIZATION', ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, ?, ?, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, organizationId)
                statement.setString(3, family)
                statement.setString(4, code)
                statement.setString(5, name)
                statement.setObject(6, now)
                statement.setObject(7, actorId)
                statement.setObject(8, now)
                statement.setObject(9, actorId)
                statement.executeUpdate()
            }
        }

        config(
            evidencePolicyId,
            "evidence-policies",
            "ENG-EVIDENCE-${evidencePolicyId.toString().take(8)}",
            "Engineering evidence",
        )
        config(
            schemaTemplateId,
            "templates",
            "ENG-SCHEMA-${schemaTemplateId.toString().take(8)}",
            "Engineering schema",
        )

        connection.prepareStatement(
            "INSERT INTO evidence_policy (config_revision_id, system_max_size_bytes) VALUES (?, 16777216)",
        ).use { statement ->
            statement.setObject(1, evidencePolicyId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO template_definition
                (config_revision_id, template_type, schema_version, structured_definition)
            VALUES (?, 'ENGINEERING_TEST_SCHEMA', 1,
                    '{"label":"Loss test","metrics":[{"key":"loss","valueType":"DECIMAL","unitCode":"dB"}]}'::jsonb)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, schemaTemplateId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO evidence_policy_requirement
                (id, config_revision_id, requirement_key, evidence_type_code, stage,
                 min_count, max_count, offline_capture_allowed, allowed_content_types,
                 classification_code, retention_policy_code, client_visibility_mode,
                 reviewer_relationship_code, security_scan_class, structured_test_template_id)
            VALUES (?, ?, 'fiber_acceptance', 'ENGINEERING_TEST', 'BEFORE_SUBMIT',
                    1, 1, true, ARRAY['application/pdf']::varchar[],
                    'INTERNAL', NULL, 'INTERNAL', 'FIELD_ENGINEER', 'ARBITRARY_FILE', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, evidencePolicyId)
            statement.setObject(3, schemaTemplateId)
            statement.executeUpdate()
        }
    }

    private fun insertResult(
        connection: Connection,
        operationId: UUID,
        organizationId: UUID,
        actorId: UUID,
        workOrderId: UUID,
        assignmentId: UUID,
        evidencePolicyId: UUID,
        schemaTemplateId: UUID,
        evaluatedStatus: String,
        value: String,
        supersedesResultId: UUID?,
        now: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO field_engineering_test_result (
                operation_id, organization_id, work_order_id, assignment_id, assignment_version,
                captured_by_user_id, device_installation_id, work_order_version,
                evidence_policy_id, evidence_policy_revision, requirement_key,
                schema_template_id, schema_template_revision, schema_version,
                test_subject_ref, values_json, device_reported_status, evaluated_status,
                evaluation_json, captured_at, client_occurred_at, supersedes_result_id,
                created_at, correlation_id, version
            ) VALUES (
                ?, ?, ?, ?, 1,
                ?, ?, 1,
                ?, 1, 'fiber_acceptance',
                ?, 1, 1,
                'LINK-A01', jsonb_build_object('loss', ?), NULL, ?,
                jsonb_build_object('status', ?), ?, ?, ?,
                ?, ?, 1
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, operationId)
            statement.setObject(2, organizationId)
            statement.setObject(3, workOrderId)
            statement.setObject(4, assignmentId)
            statement.setObject(5, actorId)
            statement.setObject(6, UUID.randomUUID())
            statement.setObject(7, evidencePolicyId)
            statement.setObject(8, schemaTemplateId)
            statement.setString(9, value)
            statement.setString(10, evaluatedStatus)
            statement.setString(11, evaluatedStatus)
            statement.setObject(12, now)
            statement.setObject(13, now)
            statement.setObject(14, supersedesResultId)
            statement.setObject(15, now)
            statement.setString(16, "engineering-contract-${operationId}")
            statement.executeUpdate()
        }
    }
}
