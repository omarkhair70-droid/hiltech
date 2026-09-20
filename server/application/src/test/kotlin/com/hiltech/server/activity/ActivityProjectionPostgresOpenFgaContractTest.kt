package com.hiltech.server.activity

import com.hiltech.server.documents.EvidenceTerminalStateChanged
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.FailClosedAuthorizationAdapter
import com.hiltech.server.security.HiltechOpenFgaProperties
import com.hiltech.server.security.JdbcAuthorizationProjectionGuard
import com.hiltech.server.security.JdbcRoleTeamSourceAuthority
import com.hiltech.server.security.JdkOpenFgaHttpTransport
import com.hiltech.server.security.OpenFgaGateway
import com.hiltech.server.security.OpenFgaMutationResult
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ActivityProjectionPostgresOpenFgaContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_ACTIVITY_CONTRACT_TEST",
        ) == "1"

    private val dbUrl =
        System.getenv("HILTECH_DB_URL")
            ?: "jdbc:postgresql://localhost:5432/hiltech"
    private val dbUser =
        System.getenv("HILTECH_DB_USER")
            ?: "hiltech"
    private val dbPassword =
        System.getenv("HILTECH_DB_PASSWORD")
            ?: "hiltech"
    private val migrationPath =
        System.getenv("HILTECH_MIGRATIONS_PATH")
            ?: "database/migrations"

    private val fgaApiUrl =
        System.getenv("HILTECH_FGA_API_URL")
            ?: ""
    private val fgaStoreId =
        System.getenv("HILTECH_FGA_STORE_ID")
            ?: ""
    private val fgaModelId =
        System.getenv("HILTECH_FGA_MODEL_ID")
            ?: ""

    @Test
    fun workOrderActivityIsPermissionSafeDeduplicatedAndStable() {
        assumeTrue(enabled)

        Flyway.configure()
            .dataSource(
                dbUrl,
                dbUser,
                dbPassword,
            )
            .locations(
                "filesystem:$migrationPath",
            )
            .load()
            .migrate()

        val dataSource =
            DriverManagerDataSource(
                dbUrl,
                dbUser,
                dbPassword,
            )
        val jdbc =
            JdbcTemplate(dataSource)
        val clock =
            Clock.fixed(
                Instant.parse(
                    "2026-09-19T11:30:00Z",
                ),
                ZoneOffset.UTC,
            )
        val ids =
            seed(
                jdbc = jdbc,
                now =
                    clock.instant()
                        .minusSeconds(600),
            )

        val properties =
            HiltechOpenFgaProperties(
                enabled = true,
                apiUrl = fgaApiUrl,
                storeId = fgaStoreId,
                authorizationModelId =
                    fgaModelId,
            )
        val gateway =
            OpenFgaGateway(
                properties = properties,
                transport =
                    JdkOpenFgaHttpTransport(
                        properties,
                    ),
            )

        assertEquals(
            OpenFgaMutationResult.Applied,
            gateway.apply(
                ActivityAuthorizationRelations
                    .assignedUser(
                        identityId =
                            ids.actorId,
                        workOrderId =
                            ids.workOrderId,
                    ),
                AuthorizationDesiredState
                    .PRESENT,
            ),
        )

        val authorization =
            FailClosedAuthorizationAdapter(
                guard =
                    JdbcAuthorizationProjectionGuard(
                        jdbc,
                    ),
                openFgaGateway =
                    gateway,
            )
        val sourceAuthority =
            JdbcRoleTeamSourceAuthority(
                jdbc,
            )
        val workAuthorization =
            JdbcWorkOrderActivityAuthorization(
                jdbc = jdbc,
                authorization =
                    authorization,
                sourceAuthority =
                    sourceAuthority,
                clock = clock,
            )
        val projection =
            JdbcActivityProjection(
                jdbc = jdbc,
                clock = clock,
            )
        val cursorCodec =
            ActivityCursorCodec(
                "activity-contract-signing-key-20260919-strong",
            )
        val service =
            WorkOrderActivityService(
                projection = projection,
                authorization =
                    workAuthorization,
                cursorCodec = cursorCodec,
                clock = clock,
            )

        val ready =
            event(
                ids = ids,
                evidenceId =
                    ids.readyEvidenceId,
                state = "READY",
                classification =
                    "INTERNAL",
                occurredAt =
                    clock.instant()
                        .minusSeconds(30),
            )
        val quarantined =
            event(
                ids = ids,
                evidenceId =
                    ids.quarantinedEvidenceId,
                state = "QUARANTINED",
                classification =
                    "RESTRICTED",
                occurredAt =
                    clock.instant()
                        .minusSeconds(20),
            )
        val rejected =
            event(
                ids = ids,
                evidenceId =
                    ids.rejectedEvidenceId,
                state = "REJECTED",
                classification =
                    "HIGHLY_RESTRICTED",
                occurredAt =
                    clock.instant()
                        .minusSeconds(10),
            )

        assertTrue(
            projection.appendEvidenceTerminal(
                ready,
            ),
        )
        assertTrue(
            projection.appendEvidenceTerminal(
                quarantined,
            ),
        )
        assertTrue(
            projection.appendEvidenceTerminal(
                rejected,
            ),
        )
        assertFalse(
            projection.appendEvidenceTerminal(
                ready,
            ),
            "Redelivery must be deduplicated by source_event_id.",
        )

        assertEquals(
            3,
            jdbc.queryForObject(
                """
                SELECT count(*)
                FROM activity_event
                WHERE context_id = ?
                """.trimIndent(),
                Int::class.java,
                ids.workOrderId,
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                """
                SELECT count(*)
                FROM audit_event
                WHERE target_id IN (?, ?, ?)
                """.trimIndent(),
                Int::class.java,
                ids.readyEvidenceId,
                ids.quarantinedEvidenceId,
                ids.rejectedEvidenceId,
            ),
            "Activity projection must not write the Audit boundary.",
        )

        val restrictedSummary =
            jdbc.queryForObject(
                """
                SELECT safe_summary::text
                FROM activity_event
                WHERE source_id = ?
                """.trimIndent(),
                String::class.java,
                ids.rejectedEvidenceId,
            ) ?: error(
                "Missing HIGHLY_RESTRICTED Activity row.",
            )
        assertTrue(
            restrictedSummary.contains(
                "\"storageState\": \"REJECTED\"",
            ) ||
                restrictedSummary.contains(
                    "\"storageState\":\"REJECTED\"",
                ),
        )
        assertFalse(
            restrictedSummary.contains(
                "evidenceRequirementKey",
            ),
        )
        assertFalse(
            restrictedSummary.contains(
                "evidenceTypeCode",
            ),
        )

        val leak =
            jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM activity_event
                    WHERE safe_summary::text
                              ILIKE '%http%'
                       OR safe_summary::text
                              ILIKE '%token%'
                       OR safe_summary::text
                              ILIKE '%secret%'
                       OR safe_summary::text
                              ILIKE '%object_key%'
                       OR safe_summary::text
                              ~ '[0-9a-f]{64}'
                )
                """.trimIndent(),
                Boolean::class.java,
            ) ?: true
        assertFalse(
            leak,
            "Activity must not expose signed targets, secrets, storage keys or SHA payloads.",
        )

        val first =
            service.read(
                actorIdentityId =
                    ids.actorId,
                workOrderId =
                    ids.workOrderId,
                rawCursor = null,
                requestedLimit = 2,
                correlationId =
                    "corr-activity-first",
            )
        assertEquals(
            listOf(
                "EVIDENCE_REJECTED",
                "EVIDENCE_QUARANTINED",
            ),
            first.items.map {
                it.activityType
            },
        )
        assertNotNull(first.nextCursor)

        val second =
            service.read(
                actorIdentityId =
                    ids.actorId,
                workOrderId =
                    ids.workOrderId,
                rawCursor =
                    first.nextCursor,
                requestedLimit = 2,
                correlationId =
                    "corr-activity-second",
            )
        assertEquals(
            listOf(
                "EVIDENCE_READY",
            ),
            second.items.map {
                it.activityType
            },
        )
        assertEquals(
            first.asOf,
            second.asOf,
            "Pagination must remain on one fixed asOf snapshot.",
        )
        assertEquals(
            3,
            (
                first.items +
                    second.items
            ).map {
                it.activityId
            }.distinct().size,
            "Cursor pagination must not duplicate or skip fixed-snapshot rows.",
        )

        val tampered =
            requireNotNull(first.nextCursor)
                .split('.', limit = 2)
                .let { parts ->
                    val signature = parts.getOrNull(1)
                        ?: error("Expected a signed Activity cursor.")
                    val replacement = if (signature.first() == 'A') 'B' else 'A'
                    "${parts[0]}.$replacement${signature.drop(1)}"
                }
        val invalidCursor =
            assertThrows<
                ProductApiException
            > {
                service.read(
                    actorIdentityId =
                        ids.actorId,
                    workOrderId =
                        ids.workOrderId,
                    rawCursor = tampered,
                    requestedLimit = 2,
                    correlationId =
                        "corr-tampered",
                )
            }
        assertEquals(
            "CURSOR_INVALID",
            invalidCursor.code,
        )

        val outsider =
            assertThrows<
                ProductApiException
            > {
                service.read(
                    actorIdentityId =
                        ids.outsiderId,
                    workOrderId =
                        ids.workOrderId,
                    rawCursor = null,
                    requestedLimit = 10,
                    correlationId =
                        "corr-outsider",
                )
            }
        assertEquals(
            "OBJECT_NOT_VISIBLE",
            outsider.code,
        )

        jdbc.update(
            """
            UPDATE work_assignment
            SET state = 'ENDED',
                valid_until = ?,
                version = version + 1
            WHERE id = ?
            """.trimIndent(),
            clock.instant()
                .minusSeconds(1)
                .atOffset(
                    ZoneOffset.UTC,
                ),
            ids.assignmentId,
        )

        val staleOpenFgaTuple =
            assertThrows<
                ProductApiException
            > {
                service.read(
                    actorIdentityId =
                        ids.actorId,
                    workOrderId =
                        ids.workOrderId,
                    rawCursor = null,
                    requestedLimit = 10,
                    correlationId =
                        "corr-after-reassignment",
                )
            }
        assertEquals(
            "OBJECT_NOT_VISIBLE",
            staleOpenFgaTuple.code,
            "Current PostgreSQL source truth must defeat stale OpenFGA assignment visibility.",
        )
    }

    private fun event(
        ids: SeedIds,
        evidenceId: UUID,
        state: String,
        classification: String,
        occurredAt: Instant,
    ): EvidenceTerminalStateChanged =
        EvidenceTerminalStateChanged(
            eventId = UUID.randomUUID(),
            evidenceId = evidenceId,
            workOrderId =
                ids.workOrderId,
            sourceVersion = 2,
            storageState = state,
            evidenceTypeCode = "PHOTO",
            evidenceRequirementKey =
                "after-photo",
            classificationCode =
                classification,
            actorIdentityId =
                ids.actorId,
            occurredAt = occurredAt,
            correlationId =
                "corr-$state",
        )

    private fun seed(
        jdbc: JdbcTemplate,
        now: Instant,
    ): SeedIds {
        val organizationId =
            UUID.randomUUID()
        val actorId =
            UUID.randomUUID()
        val outsiderId =
            UUID.randomUUID()
        val projectId =
            UUID.randomUUID()
        val siteId =
            UUID.randomUUID()
        val workOrderId =
            UUID.randomUUID()
        val assignmentId =
            UUID.randomUUID()
        val readyEvidenceId =
            UUID.randomUUID()
        val quarantinedEvidenceId =
            UUID.randomUUID()
        val rejectedEvidenceId =
            UUID.randomUUID()
        val at =
            now.atOffset(
                ZoneOffset.UTC,
            )

        jdbc.update(
            """
            INSERT INTO organization (
                id, organization_code,
                legal_name, display_name,
                organization_type, status,
                created_at, version
            )
            VALUES (
                ?, ?, 'Activity Contract',
                'Activity Contract',
                'HILTECH', 'ACTIVE', ?, 1
            )
            """.trimIndent(),
            organizationId,
            "ACT-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            at,
        )

        listOf(
            actorId,
            outsiderId,
        ).forEach { userId ->
            jdbc.update(
                """
                INSERT INTO user_identity (
                    id, auth_provider,
                    auth_subject, status,
                    primary_organization_id,
                    created_at, version
                )
                VALUES (
                    ?, 'contract',
                    ?, 'ACTIVE',
                    ?, ?, 1
                )
                """.trimIndent(),
                userId,
                "activity-$userId",
                organizationId,
                at,
            )
            jdbc.update(
                """
                INSERT INTO organization_membership (
                    id, organization_id,
                    user_identity_id,
                    membership_type,
                    role_label, state,
                    valid_from,
                    valid_until,
                    invited_by, version
                )
                VALUES (
                    ?, ?, ?,
                    'EMPLOYEE',
                    'descriptive-only',
                    'ACTIVE',
                    ?, NULL,
                    NULL, 1
                )
                """.trimIndent(),
                UUID.randomUUID(),
                organizationId,
                userId,
                at.minusMinutes(1),
            )
        }

        jdbc.update(
            """
            INSERT INTO project (
                id, organization_id,
                project_code, name,
                client_organization_id,
                lifecycle_state,
                source_type,
                created_at, created_by,
                updated_at, version
            )
            VALUES (
                ?, ?, ?,
                'Activity Project',
                ?, 'ACTIVE',
                'INTERNAL',
                ?, ?, ?, 1
            )
            """.trimIndent(),
            projectId,
            organizationId,
            "P-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            organizationId,
            at,
            actorId,
            at,
        )

        jdbc.update(
            """
            INSERT INTO site (
                id, organization_id,
                client_organization_id,
                site_code, name,
                status,
                created_at, created_by,
                updated_at, version
            )
            VALUES (
                ?, ?, ?, ?,
                'Activity Site',
                'ACTIVE',
                ?, ?, ?, 1
            )
            """.trimIndent(),
            siteId,
            organizationId,
            organizationId,
            "S-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            at,
            actorId,
            at,
        )

        val slice03ProjectSiteId =
            UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO project_site (
                id,
                project_id,
                site_id,
                lifecycle_state,
                version,
                organization_id
            )
            VALUES (
                ?, ?, ?,
                'ACTIVE',
                1,
                ?
            )
            """.trimIndent(),
            slice03ProjectSiteId,
            projectId,
            siteId,
            organizationId,
        )

        jdbc.update(
            """
            INSERT INTO work_order (
                id, organization_id,
                work_order_code,
                project_id, site_id,
                project_site_id,
                title,
                lifecycle_state,
                readiness_state,
                priority_code,
                baseline_version,
                created_at,
                created_by,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, ?, ?, ?,
                'Activity WorkOrder',
                'DRAFT',
                'READY',
                'NORMAL',
                1,
                ?, ?, ?, 1
            )
            """.trimIndent(),
            workOrderId,
            organizationId,
            "WO-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            projectId,
            siteId,
            slice03ProjectSiteId,
            at,
            actorId,
            at,
        )

        jdbc.update(
            """
            INSERT INTO work_assignment (
                id, work_order_id,
                target_type, target_id,
                lead,
                assigned_at,
                assigned_by,
                valid_from,
                valid_until,
                state,
                source_operation_id,
                version
            )
            VALUES (
                ?, ?,
                'USER', ?,
                true,
                ?, ?,
                ?, NULL,
                'ACTIVE',
                ?, 1
            )
            """.trimIndent(),
            assignmentId,
            workOrderId,
            actorId,
            at,
            actorId,
            at.minusMinutes(1),
            UUID.randomUUID(),
        )

        listOf(
            readyEvidenceId to
                ("READY" to "INTERNAL"),
            quarantinedEvidenceId to
                (
                    "QUARANTINED" to
                        "RESTRICTED"
                ),
            rejectedEvidenceId to
                (
                    "REJECTED" to
                        "HIGHLY_RESTRICTED"
                ),
        ).forEach {
                (
                    evidenceId,
                    stateAndClassification,
                )
            ->
            jdbc.update(
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
                    size_bytes,
                    sha256,
                    captured_at,
                    captured_by_user_id,
                    storage_state,
                    finalized_at,
                    classification_code,
                    client_visibility_mode,
                    created_at,
                    version
                )
                VALUES (
                    ?, ?,
                    'WORK_ORDER', ?,
                    ?,
                    'after-photo',
                    'PHOTO',
                    'image/jpeg',
                    4,
                    ?,
                    ?, ?,
                    ?,
                    ?,
                    ?,
                    'INTERNAL_ONLY',
                    ?, 2
                )
                """.trimIndent(),
                evidenceId,
                organizationId,
                workOrderId,
                workOrderId,
                "a".repeat(64),
                at,
                actorId,
                stateAndClassification.first,
                at,
                stateAndClassification.second,
                at,
            )
        }

        return SeedIds(
            actorId = actorId,
            outsiderId =
                outsiderId,
            workOrderId =
                workOrderId,
            assignmentId =
                assignmentId,
            readyEvidenceId =
                readyEvidenceId,
            quarantinedEvidenceId =
                quarantinedEvidenceId,
            rejectedEvidenceId =
                rejectedEvidenceId,
        )
    }

    private data class SeedIds(
        val actorId: UUID,
        val outsiderId: UUID,
        val workOrderId: UUID,
        val assignmentId: UUID,
        val readyEvidenceId: UUID,
        val quarantinedEvidenceId: UUID,
        val rejectedEvidenceId: UUID,
    )
}
