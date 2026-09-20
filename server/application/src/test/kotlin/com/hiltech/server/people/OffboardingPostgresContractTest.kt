package com.hiltech.server.people

import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.identity.JdbcEmploymentAccessRevocation
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.security.HiltechOpenFgaProperties
import com.hiltech.server.security.JdbcAuthorizationProjectionIntentWriter
import com.hiltech.server.security.JdbcRoleTeamAuthorizationProjectionBridge
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class OffboardingPostgresContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_OFFBOARDING_CONTRACT_TEST",
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
            ?: "http://127.0.0.1:8082"
    private val fgaStoreId =
        System.getenv("HILTECH_FGA_STORE_ID")
            ?: "contract-store"
    private val fgaModelId =
        System.getenv("HILTECH_FGA_MODEL_ID")
            ?: "contract-model"

    @Test
    fun offboardingPreservesHistoryAndRevokesRealHiltechAccess() {
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
        val txManager =
            DataSourceTransactionManager(
                dataSource,
            )
        val now =
            Instant.parse(
                "2026-09-20T12:00:00Z",
            )
        val clock =
            Clock.fixed(
                now,
                ZoneOffset.UTC,
            )
        val ids =
            seed(
                jdbc = jdbc,
                now =
                    now.minusSeconds(300),
            )

        val properties =
            HiltechOpenFgaProperties(
                enabled = true,
                apiUrl = fgaApiUrl,
                storeId = fgaStoreId,
                authorizationModelId =
                    fgaModelId,
            )
        properties
            .validateEnabledConfiguration()

        val projectionWriter =
            JdbcAuthorizationProjectionIntentWriter(
                jdbc = jdbc,
                properties = properties,
            )
        val projectionBridge =
            JdbcRoleTeamAuthorizationProjectionBridge(
                jdbc = jdbc,
                projectionWriter =
                    projectionWriter,
            )
        val published =
            mutableListOf<Any>()
        val publisher =
            ApplicationEventPublisher {
                event ->
                published += event
            }
        val workforcePersistence =
            JdbcWorkforceAssignmentPersistence(
                jdbc,
            )
        val workforceSecurity =
            WorkforceAssignmentSecuritySynchronizer(
                jdbc = jdbc,
                roleTeamProjection =
                    projectionBridge,
                events = publisher,
            )
        val authorization =
            object : PeopleAuthorizationPort {
                override fun canManagePeople(
                    actorUserId: UUID,
                    organizationId: UUID,
                ): Boolean =
                    actorUserId ==
                        ids.adminIdentity &&
                        organizationId ==
                        ids.organizationId

                override fun canViewDirectory(
                    actorUserId: UUID,
                    organizationId: UUID,
                ): Boolean =
                    canManagePeople(
                        actorUserId,
                        organizationId,
                    )
            }
        val telemetry =
            HiltechTelemetryRuntime(
                openTelemetry =
                    OpenTelemetry.noop(),
                closeAction = {},
            )

        try {
            val idempotency =
                JdbcIdempotentCommandExecutor(
                    jdbc = jdbc,
                    transactionManager =
                        txManager,
                    clock = clock,
                    telemetry = telemetry,
                )
            val audit =
                JdbcAuditEventWriter(jdbc)

            val workforceService =
                WorkforceAssignmentService(
                    persistence =
                        workforcePersistence,
                    peopleAuthorization =
                        authorization,
                    idempotency =
                        idempotency,
                    security =
                        workforceSecurity,
                    audit = audit,
                    events = publisher,
                    clock = clock,
                )

            val assignment =
                workforceService.create(
                    CreateWorkforceAssignmentCommand(
                        operationId =
                            UUID.randomUUID(),
                        employeeId =
                            ids.employeeId,
                        baseEmployeeVersion =
                            1,
                        teamId = ids.teamId,
                        roleCode =
                            "TECHNICIAN",
                        roleLabel =
                            "Field Technician",
                        reportsToEmployeeId =
                            null,
                        effectiveFrom =
                            now.minusSeconds(120),
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-offboarding-assignment",
                    ),
                ).assignment

            assertEquals(
                "ACTIVE",
                assignment.state.name,
            )
            assertEquals(
                1,
                currentTeamMembershipCount(
                    jdbc,
                    ids.workerIdentity,
                    ids.teamId,
                    now,
                ),
            )

            val identityAccess =
                JdbcEmploymentAccessRevocation(
                    jdbc = jdbc,
                    roleTeamProjection =
                        projectionBridge,
                )

            val service =
                OffboardingService(
                    persistence =
                        JdbcOffboardingPersistence(
                            jdbc,
                        ),
                    authorization =
                        authorization,
                    identityAccess =
                        identityAccess,
                    workforce =
                        workforcePersistence,
                    workforceSecurity =
                        workforceSecurity,
                    idempotency =
                        idempotency,
                    audit = audit,
                    events = publisher,
                    clock = clock,
                )

            val startOperation =
                UUID.randomUUID()
            val started =
                service.start(
                    StartEmployeeOffboardingCommand(
                        operationId =
                            startOperation,
                        employeeId =
                            ids.employeeId,
                        baseEmployeeVersion =
                            1,
                        lastWorkingDate =
                            LocalDate.of(
                                2026,
                                9,
                                20,
                            ),
                        reasonCategoryCode =
                            "RESIGNATION",
                        note =
                            "People coordination only.",
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-offboarding-start",
                    ),
                )

            assertFalse(started.replayed)
            assertEquals(
                EmployeeState.OFFBOARDING,
                started.offboarding
                    .employeeState,
            )
            assertEquals(
                6,
                started.offboarding
                    .clearances.size,
            )
            assertFalse(
                started.offboarding
                    .accessFacts.clear,
            )
            assertEquals(
                "ACTIVE",
                employmentState(
                    jdbc,
                    ids.employmentId,
                ),
                "Starting offboarding must not end Employment.",
            )

            val replay =
                service.start(
                    StartEmployeeOffboardingCommand(
                        operationId =
                            startOperation,
                        employeeId =
                            ids.employeeId,
                        baseEmployeeVersion =
                            1,
                        lastWorkingDate =
                            LocalDate.of(
                                2026,
                                9,
                                20,
                            ),
                        reasonCategoryCode =
                            "RESIGNATION",
                        note =
                            "People coordination only.",
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-offboarding-start",
                    ),
                )
            assertTrue(replay.replayed)

            val premature =
                assertThrows<
                    ProductApiException
                > {
                    service.complete(
                        CompleteEmployeeOffboardingCommand(
                            operationId =
                                UUID.randomUUID(),
                            caseId =
                                started.offboarding
                                    .caseId,
                            baseCaseVersion =
                                started.offboarding
                                    .caseVersion,
                            baseEmployeeVersion =
                                started.offboarding
                                    .employeeVersion,
                            baseEmploymentVersion =
                                requireNotNull(
                                    started.offboarding
                                        .employmentVersion,
                                ),
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-offboarding-premature",
                        ),
                    )
                }
            assertEquals(
                "OFFBOARDING_BLOCKERS_REMAIN",
                premature.code,
            )

            val accessSpoof =
                assertThrows<
                    ProductApiException
                > {
                    service.resolveExternal(
                        ResolveOffboardingClearanceCommand(
                            operationId =
                                UUID.randomUUID(),
                            caseId =
                                started.offboarding
                                    .caseId,
                            baseCaseVersion =
                                started.offboarding
                                    .caseVersion,
                            clearanceType =
                                OffboardingClearanceType
                                    .ACCESS,
                            resolution =
                                OffboardingClearanceState
                                    .CLEAR,
                            reason = null,
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-offboarding-access-spoof",
                        ),
                    )
                }
            assertEquals(
                "OFFBOARDING_CLEARANCE_TYPE_NOT_ALLOWED",
                accessSpoof.code,
            )

            var current =
                service.revokeAccess(
                    RevokeEmployeeOffboardingAccessCommand(
                        operationId =
                            UUID.randomUUID(),
                        caseId =
                            started.offboarding
                                .caseId,
                        baseCaseVersion =
                            started.offboarding
                                .caseVersion,
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-offboarding-revoke",
                    ),
                ).offboarding

            assertTrue(
                current.accessFacts.clear,
            )
            assertEquals(
                0,
                current.accessFacts
                    .activeOrganizationMemberships,
            )
            assertEquals(
                0,
                current.accessFacts
                    .activeSessions,
            )
            assertFalse(
                current.accessFacts
                    .currentWorkforceAssignmentPresent,
            )
            assertEquals(
                "ENDED",
                organizationMembershipState(
                    jdbc,
                    ids.workerIdentity,
                    ids.organizationId,
                ),
            )
            assertNotNull(
                sessionRevokedAt(
                    jdbc,
                    ids.sessionId,
                ),
            )
            assertEquals(
                "ACTIVE",
                identityStatus(
                    jdbc,
                    ids.workerIdentity,
                ),
                "Offboarding must not globally revoke/delete the identity.",
            )
            assertNull(
                deviceRevokedAt(
                    jdbc,
                    ids.deviceId,
                ),
                "Offboarding must not blindly revoke/wipe device history.",
            )
            assertEquals(
                "ENDED",
                assignmentState(
                    jdbc,
                    assignment.assignmentId,
                ),
            )
            assertNotNull(
                assignmentEffectiveTo(
                    jdbc,
                    assignment.assignmentId,
                ),
            )
            assertEquals(
                0,
                currentTeamMembershipCount(
                    jdbc,
                    ids.workerIdentity,
                    ids.teamId,
                    now.plusMillis(2),
                ),
            )
            assertEquals(
                "ABSENT",
                projectedDesiredState(
                    jdbc,
                    "user:${ids.workerIdentity}#member@team:${ids.teamId}",
                ),
            )
            assertEquals(
                "ABSENT",
                projectedDesiredState(
                    jdbc,
                    "user:${ids.workerIdentity}#member@organization:${ids.organizationId}",
                ),
            )

            current =
                service.resolveHr(
                    ResolveOffboardingClearanceCommand(
                        operationId =
                            UUID.randomUUID(),
                        caseId = current.caseId,
                        baseCaseVersion =
                            current.caseVersion,
                        clearanceType =
                            OffboardingClearanceType.HR,
                        resolution =
                            OffboardingClearanceState.CLEAR,
                        reason = null,
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-offboarding-hr",
                    ),
                ).offboarding

            for (
                type in listOf(
                    OffboardingClearanceType.PROJECT,
                    OffboardingClearanceType.ASSET,
                    OffboardingClearanceType.FINANCE,
                    OffboardingClearanceType.PAYROLL,
                )
            ) {
                current =
                    service.resolveExternal(
                        ResolveOffboardingClearanceCommand(
                            operationId =
                                UUID.randomUUID(),
                            caseId =
                                current.caseId,
                            baseCaseVersion =
                                current.caseVersion,
                            clearanceType =
                                type,
                            resolution =
                                OffboardingClearanceState.CLEAR,
                            reason = null,
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-offboarding-${type.name.lowercase()}",
                        ),
                    ).offboarding
            }

            assertTrue(
                current.canComplete,
                "Once real access is clear and every coordination slot is resolved, completion should be available.",
            )

            val completed =
                service.complete(
                    CompleteEmployeeOffboardingCommand(
                        operationId =
                            UUID.randomUUID(),
                        caseId =
                            current.caseId,
                        baseCaseVersion =
                            current.caseVersion,
                        baseEmployeeVersion =
                            current.employeeVersion,
                        baseEmploymentVersion =
                            requireNotNull(
                                current.employmentVersion,
                            ),
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-offboarding-complete",
                    ),
                ).offboarding

            assertEquals(
                OffboardingCaseState.COMPLETED,
                completed.state,
            )
            assertEquals(
                EmployeeState.FORMER,
                completed.employeeState,
            )
            assertEquals(
                "ENDED",
                employmentState(
                    jdbc,
                    ids.employmentId,
                ),
            )
            assertEquals(
                LocalDate.of(
                    2026,
                    9,
                    20,
                ),
                employmentEndDate(
                    jdbc,
                    ids.employmentId,
                ),
            )
            assertEquals(
                LocalDate.of(
                    2026,
                    9,
                    20,
                ),
                employeeEndDate(
                    jdbc,
                    ids.employeeId,
                ),
            )
            assertEquals(
                0,
                businessMutationCount(
                    jdbc,
                ),
                "The offboarding skeleton must not fabricate Project/Asset/Finance/Payroll business transactions.",
            )
            assertTrue(
                published.any {
                    it is EmployeeOffboardingStarted
                },
            )
            assertTrue(
                published.any {
                    it is EmployeeOffboardingAccessRevoked
                },
            )
            assertTrue(
                published.any {
                    it is EmployeeOffboarded
                },
            )
        } finally {
            telemetry.close()
        }
    }

    private fun seed(
        jdbc: JdbcTemplate,
        now: Instant,
    ): SeedIds {
        val organizationId =
            UUID.randomUUID()
        val adminPerson =
            UUID.randomUUID()
        val workerPerson =
            UUID.randomUUID()
        val adminIdentity =
            UUID.randomUUID()
        val workerIdentity =
            UUID.randomUUID()
        val employeeId =
            UUID.randomUUID()
        val employmentId =
            UUID.randomUUID()
        val teamId =
            UUID.randomUUID()
        val deviceId =
            UUID.randomUUID()
        val sessionId =
            UUID.randomUUID()
        val timestamp =
            now.atOffset(
                ZoneOffset.UTC,
            )

        jdbc.update(
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
            )
            VALUES (
                ?, ?, 'HILTECH',
                'HILTECH',
                'HILTECH',
                'ACTIVE',
                ?, 1
            )
            """.trimIndent(),
            organizationId,
            "OFFBOARD-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            timestamp,
        )

        listOf(
            adminPerson to "People Admin",
            workerPerson to "Departing Employee",
        ).forEach {
            (personId, name) ->
            jdbc.update(
                """
                INSERT INTO person (
                    id,
                    display_name,
                    legal_name,
                    mobile,
                    email,
                    created_at,
                    updated_at,
                    version
                )
                VALUES (
                    ?, ?, NULL,
                    NULL, NULL,
                    ?, ?, 1
                )
                """.trimIndent(),
                personId,
                name,
                timestamp,
                timestamp,
            )
        }

        listOf(
            Triple(
                adminIdentity,
                adminPerson,
                "offboarding-admin",
            ),
            Triple(
                workerIdentity,
                workerPerson,
                "offboarding-worker",
            ),
        ).forEach {
            (identityId, personId, subject) ->
            jdbc.update(
                """
                INSERT INTO user_identity (
                    id,
                    auth_provider,
                    auth_subject,
                    person_id,
                    status,
                    primary_organization_id,
                    created_at,
                    version
                )
                VALUES (
                    ?, 'contract', ?,
                    ?, 'ACTIVE',
                    ?, ?, 1
                )
                """.trimIndent(),
                identityId,
                "$subject-$identityId",
                personId,
                organizationId,
                timestamp,
            )

            jdbc.update(
                """
                INSERT INTO organization_membership (
                    id,
                    organization_id,
                    user_identity_id,
                    membership_type,
                    role_label,
                    state,
                    valid_from,
                    valid_until,
                    invited_by,
                    version
                )
                VALUES (
                    ?, ?, ?,
                    'EMPLOYEE',
                    NULL,
                    'ACTIVE',
                    ?, NULL,
                    NULL, 1
                )
                """.trimIndent(),
                UUID.randomUUID(),
                organizationId,
                identityId,
                timestamp,
            )
        }

        jdbc.update(
            """
            INSERT INTO employee (
                id,
                organization_id,
                person_id,
                employee_code,
                state,
                hire_date,
                end_date,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?,
                'EMP-OFF-001',
                'ACTIVE',
                DATE '2025-11-01',
                NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            employeeId,
            organizationId,
            workerPerson,
            timestamp,
            timestamp,
        )

        jdbc.update(
            """
            INSERT INTO employment (
                id,
                employee_id,
                employment_type_code,
                start_date,
                end_date,
                state,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?,
                'FULL_TIME',
                DATE '2025-11-01',
                NULL,
                'ACTIVE',
                ?, ?, 1
            )
            """.trimIndent(),
            employmentId,
            employeeId,
            timestamp,
            timestamp,
        )

        jdbc.update(
            """
            INSERT INTO team (
                id,
                organization_id,
                code,
                name,
                parent_team_id,
                manager_user_identity_id,
                active,
                version
            )
            VALUES (
                ?, ?,
                'FIELD-OFF',
                'Field Offboarding',
                NULL, NULL,
                true, 1
            )
            """.trimIndent(),
            teamId,
            organizationId,
        )

        jdbc.update(
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
            VALUES (
                ?, ?,
                'WINDOWS',
                'Offboarding Laptop',
                ?,
                '1',
                '11',
                ?, NULL,
                ?, 1
            )
            """.trimIndent(),
            deviceId,
            workerIdentity,
            "offboarding-install-" +
                UUID.randomUUID(),
            timestamp,
            timestamp,
        )

        jdbc.update(
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
            VALUES (
                ?, ?, ?,
                ?,
                ?, ?, ?,
                NULL,
                '1',
                NULL,
                1
            )
            """.trimIndent(),
            sessionId,
            workerIdentity,
            deviceId,
            "a".repeat(64),
            timestamp,
            timestamp,
            now.plusSeconds(3600)
                .atOffset(
                    ZoneOffset.UTC,
                ),
        )

        return SeedIds(
            organizationId =
                organizationId,
            adminIdentity =
                adminIdentity,
            workerIdentity =
                workerIdentity,
            employeeId =
                employeeId,
            employmentId =
                employmentId,
            teamId = teamId,
            deviceId = deviceId,
            sessionId = sessionId,
        )
    }

    private fun currentTeamMembershipCount(
        jdbc: JdbcTemplate,
        identityId: UUID,
        teamId: UUID,
        at: Instant,
    ): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM team_membership
            WHERE user_identity_id = ?
              AND team_id = ?
              AND valid_from <= ?
              AND (
                  valid_until IS NULL
                  OR valid_until >= ?
              )
            """.trimIndent(),
            Int::class.java,
            identityId,
            teamId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        ) ?: 0

    private fun organizationMembershipState(
        jdbc: JdbcTemplate,
        identityId: UUID,
        organizationId: UUID,
    ): String? =
        jdbc.queryForObject(
            """
            SELECT state
            FROM organization_membership
            WHERE user_identity_id = ?
              AND organization_id = ?
            """.trimIndent(),
            String::class.java,
            identityId,
            organizationId,
        )

    private fun sessionRevokedAt(
        jdbc: JdbcTemplate,
        sessionId: UUID,
    ): java.time.OffsetDateTime? =
        jdbc.queryForObject(
            """
            SELECT revoked_at
            FROM identity_session
            WHERE id = ?
            """.trimIndent(),
            java.time.OffsetDateTime::class.java,
            sessionId,
        )

    private fun identityStatus(
        jdbc: JdbcTemplate,
        identityId: UUID,
    ): String? =
        jdbc.queryForObject(
            """
            SELECT status
            FROM user_identity
            WHERE id = ?
            """.trimIndent(),
            String::class.java,
            identityId,
        )

    private fun deviceRevokedAt(
        jdbc: JdbcTemplate,
        deviceId: UUID,
    ): java.time.OffsetDateTime? =
        jdbc.queryForObject(
            """
            SELECT revoked_at
            FROM device
            WHERE id = ?
            """.trimIndent(),
            java.time.OffsetDateTime::class.java,
            deviceId,
        )

    private fun assignmentState(
        jdbc: JdbcTemplate,
        assignmentId: UUID,
    ): String? =
        jdbc.queryForObject(
            """
            SELECT state
            FROM workforce_assignment
            WHERE id = ?
            """.trimIndent(),
            String::class.java,
            assignmentId,
        )

    private fun assignmentEffectiveTo(
        jdbc: JdbcTemplate,
        assignmentId: UUID,
    ): java.time.OffsetDateTime? =
        jdbc.queryForObject(
            """
            SELECT effective_to
            FROM workforce_assignment
            WHERE id = ?
            """.trimIndent(),
            java.time.OffsetDateTime::class.java,
            assignmentId,
        )

    private fun projectedDesiredState(
        jdbc: JdbcTemplate,
        relationKey: String,
    ): String? =
        jdbc.queryForObject(
            """
            SELECT desired_state
            FROM authorization_relation_projection
            WHERE relation_key = ?
            """.trimIndent(),
            String::class.java,
            relationKey,
        )

    private fun employmentState(
        jdbc: JdbcTemplate,
        employmentId: UUID,
    ): String? =
        jdbc.queryForObject(
            """
            SELECT state
            FROM employment
            WHERE id = ?
            """.trimIndent(),
            String::class.java,
            employmentId,
        )

    private fun employmentEndDate(
        jdbc: JdbcTemplate,
        employmentId: UUID,
    ): LocalDate? =
        jdbc.queryForObject(
            """
            SELECT end_date
            FROM employment
            WHERE id = ?
            """.trimIndent(),
            LocalDate::class.java,
            employmentId,
        )

    private fun employeeEndDate(
        jdbc: JdbcTemplate,
        employeeId: UUID,
    ): LocalDate? =
        jdbc.queryForObject(
            """
            SELECT end_date
            FROM employee
            WHERE id = ?
            """.trimIndent(),
            LocalDate::class.java,
            employeeId,
        )

    private fun businessMutationCount(
        jdbc: JdbcTemplate,
    ): Int {
        // Phase 4+ tables do not exist in the People slice.
        // This sentinel proves the offboarding contract itself creates only
        // its own coordinator rows plus existing Identity/People mutations.
        return jdbc.queryForObject(
            """
            SELECT count(*)
            FROM offboarding_clearance
            WHERE clearance_type IN (
                'PROJECT',
                'ASSET',
                'FINANCE',
                'PAYROLL'
            )
              AND source <> 'PEOPLE'
            """.trimIndent(),
            Int::class.java,
        ) ?: 0
    }

    private data class SeedIds(
        val organizationId: UUID,
        val adminIdentity: UUID,
        val workerIdentity: UUID,
        val employeeId: UUID,
        val employmentId: UUID,
        val teamId: UUID,
        val deviceId: UUID,
        val sessionId: UUID,
    )
}
