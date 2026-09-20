package com.hiltech.server.people

import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionProcessor
import com.hiltech.server.security.AuthorizationProjectionRetryPolicy
import com.hiltech.server.security.FailClosedAuthorizationAdapter
import com.hiltech.server.security.HiltechOpenFgaProperties
import com.hiltech.server.security.JdbcAuthorizationProjectionGuard
import com.hiltech.server.security.JdbcAuthorizationProjectionIntentWriter
import com.hiltech.server.security.JdbcAuthorizationProjectionStore
import com.hiltech.server.security.JdbcRoleTeamSourceAuthority
import com.hiltech.server.security.JdkOpenFgaHttpTransport
import com.hiltech.server.security.OpenFgaGateway
import com.hiltech.server.security.OpenFgaMutationResult
import com.hiltech.server.security.OpenFgaTuple
import com.hiltech.server.security.RoleTeamAuthorizationRelations
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
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PeoplePostgresOpenFgaContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_PEOPLE_CONTRACT_TEST",
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
    fun employeeCoreIsIdempotentOrganizationScopedHistorySafeAndIdentityLinked() {
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
        val clock =
            Clock.fixed(
                Instant.parse(
                    "2026-09-20T01:15:00Z",
                ),
                ZoneOffset.UTC,
            )
        val ids =
            seedBase(
                jdbc = jdbc,
                at =
                    clock.instant()
                        .minusSeconds(600),
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

        listOf(
            ids.adminOne to
                ids.organizationOne,
            ids.workerOne to
                ids.organizationOne,
            ids.adminTwo to
                ids.organizationTwo,
            ids.outsider to
                ids.organizationTwo,
        ).forEach {
                (identityId, organizationId) ->
            applyTuple(
                gateway,
                RoleTeamAuthorizationRelations
                    .organizationMember(
                        identityId,
                        organizationId,
                    ),
            )
        }

        val projectionWriter =
            JdbcAuthorizationProjectionIntentWriter(
                jdbc = jdbc,
                properties =
                    fgaProperties,
            )
        val peopleProjectionBridge =
            JdbcPeopleAuthorizationProjectionBridge(
                jdbc = jdbc,
                projectionWriter =
                    projectionWriter,
            )
        val adminOneBinding =
            insertPeopleAdminBinding(
                jdbc = jdbc,
                organizationId =
                    ids.organizationOne,
                principalUserId =
                    ids.adminOne,
                createdBy =
                    ids.adminOne,
                at =
                    clock.instant()
                        .minusSeconds(120),
            )
        val adminTwoBinding =
            insertPeopleAdminBinding(
                jdbc = jdbc,
                organizationId =
                    ids.organizationTwo,
                principalUserId =
                    ids.adminTwo,
                createdBy =
                    ids.adminTwo,
                at =
                    clock.instant()
                        .minusSeconds(120),
            )

        val transaction =
            TransactionTemplate(
                txManager,
            )
        transaction
            .executeWithoutResult {
                peopleProjectionBridge
                    .syncBinding(
                        bindingId =
                            adminOneBinding,
                        occurredAt =
                            clock.instant()
                                .minusSeconds(60),
                    )
                peopleProjectionBridge
                    .syncBinding(
                        bindingId =
                            adminTwoBinding,
                        occurredAt =
                            clock.instant()
                                .minusSeconds(60),
                    )
            }

        val authorizationProvider =
            StaticListableBeanFactory()
                .also {
                    it.addBean(
                        "authorizationCheckPort",
                        authorization,
                    )
                }
                .getBeanProvider(
                    com.hiltech.server.security
                        .AuthorizationCheckPort::class.java,
                )

        val peopleAuthorization =
            SpringPeopleAuthorization(
                authorizationProvider =
                    authorizationProvider,
                sourceAuthority =
                    sourceAuthority,
                peopleAuthoritySource =
                    JdbcPeopleAuthoritySource(
                        jdbc,
                    ),
                clock = clock,
            )

        assertFalse(
            peopleAuthorization
                .canManagePeople(
                    ids.adminOne,
                    ids.organizationOne,
                ),
            "Pending People authority projection must fail closed.",
        )

        val projectionProcessor =
            AuthorizationProjectionProcessor(
                store =
                    JdbcAuthorizationProjectionStore(
                        jdbc = jdbc,
                        transactionManager =
                            txManager,
                        properties =
                            fgaProperties,
                        retryPolicy =
                            AuthorizationProjectionRetryPolicy(),
                    ),
                openFga = gateway,
                properties =
                    fgaProperties,
            )

        repeat(4) {
            projectionProcessor.processOne(
                clock.instant(),
            )
        }

        assertTrue(
            peopleAuthorization
                .canManagePeople(
                    ids.adminOne,
                    ids.organizationOne,
                ),
        )
        assertTrue(
            peopleAuthorization
                .canManagePeople(
                    ids.adminTwo,
                    ids.organizationTwo,
                ),
        )
        assertFalse(
            peopleAuthorization
                .canManagePeople(
                    ids.workerOne,
                    ids.organizationOne,
                ),
            "Ordinary organization membership must not imply People administration.",
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
                JdbcPeoplePersistence(
                    jdbc,
                )
            val service =
                PeopleService(
                    persistence =
                        persistence,
                    authorization =
                        peopleAuthorization,
                    sourceAuthority =
                        sourceAuthority,
                    idempotency =
                        JdbcIdempotentCommandExecutor(
                            jdbc = jdbc,
                            transactionManager =
                                txManager,
                            clock = clock,
                            telemetry =
                                telemetry,
                        ),
                    audit =
                        JdbcAuditEventWriter(
                            jdbc,
                        ),
                    events = publisher,
                    clock = clock,
                )

            val createOperation =
                UUID.randomUUID()
            val first =
                service.createEmployee(
                    CreateEmployeeCommand(
                        operationId =
                            createOperation,
                        organizationId =
                            ids.organizationOne,
                        displayName =
                            "عامل اختبار",
                        employeeCode =
                            "emp-001",
                        startDate =
                            LocalDate.parse(
                                "2026-09-01",
                            ),
                        employmentTypeCode =
                            "FIELD",
                        linkedUserIdentityId =
                            null,
                        hireDate =
                            LocalDate.parse(
                                "2026-09-01",
                            ),
                        legalName =
                            "Restricted Legal Name",
                        mobile =
                            "01000000000",
                        email =
                            "private@example.test",
                        actorUserId =
                            ids.adminOne,
                        correlationId =
                            "corr-people-create",
                    ),
                )
            assertFalse(first.replayed)
            assertEquals(
                "EMP-001",
                first.employee
                    .employeeCode,
            )
            assertEquals(
                EmployeeState.PREBOARDING,
                first.employee
                    .employeeState,
            )
            assertEquals(
                EmploymentState.ACTIVE.name,
                employmentState(
                    jdbc,
                    first.employee
                        .activeEmploymentId!!,
                ),
            )

            val replay =
                service.createEmployee(
                    CreateEmployeeCommand(
                        operationId =
                            createOperation,
                        organizationId =
                            ids.organizationOne,
                        displayName =
                            "عامل اختبار",
                        employeeCode =
                            "emp-001",
                        startDate =
                            LocalDate.parse(
                                "2026-09-01",
                            ),
                        employmentTypeCode =
                            "FIELD",
                        linkedUserIdentityId =
                            null,
                        hireDate =
                            LocalDate.parse(
                                "2026-09-01",
                            ),
                        legalName =
                            "Restricted Legal Name",
                        mobile =
                            "01000000000",
                        email =
                            "private@example.test",
                        actorUserId =
                            ids.adminOne,
                        correlationId =
                            "corr-people-create",
                    ),
                )
            assertTrue(replay.replayed)
            assertEquals(
                first.employee.employeeId,
                replay.employee.employeeId,
            )
            assertEquals(
                1,
                employeeCount(
                    jdbc,
                    ids.organizationOne,
                ),
            )

            val duplicate =
                assertThrows<
                    ProductApiException
                > {
                    service.createEmployee(
                        CreateEmployeeCommand(
                            operationId =
                                UUID.randomUUID(),
                            organizationId =
                                ids.organizationOne,
                            displayName =
                                "Duplicate",
                            employeeCode =
                                "EMP-001",
                            startDate =
                                LocalDate.parse(
                                    "2026-09-02",
                                ),
                            employmentTypeCode =
                                null,
                            linkedUserIdentityId =
                                null,
                            hireDate = null,
                            legalName = null,
                            mobile = null,
                            email = null,
                            actorUserId =
                                ids.adminOne,
                            correlationId =
                                "corr-duplicate",
                        ),
                    )
                }
            assertEquals(
                "EMPLOYEE_CODE_CONFLICT",
                duplicate.code,
            )

            val otherOrg =
                service.createEmployee(
                    CreateEmployeeCommand(
                        operationId =
                            UUID.randomUUID(),
                        organizationId =
                            ids.organizationTwo,
                        displayName =
                            "Same Code Other Org",
                        employeeCode =
                            "EMP-001",
                        startDate =
                            LocalDate.parse(
                                "2026-09-03",
                            ),
                        employmentTypeCode =
                            null,
                        linkedUserIdentityId =
                            null,
                        hireDate = null,
                        legalName = null,
                        mobile = null,
                        email = null,
                        actorUserId =
                            ids.adminTwo,
                        correlationId =
                            "corr-other-org",
                    ),
                )
            assertEquals(
                "EMP-001",
                otherOrg.employee
                    .employeeCode,
            )

            val linkOperation =
                UUID.randomUUID()
            val linked =
                service.linkIdentity(
                    LinkEmployeeIdentityCommand(
                        operationId =
                            linkOperation,
                        employeeId =
                            first.employee
                                .employeeId,
                        baseVersion = 1,
                        userIdentityId =
                            ids.workerOne,
                        actorUserId =
                            ids.adminOne,
                        correlationId =
                            "corr-link",
                    ),
                )
            assertFalse(linked.replayed)
            assertEquals(
                2,
                linked.employee
                    .employeeVersion,
            )
            assertEquals(
                first.employee.personId,
                personIdForIdentity(
                    jdbc,
                    ids.workerOne,
                ),
            )

            val linkedReplay =
                service.linkIdentity(
                    LinkEmployeeIdentityCommand(
                        operationId =
                            linkOperation,
                        employeeId =
                            first.employee
                                .employeeId,
                        baseVersion = 1,
                        userIdentityId =
                            ids.workerOne,
                        actorUserId =
                            ids.adminOne,
                        correlationId =
                            "corr-link",
                    ),
                )
            assertTrue(
                linkedReplay.replayed,
            )
            assertEquals(
                2,
                linkedReplay.employee
                    .employeeVersion,
            )

            val own =
                service.ownProfile(
                    actorUserId =
                        ids.workerOne,
                    organizationId =
                        ids.organizationOne,
                )
            assertEquals(
                first.employee.employeeId,
                own.employeeId,
            )
            assertEquals(
                "private@example.test",
                own.email,
            )

            val directory =
                service.directory(
                    actorUserId =
                        ids.workerOne,
                    organizationId =
                        ids.organizationOne,
                    limit = 100,
                )
            val safe =
                directory.single {
                    it.employeeId ==
                        first.employee
                            .employeeId
                }
            assertEquals(
                "عامل اختبار",
                safe.displayName,
            )
            assertTrue(
                safe.linkedIdentity,
            )

            val privateDenied =
                assertThrows<
                    ProductApiException
                > {
                    service.detail(
                        actorUserId =
                            ids.workerOne,
                        employeeId =
                            first.employee
                                .employeeId,
                    )
                }
            assertEquals(
                "PEOPLE_ACCESS_DENIED",
                privateDenied.code,
            )

            val updated =
                service.updateProfile(
                    UpdateEmployeeProfileCommand(
                        operationId =
                            UUID.randomUUID(),
                        employeeId =
                            first.employee
                                .employeeId,
                        baseVersion = 2,
                        displayName =
                            "عامل اختبار محدث",
                        legalName =
                            "New Restricted Name",
                        mobile =
                            "01111111111",
                        email =
                            "new-private@example.test",
                        actorUserId =
                            ids.adminOne,
                        correlationId =
                            "corr-update",
                    ),
                )
            assertEquals(
                3,
                updated.employee
                    .employeeVersion,
            )
            assertEquals(
                "عامل اختبار محدث",
                updated.employee
                    .displayName,
            )

            val stale =
                assertThrows<
                    ProductApiException
                > {
                    service.updateProfile(
                        UpdateEmployeeProfileCommand(
                            operationId =
                                UUID.randomUUID(),
                            employeeId =
                                first.employee
                                    .employeeId,
                            baseVersion = 2,
                            displayName =
                                "Stale",
                            legalName = null,
                            mobile = null,
                            email = null,
                            actorUserId =
                                ids.adminOne,
                            correlationId =
                                "corr-stale",
                        ),
                    )
                }
            assertEquals(
                "EMPLOYEE_VERSION_CONFLICT",
                stale.code,
            )
            assertEquals(
                3,
                stale.currentVersion,
            )

            val second =
                service.createEmployee(
                    CreateEmployeeCommand(
                        operationId =
                            UUID.randomUUID(),
                        organizationId =
                            ids.organizationOne,
                        displayName =
                            "Second Worker",
                        employeeCode =
                            "EMP-002",
                        startDate =
                            LocalDate.parse(
                                "2026-09-05",
                            ),
                        employmentTypeCode =
                            null,
                        linkedUserIdentityId =
                            null,
                        hireDate = null,
                        legalName = null,
                        mobile = null,
                        email = null,
                        actorUserId =
                            ids.adminOne,
                        correlationId =
                            "corr-second",
                    ),
                )
            val wrongOrgLink =
                assertThrows<
                    ProductApiException
                > {
                    service.linkIdentity(
                        LinkEmployeeIdentityCommand(
                            operationId =
                                UUID.randomUUID(),
                            employeeId =
                                second.employee
                                    .employeeId,
                            baseVersion = 1,
                            userIdentityId =
                                ids.outsider,
                            actorUserId =
                                ids.adminOne,
                            correlationId =
                                "corr-wrong-org",
                        ),
                    )
                }
            assertEquals(
                "IDENTITY_ORGANIZATION_MISMATCH",
                wrongOrgLink.code,
            )

            val outsiderDirectory =
                assertThrows<
                    ProductApiException
                > {
                    service.directory(
                        actorUserId =
                            ids.outsider,
                        organizationId =
                            ids.organizationOne,
                        limit = 10,
                    )
                }
            assertEquals(
                "PEOPLE_ACCESS_DENIED",
                outsiderDirectory.code,
            )

            assertEmploymentHistoryInvariant(
                jdbc = jdbc,
                employeeId =
                    first.employee
                        .employeeId,
                activeEmploymentId =
                    first.employee
                        .activeEmploymentId!!,
                at = clock.instant(),
            )

            val eventDump =
                published.joinToString("|")
            assertTrue(
                published.any {
                    it is EmployeeCreated
                },
            )
            assertTrue(
                published.any {
                    it is EmployeeIdentityLinked
                },
            )
            assertTrue(
                published.any {
                    it is EmployeeProfileUpdated
                },
            )
            assertFalse(
                eventDump.contains(
                    "new-private@example.test",
                ),
            )
            assertFalse(
                eventDump.contains(
                    "01111111111",
                ),
            )
            assertFalse(
                eventDump.contains(
                    "New Restricted Name",
                ),
            )

            val auditCount =
                jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM audit_event
                    WHERE target_type = 'EMPLOYEE'
                      AND target_id = ?
                    """.trimIndent(),
                    Int::class.java,
                    first.employee
                        .employeeId,
                ) ?: 0
            assertTrue(
                auditCount >= 3,
            )

            val auditText =
                jdbc.queryForObject(
                    """
                    SELECT string_agg(
                        COALESCE(safe_diff::text, ''),
                        '|'
                    )
                    FROM audit_event
                    WHERE target_type = 'EMPLOYEE'
                      AND target_id = ?
                    """.trimIndent(),
                    String::class.java,
                    first.employee
                        .employeeId,
                ) ?: ""
            assertFalse(
                auditText.contains(
                    "new-private@example.test",
                ),
            )
            assertFalse(
                auditText.contains(
                    "01111111111",
                ),
            )

            jdbc.update(
                """
                UPDATE people_authority_binding
                SET active = false,
                    effective_to = ?,
                    version = version + 1
                WHERE id = ?
                """.trimIndent(),
                clock.instant()
                    .atOffset(
                        ZoneOffset.UTC,
                    ),
                adminOneBinding,
            )

            assertFalse(
                peopleAuthorization
                    .canManagePeople(
                        ids.adminOne,
                        ids.organizationOne,
                    ),
                "Revoked PostgreSQL People authority must deny immediately even before OpenFGA tuple cleanup.",
            )

            transaction
                .executeWithoutResult {
                    peopleProjectionBridge
                        .syncBinding(
                            bindingId =
                                adminOneBinding,
                            occurredAt =
                                clock.instant(),
                        )
                }

            projectionProcessor
                .processOne(
                    clock.instant(),
                )

            assertFalse(
                gateway.isAllowed(
                    PeopleAuthorizationRelations
                        .canManagePeople(
                            ids.adminOne,
                            ids.organizationOne,
                        ),
                ),
                "Revoked People authority must converge to an absent OpenFGA permission.",
            )
        } finally {
            telemetry.close()
        }
    }

    private fun seedBase(
        jdbc: JdbcTemplate,
        at: Instant,
    ): SeedIds {
        val orgOne =
            UUID.randomUUID()
        val orgTwo =
            UUID.randomUUID()
        val adminOne =
            UUID.randomUUID()
        val workerOne =
            UUID.randomUUID()
        val adminTwo =
            UUID.randomUUID()
        val outsider =
            UUID.randomUUID()

        insertOrganization(
            jdbc,
            orgOne,
            "PEOPLE-ONE",
            at,
        )
        insertOrganization(
            jdbc,
            orgTwo,
            "PEOPLE-TWO",
            at,
        )

        listOf(
            adminOne to orgOne,
            workerOne to orgOne,
            adminTwo to orgTwo,
            outsider to orgTwo,
        ).forEach { (user, org) ->
            insertIdentity(
                jdbc,
                user,
                org,
                at,
            )
            insertMembership(
                jdbc,
                user,
                org,
                at,
            )
        }

        return SeedIds(
            organizationOne =
                orgOne,
            organizationTwo =
                orgTwo,
            adminOne = adminOne,
            workerOne = workerOne,
            adminTwo = adminTwo,
            outsider = outsider,
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
                ?, ?,
                'People Contract',
                'People Contract',
                'HILTECH',
                'ACTIVE',
                ?, 1
            )
            """.trimIndent(),
            id,
            code + "-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertIdentity(
        jdbc: JdbcTemplate,
        id: UUID,
        organizationId: UUID,
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
                ?, 'contract',
                ?, NULL,
                'ACTIVE',
                ?, ?, 1
            )
            """.trimIndent(),
            id,
            "people-" + id,
            organizationId,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertMembership(
        jdbc: JdbcTemplate,
        userId: UUID,
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
            userId,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertPeopleAdminBinding(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        principalUserId: UUID,
        createdBy: UUID,
        at: Instant,
    ): UUID {
        val id =
            UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO people_authority_binding (
                id,
                organization_id,
                authority_key,
                principal_type,
                principal_user_id,
                principal_team_id,
                effective_from,
                effective_to,
                active,
                created_by_user_id,
                created_at,
                version
            )
            VALUES (
                ?, ?,
                'PEOPLE_ADMIN',
                'USER',
                ?, NULL,
                ?, NULL,
                true,
                ?, ?, 1
            )
            """.trimIndent(),
            id,
            organizationId,
            principalUserId,
            at.atOffset(ZoneOffset.UTC),
            createdBy,
            at.atOffset(ZoneOffset.UTC),
        )
        return id
    }

    private fun applyTuple(
        gateway: OpenFgaGateway,
        tuple: OpenFgaTuple,
    ) {
        val result =
            gateway.apply(
                tuple,
                AuthorizationDesiredState
                    .PRESENT,
            )
        assertTrue(
            result is
                OpenFgaMutationResult.Applied,
            "OpenFGA fixture failed: $result",
        )
    }

    private fun employeeCount(
        jdbc: JdbcTemplate,
        organizationId: UUID,
    ): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM employee
            WHERE organization_id = ?
            """.trimIndent(),
            Int::class.java,
            organizationId,
        ) ?: 0

    private fun personIdForIdentity(
        jdbc: JdbcTemplate,
        identityId: UUID,
    ): UUID? =
        jdbc.queryForObject(
            """
            SELECT person_id
            FROM user_identity
            WHERE id = ?
            """.trimIndent(),
            UUID::class.java,
            identityId,
        )

    private fun employmentState(
        jdbc: JdbcTemplate,
        employmentId: UUID,
    ): String =
        requireNotNull(
            jdbc.queryForObject(
                """
                SELECT state
                FROM employment
                WHERE id = ?
                """.trimIndent(),
                String::class.java,
                employmentId,
            ),
        )

    private fun assertEmploymentHistoryInvariant(
        jdbc: JdbcTemplate,
        employeeId: UUID,
        activeEmploymentId: UUID,
        at: Instant,
    ) {
        var duplicateActiveRejected =
            false
        try {
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
                    ?, ?, 'FIELD',
                    ?, NULL,
                    'ACTIVE',
                    ?, ?, 1
                )
                """.trimIndent(),
                UUID.randomUUID(),
                employeeId,
                LocalDate.parse(
                    "2026-10-01",
                ),
                at.atOffset(
                    ZoneOffset.UTC,
                ),
                at.atOffset(
                    ZoneOffset.UTC,
                ),
            )
        } catch (_: Exception) {
            duplicateActiveRejected =
                true
        }
        assertTrue(
            duplicateActiveRejected,
            "Two simultaneously ACTIVE Employment periods must be rejected.",
        )

        jdbc.update(
            """
            UPDATE employment
            SET state = 'ENDED',
                end_date = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
            """.trimIndent(),
            LocalDate.parse(
                "2026-09-30",
            ),
            at.atOffset(ZoneOffset.UTC),
            activeEmploymentId,
        )

        val secondEmployment =
            UUID.randomUUID()
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
                ?, ?, 'FIELD',
                ?, NULL,
                'ACTIVE',
                ?, ?, 1
            )
            """.trimIndent(),
            secondEmployment,
            employeeId,
            LocalDate.parse(
                "2026-10-01",
            ),
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )

        assertEquals(
            2,
            jdbc.queryForObject(
                """
                SELECT count(*)
                FROM employment
                WHERE employee_id = ?
                """.trimIndent(),
                Int::class.java,
                employeeId,
            ),
        )
        assertEquals(
            "ACTIVE",
            employmentState(
                jdbc,
                secondEmployment,
            ),
        )
    }

    private data class SeedIds(
        val organizationOne: UUID,
        val organizationTwo: UUID,
        val adminOne: UUID,
        val workerOne: UUID,
        val adminTwo: UUID,
        val outsider: UUID,
    )
}
