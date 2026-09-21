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
import com.hiltech.server.people.JdbcHrDocumentsPersistence
import com.hiltech.server.people.JdbcPeoplePersistence
import com.hiltech.server.people.JdbcWorkforceAssignmentPersistence
import com.hiltech.server.work.ActivateProjectCommand
import com.hiltech.server.work.AssignWorkCommand
import com.hiltech.server.work.AssignmentTargetType
import com.hiltech.server.work.EvaluateReadinessCommand
import com.hiltech.server.work.JdbcReadinessAssignmentPersistence
import com.hiltech.server.work.JdbcWorkAssignmentSourceAuthority
import com.hiltech.server.work.ReadinessAssignmentService
import com.hiltech.server.work.ReassignWorkCommand
import com.hiltech.server.work.SpringWorkAssignmentAuthorization
import com.hiltech.server.work.WorkAssignmentAuthorizationProjectionBridge
import com.hiltech.server.work.WorkEligibilityResolver
import com.hiltech.server.work.AddWorkDependencyCommand
import com.hiltech.server.work.CreateWorkOrderCommand
import com.hiltech.server.work.CreateWorkTaskCommand
import com.hiltech.server.work.JdbcWorkPersistence
import com.hiltech.server.work.PlanWorkCommand
import com.hiltech.server.work.RemoveWorkDependencyCommand
import com.hiltech.server.work.ReviseWorkInstructionCommand
import com.hiltech.server.work.UpdateWorkOrderDetailsCommand
import com.hiltech.server.work.UpdateWorkTaskCommand
import com.hiltech.server.work.WorkAuthorizationProjectionBridge
import com.hiltech.server.work.WorkDependencyType
import com.hiltech.server.work.WorkOrderLifecycle
import com.hiltech.server.work.WorkReadiness
import com.hiltech.server.work.WorkService
import com.hiltech.server.work.WorkTaskState
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

            seedWorkConfiguration(
                jdbc = jdbc,
                organizationId = ids.organization,
                actorUserId = ids.admin,
                at = clock.instant(),
            )
            val workService =
                WorkService(
                    projects = JdbcProjectsPersistence(jdbc),
                    work = JdbcWorkPersistence(jdbc),
                    authorization = projectAuthorization,
                    projection =
                        WorkAuthorizationProjectionBridge(
                            writer,
                        ),
                    idempotency =
                        JdbcIdempotentCommandExecutor(
                            jdbc = jdbc,
                            transactionManager = txManager,
                            clock = clock,
                            telemetry = telemetry,
                        ),
                    audit = JdbcAuditEventWriter(jdbc),
                    events =
                        ApplicationEventPublisher { event ->
                            published += event
                        },
                    clock = clock,
                )

            val readyProject =
                requireNotNull(
                    JdbcProjectsPersistence(jdbc)
                        .project(
                            ready.plan.project.projectId,
                            clock.instant(),
                        ),
                )
            assertTrue(
                projectAuthorization.canCreateWork(
                    ids.teamUser,
                    readyProject,
                ),
                "Current Project manager Team membership must grant can_create_work.",
            )
            assertFalse(
                projectAuthorization.canCreateWork(
                    ids.ordinaryMember,
                    readyProject,
                ),
                "Plain organization membership must not grant can_create_work.",
            )

            val deniedWork =
                assertThrows<ProductApiException> {
                    workService.create(
                        CreateWorkOrderCommand(
                            operationId = UUID.randomUUID(),
                            projectId = readyProject.projectId,
                            siteId = site.site.siteId,
                            projectSiteId =
                                attached.projectSite.projectSiteId,
                            areaId = area.targetId,
                            workPackageId = workPackage.targetId,
                            explicitCode = null,
                            workTypeCode = "INSTALL",
                            workTypeRevision = 1,
                            title = "Denied work",
                            description = null,
                            plannedStart = null,
                            plannedEnd = null,
                            priorityCode = null,
                            baseProjectVersion = readyProject.version,
                            expectedBaselineVersion =
                                readyProject.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.ordinaryMember,
                            correlationId = "corr-work-denied",
                        ),
                    )
                }
            assertEquals("OBJECT_NOT_VISIBLE", deniedWork.code)

            val manualCodeDenied =
                assertThrows<ProductApiException> {
                    workService.create(
                        CreateWorkOrderCommand(
                            operationId = UUID.randomUUID(),
                            projectId = readyProject.projectId,
                            siteId = site.site.siteId,
                            projectSiteId =
                                attached.projectSite.projectSiteId,
                            areaId = area.targetId,
                            workPackageId = workPackage.targetId,
                            explicitCode = "MANUAL-001",
                            workTypeCode = "INSTALL",
                            workTypeRevision = 1,
                            title = "Manual code denied",
                            description = null,
                            plannedStart = null,
                            plannedEnd = null,
                            priorityCode = null,
                            baseProjectVersion = readyProject.version,
                            expectedBaselineVersion =
                                readyProject.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.teamUser,
                            correlationId = "corr-work-manual-code",
                        ),
                    )
                }
            assertEquals(
                "MANUAL_CODE_NOT_ALLOWED",
                manualCodeDenied.code,
            )

            val invalidWorkContext =
                assertThrows<ProductApiException> {
                    workService.create(
                        CreateWorkOrderCommand(
                            operationId = UUID.randomUUID(),
                            projectId = readyProject.projectId,
                            siteId = otherClientSite.site.siteId,
                            projectSiteId =
                                attached.projectSite.projectSiteId,
                            areaId = area.targetId,
                            workPackageId = workPackage.targetId,
                            explicitCode = null,
                            workTypeCode = "INSTALL",
                            workTypeRevision = 1,
                            title = "Invalid WorkOrder context",
                            description = null,
                            plannedStart = null,
                            plannedEnd = null,
                            priorityCode = null,
                            baseProjectVersion = readyProject.version,
                            expectedBaselineVersion =
                                readyProject.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.teamUser,
                            correlationId =
                                "corr-work-context-invalid",
                        ),
                    )
                }
            assertEquals(
                "WORK_CONTEXT_INVALID",
                invalidWorkContext.code,
            )

            val firstCreateOperation = UUID.randomUUID()
            val firstCreateCommand =
                CreateWorkOrderCommand(
                    operationId = firstCreateOperation,
                    projectId = readyProject.projectId,
                    siteId = site.site.siteId,
                    projectSiteId =
                        attached.projectSite.projectSiteId,
                    areaId = area.targetId,
                    workPackageId = workPackage.targetId,
                    explicitCode = null,
                    workTypeCode = "INSTALL",
                    workTypeRevision = 1,
                    title = "Install backbone rack",
                    description =
                        "Authoritative Slice 03 planning fixture",
                    plannedStart = clock.instant(),
                    plannedEnd =
                        clock.instant().plusSeconds(7200),
                    priorityCode = null,
                    baseProjectVersion = readyProject.version,
                    expectedBaselineVersion =
                        readyProject.baselineVersion,
                    clientOccurredAt = clock.instant(),
                    actorUserId = ids.teamUser,
                    correlationId = "corr-work-create-1",
                )
            val firstCreated =
                workService.create(firstCreateCommand)
            assertEquals(
                WorkOrderLifecycle.DRAFT,
                firstCreated.workOrder.lifecycleState,
            )
            assertEquals(
                WorkReadiness.NOT_EVALUATED,
                firstCreated.workOrder.readinessState,
            )
            assertEquals(
                "WO-2026-001",
                firstCreated.workOrder.workOrderCode,
            )
            val firstReplay =
                workService.create(firstCreateCommand)
            assertTrue(firstReplay.replayed)
            assertEquals(
                firstCreated.workOrder.workOrderId,
                firstReplay.workOrder.workOrderId,
            )

            val firstUpdated =
                workService.update(
                    UpdateWorkOrderDetailsCommand(
                        operationId = UUID.randomUUID(),
                        workOrderId =
                            firstCreated.workOrder.workOrderId,
                        areaId = area.targetId,
                        workPackageId = workPackage.targetId,
                        title = "Install backbone rack — planned",
                        description =
                            "Authoritative Slice 03 planning fixture updated",
                        plannedStart = firstCreated.workOrder.plannedStart,
                        plannedEnd = firstCreated.workOrder.plannedEnd,
                        priorityCode = firstCreated.workOrder.priorityCode,
                        baseVersion = firstCreated.workOrder.version,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.teamUser,
                        correlationId = "corr-work-update-1",
                    ),
                )
            assertEquals(
                "Install backbone rack — planned",
                firstUpdated.workOrder.title,
            )
            val staleUpdate =
                assertThrows<ProductApiException> {
                    workService.update(
                        UpdateWorkOrderDetailsCommand(
                            operationId = UUID.randomUUID(),
                            workOrderId =
                                firstUpdated.workOrder.workOrderId,
                            areaId = area.targetId,
                            workPackageId = workPackage.targetId,
                            title = "Stale update",
                            description = null,
                            plannedStart =
                                firstUpdated.workOrder.plannedStart,
                            plannedEnd =
                                firstUpdated.workOrder.plannedEnd,
                            priorityCode =
                                firstUpdated.workOrder.priorityCode,
                            baseVersion =
                                firstCreated.workOrder.version,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.teamUser,
                            correlationId =
                                "corr-work-update-stale",
                        ),
                    )
                }
            assertEquals("VERSION_CONFLICT", staleUpdate.code)

            val unauthorizedPlan =
                assertThrows<ProductApiException> {
                    workService.plan(
                        PlanWorkCommand(
                            operationId = UUID.randomUUID(),
                            workOrderId =
                                firstUpdated.workOrder.workOrderId,
                            workTypeCode = "INSTALL",
                            workTypeRevision = 1,
                            payloadSchemaVersion = 1,
                            structuredInstructionJson = null,
                            instructionSummary = null,
                            baseVersion =
                                firstUpdated.workOrder.version,
                            expectedBaselineVersion =
                                readyProject.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.ordinaryMember,
                            correlationId =
                                "corr-work-plan-denied",
                        ),
                    )
                }
            assertEquals(
                "OBJECT_NOT_VISIBLE",
                unauthorizedPlan.code,
            )

            val stalePlan =
                assertThrows<ProductApiException> {
                    workService.plan(
                        PlanWorkCommand(
                            operationId = UUID.randomUUID(),
                            workOrderId =
                                firstUpdated.workOrder.workOrderId,
                            workTypeCode = "INSTALL",
                            workTypeRevision = 1,
                            payloadSchemaVersion = 1,
                            structuredInstructionJson = null,
                            instructionSummary = null,
                            baseVersion =
                                firstUpdated.workOrder.version + 1,
                            expectedBaselineVersion =
                                readyProject.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.teamUser,
                            correlationId =
                                "corr-work-plan-stale",
                        ),
                    )
                }
            assertEquals("VERSION_CONFLICT", stalePlan.code)

            val firstPlanOperation = UUID.randomUUID()
            val firstPlanCommand =
                PlanWorkCommand(
                    operationId = firstPlanOperation,
                    workOrderId =
                        firstUpdated.workOrder.workOrderId,
                    workTypeCode = "INSTALL",
                    workTypeRevision = 1,
                    payloadSchemaVersion = 1,
                    structuredInstructionJson = null,
                    instructionSummary =
                        "Use the bound installation instruction",
                    baseVersion =
                        firstUpdated.workOrder.version,
                    expectedBaselineVersion =
                        readyProject.baselineVersion,
                    clientOccurredAt = clock.instant(),
                    actorUserId = ids.teamUser,
                    correlationId = "corr-work-plan-1",
                )
            val firstPlanned =
                workService.plan(firstPlanCommand)
            assertEquals(
                WorkOrderLifecycle.PLANNED,
                firstPlanned.workOrder.lifecycleState,
            )
            assertEquals(
                WorkReadiness.NOT_EVALUATED,
                firstPlanned.workOrder.readinessState,
            )
            assertEquals(
                1,
                firstPlanned.workOrder.binding?.workType?.revision,
            )
            assertEquals(
                1,
                firstPlanned.workOrder.instruction?.revision,
            )
            assertEquals(
                setOf(
                    "READINESS",
                    "EVIDENCE",
                    "ASSET",
                    "MATERIAL",
                    "DOCUMENT",
                ),
                firstPlanned.workOrder.requirements
                    .map { it.family }
                    .toSet(),
            )
            assertTrue(
                firstPlanned.workOrder.requirements
                    .all {
                        it.satisfactionState == "PENDING"
                    },
            )
            assertEquals(
                1,
                firstPlanned.workOrder.checklist.size,
            )
            val firstPlanReplay =
                workService.plan(firstPlanCommand)
            assertTrue(firstPlanReplay.replayed)
            assertEquals(
                1,
                firstPlanReplay.workOrder.checklist.size,
            )
            assertEquals(
                5,
                firstPlanReplay.workOrder.requirements.size,
            )

            val revised =
                workService.revise(
                    ReviseWorkInstructionCommand(
                        operationId = UUID.randomUUID(),
                        workOrderId =
                            firstPlanned.workOrder.workOrderId,
                        payloadSchemaVersion = 1,
                        structuredInstructionJson =
                            """{"steps":["verify","install","label"]}""",
                        summary = "Revision two",
                        changeReason = "Site sequencing clarified",
                        baseVersion =
                            firstPlanned.workOrder.version,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.teamUser,
                        correlationId =
                            "corr-work-instruction-r2",
                    ),
                )
            assertEquals(
                2,
                revised.workOrder.instruction?.revision,
            )
            assertEquals(
                2,
                jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM work_instruction_revision
                    WHERE work_order_id = ?
                    """.trimIndent(),
                    Int::class.java,
                    revised.workOrder.workOrderId,
                ),
            )

            val withTask =
                workService.createTask(
                    CreateWorkTaskCommand(
                        operationId = UUID.randomUUID(),
                        workOrderId =
                            revised.workOrder.workOrderId,
                        taskCode = "T-01",
                        title = "Verify rack position",
                        description = null,
                        sortOrder = 10,
                        mandatory = true,
                        estimatedDurationMinutes = 20,
                        evidenceRequirementKey = "PHOTO-FINAL",
                        baseVersion = revised.workOrder.version,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.teamUser,
                        correlationId = "corr-work-task",
                    ),
                )
            assertEquals(
                1,
                withTask.workOrder.tasks.size,
            )
            assertEquals(
                "PLANNED",
                withTask.workOrder.tasks.single().state.name,
            )

            val taskBeforeUpdate = withTask.workOrder.tasks.single()
            val taskUpdateOperation = UUID.randomUUID()
            val taskUpdateCommand =
                UpdateWorkTaskCommand(
                    operationId = taskUpdateOperation,
                    taskId = withTask.taskId,
                    taskCode = "T-01",
                    title = "Verify and label rack position",
                    description = "Planning-only task update",
                    sortOrder = 20,
                    mandatory = true,
                    estimatedDurationMinutes = 25,
                    evidenceRequirementKey = "PHOTO-FINAL",
                    state = WorkTaskState.PLANNED,
                    baseTaskVersion = taskBeforeUpdate.version,
                    baseWorkOrderVersion = withTask.workOrder.version,
                    clientOccurredAt = clock.instant(),
                    actorUserId = ids.teamUser,
                    correlationId = "corr-work-task-update",
                )
            val withUpdatedTask =
                workService.updateTask(taskUpdateCommand)
            assertEquals(
                "Verify and label rack position",
                withUpdatedTask.workOrder.tasks.single().title,
            )
            assertEquals(
                taskBeforeUpdate.version + 1,
                withUpdatedTask.workOrder.tasks.single().version,
            )
            val taskUpdateReplay =
                workService.updateTask(taskUpdateCommand)
            assertTrue(taskUpdateReplay.replayed)
            assertEquals(
                withUpdatedTask.workOrder.version,
                taskUpdateReplay.workOrder.version,
            )

            val projectForSecond =
                requireNotNull(
                    JdbcProjectsPersistence(jdbc)
                        .project(
                            readyProject.projectId,
                            clock.instant(),
                        ),
                )
            val secondCreated =
                workService.create(
                    CreateWorkOrderCommand(
                        operationId = UUID.randomUUID(),
                        projectId = projectForSecond.projectId,
                        siteId = site.site.siteId,
                        projectSiteId =
                            attached.projectSite.projectSiteId,
                        areaId = area.targetId,
                        workPackageId = workPackage.targetId,
                        explicitCode = null,
                        workTypeCode = "INSTALL",
                        workTypeRevision = 1,
                        title = "Terminate backbone",
                        description = null,
                        plannedStart = null,
                        plannedEnd = null,
                        priorityCode = "NORMAL",
                        baseProjectVersion =
                            projectForSecond.version,
                        expectedBaselineVersion =
                            projectForSecond.baselineVersion,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.teamUser,
                        correlationId = "corr-work-create-2",
                    ),
                )
            assertEquals(
                "WO-2026-002",
                secondCreated.workOrder.workOrderCode,
            )

            val blockedProject =
                requireNotNull(
                    JdbcProjectsPersistence(jdbc)
                        .project(
                            readyProject.projectId,
                            clock.instant(),
                        ),
                )
            val blockedActivation =
                assertThrows<ProductApiException> {
                    workService.activate(
                        ActivateProjectCommand(
                            operationId = UUID.randomUUID(),
                            projectId =
                                blockedProject.projectId,
                            baseVersion =
                                blockedProject.version,
                            expectedBaselineVersion =
                                blockedProject.baselineVersion,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.teamUser,
                            correlationId =
                                "corr-project-activate-blocked",
                        ),
                    )
                }
            assertEquals(
                "PROJECT_ACTIVATION_BLOCKED",
                blockedActivation.code,
            )

            val secondPlanned =
                workService.plan(
                    PlanWorkCommand(
                        operationId = UUID.randomUUID(),
                        workOrderId =
                            secondCreated.workOrder.workOrderId,
                        workTypeCode = "INSTALL",
                        workTypeRevision = 1,
                        payloadSchemaVersion = 1,
                        structuredInstructionJson = null,
                        instructionSummary = "Second order",
                        baseVersion =
                            secondCreated.workOrder.version,
                        expectedBaselineVersion =
                            blockedProject.baselineVersion,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.teamUser,
                        correlationId = "corr-work-plan-2",
                    ),
                )

            val linked =
                workService.addDependency(
                    AddWorkDependencyCommand(
                        operationId = UUID.randomUUID(),
                        workOrderId =
                            secondPlanned.workOrder.workOrderId,
                        predecessorWorkOrderId =
                            withTask.workOrder.workOrderId,
                        type =
                            WorkDependencyType.FINISH_TO_START,
                        lagMinutes = 30,
                        baseVersion =
                            secondPlanned.workOrder.version,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.teamUser,
                        correlationId =
                            "corr-work-dependency",
                    ),
                )
            assertEquals(
                1,
                linked.workOrder.dependencies
                    .count {
                        it.successorWorkOrderId ==
                            linked.workOrder.workOrderId
                    },
            )

            val latestFirst =
                workService.get(
                    ids.teamUser,
                    withTask.workOrder.workOrderId,
                )
            val cycleRejected =
                assertThrows<ProductApiException> {
                    workService.addDependency(
                        AddWorkDependencyCommand(
                            operationId = UUID.randomUUID(),
                            workOrderId =
                                latestFirst.workOrderId,
                            predecessorWorkOrderId =
                                linked.workOrder.workOrderId,
                            type =
                                WorkDependencyType.FINISH_TO_START,
                            lagMinutes = 0,
                            baseVersion = latestFirst.version,
                            clientOccurredAt = clock.instant(),
                            actorUserId = ids.teamUser,
                            correlationId =
                                "corr-work-cycle",
                        ),
                    )
                }
            assertEquals(
                "WORK_DEPENDENCY_CYCLE",
                cycleRejected.code,
            )

            val linkedDependency =
                linked.workOrder.dependencies.single {
                    it.dependencyId == linked.dependencyId
                }
            val removeDependencyOperation = UUID.randomUUID()
            val removeDependencyCommand =
                RemoveWorkDependencyCommand(
                    operationId = removeDependencyOperation,
                    workOrderId = linked.workOrder.workOrderId,
                    dependencyId = linked.dependencyId,
                    baseVersion = linked.workOrder.version,
                    baseDependencyVersion = linkedDependency.version,
                    clientOccurredAt = clock.instant(),
                    actorUserId = ids.teamUser,
                    correlationId = "corr-work-dependency-remove",
                )
            val unlinked =
                workService.removeDependency(removeDependencyCommand)
            assertEquals(
                0,
                unlinked.workOrder.dependencies.count {
                    it.successorWorkOrderId ==
                        unlinked.workOrder.workOrderId
                },
            )
            val removeDependencyReplay =
                workService.removeDependency(removeDependencyCommand)
            assertTrue(removeDependencyReplay.replayed)
            assertEquals(
                unlinked.workOrder.version,
                removeDependencyReplay.workOrder.version,
            )

            drain(
                processor,
                clock.instant(),
            )

            val projectForActivation =
                requireNotNull(
                    JdbcProjectsPersistence(jdbc)
                        .project(
                            readyProject.projectId,
                            clock.instant(),
                        ),
                )
            val activated =
                workService.activate(
                    ActivateProjectCommand(
                        operationId = UUID.randomUUID(),
                        projectId =
                            projectForActivation.projectId,
                        baseVersion =
                            projectForActivation.version,
                        expectedBaselineVersion =
                            projectForActivation.baselineVersion,
                        clientOccurredAt = clock.instant(),
                        actorUserId = ids.teamUser,
                        correlationId =
                            "corr-project-activate",
                    ),
                )
            assertEquals(
                ProjectLifecycleState.ACTIVE.name,
                activated.lifecycleState,
            )
            assertEquals(
                1,
                jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM work_policy_binding
                    WHERE work_order_id = ?
                      AND superseded_at IS NULL
                    """.trimIndent(),
                    Int::class.java,
                    firstPlanned.workOrder.workOrderId,
                ),
            )
            assertTrue(
                auditCount(
                    jdbc,
                    "WORK_ORDER_PLANNED",
                    firstPlanned.workOrder.workOrderId,
                ) >= 1,
            )
            assertTrue(
                auditCount(
                    jdbc,
                    "PROJECT_ACTIVATED",
                    readyProject.projectId,
                ) >= 1,
            )
            assertTrue(
                auditCount(
                    jdbc,
                    "WORK_TASK_UPDATED",
                    withTask.workOrder.workOrderId,
                ) >= 1,
            )
            assertTrue(
                auditCount(
                    jdbc,
                    "WORK_DEPENDENCY_REMOVED",
                    linked.workOrder.workOrderId,
                ) >= 1,
            )

            // Slice 04 extends the exact Slice 03 WorkOrder/history fixture.
            // First prove Phase-5 resource/document sources remain honestly
            // blocked. Then isolate the ASSIGNEE readiness source to prove
            // pre-assignment eligibility, AUTO exactly-one assignment,
            // fail-closed projection and reassignment history.
            jdbc.update(
                """
                INSERT INTO workforce_assignment (
                    id,
                    organization_id,
                    employee_id,
                    team_id,
                    role_code,
                    role_label,
                    reports_to_employee_id,
                    state,
                    effective_from,
                    effective_to,
                    created_at,
                    updated_at,
                    version
                )
                VALUES (
                    ?, ?, ?, NULL,
                    'TECHNICIAN',
                    'Technician',
                    NULL,
                    'ACTIVE',
                    ?, NULL,
                    ?, ?,
                    1
                )
                """.trimIndent(),
                UUID.randomUUID(),
                ids.organization,
                ids.employee,
                clock.instant()
                    .minusSeconds(300)
                    .atOffset(ZoneOffset.UTC),
                clock.instant()
                    .minusSeconds(300)
                    .atOffset(ZoneOffset.UTC),
                clock.instant()
                    .minusSeconds(300)
                    .atOffset(ZoneOffset.UTC),
            )

            val readinessPersistence =
                JdbcReadinessAssignmentPersistence(
                    jdbc,
                )
            val readinessService =
                ReadinessAssignmentService(
                    projects =
                        JdbcProjectsPersistence(jdbc),
                    work =
                        JdbcWorkPersistence(jdbc),
                    persistence =
                        readinessPersistence,
                    eligibility =
                        WorkEligibilityResolver(
                            people =
                                JdbcPeoplePersistence(
                                    jdbc,
                                ),
                            workforce =
                                JdbcWorkforceAssignmentPersistence(
                                    jdbc,
                                ),
                            hr =
                                JdbcHrDocumentsPersistence(
                                    jdbc,
                                ),
                            sourceAuthority =
                                sourceAuthority,
                            jdbc = jdbc,
                        ),
                    authorization =
                        projectAuthorization,
                    projection =
                        WorkAssignmentAuthorizationProjectionBridge(
                            writer,
                            jdbc,
                        ),
                    idempotency =
                        JdbcIdempotentCommandExecutor(
                            jdbc = jdbc,
                            transactionManager =
                                txManager,
                            clock = clock,
                            telemetry = telemetry,
                        ),
                    audit =
                        JdbcAuditEventWriter(jdbc),
                    events =
                        ApplicationEventPublisher { event ->
                            published += event
                        },
                    clock = clock,
                )

            val firstSlice04Order =
                workService.get(
                    ids.teamUser,
                    firstPlanned.workOrder.workOrderId,
                )
            val blockedReadiness =
                readinessService.evaluate(
                    EvaluateReadinessCommand(
                        operationId =
                            UUID.randomUUID(),
                        workOrderId =
                            firstSlice04Order.workOrderId,
                        baseVersion =
                            firstSlice04Order.version,
                        clientOccurredAt =
                            clock.instant(),
                        actorUserId =
                            ids.teamUser,
                        correlationId =
                            "corr-slice04-readiness-blocked",
                    ),
                )
            assertEquals(
                WorkReadiness.BLOCKED,
                blockedReadiness.readiness.readinessState,
            )
            assertTrue(
                blockedReadiness.readiness.requirements
                    .any {
                        it.required &&
                            it.typeCode == "MATERIAL" &&
                            it.satisfactionState ==
                                "BLOCKED" &&
                            it.evaluationReasonCode ==
                                "PHASE5_RESOURCE_SOURCE_UNAVAILABLE"
                    },
                "Material truth must remain blocked until Phase 5.",
            )
            assertTrue(
                blockedReadiness.readiness.requirements
                    .any {
                        it.required &&
                            it.typeCode ==
                                "ASSET_TOOL" &&
                            it.satisfactionState ==
                                "BLOCKED" &&
                            it.evaluationReasonCode ==
                                "PHASE5_RESOURCE_SOURCE_UNAVAILABLE"
                    },
                "Asset/tool truth must remain blocked until Phase 5.",
            )
            assertTrue(
                blockedReadiness.readiness.requirements
                    .any {
                        it.required &&
                            it.typeCode ==
                                "DRAWING_REVISION" &&
                            it.satisfactionState ==
                                "BLOCKED"
                    },
                "Document readiness must not be fabricated.",
            )

            val readinessPolicyId =
                requireNotNull(
                    blockedReadiness.readiness
                        .requirements
                        .firstOrNull {
                            it.family ==
                                "READINESS"
                        },
                ).let {
                    requireNotNull(
                        jdbc.queryForObject(
                            """
                            SELECT source_config_id
                            FROM work_requirement_instance
                            WHERE id = ?
                            """.trimIndent(),
                            UUID::class.java,
                            it.requirementId,
                        ),
                    )
                }

            jdbc.update(
                """
                UPDATE readiness_policy_requirement
                SET requirement_type_code = 'ASSIGNEE'
                WHERE config_revision_id = ?
                  AND requirement_key = 'SITE-ACCESS'
                """.trimIndent(),
                readinessPolicyId,
            )
            jdbc.update(
                """
                UPDATE work_requirement_instance
                SET requirement_type_code =
                        CASE
                            WHEN requirement_family = 'READINESS'
                                THEN 'ASSIGNEE'
                            ELSE requirement_type_code
                        END,
                    required =
                        CASE
                            WHEN requirement_family IN (
                                'ASSET',
                                'MATERIAL',
                                'DOCUMENT'
                            )
                                THEN false
                            ELSE required
                        END,
                    satisfaction_state = 'PENDING',
                    evaluation_reason_code = NULL,
                    source_as_of = NULL,
                    evaluated_at = NULL,
                    updated_at = ?,
                    version = version + 1
                WHERE work_order_id = ?
                  AND requirement_family IN (
                      'READINESS',
                      'ASSET',
                      'MATERIAL',
                      'DOCUMENT'
                  )
                """.trimIndent(),
                clock.instant()
                    .atOffset(ZoneOffset.UTC),
                firstSlice04Order.workOrderId,
            )

            val afterBlocked =
                workService.get(
                    ids.teamUser,
                    firstSlice04Order.workOrderId,
                )
            val readyForAssignment =
                readinessService.evaluate(
                    EvaluateReadinessCommand(
                        operationId =
                            UUID.randomUUID(),
                        workOrderId =
                            afterBlocked.workOrderId,
                        baseVersion =
                            afterBlocked.version,
                        clientOccurredAt =
                            clock.instant(),
                        actorUserId =
                            ids.teamUser,
                        correlationId =
                            "corr-slice04-readiness-ready",
                    ),
                )
            assertEquals(
                WorkReadiness.READY,
                readyForAssignment.readiness.readinessState,
            )
            assertEquals(
                1,
                readyForAssignment.readiness
                    .eligibleTargets
                    .count { it.eligible },
                "AUTO fixture must have exactly one eligible target before assignment.",
            )
            assertEquals(
                ids.oldPm,
                readyForAssignment.readiness
                    .eligibleTargets
                    .single { it.eligible }
                    .targetId,
            )

            val deniedAssign =
                assertThrows<ProductApiException> {
                    readinessService.assign(
                        AssignWorkCommand(
                            operationId =
                                UUID.randomUUID(),
                            workOrderId =
                                firstSlice04Order.workOrderId,
                            targetType = null,
                            targetId = null,
                            baseVersion =
                                readyForAssignment.readiness
                                    .workOrderVersion,
                            clientOccurredAt =
                                clock.instant(),
                            actorUserId =
                                ids.ordinaryMember,
                            correlationId =
                                "corr-slice04-assign-denied",
                        ),
                    )
                }
            assertEquals(
                "OBJECT_NOT_VISIBLE",
                deniedAssign.code,
            )

            val assignOperation =
                UUID.randomUUID()
            val assignCommand =
                AssignWorkCommand(
                    operationId =
                        assignOperation,
                    workOrderId =
                        firstSlice04Order.workOrderId,
                    targetType = null,
                    targetId = null,
                    baseVersion =
                        readyForAssignment.readiness
                            .workOrderVersion,
                    clientOccurredAt =
                        clock.instant(),
                    actorUserId =
                        ids.teamUser,
                    correlationId =
                        "corr-slice04-assign",
                )
            val assigned =
                readinessService.assign(
                    assignCommand,
                )
            assertEquals(
                WorkOrderLifecycle.ASSIGNED,
                assigned.readiness.lifecycleState,
            )
            assertEquals(
                ids.oldPm,
                assigned.readiness
                    .currentAssignment
                    ?.targetId,
            )
            val assignReplay =
                readinessService.assign(
                    assignCommand,
                )
            assertTrue(assignReplay.replayed)
            assertEquals(
                assigned.assignmentId,
                assignReplay.assignmentId,
            )

            val assignedAuthorization =
                SpringWorkAssignmentAuthorization(
                    authorizationProvider =
                        provider,
                    source =
                        JdbcWorkAssignmentSourceAuthority(
                            jdbc,
                        ),
                    clock = clock,
                )
            assertFalse(
                assignedAuthorization.canUseAssignedWork(
                    ids.oldPm,
                    firstSlice04Order.workOrderId,
                    "can_start",
                ),
                "Pending assignment grant must fail closed until projection is APPLIED.",
            )
            drain(
                processor,
                clock.instant(),
            )
            assertTrue(
                assignedAuthorization.canUseAssignedWork(
                    ids.oldPm,
                    firstSlice04Order.workOrderId,
                    "can_start",
                ),
                "Projected current USER assignment must authorize the assigned user.",
            )

            jdbc.update(
                """
                UPDATE workforce_assignment
                SET state = 'ENDED',
                    effective_to = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE employee_id = ?
                  AND state = 'ACTIVE'
                """.trimIndent(),
                clock.instant()
                    .atOffset(ZoneOffset.UTC),
                clock.instant()
                    .atOffset(ZoneOffset.UTC),
                ids.employee,
            )
            val assignedCurrent =
                workService.get(
                    ids.teamUser,
                    firstSlice04Order.workOrderId,
                )
            val lostEligibility =
                readinessService.evaluate(
                    EvaluateReadinessCommand(
                        operationId =
                            UUID.randomUUID(),
                        workOrderId =
                            assignedCurrent.workOrderId,
                        baseVersion =
                            assignedCurrent.version,
                        clientOccurredAt =
                            clock.instant(),
                        actorUserId =
                            ids.teamUser,
                        correlationId =
                            "corr-slice04-assignee-lost",
                    ),
                )
            assertEquals(
                WorkReadiness.BLOCKED,
                lostEligibility.readiness.readinessState,
            )
            assertTrue(
                lostEligibility.readiness.requirements
                    .any {
                        it.typeCode == "ASSIGNEE" &&
                            it.satisfactionState ==
                                "BLOCKED"
                    },
            )

            val subcontractor =
                UUID.randomUUID()
            insertOrganization(
                jdbc,
                subcontractor,
                "SUBCONTRACTOR-A",
                "Subcontractor A",
                "SUBCONTRACTOR",
                clock.instant(),
            )
            val currentAssignment =
                requireNotNull(
                    lostEligibility.readiness
                        .currentAssignment,
                )
            val reassigned =
                readinessService.reassign(
                    ReassignWorkCommand(
                        operationId =
                            UUID.randomUUID(),
                        workOrderId =
                            firstSlice04Order.workOrderId,
                        targetType =
                            AssignmentTargetType
                                .SUBCONTRACTOR_ORGANIZATION,
                        targetId = subcontractor,
                        baseVersion =
                            lostEligibility.readiness
                                .workOrderVersion,
                        currentAssignmentId =
                            currentAssignment.assignmentId,
                        baseAssignmentVersion =
                            currentAssignment.version,
                        reason =
                            "Current USER target lost eligibility",
                        clientOccurredAt =
                            clock.instant(),
                        actorUserId =
                            ids.teamUser,
                        correlationId =
                            "corr-slice04-reassign",
                    ),
                )
            assertEquals(
                subcontractor,
                reassigned.readiness
                    .currentAssignment
                    ?.targetId,
            )
            assertEquals(
                2,
                reassigned.readiness
                    .assignmentHistory.size,
            )
            assertTrue(
                reassigned.readiness
                    .assignmentHistory
                    .any {
                        it.assignmentId ==
                            currentAssignment.assignmentId &&
                            it.state ==
                                "REPLACED"
                    },
            )
            assertFalse(
                assignedAuthorization.canUseAssignedWork(
                    ids.oldPm,
                    firstSlice04Order.workOrderId,
                    "can_start",
                ),
                "Replaced assignment must deny immediately even while its old FGA tuple is stale.",
            )

            val afterReassign =
                workService.get(
                    ids.teamUser,
                    firstSlice04Order.workOrderId,
                )
            val reassignedReady =
                readinessService.evaluate(
                    EvaluateReadinessCommand(
                        operationId =
                            UUID.randomUUID(),
                        workOrderId =
                            afterReassign.workOrderId,
                        baseVersion =
                            afterReassign.version,
                        clientOccurredAt =
                            clock.instant(),
                        actorUserId =
                            ids.teamUser,
                        correlationId =
                            "corr-slice04-reassign-ready",
                    ),
                )
            assertEquals(
                WorkReadiness.READY,
                reassignedReady.readiness.readinessState,
                "A current eligible replacement target must satisfy ASSIGNEE readiness.",
            )
            assertTrue(
                auditCount(
                    jdbc,
                    "WORK_READINESS_EVALUATED",
                    firstSlice04Order.workOrderId,
                ) >= 3,
            )
            assertTrue(
                auditCount(
                    jdbc,
                    "WORK_ASSIGNED",
                    firstSlice04Order.workOrderId,
                ) >= 1,
            )
            assertTrue(
                auditCount(
                    jdbc,
                    "WORK_REASSIGNED",
                    firstSlice04Order.workOrderId,
                ) >= 1,
            )

            jdbc.update(
                """
                UPDATE config_revision
                SET lifecycle_state = 'SUPERSEDED',
                    effective_to = ?
                WHERE family = 'work-types'
                  AND code = 'INSTALL'
                  AND scope_organization_id = ?
                """.trimIndent(),
                clock.instant()
                    .plusSeconds(60)
                    .atOffset(ZoneOffset.UTC),
                ids.organization,
            )
            val historical =
                workService.get(
                    ids.teamUser,
                    firstPlanned.workOrder.workOrderId,
                )
            assertEquals(
                1,
                historical.binding?.workType?.revision,
                "Superseding active configuration must not rewrite historical WorkPolicyBinding.",
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

    private fun seedWorkConfiguration(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        actorUserId: UUID,
        at: Instant,
    ) {
        fun revision(
            family: String,
            code: String,
            name: String,
        ): UUID {
            val id = UUID.randomUUID()
            val timestamp = at.atOffset(ZoneOffset.UTC)
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
                    ?, ?, ?,
                    'ACTIVE',
                    1,
                    ?,
                    ?, ?,
                    ?, ?,
                    1
                )
                """.trimIndent(),
                id,
                organizationId,
                family,
                code,
                name,
                timestamp,
                timestamp,
                actorUserId,
                timestamp,
                actorUserId,
            )
            return id
        }

        fun template(
            code: String,
            type: String,
            json: String,
        ): UUID {
            val id =
                revision(
                    "templates",
                    code,
                    "$code template",
                )
            jdbc.update(
                """
                INSERT INTO template_definition (
                    config_revision_id,
                    template_type,
                    schema_version,
                    structured_definition
                )
                VALUES (?, ?, 1, ?::jsonb)
                """.trimIndent(),
                id,
                type,
                json,
            )
            return id
        }

        val codePolicy =
            revision(
                "code-policies",
                "WORK_ORDER",
                "Work order codes",
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
                'WORK_ORDER',
                'WO',
                true,
                '-',
                'PROJECT',
                3,
                false,
                'PROJECT',
                'YEARLY'
            )
            """.trimIndent(),
            codePolicy,
        )

        val assignment =
            revision(
                "assignment-policies",
                "INSTALL-ASSIGN",
                "Install assignment",
            )
        jdbc.update(
            """
            INSERT INTO assignment_policy (
                config_revision_id,
                allowed_target_types,
                execution_mode,
                allow_external_subcontractor
            )
            VALUES (
                ?,
                ARRAY['USER','TEAM','SUBCONTRACTOR_ORGANIZATION']::varchar[],
                'AUTO',
                true
            )
            """.trimIndent(),
            assignment,
        )

        val readiness =
            revision(
                "readiness-policies",
                "INSTALL-READY",
                "Install readiness",
            )
        jdbc.update(
            """
            INSERT INTO readiness_policy (
                config_revision_id
            )
            VALUES (?)
            """.trimIndent(),
            readiness,
        )
        jdbc.update(
            """
            INSERT INTO readiness_policy_requirement (
                id,
                config_revision_id,
                requirement_key,
                requirement_type_code,
                label,
                required,
                sort_order
            )
            VALUES (
                ?, ?,
                'SITE-ACCESS',
                'SITE_ACCESS',
                'Site access confirmed',
                true,
                10
            )
            """.trimIndent(),
            UUID.randomUUID(),
            readiness,
        )

        val evidence =
            revision(
                "evidence-policies",
                "INSTALL-EVIDENCE",
                "Install evidence",
            )
        jdbc.update(
            """
            INSERT INTO evidence_policy (
                config_revision_id
            )
            VALUES (?)
            """.trimIndent(),
            evidence,
        )
        jdbc.update(
            """
            INSERT INTO evidence_policy_requirement (
                id,
                config_revision_id,
                requirement_key,
                evidence_type_code,
                stage,
                min_count,
                classification_code,
                client_visibility_mode,
                security_scan_class
            )
            VALUES (
                ?, ?,
                'PHOTO-FINAL',
                'PHOTO',
                'BEFORE_SUBMIT',
                1,
                'INTERNAL',
                'INTERNAL_ONLY',
                'NATIVE_MEDIA'
            )
            """.trimIndent(),
            UUID.randomUUID(),
            evidence,
        )

        val review =
            revision(
                "review-policies",
                "INSTALL-REVIEW",
                "Install review",
            )
        jdbc.update(
            """
            INSERT INTO review_policy (
                config_revision_id
            )
            VALUES (?)
            """.trimIndent(),
            review,
        )

        val asset =
            template(
                "INSTALL-ASSET",
                "ASSET_REQUIREMENTS",
                """{"requirements":[{"key":"CALIBRATED-METER","type":"ASSET_TOOL","label":"Calibrated meter","required":true,"sortOrder":10}]}""",
            )
        val material =
            template(
                "INSTALL-MATERIAL",
                "MATERIAL_REQUIREMENTS",
                """{"requirements":[{"key":"PATCH-CORD","type":"MATERIAL","label":"Patch cord","required":true,"sortOrder":10}]}""",
            )
        val document =
            template(
                "INSTALL-DOCUMENT",
                "DOCUMENT_REQUIREMENTS",
                """{"requirements":[{"key":"APPROVED-DRAWING","type":"DRAWING_REVISION","label":"Approved drawing","required":true,"sortOrder":10}]}""",
            )
        val checklist =
            template(
                "INSTALL-CHECKLIST",
                "CHECKLIST",
                """{"items":[{"key":"LABEL","label":"Label rack and ports","required":true,"sortOrder":10,"evidenceRequirementKey":"PHOTO-FINAL"}]}""",
            )
        val instruction =
            template(
                "INSTALL-INSTRUCTION",
                "WORK_INSTRUCTION",
                """{"steps":["verify","install"]}""",
            )

        val workType =
            revision(
                "work-types",
                "INSTALL",
                "Installation",
            )
        jdbc.update(
            """
            INSERT INTO work_type_definition (
                config_revision_id,
                assignment_policy_id,
                readiness_policy_id,
                evidence_policy_id,
                review_policy_id,
                tracking_policy_id,
                asset_requirement_template_id,
                material_requirement_template_id,
                document_requirement_template_id,
                checklist_template_id,
                instruction_template_id,
                completion_policy_id,
                default_priority_code,
                default_progress_weight,
                counts_toward_project_progress
            )
            VALUES (
                ?, ?, ?, ?, ?,
                NULL,
                ?, ?, ?, ?, ?,
                NULL,
                'NORMAL',
                2.0,
                true
            )
            """.trimIndent(),
            workType,
            assignment,
            readiness,
            evidence,
            review,
            asset,
            material,
            document,
            checklist,
            instruction,
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
