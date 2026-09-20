package com.hiltech.server.people

import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionProcessResult
import com.hiltech.server.security.AuthorizationProjectionProcessor
import com.hiltech.server.security.AuthorizationProjectionRetryPolicy
import com.hiltech.server.security.FailClosedAuthorizationAdapter
import com.hiltech.server.security.HiltechOpenFgaProperties
import com.hiltech.server.security.JdbcAuthorizationProjectionGuard
import com.hiltech.server.security.JdbcAuthorizationProjectionIntentWriter
import com.hiltech.server.security.JdbcAuthorizationProjectionStore
import com.hiltech.server.security.JdbcRoleTeamAuthorizationProjectionBridge
import com.hiltech.server.security.JdbcRoleTeamSourceAuthority
import com.hiltech.server.security.JdkOpenFgaHttpTransport
import com.hiltech.server.security.OpenFgaGateway
import com.hiltech.server.security.OpenFgaTuple
import com.hiltech.server.security.RoleTeamAuthorizationRelations
import com.hiltech.server.security.RoleTeamAuthorizationService
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
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class WorkforceAssignmentPostgresOpenFgaContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_WORKFORCE_ASSIGNMENT_CONTRACT_TEST",
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
    fun workforceAssignmentPreservesBusinessHistoryAndSharedTeamAuthority() {
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
        val transaction =
            TransactionTemplate(txManager)
        val now =
            Instant.parse(
                "2026-09-20T00:30:00Z",
            )
        val clock =
            Clock.fixed(
                now,
                ZoneOffset.UTC,
            )
        val ids =
            seed(
                jdbc = jdbc,
                at = now.minusSeconds(600),
            )

        val fgaProperties =
            HiltechOpenFgaProperties(
                enabled = true,
                apiUrl = fgaApiUrl,
                storeId = fgaStoreId,
                authorizationModelId =
                    fgaModelId,
            )
        fgaProperties
            .validateEnabledConfiguration()

        val gateway =
            OpenFgaGateway(
                properties =
                    fgaProperties,
                transport =
                    JdkOpenFgaHttpTransport(
                        fgaProperties,
                    ),
            )
        val projectionWriter =
            JdbcAuthorizationProjectionIntentWriter(
                jdbc = jdbc,
                properties =
                    fgaProperties,
            )
        val roleTeamBridge =
            JdbcRoleTeamAuthorizationProjectionBridge(
                jdbc = jdbc,
                projectionWriter =
                    projectionWriter,
            )
        val projectionGuard =
            JdbcAuthorizationProjectionGuard(
                jdbc,
            )
        val authorization =
            FailClosedAuthorizationAdapter(
                guard =
                    projectionGuard,
                openFgaGateway =
                    gateway,
            )
        val sourceAuthority =
            JdbcRoleTeamSourceAuthority(
                jdbc,
            )
        val projectionStore =
            JdbcAuthorizationProjectionStore(
                jdbc = jdbc,
                transactionManager =
                    txManager,
                properties =
                    fgaProperties,
                retryPolicy =
                    AuthorizationProjectionRetryPolicy(),
            )
        val projectionProcessor =
            AuthorizationProjectionProcessor(
                store =
                    projectionStore,
                openFga =
                    gateway,
                properties =
                    fgaProperties,
            )

        val published =
            mutableListOf<Any>()
        val publisher =
            ApplicationEventPublisher {
                event ->
                published += event
            }
        val telemetry =
            HiltechTelemetryRuntime(
                openTelemetry =
                    OpenTelemetry.noop(),
                closeAction = {},
            )

        try {
            val persistence =
                JdbcWorkforceAssignmentPersistence(
                    jdbc,
                )
            val security =
                WorkforceAssignmentSecuritySynchronizer(
                    jdbc = jdbc,
                    roleTeamProjection =
                        roleTeamBridge,
                    events =
                        publisher,
                )
            val peopleAuthorization =
                testPeopleAuthorization(
                    adminIdentityId =
                        ids.adminIdentity,
                    organizationId =
                        ids.organizationOne,
                    sourceAuthority =
                        sourceAuthority,
                    clock = clock,
                )
            val service =
                WorkforceAssignmentService(
                    persistence =
                        persistence,
                    peopleAuthorization =
                        peopleAuthorization,
                    idempotency =
                        JdbcIdempotentCommandExecutor(
                            jdbc = jdbc,
                            transactionManager =
                                txManager,
                            clock = clock,
                            telemetry =
                                telemetry,
                        ),
                    security = security,
                    audit =
                        JdbcAuditEventWriter(
                            jdbc,
                        ),
                    events = publisher,
                    clock = clock,
                )

            val createOperation =
                UUID.randomUUID()
            val created =
                service.create(
                    CreateWorkforceAssignmentCommand(
                        operationId =
                            createOperation,
                        employeeId =
                            ids.workerEmployee,
                        baseEmployeeVersion = 1,
                        teamId =
                            ids.fieldTeam,
                        roleCode =
                            "technician",
                        roleLabel =
                            "Field Technician",
                        reportsToEmployeeId =
                            ids.managerEmployee,
                        effectiveFrom =
                            now.minusSeconds(60),
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-workforce-create",
                    ),
                )

            assertFalse(created.replayed)
            assertEquals(
                "TECHNICIAN",
                created.assignment
                    .roleCode,
            )
            assertEquals(
                ids.fieldTeam,
                created.assignment
                    .teamId,
            )
            assertEquals(
                ids.managerEmployee,
                created.assignment
                    .reportsToEmployeeId,
            )
            assertEquals(
                1,
                peopleMembershipCount(
                    jdbc,
                    created.assignment
                        .assignmentId,
                ),
            )

            val replay =
                service.create(
                    CreateWorkforceAssignmentCommand(
                        operationId =
                            createOperation,
                        employeeId =
                            ids.workerEmployee,
                        baseEmployeeVersion = 1,
                        teamId =
                            ids.fieldTeam,
                        roleCode =
                            "technician",
                        roleLabel =
                            "Field Technician",
                        reportsToEmployeeId =
                            ids.managerEmployee,
                        effectiveFrom =
                            now.minusSeconds(60),
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-workforce-create",
                    ),
                )
            assertTrue(replay.replayed)
            assertEquals(
                created.assignment
                    .assignmentId,
                replay.assignment
                    .assignmentId,
            )
            assertEquals(
                1,
                peopleMembershipCount(
                    jdbc,
                    created.assignment
                        .assignmentId,
                ),
            )

            val roleTeamServicePending =
                RoleTeamAuthorizationService(
                    authorization =
                        authorization,
                    sourceAuthority =
                        sourceAuthority,
                    clock = clock,
                )
            assertFalse(
                roleTeamServicePending
                    .canViewTeam(
                        ids.workerIdentity,
                        ids.fieldTeam,
                    ),
                "Pending People membership grant must fail closed before OpenFGA projection.",
            )

            drain(
                projectionProcessor,
                now.plusSeconds(1),
            )

            val roleTeamServiceApplied =
                RoleTeamAuthorizationService(
                    authorization =
                        authorization,
                    sourceAuthority =
                        sourceAuthority,
                    clock =
                        Clock.fixed(
                            now.plusSeconds(2),
                            ZoneOffset.UTC,
                        ),
                )
            assertTrue(
                roleTeamServiceApplied
                    .canViewTeam(
                        ids.workerIdentity,
                        ids.fieldTeam,
                    ),
            )

            val own =
                service.own(
                    actorUserId =
                        ids.workerIdentity,
                    organizationId =
                        ids.organizationOne,
                )
            assertEquals(
                created.assignment
                    .assignmentId,
                own.assignmentId,
            )

            val structure =
                service.organizationStructure(
                    actorUserId =
                        ids.workerIdentity,
                    organizationId =
                        ids.organizationOne,
                    limit = 100,
                )
            assertTrue(
                structure.any {
                    it.employeeId ==
                        ids.workerEmployee &&
                        it.roleCode ==
                        "TECHNICIAN"
                },
            )

            val duplicate =
                assertThrows<
                    ProductApiException
                > {
                    service.create(
                        CreateWorkforceAssignmentCommand(
                            operationId =
                                UUID.randomUUID(),
                            employeeId =
                                ids.workerEmployee,
                            baseEmployeeVersion =
                                1,
                            teamId =
                                ids.fieldTeam,
                            roleCode =
                                "ENGINEER",
                            roleLabel = null,
                            reportsToEmployeeId =
                                null,
                            effectiveFrom =
                                now.minusSeconds(1),
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-duplicate",
                        ),
                    )
                }
            assertEquals(
                "ACTIVE_WORKFORCE_ASSIGNMENT_EXISTS",
                duplicate.code,
            )

            val future =
                assertThrows<
                    ProductApiException
                > {
                    service.create(
                        CreateWorkforceAssignmentCommand(
                            operationId =
                                UUID.randomUUID(),
                            employeeId =
                                ids.futureEmployee,
                            baseEmployeeVersion = 1,
                            teamId = null,
                            roleCode = "ENGINEER",
                            roleLabel = null,
                            reportsToEmployeeId =
                                null,
                            effectiveFrom =
                                now.plusSeconds(60),
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-future",
                        ),
                    )
                }
            assertEquals(
                "FUTURE_ASSIGNMENT_NOT_SUPPORTED",
                future.code,
            )

            val selfManager =
                assertThrows<
                    ProductApiException
                > {
                    service.create(
                        CreateWorkforceAssignmentCommand(
                            operationId =
                                UUID.randomUUID(),
                            employeeId =
                                ids.selfEmployee,
                            baseEmployeeVersion = 1,
                            teamId = null,
                            roleCode = "ENGINEER",
                            roleLabel = null,
                            reportsToEmployeeId =
                                ids.selfEmployee,
                            effectiveFrom =
                                now.minusSeconds(1),
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-self",
                        ),
                    )
                }
            assertEquals(
                "REPORTING_SELF_REFERENCE",
                selfManager.code,
            )

            val cycle =
                assertThrows<
                    ProductApiException
                > {
                    service.create(
                        CreateWorkforceAssignmentCommand(
                            operationId =
                                UUID.randomUUID(),
                            employeeId =
                                ids.managerEmployee,
                            baseEmployeeVersion = 1,
                            teamId = null,
                            roleCode = "ENGINEER",
                            roleLabel = null,
                            reportsToEmployeeId =
                                ids.workerEmployee,
                            effectiveFrom =
                                now.minusSeconds(1),
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-cycle",
                        ),
                    )
                }
            assertEquals(
                "REPORTING_CYCLE",
                cycle.code,
            )

            val wrongTeam =
                assertThrows<
                    ProductApiException
                > {
                    service.create(
                        CreateWorkforceAssignmentCommand(
                            operationId =
                                UUID.randomUUID(),
                            employeeId =
                                ids.crossOrgEmployee,
                            baseEmployeeVersion = 1,
                            teamId =
                                ids.fieldTeam,
                            roleCode =
                                "TECHNICIAN",
                            roleLabel = null,
                            reportsToEmployeeId =
                                null,
                            effectiveFrom =
                                now.minusSeconds(1),
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-cross-org",
                        ),
                    )
                }
            assertEquals(
                "PEOPLE_ACCESS_DENIED",
                wrongTeam.code,
                "Cross-organization write must fail at People authority before Team details leak.",
            )

            val unlinkedCreated =
                service.create(
                    CreateWorkforceAssignmentCommand(
                        operationId =
                            UUID.randomUUID(),
                        employeeId =
                            ids.unlinkedEmployee,
                        baseEmployeeVersion = 1,
                        teamId =
                            ids.fieldTeam,
                        roleCode =
                            "TECHNICIAN",
                        roleLabel = null,
                        reportsToEmployeeId =
                            ids.managerEmployee,
                        effectiveFrom =
                            now.minusSeconds(1),
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-unlinked",
                    ),
                )
            assertEquals(
                0,
                peopleMembershipCount(
                    jdbc,
                    unlinkedCreated.assignment
                        .assignmentId,
                ),
            )

            transaction.executeWithoutResult {
                jdbc.update(
                    """
                    UPDATE user_identity
                    SET person_id = ?,
                        version = version + 1
                    WHERE id = ?
                    """.trimIndent(),
                    ids.unlinkedPerson,
                    ids.unlinkedIdentity,
                )
            }

            val listener =
                WorkforceAssignmentIdentityLinkedListener(
                    persistence =
                        persistence,
                    synchronizer =
                        security,
                )
            listener.on(
                EmployeeIdentityLinked(
                    employeeId =
                        ids.unlinkedEmployee,
                    organizationId =
                        ids.organizationOne,
                    sourceVersion = 2,
                    actorUserId =
                        ids.adminIdentity,
                    occurredAt =
                        now.plusSeconds(3),
                    correlationId =
                        "corr-unlinked-link",
                ),
            )
            assertEquals(
                1,
                peopleMembershipCount(
                    jdbc,
                    unlinkedCreated.assignment
                        .assignmentId,
                ),
            )
            drain(
                projectionProcessor,
                now.plusSeconds(4),
            )
            assertTrue(
                RoleTeamAuthorizationService(
                    authorization =
                        authorization,
                    sourceAuthority =
                        sourceAuthority,
                    clock =
                        Clock.fixed(
                            now.plusSeconds(5),
                            ZoneOffset.UTC,
                        ),
                ).canViewTeam(
                    ids.unlinkedIdentity,
                    ids.fieldTeam,
                ),
            )

            proveSharedTupleAggregate(
                jdbc = jdbc,
                transaction = transaction,
                bridge = roleTeamBridge,
                processor =
                    projectionProcessor,
                gateway = gateway,
                sourceAuthority =
                    sourceAuthority,
                authorization =
                    authorization,
                teamId =
                    ids.fieldTeam,
                identityId =
                    ids.workerIdentity,
                peopleMembershipId =
                    requireNotNull(
                        peopleMembershipId(
                            jdbc,
                            created.assignment
                                .assignmentId,
                        ),
                    ),
                at =
                    now.plusSeconds(10),
            )

            assertTrue(
                published.any {
                    it is
                        WorkforceAssignmentCreated
                },
            )
            assertTrue(
                published.any {
                    it is
                        WorkforceAssignmentSecuritySynchronized
                },
            )

            val eventDump =
                published.joinToString("|")
            assertFalse(
                eventDump.contains(
                    "private@example.test",
                ),
            )

            val auditCount =
                jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM audit_event
                    WHERE target_type =
                          'WORKFORCE_ASSIGNMENT'
                      AND target_id = ?
                    """.trimIndent(),
                    Int::class.java,
                    created.assignment
                        .assignmentId,
                ) ?: 0
            assertEquals(1, auditCount)
        } finally {
            telemetry.close()
        }
    }

    private fun proveSharedTupleAggregate(
        jdbc: JdbcTemplate,
        transaction: TransactionTemplate,
        bridge:
            JdbcRoleTeamAuthorizationProjectionBridge,
        processor:
            AuthorizationProjectionProcessor,
        gateway: OpenFgaGateway,
        sourceAuthority:
            JdbcRoleTeamSourceAuthority,
        authorization:
            FailClosedAuthorizationAdapter,
        teamId: UUID,
        identityId: UUID,
        peopleMembershipId: UUID,
        at: Instant,
    ) {
        val manualMembershipId =
            UUID.randomUUID()

        transaction.executeWithoutResult {
            jdbc.update(
                """
                INSERT INTO team_membership (
                    id,
                    team_id,
                    user_identity_id,
                    role_in_team,
                    valid_from,
                    valid_until,
                    version,
                    source_workforce_assignment_id
                )
                VALUES (
                    ?, ?, ?,
                    'MANUAL_EXTRA',
                    ?, NULL,
                    1, NULL
                )
                """.trimIndent(),
                manualMembershipId,
                teamId,
                identityId,
                at.minusSeconds(60)
                    .atOffset(
                        ZoneOffset.UTC,
                    ),
            )
            bridge.syncTeamMembership(
                manualMembershipId,
                at,
            )
        }
        drain(
            processor,
            at.plusSeconds(1),
        )

        val memberTuple =
            OpenFgaTuple(
                subjectType = "user",
                subjectId =
                    identityId.toString(),
                relation = "member",
                objectType = "team",
                objectId =
                    teamId.toString(),
            )
        assertTrue(
            gateway.isAllowed(
                memberTuple,
            ),
        )

        transaction.executeWithoutResult {
            jdbc.update(
                """
                UPDATE team_membership
                SET valid_until = ?,
                    version = version + 1
                WHERE id = ?
                """.trimIndent(),
                at.plusSeconds(2)
                    .minusMillis(1)
                    .atOffset(
                        ZoneOffset.UTC,
                    ),
                manualMembershipId,
            )
            bridge.syncTeamMembership(
                manualMembershipId,
                at.plusSeconds(2),
            )
        }

        drain(
            processor,
            at.plusSeconds(3),
        )
        assertTrue(
            gateway.isAllowed(
                memberTuple,
            ),
            "Ending the manual source must not revoke the tuple while the People source remains current.",
        )
        assertTrue(
            RoleTeamAuthorizationService(
                authorization =
                    authorization,
                sourceAuthority =
                    sourceAuthority,
                clock =
                    Clock.fixed(
                        at.plusSeconds(4),
                        ZoneOffset.UTC,
                    ),
            ).canViewTeam(
                identityId,
                teamId,
            ),
        )

        transaction.executeWithoutResult {
            jdbc.update(
                """
                UPDATE team_membership
                SET valid_until = ?,
                    version = version + 1
                WHERE id = ?
                """.trimIndent(),
                at.plusSeconds(5)
                    .minusMillis(1)
                    .atOffset(
                        ZoneOffset.UTC,
                    ),
                peopleMembershipId,
            )
            bridge.syncTeamMembership(
                peopleMembershipId,
                at.plusSeconds(5),
            )
        }

        assertFalse(
            RoleTeamAuthorizationService(
                authorization =
                    authorization,
                sourceAuthority =
                    sourceAuthority,
                clock =
                    Clock.fixed(
                        at.plusSeconds(5),
                        ZoneOffset.UTC,
                    ),
            ).canViewTeam(
                identityId,
                teamId,
            ),
            "Source truth must deny immediately after the last current membership source ends.",
        )

        drain(
            processor,
            at.plusSeconds(6),
        )
        assertFalse(
            gateway.isAllowed(
                memberTuple,
            ),
        )

        val aggregateGeneration =
            jdbc.queryForObject(
                """
                SELECT generation
                FROM team_membership_authority_aggregate
                WHERE team_id = ?
                  AND user_identity_id = ?
                """.trimIndent(),
                Long::class.java,
                teamId,
                identityId,
            )
        assertTrue(
            requireNotNull(
                aggregateGeneration,
            ) >= 4,
        )
    }

    private fun testPeopleAuthorization(
        adminIdentityId: UUID,
        organizationId: UUID,
        sourceAuthority:
            JdbcRoleTeamSourceAuthority,
        clock: Clock,
    ): PeopleAuthorizationPort =
        object : PeopleAuthorizationPort {
            override fun canManagePeople(
                actorUserId: UUID,
                organizationId:
                    UUID,
            ): Boolean =
                actorUserId ==
                    adminIdentityId &&
                    organizationId ==
                    organizationId &&
                    sourceAuthority
                        .isOrganizationMemberCurrent(
                            actorUserId,
                            organizationId,
                            clock.instant(),
                        )

            override fun canViewDirectory(
                actorUserId: UUID,
                organizationId:
                    UUID,
            ): Boolean =
                sourceAuthority
                    .isOrganizationMemberCurrent(
                        actorUserId,
                        organizationId,
                        clock.instant(),
                    )
        }

    private fun seed(
        jdbc: JdbcTemplate,
        at: Instant,
    ): SeedIds {
        val orgOne =
            UUID.randomUUID()
        val orgTwo =
            UUID.randomUUID()
        insertOrganization(
            jdbc,
            orgOne,
            "WORKFORCE-ONE",
            at,
        )
        insertOrganization(
            jdbc,
            orgTwo,
            "WORKFORCE-TWO",
            at,
        )

        val adminIdentity =
            UUID.randomUUID()
        val workerIdentity =
            UUID.randomUUID()
        val unlinkedIdentity =
            UUID.randomUUID()
        val outsiderIdentity =
            UUID.randomUUID()

        val adminPerson =
            insertPerson(
                jdbc,
                "People Admin",
                at,
            )
        val workerPerson =
            insertPerson(
                jdbc,
                "Field Worker",
                at,
            )
        val managerPerson =
            insertPerson(
                jdbc,
                "Field Manager",
                at,
            )
        val unlinkedPerson =
            insertPerson(
                jdbc,
                "Unlinked Worker",
                at,
            )
        val futurePerson =
            insertPerson(
                jdbc,
                "Future Worker",
                at,
            )
        val selfPerson =
            insertPerson(
                jdbc,
                "Self Worker",
                at,
            )
        val crossOrgPerson =
            insertPerson(
                jdbc,
                "Other Org Worker",
                at,
            )

        insertIdentity(
            jdbc,
            adminIdentity,
            orgOne,
            adminPerson,
            at,
        )
        insertIdentity(
            jdbc,
            workerIdentity,
            orgOne,
            workerPerson,
            at,
        )
        insertIdentity(
            jdbc,
            unlinkedIdentity,
            orgOne,
            null,
            at,
        )
        insertIdentity(
            jdbc,
            outsiderIdentity,
            orgTwo,
            crossOrgPerson,
            at,
        )

        listOf(
            adminIdentity to orgOne,
            workerIdentity to orgOne,
            unlinkedIdentity to orgOne,
            outsiderIdentity to orgTwo,
        ).forEach { (identity, org) ->
            insertMembership(
                jdbc,
                identity,
                org,
                at,
            )
        }

        val adminEmployee =
            insertEmployee(
                jdbc,
                orgOne,
                adminPerson,
                "ADM-001",
                at,
            )
        val workerEmployee =
            insertEmployee(
                jdbc,
                orgOne,
                workerPerson,
                "EMP-001",
                at,
            )
        val managerEmployee =
            insertEmployee(
                jdbc,
                orgOne,
                managerPerson,
                "EMP-002",
                at,
            )
        val unlinkedEmployee =
            insertEmployee(
                jdbc,
                orgOne,
                unlinkedPerson,
                "EMP-003",
                at,
            )
        val futureEmployee =
            insertEmployee(
                jdbc,
                orgOne,
                futurePerson,
                "EMP-004",
                at,
            )
        val selfEmployee =
            insertEmployee(
                jdbc,
                orgOne,
                selfPerson,
                "EMP-005",
                at,
            )
        val crossOrgEmployee =
            insertEmployee(
                jdbc,
                orgTwo,
                crossOrgPerson,
                "OTHER-001",
                at,
            )

        val fieldTeam =
            UUID.randomUUID()
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
                'FIELD-A',
                'Field Crew A',
                NULL, NULL,
                true, 1
            )
            """.trimIndent(),
            fieldTeam,
            orgOne,
        )

        val otherTeam =
            UUID.randomUUID()
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
                'OTHER-A',
                'Other Team',
                NULL, NULL,
                true, 1
            )
            """.trimIndent(),
            otherTeam,
            orgTwo,
        )

        return SeedIds(
            organizationOne = orgOne,
            organizationTwo = orgTwo,
            adminIdentity =
                adminIdentity,
            workerIdentity =
                workerIdentity,
            unlinkedIdentity =
                unlinkedIdentity,
            outsiderIdentity =
                outsiderIdentity,
            adminEmployee =
                adminEmployee,
            workerEmployee =
                workerEmployee,
            managerEmployee =
                managerEmployee,
            unlinkedEmployee =
                unlinkedEmployee,
            futureEmployee =
                futureEmployee,
            selfEmployee =
                selfEmployee,
            crossOrgEmployee =
                crossOrgEmployee,
            unlinkedPerson =
                unlinkedPerson,
            fieldTeam = fieldTeam,
            otherTeam = otherTeam,
        )
    }

    private fun insertOrganization(
        jdbc: JdbcTemplate,
        id: UUID,
        code: String,
        at: Instant,
    ) {
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
                ?, ?, ?, ?,
                'HILTECH',
                'ACTIVE',
                ?, 1
            )
            """.trimIndent(),
            id,
            "$code-${UUID.randomUUID().toString().take(8)}",
            code,
            code,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertPerson(
        jdbc: JdbcTemplate,
        name: String,
        at: Instant,
    ): UUID {
        val id =
            UUID.randomUUID()
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
            id,
            name,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )
        return id
    }

    private fun insertIdentity(
        jdbc: JdbcTemplate,
        id: UUID,
        organizationId: UUID,
        personId: UUID?,
        at: Instant,
    ) {
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
            id,
            "workforce-$id",
            personId,
            organizationId,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertMembership(
        jdbc: JdbcTemplate,
        identityId: UUID,
        organizationId: UUID,
        at: Instant,
    ) {
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
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertEmployee(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        personId: UUID,
        code: String,
        at: Instant,
    ): UUID {
        val employeeId =
            UUID.randomUUID()
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
                ?, ?, ?, ?,
                'ACTIVE',
                NULL, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            employeeId,
            organizationId,
            personId,
            code,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )
        return employeeId
    }

    private fun peopleMembershipCount(
        jdbc: JdbcTemplate,
        assignmentId: UUID,
    ): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM team_membership
            WHERE source_workforce_assignment_id = ?
            """.trimIndent(),
            Int::class.java,
            assignmentId,
        ) ?: 0

    private fun peopleMembershipId(
        jdbc: JdbcTemplate,
        assignmentId: UUID,
    ): UUID? =
        jdbc.query(
            """
            SELECT id
            FROM team_membership
            WHERE source_workforce_assignment_id = ?
            """.trimIndent(),
            { rs, _ ->
                rs.getObject(
                    "id",
                    UUID::class.java,
                )
            },
            assignmentId,
        ).singleOrNull()

    private fun drain(
        processor:
            AuthorizationProjectionProcessor,
        at: Instant,
    ) {
        repeat(100) { index ->
            when (
                processor.processOne(
                    at.plusMillis(
                        index.toLong(),
                    ),
                )
            ) {
                AuthorizationProjectionProcessResult
                    .NO_WORK ->
                    return

                AuthorizationProjectionProcessResult
                    .RETRY_SCHEDULED,
                AuthorizationProjectionProcessResult
                    .FAILED_PERMANENT ->
                    error(
                        "Authorization projection did not converge.",
                    )

                else ->
                    Unit
            }
        }
        error(
            "Authorization projection queue did not drain.",
        )
    }

    private data class SeedIds(
        val organizationOne: UUID,
        val organizationTwo: UUID,
        val adminIdentity: UUID,
        val workerIdentity: UUID,
        val unlinkedIdentity: UUID,
        val outsiderIdentity: UUID,
        val adminEmployee: UUID,
        val workerEmployee: UUID,
        val managerEmployee: UUID,
        val unlinkedEmployee: UUID,
        val futureEmployee: UUID,
        val selfEmployee: UUID,
        val crossOrgEmployee: UUID,
        val unlinkedPerson: UUID,
        val fieldTeam: UUID,
        val otherTeam: UUID,
    )
}
