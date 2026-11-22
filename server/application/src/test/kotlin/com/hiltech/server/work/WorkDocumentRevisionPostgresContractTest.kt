package com.hiltech.server.work

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class WorkDocumentRevisionPostgresContractTest {
    private val enabled =
        System.getenv("HILTECH_DB_CONTRACT_TEST") == "1"
    private val url =
        System.getenv("HILTECH_DB_URL")
            ?: "jdbc:postgresql://localhost:5432/hiltech"
    private val user =
        System.getenv("HILTECH_DB_USER")
            ?: "hiltech"
    private val password =
        System.getenv("HILTECH_DB_PASSWORD")
            ?: "hiltech"
    private val migrationPath =
        System.getenv("HILTECH_MIGRATIONS_PATH")
            ?: "database/migrations"

    @Test
    fun `v0037 keeps old approved revision current until newer revision is approved`() {
        assumeTrue(enabled)

        Flyway.configure()
            .dataSource(
                url,
                user,
                password,
            )
            .locations(
                "filesystem:$migrationPath",
            )
            .load()
            .migrate()

        val dataSource =
            DriverManagerDataSource(
                url,
                user,
                password,
            )
        val jdbc =
            JdbcTemplate(dataSource)
        val persistence =
            WorkDocumentRevisionPersistence(
                jdbc,
            )
        val fieldBundleSource =
            JdbcFieldAssignedWorkPersistence(
                jdbc,
                workDocumentsEnabled = true,
            )

        DriverManager
            .getConnection(
                url,
                user,
                password,
            )
            .use { connection ->
                val now =
                    OffsetDateTime.now(
                        ZoneOffset.UTC,
                    )
                val organizationId =
                    UUID.randomUUID()
                val actorId =
                    UUID.randomUUID()
                val projectId =
                    UUID.randomUUID()
                val siteId =
                    UUID.randomUUID()
                val projectSiteId =
                    UUID.randomUUID()
                val workOrderId =
                    UUID.randomUUID()
                val otherWorkOrderId =
                    UUID.randomUUID()
                val policyId =
                    UUID.randomUUID()
                val documentId =
                    UUID.randomUUID()
                val firstRevisionId =
                    UUID.randomUUID()
                val secondRevisionId =
                    UUID.randomUUID()
                val firstEvidenceId =
                    UUID.randomUUID()
                val secondEvidenceId =
                    UUID.randomUUID()
                val wrongWorkEvidenceId =
                    UUID.randomUUID()
                val firstApprovalId =
                    UUID.randomUUID()
                val secondApprovalId =
                    UUID.randomUUID()

                insertOrganization(
                    connection,
                    organizationId,
                    now,
                )
                insertActorAndMembership(
                    connection,
                    organizationId,
                    actorId,
                    now,
                )
                insertProjectSiteAndWork(
                    connection,
                    organizationId,
                    actorId,
                    projectId,
                    siteId,
                    projectSiteId,
                    workOrderId,
                    otherWorkOrderId,
                    now,
                )
                insertApprovalPolicy(
                    connection,
                    policyId,
                    organizationId,
                    actorId,
                    now,
                )
                insertEvidence(
                    connection,
                    firstEvidenceId,
                    organizationId,
                    workOrderId,
                    actorId,
                    "READY",
                    now,
                )
                insertEvidence(
                    connection,
                    secondEvidenceId,
                    organizationId,
                    workOrderId,
                    actorId,
                    "READY",
                    now.plusSeconds(60),
                )
                insertEvidence(
                    connection,
                    wrongWorkEvidenceId,
                    organizationId,
                    otherWorkOrderId,
                    actorId,
                    "READY",
                    now,
                )

                insertDocument(
                    connection,
                    documentId,
                    organizationId,
                    workOrderId,
                    actorId,
                    now,
                )

                // The database itself rejects content from another WorkOrder.
                assertThrows<SQLException> {
                    insertRevision(
                        connection,
                        UUID.randomUUID(),
                        organizationId,
                        workOrderId,
                        documentId,
                        1,
                        "BAD",
                        wrongWorkEvidenceId,
                        null,
                        actorId,
                        now,
                    )
                }

                insertRevision(
                    connection,
                    firstRevisionId,
                    organizationId,
                    workOrderId,
                    documentId,
                    1,
                    "A",
                    firstEvidenceId,
                    null,
                    actorId,
                    now,
                )
                insertApprovalRequest(
                    connection,
                    firstApprovalId,
                    organizationId,
                    firstRevisionId,
                    actorId,
                    "APPROVED",
                    now,
                    now.plusSeconds(10),
                )
                insertRelease(
                    connection,
                    firstRevisionId,
                    organizationId,
                    workOrderId,
                    documentId,
                    firstApprovalId,
                    now.plusSeconds(10),
                )

                insertRevision(
                    connection,
                    secondRevisionId,
                    organizationId,
                    workOrderId,
                    documentId,
                    2,
                    "B",
                    secondEvidenceId,
                    firstRevisionId,
                    actorId,
                    now.plusSeconds(60),
                )
                insertApprovalRequest(
                    connection,
                    secondApprovalId,
                    organizationId,
                    secondRevisionId,
                    actorId,
                    "PENDING",
                    now.plusSeconds(60),
                    null,
                )
                insertRelease(
                    connection,
                    secondRevisionId,
                    organizationId,
                    workOrderId,
                    documentId,
                    secondApprovalId,
                    now.plusSeconds(60),
                )

                // A pending newer revision must not invalidate the prior approved current source.
                var current =
                    persistence.currentApproved(
                        workOrderId,
                        "drawing",
                        "DWG-001",
                    )
                assertEquals(1, current.size)
                assertEquals(
                    firstRevisionId,
                    current.single().revisionId,
                )
                assertEquals(
                    "A",
                    current.single().revisionLabel,
                )
                var safeRefs =
                    fieldBundleSource.safeDocumentRefs(
                        workOrderId,
                    )
                assertEquals(1, safeRefs.size)
                assertEquals(
                    "DWG-001",
                    safeRefs.single().code,
                )
                assertEquals(
                    "A",
                    safeRefs.single().revision,
                )
                assertEquals(
                    WORK_DOCUMENT_REVISION_SUBJECT,
                    safeRefs.single().sourceType,
                )

                // Revision identities/releases are append-only.
                assertThrows<SQLException> {
                    connection.prepareStatement(
                        """
                        UPDATE work_document_revision
                        SET revision_label = 'MUTATED'
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(
                            1,
                            firstRevisionId,
                        )
                        statement.executeUpdate()
                    }
                }
                assertThrows<SQLException> {
                    connection.prepareStatement(
                        """
                        DELETE FROM work_document_revision_release
                        WHERE revision_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(
                            1,
                            firstRevisionId,
                        )
                        statement.executeUpdate()
                    }
                }
                assertThrows<SQLException> {
                    connection.prepareStatement(
                        """
                        UPDATE work_document
                        SET title = 'Mutated'
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(
                            1,
                            documentId,
                        )
                        statement.executeUpdate()
                    }
                }

                // No branch may fork from revision A after B already superseded it.
                assertThrows<SQLException> {
                    insertRevision(
                        connection,
                        UUID.randomUUID(),
                        organizationId,
                        workOrderId,
                        documentId,
                        3,
                        "C-BRANCH",
                        UUID.randomUUID().also { id ->
                            insertEvidence(
                                connection,
                                id,
                                organizationId,
                                workOrderId,
                                actorId,
                                "READY",
                                now.plusSeconds(90),
                            )
                        },
                        firstRevisionId,
                        actorId,
                        now.plusSeconds(90),
                    )
                }

                approveRequest(
                    connection,
                    secondApprovalId,
                    now.plusSeconds(120),
                )

                current =
                    persistence.currentApproved(
                        workOrderId,
                        "drawing",
                        "DWG-001",
                    )
                assertEquals(1, current.size)
                assertEquals(
                    secondRevisionId,
                    current.single().revisionId,
                )
                assertEquals(
                    "B",
                    current.single().revisionLabel,
                )
                assertEquals(
                    2,
                    current.single().revisionSequence,
                )
                safeRefs =
                    fieldBundleSource.safeDocumentRefs(
                        workOrderId,
                    )
                assertEquals(1, safeRefs.size)
                assertEquals(
                    "B",
                    safeRefs.single().revision,
                )

                assertTrue(
                    persistence.isLatestRevision(
                        secondRevisionId,
                        documentId,
                    ),
                )
                assertEquals(
                    false,
                    persistence.isLatestRevision(
                        firstRevisionId,
                        documentId,
                    ),
                )
            }
    }

    private fun insertOrganization(
        connection: Connection,
        organizationId: UUID,
        now: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO organization (
                id,
                organization_code,
                legal_name,
                display_name,
                organization_type,
                status,
                created_at,
                version
            ) VALUES (
                ?,
                ?,
                'Work Document Contract',
                'Work Document Contract',
                'HILTECH',
                'ACTIVE',
                ?,
                1
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(
                1,
                organizationId,
            )
            statement.setString(
                2,
                "DOC-" +
                    organizationId
                        .toString()
                        .take(8),
            )
            statement.setObject(
                3,
                now,
            )
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
            INSERT INTO user_identity (
                id,
                auth_provider,
                auth_subject,
                status,
                primary_organization_id,
                created_at,
                version
            ) VALUES (
                ?,
                'test',
                ?,
                'ACTIVE',
                ?,
                ?,
                1
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(
                1,
                actorId,
            )
            statement.setString(
                2,
                "document-" +
                    UUID.randomUUID(),
            )
            statement.setObject(
                3,
                organizationId,
            )
            statement.setObject(
                4,
                now,
            )
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO organization_membership (
                id,
                organization_id,
                user_identity_id,
                membership_type,
                role_label,
                state,
                valid_from,
                version
            ) VALUES (
                ?,
                ?,
                ?,
                'EMPLOYEE',
                'Document contract actor',
                'ACTIVE',
                ?,
                1
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(
                1,
                UUID.randomUUID(),
            )
            statement.setObject(
                2,
                organizationId,
            )
            statement.setObject(
                3,
                actorId,
            )
            statement.setObject(
                4,
                now.minusDays(1),
            )
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
            INSERT INTO project (
                id,
                organization_id,
                project_code,
                name,
                client_organization_id,
                lifecycle_state,
                baseline_version,
                created_at,
                created_by,
                updated_at,
                version,
                source_type
            ) VALUES (
                ?,
                ?,
                ?,
                'Document contract project',
                ?,
                'PLANNING',
                1,
                ?,
                ?,
                ?,
                1,
                'INTERNAL'
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(
                1,
                projectId,
            )
            statement.setObject(
                2,
                organizationId,
            )
            statement.setString(
                3,
                "DOC-" +
                    projectId
                        .toString()
                        .take(8),
            )
            statement.setObject(
                4,
                organizationId,
            )
            statement.setObject(
                5,
                now,
            )
            statement.setObject(
                6,
                actorId,
            )
            statement.setObject(
                7,
                now,
            )
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO site (
                id,
                client_organization_id,
                site_code,
                name,
                status,
                created_at,
                created_by,
                updated_at,
                version,
                organization_id
            ) VALUES (
                ?,
                ?,
                ?,
                'Document contract site',
                'ACTIVE',
                ?,
                ?,
                ?,
                1,
                ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(
                1,
                siteId,
            )
            statement.setObject(
                2,
                organizationId,
            )
            statement.setString(
                3,
                "SITE-" +
                    siteId
                        .toString()
                        .take(8),
            )
            statement.setObject(
                4,
                now,
            )
            statement.setObject(
                5,
                actorId,
            )
            statement.setObject(
                6,
                now,
            )
            statement.setObject(
                7,
                organizationId,
            )
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO project_site (
                id,
                project_id,
                site_id,
                project_site_code,
                lifecycle_state,
                version,
                organization_id
            ) VALUES (
                ?,
                ?,
                ?,
                ?,
                'PLANNED',
                1,
                ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(
                1,
                projectSiteId,
            )
            statement.setObject(
                2,
                projectId,
            )
            statement.setObject(
                3,
                siteId,
            )
            statement.setString(
                4,
                "PS-" +
                    projectSiteId
                        .toString()
                        .take(8),
            )
            statement.setObject(
                5,
                organizationId,
            )
            statement.executeUpdate()
        }

        listOf(
            workOrderId,
            otherWorkOrderId,
        ).forEachIndexed { index, id ->
            connection.prepareStatement(
                """
                INSERT INTO work_order (
                    id,
                    work_order_code,
                    project_id,
                    site_id,
                    project_site_id,
                    title,
                    lifecycle_state,
                    readiness_state,
                    priority_code,
                    created_at,
                    created_by,
                    updated_at,
                    version,
                    organization_id,
                    baseline_version
                ) VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    'DRAFT',
                    'NOT_EVALUATED',
                    'NORMAL',
                    ?,
                    ?,
                    ?,
                    1,
                    ?,
                    1
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(
                    1,
                    id,
                )
                statement.setString(
                    2,
                    "WO-DOC-" +
                        (index + 1) +
                        "-" +
                        id.toString()
                            .take(6),
                )
                statement.setObject(
                    3,
                    projectId,
                )
                statement.setObject(
                    4,
                    siteId,
                )
                statement.setObject(
                    5,
                    projectSiteId,
                )
                statement.setString(
                    6,
                    "Document contract " +
                        (index + 1),
                )
                statement.setObject(
                    7,
                    now,
                )
                statement.setObject(
                    8,
                    actorId,
                )
                statement.setObject(
                    9,
                    now,
                )
                statement.setObject(
                    10,
                    organizationId,
                )
                statement.executeUpdate()
            }
        }
    }

    private fun insertApprovalPolicy(
        connection: Connection,
        policyId: UUID,
        organizationId: UUID,
        actorId: UUID,
        now: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO approval_policy_version (
                id,
                organization_id,
                policy_key,
                version_number,
                lifecycle_state,
                approval_required,
                step_mode,
                authority_key,
                reason_required,
                effective_from,
                created_by_user_id,
                created_at,
                version
            ) VALUES (
                ?,
                ?,
                'DRAWING_RELEASE',
                1,
                'ACTIVE',
                true,
                'SINGLE',
                'FIELD_ENGINEER',
                false,
                ?,
                ?,
                ?,
                1
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(
                1,
                policyId,
            )
            statement.setObject(
                2,
                organizationId,
            )
            statement.setObject(
                3,
                now.minusSeconds(60),
            )
            statement.setObject(
                4,
                actorId,
            )
            statement.setObject(
                5,
                now,
            )
            statement.executeUpdate()
        }
    }

    private fun insertEvidence(
        connection: Connection,
        evidenceId: UUID,
        organizationId: UUID,
        workOrderId: UUID,
        actorId: UUID,
        storageState: String,
        now: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO evidence (
                id,
                organization_id,
                target_type,
                target_id,
                work_order_id,
                evidence_requirement_key,
                evidence_type_code,
                content_type,
                original_file_name,
                size_bytes,
                sha256,
                captured_at,
                captured_by_user_id,
                storage_state,
                object_key_ref,
                finalized_at,
                classification_code,
                client_visibility_mode,
                created_at,
                version
            ) VALUES (
                ?,
                ?,
                'WORK_ORDER',
                ?,
                ?,
                'drawing',
                'DRAWING',
                'application/pdf',
                'drawing.pdf',
                128,
                ?,
                ?,
                ?,
                ?,
                'private/work-documents',
                ?,
                'INTERNAL',
                'INTERNAL',
                ?,
                1
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(
                1,
                evidenceId,
            )
            statement.setObject(
                2,
                organizationId,
            )
            statement.setObject(
                3,
                workOrderId,
            )
            statement.setObject(
                4,
                workOrderId,
            )
            statement.setString(
                5,
                "a".repeat(64),
            )
            statement.setObject(
                6,
                now,
            )
            statement.setObject(
                7,
                actorId,
            )
            statement.setString(
                8,
                storageState,
            )
            statement.setObject(
                9,
                if (storageState == "READY") {
                    now
                } else {
                    null
                },
            )
            statement.setObject(
                10,
                now,
            )
            statement.executeUpdate()
        }
    }

    private fun insertDocument(
        connection: Connection,
        documentId: UUID,
        organizationId: UUID,
        workOrderId: UUID,
        actorId: UUID,
        now: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO work_document (
                id,
                organization_id,
                work_order_id,
                requirement_key,
                document_code,
                title,
                approval_policy_key,
                created_by_user_id,
                created_at,
                version
            ) VALUES (
                ?,
                ?,
                ?,
                'drawing',
                'DWG-001',
                'Fiber route drawing',
                'DRAWING_RELEASE',
                ?,
                ?,
                1
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(
                1,
                documentId,
            )
            statement.setObject(
                2,
                organizationId,
            )
            statement.setObject(
                3,
                workOrderId,
            )
            statement.setObject(
                4,
                actorId,
            )
            statement.setObject(
                5,
                now,
            )
            statement.executeUpdate()
        }
    }

    private fun insertRevision(
        connection: Connection,
        revisionId: UUID,
        organizationId: UUID,
        workOrderId: UUID,
        documentId: UUID,
        sequence: Int,
        label: String,
        evidenceId: UUID,
        supersedes: UUID?,
        actorId: UUID,
        now: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO work_document_revision (
                id,
                organization_id,
                work_order_id,
                document_id,
                revision_sequence,
                revision_label,
                evidence_id,
                supersedes_revision_id,
                created_by_user_id,
                created_at,
                version
            ) VALUES (
                ?,
                ?,
                ?,
                ?,
                ?,
                ?,
                ?,
                ?,
                ?,
                ?,
                1
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(
                1,
                revisionId,
            )
            statement.setObject(
                2,
                organizationId,
            )
            statement.setObject(
                3,
                workOrderId,
            )
            statement.setObject(
                4,
                documentId,
            )
            statement.setInt(
                5,
                sequence,
            )
            statement.setString(
                6,
                label,
            )
            statement.setObject(
                7,
                evidenceId,
            )
            statement.setObject(
                8,
                supersedes,
            )
            statement.setObject(
                9,
                actorId,
            )
            statement.setObject(
                10,
                now,
            )
            statement.executeUpdate()
        }
    }

    private fun insertApprovalRequest(
        connection: Connection,
        approvalId: UUID,
        organizationId: UUID,
        revisionId: UUID,
        actorId: UUID,
        state: String,
        createdAt: OffsetDateTime,
        completedAt: OffsetDateTime?,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO approval_request (
                id,
                organization_id,
                subject_type,
                subject_id,
                subject_version,
                policy_key,
                policy_version,
                requester_user_id,
                state,
                reason_code,
                safe_reason_summary,
                created_at,
                completed_at,
                correlation_id,
                version
            ) VALUES (
                ?,
                ?,
                'WORK_DOCUMENT_REVISION',
                ?,
                1,
                'DRAWING_RELEASE',
                1,
                ?,
                ?,
                'WORK_DOCUMENT_REVISION',
                'Drawing release contract',
                ?,
                ?,
                ?,
                1
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(
                1,
                approvalId,
            )
            statement.setObject(
                2,
                organizationId,
            )
            statement.setObject(
                3,
                revisionId,
            )
            statement.setObject(
                4,
                actorId,
            )
            statement.setString(
                5,
                state,
            )
            statement.setObject(
                6,
                createdAt,
            )
            statement.setObject(
                7,
                completedAt,
            )
            statement.setString(
                8,
                "drawing-contract-" +
                    approvalId,
            )
            statement.executeUpdate()
        }
    }

    private fun insertRelease(
        connection: Connection,
        revisionId: UUID,
        organizationId: UUID,
        workOrderId: UUID,
        documentId: UUID,
        approvalId: UUID,
        now: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO work_document_revision_release (
                revision_id,
                organization_id,
                work_order_id,
                document_id,
                policy_key,
                policy_version,
                release_mode,
                approval_request_id,
                created_at
            ) VALUES (
                ?,
                ?,
                ?,
                ?,
                'DRAWING_RELEASE',
                1,
                'APPROVAL_REQUEST',
                ?,
                ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(
                1,
                revisionId,
            )
            statement.setObject(
                2,
                organizationId,
            )
            statement.setObject(
                3,
                workOrderId,
            )
            statement.setObject(
                4,
                documentId,
            )
            statement.setObject(
                5,
                approvalId,
            )
            statement.setObject(
                6,
                now,
            )
            statement.executeUpdate()
        }
    }

    private fun approveRequest(
        connection: Connection,
        approvalId: UUID,
        completedAt: OffsetDateTime,
    ) {
        connection.prepareStatement(
            """
            UPDATE approval_request
            SET state = 'APPROVED',
                completed_at = ?,
                version = version + 1
            WHERE id = ?
              AND state = 'PENDING'
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(
                1,
                completedAt,
            )
            statement.setObject(
                2,
                approvalId,
            )
            assertEquals(
                1,
                statement.executeUpdate(),
            )
        }
    }
}
