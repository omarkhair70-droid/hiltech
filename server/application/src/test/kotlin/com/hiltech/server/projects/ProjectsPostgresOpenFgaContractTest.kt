package com.hiltech.server.projects

import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.security.AuthorizationCheckPort
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionProcessResult
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

class ProjectsPostgresOpenFgaContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_PROJECTS_CONTRACT_TEST",
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
    fun projectSiteCoreIsTenantSafeIdempotentHistoryPreservingAndFailClosed() {
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
            TransactionTemplate(
                txManager,
            )
        val clock =
            Clock.fixed(
                Instant.parse(
                    "2026-09-20T13:30:00Z",
                ),
                ZoneOffset.UTC,
            )
        val ids =
            seed(
                jdbc,
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
        properties.validateEnabledConfiguration()

        val gateway =
            OpenFgaGateway(
                properties = properties,
                transport =
                    JdkOpenFgaHttpTransport(
                        properties,
                    ),
            )
        val authorizationAdapter =
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
            ids.admin,
            ids.oldPm,
            ids.teamUser,
            ids.ordinaryMember,
        ).forEach {
            applyTuple(
                gateway,
                RoleTeamAuthorizationRelations
                    .organizationMember(
                        it,
                        ids.organization,
                    ),
            )
        }
        applyTuple(
            gateway,
            RoleTeamAuthorizationRelations
                .teamMember(
                    ids.teamUser,
                    ids.team,
                ),
        )

        val writer =
            JdbcAuthorizationProjectionIntentWriter(
                jdbc = jdbc,
                properties = properties,
            )
        val projectAuthoritySource =
            JdbcProjectAuthoritySource(
                jdbc,
            )
        val bridge =
            ProjectAuthorizationProjectionBridge(
                source =
                    projectAuthoritySource,
                writer = writer,
                clock = clock,
            )
        transaction.executeWithoutResult {
            bridge.syncAuthorityBinding(
                ids.projectAdminBinding,
            )
        }

        val provider =
            StaticListableBeanFactory()
                .also {
                    it.addBean(
                        "authorizationCheckPort",
                        authorizationAdapter,
                    )
                }
                .getBeanProvider(
                    AuthorizationCheckPort::class.java,
                )
        val projectAuthorization =
            SpringProjectAuthorization(
                authorizationProvider =
                    provider,
                sourceAuthority =
                    sourceAuthority,
                projectAuthoritySource =
                    projectAuthoritySource,
                clock = clock,
            )

        assertFalse(
            projectAuthorization
                .canManageProjects(
                    ids.admin,
                    ids.organization,
                ),
            "Pending Project admin grant must fail closed.",
        )

        val processor =
            AuthorizationProjectionProcessor(
                store =
                    JdbcAuthorizationProjectionStore(
                        jdbc = jdbc,
                        transactionManager =
                            txManager,
                        properties = properties,
                        retryPolicy =
                            AuthorizationProjectionRetryPolicy(),
                    ),
                openFga = gateway,
                properties = properties,
            )
        drain(
            processor,
            clock.instant(),
        )
        assertTrue(
            projectAuthorization
                .canManageProjects(
                    ids.admin,
                    ids.organization,
                ),
        )
        assertFalse(
            projectAuthorization
                .canManageProjects(
                    ids.ordinaryMember,
                    ids.organization,
                ),
            "Organization membership must not imply Project administration.",
        )

        val telemetry =
            HiltechTelemetryRuntime(
                openTelemetry =
                    OpenTelemetry.noop(),
                closeAction = {},
            )
        val published =
            mutableListOf<Any>()
        val service =
            ProjectsService(
                persistence =
                    JdbcProjectsPersistence(
                        jdbc,
                    ),
                authorization =
                    projectAuthorization,
                idempotency =
                    JdbcIdempotentCommandExecutor(
                        jdbc = jdbc,
                        transactionManager =
                            txManager,
                        clock = clock,
                        telemetry = telemetry,
                    ),
                authorizationProjection =
                    bridge,
                audit =
                    JdbcAuditEventWriter(
                        jdbc,
                    ),
                events =
                    ApplicationEventPublisher {
                        event ->
                        published += event
                    },
                clock = clock,
            )

        try {
            val createOperation =
                UUID.randomUUID()
            val createCommand =
                CreateProjectCommand(
                    operationId =
                        createOperation,
                    organizationId =
                        ids.organization,
                    sourceType =
                        ProjectSourceType.INTERNAL,
                    sourceExternalReference =
                        null,
                    explicitProjectCode =
                        null,
                    name =
                        "Cairo Core Upgrade",
                    clientOrganizationId =
                        ids.clientA,
                    initialResponsibility =
                        ProjectPrincipalInput(
                            principalType =
                                ProjectResponsibilityPrincipalType
                                    .EMPLOYEE,
                            principalId =
                                ids.employee,
                        ),
                    startDatePlanned =
                        LocalDate.parse(
                            "2026-10-01",
                        ),
                    endDatePlanned =
                        LocalDate.parse(
                            "2026-11-30",
                        ),
                    clientOccurredAt =
                        clock.instant(),
                    actorUserId =
                        ids.admin,
                    correlationId =
                        "corr-project-create",
                )

            val created =
                service.createProject(
                    createCommand,
                )
            assertFalse(created.replayed)
            assertEquals(
                "PRJ-2026-001",
                created.project.projectCode,
            )
            assertEquals(
                ProjectLifecycleState.DRAFT,
                created.project.lifecycleState,
            )
            assertEquals(
                ProjectResponsibilityResolutionState
                    .RESOLVED_USER,
                created.project.responsibility
                    ?.resolutionState,
            )

            val replay =
                service.createProject(
                    createCommand,
                )
            assertTrue(replay.replayed)
            assertEquals(
                created.project.projectId,
                replay.project.projectId,
            )
            assertEquals(
                1,
                jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM project
                    WHERE organization_id = ?
                    """.trimIndent(),
                    Int::class.java,
                    ids.organization,
                ),
            )

            assertFalse(
                projectAuthorization
                    .canManageProject(
                        ids.oldPm,
                        created.project,
                    ),
                "Pending Project manager grant must fail closed.",
            )
            drain(
                processor,
                clock.instant(),
            )
            val createdVisible =
                requireNotNull(
                    JdbcProjectsPersistence(jdbc)
                        .project(
                            created.project.projectId,
                            clock.instant(),
                        ),
                )
            assertTrue(
                projectAuthorization
                    .canManageProject(
                        ids.oldPm,
                        createdVisible,
                    ),
            )

            val deniedCreate =
                assertThrows<ProductApiException> {
                    service.createProject(
                        createCommand.copy(
                            operationId =
                                UUID.randomUUID(),
                            name =
                                "Unauthorized Project",
                            actorUserId =
                                ids.ordinaryMember,
                            correlationId =
                                "corr-project-denied",
                        ),
                    )
                }
            assertEquals(
                "PERMISSION_DENIED",
                deniedCreate.code,
            )

            val imported =
                service.createProject(
                    createCommand.copy(
                        operationId =
                            UUID.randomUUID(),
                        sourceType =
                            ProjectSourceType.IMPORT,
                        sourceExternalReference =
                            "legacy-sheet:2026-09:row-18",
                        initialResponsibility =
                            null,
                        name =
                            "Imported Project",
                        correlationId =
                            "corr-project-import",
                    ),
                )
            assertEquals(
                ProjectSourceType.IMPORT,
                imported.project.sourceType,
            )
            assertEquals(
                "legacy-sheet:2026-09:row-18",
                imported.project
                    .sourceExternalReference,
            )
            assertEquals(
                "PRJ-2026-002",
                imported.project.projectCode,
            )

            val badSource =
                assertThrows<ProductApiException> {
                    service.createProject(
                        createCommand.copy(
                            operationId =
                                UUID.randomUUID(),
                            sourceType =
                                ProjectSourceType.INTERNAL,
                            sourceExternalReference =
                                "must-not-exist",
                            correlationId =
                                "corr-project-source-invalid",
                        ),
                    )
                }
            assertEquals(
                "PROJECT_SOURCE_INVALID",
                badSource.code,
            )

            val site =
                service.createSite(
                    CreateSiteCommand(
                        operationId =
                            UUID.randomUUID(),
                        organizationId =
                            ids.organization,
                        clientOrganizationId =
                            ids.clientA,
                        siteCode =
                            "SITE-CAIRO-01",
                        name =
                            "Cairo Client Site",
                        addressText =
                            "Restricted address",
                        latitude = 30.0444,
                        longitude = 31.2357,
                        timezone =
                            "Africa/Cairo",
                        clientOccurredAt =
                            clock.instant(),
                        actorUserId =
                            ids.admin,
                        correlationId =
                            "corr-site-create",
                    ),
                )
            assertEquals(
                ids.organization,
                site.site.organizationId,
            )
            drain(
                processor,
                clock.instant(),
            )

            val attached =
                service.attachSite(
                    AttachProjectSiteCommand(
                        operationId =
                            UUID.randomUUID(),
                        projectId =
                            created.project.projectId,
                        baseProjectVersion =
                            created.project.version,
                        siteId =
                            site.site.siteId,
                        projectSiteCode =
                            "MAIN",
                        accessInstructions =
                            "Call security desk",
                        projectSpecificNotes =
                            "Restricted project note",
                        clientOccurredAt =
                            clock.instant(),
                        actorUserId =
                            ids.admin,
                        correlationId =
                            "corr-site-attach",
                    ),
                )
            assertEquals(
                ProjectSiteLifecycleState.PLANNED,
                attached.projectSite.lifecycleState,
            )
            assertEquals(
                1,
                attached.project.siteCount,
            )

            val duplicateAttach =
                assertThrows<ProductApiException> {
                    service.attachSite(
                        AttachProjectSiteCommand(
                            operationId =
                                UUID.randomUUID(),
                            projectId =
                                attached.project.projectId,
                            baseProjectVersion =
                                attached.project.version,
                            siteId =
                                site.site.siteId,
                            projectSiteCode =
                                "MAIN-2",
                            accessInstructions =
                                null,
                            projectSpecificNotes =
                                null,
                            clientOccurredAt =
                                clock.instant(),
                            actorUserId =
                                ids.admin,
                            correlationId =
                                "corr-site-duplicate",
                        ),
                    )
                }
            assertEquals(
                "PROJECT_SITE_ALREADY_ATTACHED",
                duplicateAttach.code,
            )

            val otherClientSite =
                service.createSite(
                    CreateSiteCommand(
                        operationId =
                            UUID.randomUUID(),
                        organizationId =
                            ids.organization,
                        clientOrganizationId =
                            ids.clientB,
                        siteCode =
                            "SITE-OTHER-01",
                        name =
                            "Other Client Site",
                        addressText = null,
                        latitude = null,
                        longitude = null,
                        timezone =
                            "Africa/Cairo",
                        clientOccurredAt =
                            clock.instant(),
                        actorUserId =
                            ids.admin,
                        correlationId =
                            "corr-site-other",
                    ),
                )

            val clientMismatch =
                assertThrows<ProductApiException> {
                    service.attachSite(
                        AttachProjectSiteCommand(
                            operationId =
                                UUID.randomUUID(),
                            projectId =
                                attached.project.projectId,
                            baseProjectVersion =
                                attached.project.version,
                            siteId =
                                otherClientSite.site.siteId,
                            projectSiteCode =
                                null,
                            accessInstructions =
                                null,
                            projectSpecificNotes =
                                null,
                            clientOccurredAt =
                                clock.instant(),
                            actorUserId =
                                ids.admin,
                            correlationId =
                                "corr-site-mismatch",
                        ),
                    )
                }
            assertEquals(
                "SITE_CLIENT_MISMATCH",
                clientMismatch.code,
            )

            val managerChanged =
                service.changeManager(
                    ChangeProjectManagerCommand(
                        operationId =
                            UUID.randomUUID(),
                        projectId =
                            attached.project.projectId,
                        baseVersion =
                            attached.project.version,
                        principal =
                            ProjectPrincipalInput(
                                principalType =
                                    ProjectResponsibilityPrincipalType
                                        .TEAM,
                                principalId =
                                    ids.team,
                            ),
                        reason =
                            "Shift execution ownership",
                        clientOccurredAt =
                            clock.instant(),
                        actorUserId =
                            ids.admin,
                        correlationId =
                            "corr-manager-change",
                    ),
                )
            assertEquals(
                ProjectResponsibilityResolutionState.TEAM,
                managerChanged.project
                    .responsibility
                    ?.resolutionState,
            )

            val beforeProjection =
                requireNotNull(
                    JdbcProjectsPersistence(jdbc)
                        .project(
                            managerChanged.project.projectId,
                            clock.instant(),
                        ),
                )
            assertFalse(
                projectAuthorization
                    .canManageProject(
                        ids.oldPm,
                        beforeProjection,
                    ),
                "Ended Project responsibility must deny immediately even while a stale tuple exists.",
            )
            assertFalse(
                projectAuthorization
                    .canManageProject(
                        ids.teamUser,
                        beforeProjection,
                    ),
                "Replacement Team responsibility must remain denied until projection applies.",
            )

            drain(
                processor,
                clock.instant(),
            )
            val afterProjection =
                requireNotNull(
                    JdbcProjectsPersistence(jdbc)
                        .project(
                            managerChanged.project.projectId,
                            clock.instant(),
                        ),
                )
            assertTrue(
                projectAuthorization
                    .canManageProject(
                        ids.teamUser,
                        afterProjection,
                    ),
            )

            assertEquals(
                2,
                jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM project_responsibility
                    WHERE project_id = ?
                    """.trimIndent(),
                    Int::class.java,
                    created.project.projectId,
                ),
            )
            assertEquals(
                1,
                jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM project_responsibility
                    WHERE project_id = ?
                      AND state = 'ACTIVE'
                    """.trimIndent(),
                    Int::class.java,
                    created.project.projectId,
                ),
            )

            val kickoff =
                service.startKickoff(
                    ProjectLifecycleCommand(
                        operationId =
                            UUID.randomUUID(),
                        projectId =
                            managerChanged.project.projectId,
                        baseVersion =
                            managerChanged.project.version,
                        clientOccurredAt =
                            clock.instant(),
                        actorUserId =
                            ids.admin,
                        correlationId =
                            "corr-kickoff-start",
                    ),
                )
            assertEquals(
                ProjectLifecycleState.KICKOFF,
                kickoff.project.lifecycleState,
            )

            val planning =
                service.completeKickoff(
                    ProjectLifecycleCommand(
                        operationId =
                            UUID.randomUUID(),
                        projectId =
                            kickoff.project.projectId,
                        baseVersion =
                            kickoff.project.version,
                        clientOccurredAt =
                            clock.instant(),
                        actorUserId =
                            ids.admin,
                        correlationId =
                            "corr-kickoff-complete",
                    ),
                )
            assertEquals(
                ProjectLifecycleState.PLANNING,
                planning.project.lifecycleState,
            )

            val planningService =
                PlanningService(
                    projects = JdbcProjectsPersistence(jdbc),
                    planning = JdbcPlanningPersistence(jdbc),
                    authorization = projectAuthorization,
                    idempotency =
                        JdbcIdempotentCommandExecutor(
                            jdbc = jdbc,
                            transactionManager = txManager,
                            clock = clock,
                            telemetry = telemetry,
                        ),
                    audit = JdbcAuditEventWriter(jdbc),
                    events = ApplicationEventPublisher { event ->
                        published += event
                    },
                    clock = clock,
                )

            val unauthorizedPlanMutation =
                assertThrows<ProductApiException> {
                    planningService.createMilestone(
                        CreateMilestoneCommand(
                            operationId = UUID.randomUUID(),
                            projectId = planning.project.projectId,
                            code = "M-DENIED",
                            name = "Unauthorized planning mutation",
                            plannedDate = null,
                            sequence = null,
                            clientVisible = false,
                            acceptanceRequirement = null,
                            baseProjectVersion = planning.project.version,
                            expectedBaselineVersion = planning.project.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.ordinaryMember,
                            correlationId = "corr-plan-unauthorized-mutation",
                        ),
                    )
                }
            assertEquals("OBJECT_NOT_VISIBLE", unauthorizedPlanMutation.code)

            val foreignOrganization = UUID.randomUUID()
            val foreignSite = UUID.randomUUID()
            val foreignArea = UUID.randomUUID()
            val foreignTimestamp = clock.instant().atOffset(ZoneOffset.UTC)
            jdbc.update(
                """
                INSERT INTO organization
                    (id, organization_code, legal_name, display_name, organization_type, status, created_at, version)
                VALUES (?, ?, 'Foreign Organization', 'Foreign', 'HILTECH', 'ACTIVE', ?, 1)
                """.trimIndent(),
                foreignOrganization,
                "FOREIGN-${foreignOrganization.toString().take(8)}",
                foreignTimestamp,
            )
            jdbc.update(
                """
                INSERT INTO site
                    (id, organization_id, client_organization_id, site_code, name, status,
                     created_at, created_by, updated_at, version)
                VALUES (?, ?, ?, 'FOREIGN-SITE', 'Foreign Site', 'ACTIVE', ?, ?, ?, 1)
                """.trimIndent(),
                foreignSite,
                foreignOrganization,
                ids.clientA,
                foreignTimestamp,
                ids.admin,
                foreignTimestamp,
            )
            jdbc.update(
                """
                INSERT INTO area
                    (id, organization_id, site_id, type_code, code, name, restricted_access,
                     created_at, created_by, updated_at, version)
                VALUES (?, ?, ?, 'ZONE', 'FOREIGN', 'Foreign Area', false, ?, ?, ?, 1)
                """.trimIndent(),
                foreignArea,
                foreignOrganization,
                foreignSite,
                foreignTimestamp,
                ids.admin,
                foreignTimestamp,
            )
            val crossTenantArea =
                assertThrows<ProductApiException> {
                    planningService.updateArea(
                        UpdateAreaCommand(
                            operationId = UUID.randomUUID(),
                            areaId = foreignArea,
                            projectId = planning.project.projectId,
                            parentAreaId = null,
                            typeCode = "ZONE",
                            code = "FOREIGN",
                            name = "Foreign Area",
                            sequence = null,
                            restrictedAccess = false,
                            baseProjectVersion = planning.project.version,
                            baseObjectVersion = 1,
                            expectedBaselineVersion = planning.project.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.admin,
                            correlationId = "corr-plan-cross-tenant-area",
                        ),
                    )
                }
            assertEquals("OBJECT_NOT_VISIBLE", crossTenantArea.code)

            val areaCommand =
                CreateAreaCommand(
                    operationId = UUID.randomUUID(),
                    projectId = planning.project.projectId,
                    projectSiteId = attached.projectSite.projectSiteId,
                    parentAreaId = null,
                    typeCode = "BUILDING",
                    code = "BLDG-A",
                    name = "Building A",
                    sequence = 10,
                    restrictedAccess = false,
                    baseProjectVersion = planning.project.version,
                    expectedBaselineVersion = planning.project.baselineVersion,
                    clientOccurredAt = clock.instant(),
                    actorUserId = ids.admin,
                    correlationId = "corr-plan-area",
                )
            val area = planningService.createArea(areaCommand)
            assertFalse(area.replayed)
            assertEquals(1, area.plan.areas.size)

            val areaReplay = planningService.createArea(areaCommand)
            assertTrue(areaReplay.replayed)
            assertEquals(area.targetId, areaReplay.targetId)
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT count(*) FROM area WHERE organization_id = ? AND site_id = ?",
                    Int::class.java,
                    ids.organization,
                    site.site.siteId,
                ),
            )

            val childArea =
                planningService.createArea(
                    areaCommand.copy(
                        operationId = UUID.randomUUID(),
                        parentAreaId = area.targetId,
                        code = "BLDG-A-F1",
                        name = "Building A Floor 1",
                        baseProjectVersion = area.plan.project.version,
                        correlationId = "corr-plan-area-child",
                    ),
                )
            val duplicateArea =
                assertThrows<ProductApiException> {
                    planningService.createArea(
                        areaCommand.copy(
                            operationId = UUID.randomUUID(),
                            baseProjectVersion = childArea.plan.project.version,
                            correlationId = "corr-plan-area-duplicate",
                        ),
                    )
                }
            assertEquals("PLANNING_CONSTRAINT_CONFLICT", duplicateArea.code)

            val areaCycle =
                assertThrows<ProductApiException> {
                    planningService.updateArea(
                        UpdateAreaCommand(
                            operationId = UUID.randomUUID(),
                            areaId = area.targetId,
                            projectId = planning.project.projectId,
                            parentAreaId = childArea.targetId,
                            typeCode = "BUILDING",
                            code = "BLDG-A",
                            name = "Building A",
                            sequence = 10,
                            restrictedAccess = false,
                            baseProjectVersion = childArea.plan.project.version,
                            baseObjectVersion = 1,
                            expectedBaselineVersion = planning.project.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.admin,
                            correlationId = "corr-plan-area-cycle",
                        ),
                    )
                }
            assertEquals("AREA_CYCLE", areaCycle.code)

            val milestone =
                planningService.createMilestone(
                    CreateMilestoneCommand(
                        operationId = UUID.randomUUID(),
                        projectId = planning.project.projectId,
                        code = "M-FOUNDATION",
                        name = "Foundation complete",
                        plannedDate = LocalDate.parse("2026-10-31"),
                        sequence = 20,
                        clientVisible = true,
                        acceptanceRequirement = "Signed structural review",
                        baseProjectVersion = childArea.plan.project.version,
                        expectedBaselineVersion = area.plan.project.baselineVersion,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.admin,
                        correlationId = "corr-plan-milestone",
                    ),
                )
            assertEquals(1, milestone.plan.milestones.size)

            val mismatchedSitePackage =
                assertThrows<ProductApiException> {
                    planningService.createWorkPackage(
                        CreateWorkPackageCommand(
                            operationId = UUID.randomUUID(),
                            projectId = planning.project.projectId,
                            projectSiteId = attached.projectSite.projectSiteId,
                            siteId = otherClientSite.site.siteId,
                            milestoneId = milestone.targetId,
                            code = "WP-BAD-SITE",
                            name = "Invalid site context",
                            description = null,
                            owner = null,
                            plannedStart = null,
                            plannedEnd = null,
                            sequence = null,
                            baseProjectVersion = milestone.plan.project.version,
                            expectedBaselineVersion = milestone.plan.project.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.admin,
                            correlationId = "corr-plan-package-site-mismatch",
                        ),
                    )
                }
            assertEquals("WORK_PACKAGE_SITE_CONTEXT_INVALID", mismatchedSitePackage.code)

            val staleBaseline =
                assertThrows<ProductApiException> {
                    planningService.createMilestone(
                        CreateMilestoneCommand(
                            operationId = UUID.randomUUID(),
                            projectId = planning.project.projectId,
                            code = "M-STALE",
                            name = "Stale baseline",
                            plannedDate = null,
                            sequence = null,
                            clientVisible = false,
                            acceptanceRequirement = null,
                            baseProjectVersion = milestone.plan.project.version,
                            expectedBaselineVersion = milestone.plan.project.baselineVersion + 1,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.admin,
                            correlationId = "corr-plan-stale-baseline",
                        ),
                    )
                }
            assertEquals("VERSION_CONFLICT", staleBaseline.code)

            val workPackage =
                planningService.createWorkPackage(
                    CreateWorkPackageCommand(
                        operationId = UUID.randomUUID(),
                        projectId = planning.project.projectId,
                        projectSiteId = attached.projectSite.projectSiteId,
                        siteId = site.site.siteId,
                        milestoneId = milestone.targetId,
                        code = "WP-FOUNDATION",
                        name = "Foundation package",
                        description = "Planning structure only",
                        owner =
                            ProjectPrincipalInput(
                                principalType = ProjectResponsibilityPrincipalType.TEAM,
                                principalId = ids.team,
                            ),
                        plannedStart = LocalDate.parse("2026-10-01"),
                        plannedEnd = LocalDate.parse("2026-10-25"),
                        sequence = 30,
                        baseProjectVersion = milestone.plan.project.version,
                        expectedBaselineVersion = milestone.plan.project.baselineVersion,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.admin,
                        correlationId = "corr-plan-package",
                    ),
                )
            assertEquals(1, workPackage.plan.workPackages.size)
            assertEquals("Delivery A", workPackage.plan.workPackages.single().owner?.principalLabel)

            val dependency =
                planningService.addDependency(
                    AddPlanDependencyCommand(
                        operationId = UUID.randomUUID(),
                        projectId = planning.project.projectId,
                        predecessor = PlanNodeRef(PlanNodeType.MILESTONE, milestone.targetId),
                        successor = PlanNodeRef(PlanNodeType.WORK_PACKAGE, workPackage.targetId),
                        dependencyType = PlanDependencyType.FINISH_TO_START,
                        lagMinutes = 60,
                        baseProjectVersion = workPackage.plan.project.version,
                        expectedBaselineVersion = workPackage.plan.project.baselineVersion,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.admin,
                        correlationId = "corr-plan-dependency",
                    ),
                )
            assertEquals(1, dependency.plan.dependencies.size)
            assertTrue(dependency.plan.validation.valid)
            assertTrue(dependency.plan.readyGate.ready)

            val selfEdge =
                assertThrows<ProductApiException> {
                    planningService.addDependency(
                        AddPlanDependencyCommand(
                            operationId = UUID.randomUUID(),
                            projectId = planning.project.projectId,
                            predecessor = PlanNodeRef(PlanNodeType.MILESTONE, milestone.targetId),
                            successor = PlanNodeRef(PlanNodeType.MILESTONE, milestone.targetId),
                            dependencyType = PlanDependencyType.FINISH_TO_START,
                            lagMinutes = 0,
                            baseProjectVersion = dependency.plan.project.version,
                            expectedBaselineVersion = dependency.plan.project.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.admin,
                            correlationId = "corr-plan-self-edge",
                        ),
                    )
                }
            assertEquals("PLAN_DEPENDENCY_SELF_EDGE", selfEdge.code)

            val cycle =
                assertThrows<ProductApiException> {
                    planningService.addDependency(
                        AddPlanDependencyCommand(
                            operationId = UUID.randomUUID(),
                            projectId = planning.project.projectId,
                            predecessor = PlanNodeRef(PlanNodeType.WORK_PACKAGE, workPackage.targetId),
                            successor = PlanNodeRef(PlanNodeType.MILESTONE, milestone.targetId),
                            dependencyType = PlanDependencyType.FINISH_TO_START,
                            lagMinutes = 0,
                            baseProjectVersion = dependency.plan.project.version,
                            expectedBaselineVersion = dependency.plan.project.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.admin,
                            correlationId = "corr-plan-cycle",
                        ),
                    )
                }
            assertEquals("PLAN_DEPENDENCY_CYCLE", cycle.code)

            val staleArea =
                assertThrows<ProductApiException> {
                    planningService.updateArea(
                        UpdateAreaCommand(
                            operationId = UUID.randomUUID(),
                            areaId = area.targetId,
                            projectId = planning.project.projectId,
                            parentAreaId = null,
                            typeCode = "BUILDING",
                            code = "BLDG-A",
                            name = "Stale Building A",
                            sequence = 10,
                            restrictedAccess = false,
                            baseProjectVersion = dependency.plan.project.version,
                            baseObjectVersion = area.plan.areas.single().version + 1,
                            expectedBaselineVersion = dependency.plan.project.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.admin,
                            correlationId = "corr-plan-stale",
                        ),
                    )
                }
            assertEquals("VERSION_CONFLICT", staleArea.code)

            val deniedPlan =
                assertThrows<ProductApiException> {
                    planningService.plan(ids.ordinaryMember, planning.project.projectId)
                }
            assertEquals("OBJECT_NOT_VISIBLE", deniedPlan.code)

            val importedSite =
                service.attachSite(
                    AttachProjectSiteCommand(
                        operationId = UUID.randomUUID(),
                        projectId = imported.project.projectId,
                        baseProjectVersion = imported.project.version,
                        siteId = site.site.siteId,
                        projectSiteCode = "IMPORTED-MAIN",
                        accessInstructions = null,
                        projectSpecificNotes = null,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.admin,
                        correlationId = "corr-plan-empty-site",
                    ),
                )
            val importedKickoff =
                service.startKickoff(
                    ProjectLifecycleCommand(
                        operationId = UUID.randomUUID(),
                        projectId = imported.project.projectId,
                        baseVersion = importedSite.project.version,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.admin,
                        correlationId = "corr-plan-empty-kickoff",
                    ),
                )
            val importedPlanning =
                service.completeKickoff(
                    ProjectLifecycleCommand(
                        operationId = UUID.randomUUID(),
                        projectId = imported.project.projectId,
                        baseVersion = importedKickoff.project.version,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.admin,
                        correlationId = "corr-plan-empty-planning",
                    ),
                )

            val emptyReadyGate =
                assertThrows<ProductApiException> {
                    planningService.markReady(
                        MarkProjectReadyCommand(
                            operationId = UUID.randomUUID(),
                            projectId = imported.project.projectId,
                            baseProjectVersion = importedPlanning.project.version,
                            expectedBaselineVersion = importedPlanning.project.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.admin,
                            correlationId = "corr-plan-empty-ready",
                        ),
                    )
                }
            assertEquals("PROJECT_READY_GATE_BLOCKED", emptyReadyGate.code)

            val crossProjectMilestone =
                assertThrows<ProductApiException> {
                    planningService.createWorkPackage(
                        CreateWorkPackageCommand(
                            operationId = UUID.randomUUID(),
                            projectId = imported.project.projectId,
                            projectSiteId = importedSite.projectSite.projectSiteId,
                            siteId = site.site.siteId,
                            milestoneId = milestone.targetId,
                            code = "WP-CROSS-PROJECT",
                            name = "Invalid cross Project package",
                            description = null,
                            owner = null,
                            plannedStart = null,
                            plannedEnd = null,
                            sequence = null,
                            baseProjectVersion = importedPlanning.project.version,
                            expectedBaselineVersion = importedPlanning.project.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.admin,
                            correlationId = "corr-plan-cross-project",
                        ),
                    )
                }
            assertEquals("WORK_PACKAGE_MILESTONE_INVALID", crossProjectMilestone.code)

            val ready =
                planningService.markReady(
                    MarkProjectReadyCommand(
                        operationId = UUID.randomUUID(),
                        projectId = planning.project.projectId,
                        baseProjectVersion = dependency.plan.project.version,
                        expectedBaselineVersion = dependency.plan.project.baselineVersion,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.admin,
                        correlationId = "corr-plan-ready",
                    ),
                )
            assertEquals(ProjectLifecycleState.READY, ready.plan.project.lifecycleState)
            assertFalse(ready.plan.canEditPlan)
            assertTrue(published.any { it is ProjectPlanChanged })

            val frozenMutation =
                assertThrows<ProductApiException> {
                    planningService.createMilestone(
                        CreateMilestoneCommand(
                            operationId = UUID.randomUUID(),
                            projectId = planning.project.projectId,
                            code = "M-AFTER-READY",
                            name = "Must remain frozen",
                            plannedDate = null,
                            sequence = null,
                            clientVisible = false,
                            acceptanceRequirement = null,
                            baseProjectVersion = ready.plan.project.version,
                            expectedBaselineVersion = ready.plan.project.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.admin,
                            correlationId = "corr-plan-frozen",
                        ),
                    )
                }
            assertEquals("PLAN_FROZEN", frozenMutation.code)

            val stale =
                assertThrows<ProductApiException> {
                    service.updateProjectDetails(
                        UpdateProjectDetailsCommand(
                            operationId =
                                UUID.randomUUID(),
                            projectId =
                                planning.project.projectId,
                            baseVersion =
                                kickoff.project.version,
                            name =
                                "Stale overwrite",
                            startDatePlanned =
                                planning.project
                                    .startDatePlanned,
                            endDatePlanned =
                                planning.project
                                    .endDatePlanned,
                            clientOccurredAt =
                                clock.instant(),
                            actorUserId =
                                ids.admin,
                            correlationId =
                                "corr-stale",
                        ),
                    )
                }
            assertEquals(
                "VERSION_CONFLICT",
                stale.code,
            )

            assertTrue(
                published.any {
                    it is ProjectCreated
                },
            )
            assertTrue(
                published.any {
                    it is ProjectSiteAttached
                },
            )
            assertTrue(
                published.any {
                    it is ProjectLifecycleChanged
                },
            )

            assertTrue(
                auditCount(
                    jdbc,
                    "PROJECT_CREATED",
                    created.project.projectId,
                ) >= 1,
            )
            assertTrue(
                auditCount(
                    jdbc,
                    "PROJECT_SITE_ATTACHED",
                    attached.projectSite.projectSiteId,
                ) >= 1,
            )
        } finally {
            telemetry.close()
        }
    }

    private fun seed(
        jdbc: JdbcTemplate,
        at: Instant,
    ): SeedIds {
        val organization =
            UUID.randomUUID()
        val clientA =
            UUID.randomUUID()
        val clientB =
            UUID.randomUUID()
        val admin =
            UUID.randomUUID()
        val oldPm =
            UUID.randomUUID()
        val teamUser =
            UUID.randomUUID()
        val ordinaryMember =
            UUID.randomUUID()
        val person =
            UUID.randomUUID()
        val employee =
            UUID.randomUUID()
        val employment =
            UUID.randomUUID()
        val team =
            UUID.randomUUID()
        val adminBinding =
            UUID.randomUUID()
        val codeRevision =
            UUID.randomUUID()

        insertOrganization(
            jdbc,
            organization,
            "HILTECH-PROJECTS",
            "HILTECH Projects",
            "HILTECH",
            at,
        )
        insertOrganization(
            jdbc,
            clientA,
            "CLIENT-A",
            "Client A",
            "CLIENT",
            at,
        )
        insertOrganization(
            jdbc,
            clientB,
            "CLIENT-B",
            "Client B",
            "CLIENT",
            at,
        )

        listOf(
            admin,
            oldPm,
            teamUser,
            ordinaryMember,
        ).forEach { identity ->
            jdbc.update(
                """
                INSERT INTO user_identity (
                    id,
                    auth_provider,
                    auth_subject,
                    status,
                    primary_organization_id,
                    created_at,
                    version
                )
                VALUES (
                    ?, 'contract', ?,
                    'ACTIVE', ?, ?, 1
                )
                """.trimIndent(),
                identity,
                "projects-" + identity,
                organization,
                at.atOffset(ZoneOffset.UTC),
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
                    'descriptive-only',
                    'ACTIVE',
                    ?, NULL,
                    NULL,
                    1
                )
                """.trimIndent(),
                UUID.randomUUID(),
                organization,
                identity,
                at.minusSeconds(60)
                    .atOffset(ZoneOffset.UTC),
            )
        }

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
                ?, 'Old PM',
                NULL, NULL, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            person,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )
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
                ?, ?, ?, 'PM-001',
                'ACTIVE',
                '2026-01-01',
                NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            employee,
            organization,
            person,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
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
                ?, ?, 'FULL_TIME',
                '2026-01-01',
                NULL,
                'ACTIVE',
                ?, ?, 1
            )
            """.trimIndent(),
            employment,
            employee,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )
        jdbc.update(
            """
            UPDATE user_identity
            SET person_id = ?
            WHERE id = ?
            """.trimIndent(),
            person,
            oldPm,
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
                ?, ?, 'DELIVERY-A',
                'Delivery A',
                NULL, NULL,
                true, 1
            )
            """.trimIndent(),
            team,
            organization,
        )
        jdbc.update(
            """
            INSERT INTO team_membership (
                id,
                team_id,
                user_identity_id,
                role_in_team,
                valid_from,
                valid_until,
                version
            )
            VALUES (
                ?, ?, ?,
                'MEMBER',
                ?, NULL, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            team,
            teamUser,
            at.minusSeconds(60)
                .atOffset(ZoneOffset.UTC),
        )

        jdbc.update(
            """
            INSERT INTO project_authority_binding (
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
                'PROJECT_ADMIN',
                'USER',
                ?, NULL,
                ?, NULL,
                true,
                ?, ?,
                1
            )
            """.trimIndent(),
            adminBinding,
            organization,
            admin,
            at.atOffset(ZoneOffset.UTC),
            admin,
            at.atOffset(ZoneOffset.UTC),
        )

        jdbc.update(
            """
            INSERT INTO config_revision (
                id,
                scope_type,
                scope_organization_id,
                family,
                code,
                name,
                lifecycle_state,
                revision_number,
                effective_from,
                created_at,
                created_by,
                activated_at,
                activated_by,
                version
            )
            VALUES (
                ?,
                'ORGANIZATION',
                ?,
                'code-policies',
                'PROJECT',
                'Project codes',
                'ACTIVE',
                1,
                ?,
                ?, ?,
                ?, ?,
                1
            )
            """.trimIndent(),
            codeRevision,
            organization,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
            admin,
            at.atOffset(ZoneOffset.UTC),
            admin,
        )
        jdbc.update(
            """
            INSERT INTO code_policy (
                config_revision_id,
                target_object_type,
                prefix,
                include_year,
                separator,
                sequence_scope,
                sequence_padding,
                manual_override_allowed,
                uniqueness_scope,
                reset_rule
            )
            VALUES (
                ?,
                'PROJECT',
                'PRJ',
                true,
                '-',
                'ORGANIZATION',
                3,
                false,
                'ORGANIZATION',
                'YEARLY'
            )
            """.trimIndent(),
            codeRevision,
        )

        return SeedIds(
            organization = organization,
            clientA = clientA,
            clientB = clientB,
            admin = admin,
            oldPm = oldPm,
            teamUser = teamUser,
            ordinaryMember =
                ordinaryMember,
            employee = employee,
            team = team,
            projectAdminBinding =
                adminBinding,
        )
    }

    private fun insertOrganization(
        jdbc: JdbcTemplate,
        id: UUID,
        code: String,
        displayName: String,
        type: String,
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
                ?, ?, ?, ?, ?,
                'ACTIVE', ?, 1
            )
            """.trimIndent(),
            id,
            code,
            displayName,
            displayName,
            type,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun applyTuple(
        gateway: OpenFgaGateway,
        tuple: OpenFgaTuple,
    ) {
        val result =
            gateway.apply(
                tuple,
                AuthorizationDesiredState.PRESENT,
            )
        assertTrue(
            result is OpenFgaMutationResult.Applied,
            "OpenFGA fixture failed: " +
                result,
        )
    }

    private fun drain(
        processor: AuthorizationProjectionProcessor,
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
                AuthorizationProjectionProcessResult.NO_WORK ->
                    return
                AuthorizationProjectionProcessResult.RETRY_SCHEDULED,
                AuthorizationProjectionProcessResult.FAILED_PERMANENT,
                ->
                    error(
                        "Project authorization projection did not converge.",
                    )
                else ->
                    Unit
            }
        }
        error(
            "Project authorization projection queue did not drain.",
        )
    }

    private fun auditCount(
        jdbc: JdbcTemplate,
        action: String,
        targetId: UUID,
    ): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM audit_event
            WHERE action = ?
              AND target_id = ?
            """.trimIndent(),
            Int::class.java,
            action,
            targetId,
        ) ?: 0

    private data class SeedIds(
        val organization: UUID,
        val clientA: UUID,
        val clientB: UUID,
        val admin: UUID,
        val oldPm: UUID,
        val teamUser: UUID,
        val ordinaryMember: UUID,
        val employee: UUID,
        val team: UUID,
        val projectAdminBinding: UUID,
    )
}
