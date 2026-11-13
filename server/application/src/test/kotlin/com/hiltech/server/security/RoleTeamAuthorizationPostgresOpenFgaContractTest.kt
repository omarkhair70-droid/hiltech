package com.hiltech.server.security

import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import com.hiltech.server.warehouse.InventoryPrincipalType
import com.hiltech.server.warehouse.JdbcWarehousePersistence
import com.hiltech.server.warehouse.SpringWarehouseAuthorization
import com.hiltech.server.warehouse.StorageLocationAuthorityKey
import com.hiltech.server.warehouse.WarehouseAuthorityKey
import com.hiltech.server.warehouse.WarehouseAuthorizationProjectionBridge
import com.hiltech.server.warehouse.WarehouseAuthorizationRelations
import com.hiltech.server.warehouse.WarehouseManagerProvisioner
import com.hiltech.server.warehouse.WarehouseImportOperatorScenario
import com.hiltech.server.warehouse.StockLedgerOperatorScenario
import com.hiltech.server.warehouse.AssetCustodyMovementPosted
import com.hiltech.server.warehouse.AssetPassportOperatorScenario
import com.hiltech.server.warehouse.AssetPassportPersistence
import com.hiltech.server.warehouse.AssetPassportService
import com.hiltech.server.warehouse.AssetService
import com.hiltech.server.warehouse.AssetServiceChanged
import com.hiltech.server.warehouse.AssetServiceOperatorScenario
import com.hiltech.server.warehouse.AssetServicePersistence
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.warehouse.StockLedgerService
import com.hiltech.server.warehouse.StockMovementPosted
import io.opentelemetry.api.OpenTelemetry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class RoleTeamAuthorizationPostgresOpenFgaContractTest {
    private val enabled =
        System.getenv("HILTECH_ROLE_TEAM_AUTH_CONTRACT_TEST") == "1"
    private val url =
        System.getenv("HILTECH_DB_URL")
            ?: "jdbc:postgresql://localhost:5432/hiltech"
    private val user =
        System.getenv("HILTECH_DB_USER") ?: "hiltech"
    private val password =
        System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
    private val migrationPath =
        System.getenv("HILTECH_MIGRATIONS_PATH")
            ?: "database/migrations"
    private val fgaApiUrl =
        System.getenv("HILTECH_FGA_API_URL") ?: ""
    private val fgaStoreId =
        System.getenv("HILTECH_FGA_STORE_ID") ?: ""
    private val fgaModelId =
        System.getenv("HILTECH_FGA_MODEL_ID") ?: ""

    @Test
    fun structuralAuthorityGrantRevokeAndManagerReplacementStayFailClosed() {
        assumeTrue(enabled)

        Flyway.configure()
            .dataSource(url, user, password)
            .locations("filesystem:$migrationPath")
            .load()
            .migrate()

        val dataSource =
            DriverManagerDataSource(url, user, password)
        val jdbc = JdbcTemplate(dataSource)
        val transactionManager =
            DataSourceTransactionManager(dataSource)
        val transaction = TransactionTemplate(transactionManager)

        val properties = HiltechOpenFgaProperties(
            enabled = true,
            apiUrl = fgaApiUrl,
            storeId = fgaStoreId,
            authorizationModelId = fgaModelId,
        )
        properties.validateEnabledConfiguration()

        val projectionWriter =
            JdbcAuthorizationProjectionIntentWriter(
                jdbc = jdbc,
                properties = properties,
            )
        val bridge =
            JdbcRoleTeamAuthorizationProjectionBridge(
                jdbc = jdbc,
                projectionWriter = projectionWriter,
            )
        val sourceAuthority =
            JdbcRoleTeamSourceAuthority(jdbc)
        val gateway = OpenFgaGateway(
            properties = properties,
            transport = JdkOpenFgaHttpTransport(properties),
        )
        val projectionGuard =
            JdbcAuthorizationProjectionGuard(jdbc)
        val authorization =
            FailClosedAuthorizationAdapter(
                guard = projectionGuard,
                openFgaGateway = gateway,
            )
        val store =
            JdbcAuthorizationProjectionStore(
                jdbc = jdbc,
                transactionManager = transactionManager,
                properties = properties,
                retryPolicy = AuthorizationProjectionRetryPolicy(),
            )
        val processor =
            AuthorizationProjectionProcessor(
                store = store,
                openFga = gateway,
                properties = properties,
            )

        val now = Instant.parse("2026-09-19T05:00:00Z")
        val organizationId = UUID.randomUUID()
        val memberIdentityId = UUID.randomUUID()
        val managerIdentityId = UUID.randomUUID()
        val replacementManagerIdentityId = UUID.randomUUID()
        val memberMembershipId = UUID.randomUUID()
        val managerMembershipId = UUID.randomUUID()
        val replacementMembershipId = UUID.randomUUID()

        seedOrganizationAndIdentities(
            jdbc = jdbc,
            organizationId = organizationId,
            identityIds = listOf(
                memberIdentityId,
                managerIdentityId,
                replacementManagerIdentityId,
            ),
            at = now,
        )

        transaction.executeWithoutResult {
            insertOrganizationMembership(
                jdbc = jdbc,
                membershipId = memberMembershipId,
                organizationId = organizationId,
                identityId = memberIdentityId,
                at = now,
            )
            insertOrganizationMembership(
                jdbc = jdbc,
                membershipId = managerMembershipId,
                organizationId = organizationId,
                identityId = managerIdentityId,
                at = now,
            )
            insertOrganizationMembership(
                jdbc = jdbc,
                membershipId = replacementMembershipId,
                organizationId = organizationId,
                identityId = replacementManagerIdentityId,
                at = now,
            )

            bridge.syncOrganizationMembership(
                memberMembershipId,
                now,
            )
            bridge.syncOrganizationMembership(
                managerMembershipId,
                now,
            )
            bridge.syncOrganizationMembership(
                replacementMembershipId,
                now,
            )
        }

        val memberServicePending =
            serviceAt(
                at = now,
                authorization = authorization,
                sourceAuthority = sourceAuthority,
            )
        assertFalse(
            memberServicePending.isOrganizationMember(
                memberIdentityId,
                organizationId,
            ),
        )

        drain(
            processor = processor,
            at = now.plusSeconds(1),
        )

        val memberServiceApplied =
            serviceAt(
                at = now.plusSeconds(2),
                authorization = authorization,
                sourceAuthority = sourceAuthority,
            )
        assertTrue(
            memberServiceApplied.isOrganizationMember(
                memberIdentityId,
                organizationId,
            ),
        )

        val teamId = UUID.randomUUID()
        val teamMembershipId = UUID.randomUUID()

        transaction.executeWithoutResult {
            jdbc.update(
                """
                INSERT INTO team (
                    id, organization_id, code, name,
                    parent_team_id, manager_user_identity_id,
                    active, version
                )
                VALUES (?, ?, ?, ?, NULL, ?, true, 1)
                """.trimIndent(),
                teamId,
                organizationId,
                "PHASE1-${UUID.randomUUID()}",
                "Phase 1 Authorization Team",
                managerIdentityId,
            )
            jdbc.update(
                """
                INSERT INTO team_membership (
                    id, team_id, user_identity_id,
                    role_in_team, valid_from, valid_until, version
                )
                VALUES (?, ?, ?, 'MEMBER', ?, NULL, 1)
                """.trimIndent(),
                teamMembershipId,
                teamId,
                memberIdentityId,
                now.minusSeconds(60).atOffset(ZoneOffset.UTC),
            )

            bridge.syncTeamMembership(
                teamMembershipId,
                now,
            )
            bridge.syncTeamManagerChange(
                teamId = teamId,
                previousManagerIdentityId = null,
                occurredAt = now,
            )
        }

        val pendingTeamService =
            serviceAt(
                at = now,
                authorization = authorization,
                sourceAuthority = sourceAuthority,
            )
        assertFalse(
            pendingTeamService.canViewTeam(
                memberIdentityId,
                teamId,
            ),
        )
        assertFalse(
            pendingTeamService.canManageMembership(
                managerIdentityId,
                teamId,
            ),
        )

        drain(
            processor = processor,
            at = now.plusSeconds(3),
        )

        val appliedTeamService =
            serviceAt(
                at = now.plusSeconds(4),
                authorization = authorization,
                sourceAuthority = sourceAuthority,
            )
        assertTrue(
            appliedTeamService.canViewTeam(
                memberIdentityId,
                teamId,
            ),
        )
        assertTrue(
            appliedTeamService.canManageMembership(
                managerIdentityId,
                teamId,
            ),
        )

        val memberCanViewTuple = OpenFgaTuple(
            subjectType = "user",
            subjectId = memberIdentityId.toString(),
            relation = "can_view",
            objectType = "team",
            objectId = teamId.toString(),
        )
        assertTrue(gateway.isAllowed(memberCanViewTuple))

        val revokeAt = now.plusSeconds(30)
        transaction.executeWithoutResult {
            jdbc.update(
                """
                UPDATE team_membership
                SET valid_until = ?,
                    version = version + 1
                WHERE id = ?
                """.trimIndent(),
                revokeAt.minusSeconds(1)
                    .atOffset(ZoneOffset.UTC),
                teamMembershipId,
            )
            bridge.syncTeamMembership(
                teamMembershipId,
                revokeAt,
            )
        }

        assertTrue(
            gateway.isAllowed(memberCanViewTuple),
            "OpenFGA should still contain the stale member tuple before cleanup.",
        )
        val revokedService =
            serviceAt(
                at = revokeAt,
                authorization = authorization,
                sourceAuthority = sourceAuthority,
            )
        assertFalse(
            revokedService.canViewTeam(
                memberIdentityId,
                teamId,
            ),
            "Source truth must deny immediately before stale FGA cleanup.",
        )

        drain(
            processor = processor,
            at = revokeAt.plusSeconds(1),
        )
        assertFalse(gateway.isAllowed(memberCanViewTuple))

        val managerCanManageTuple = OpenFgaTuple(
            subjectType = "user",
            subjectId = managerIdentityId.toString(),
            relation = "can_manage_membership",
            objectType = "team",
            objectId = teamId.toString(),
        )
        assertTrue(gateway.isAllowed(managerCanManageTuple))

        val replaceAt = now.plusSeconds(60)
        transaction.executeWithoutResult {
            jdbc.update(
                """
                UPDATE team
                SET manager_user_identity_id = ?,
                    version = version + 1
                WHERE id = ?
                """.trimIndent(),
                replacementManagerIdentityId,
                teamId,
            )
            bridge.syncTeamManagerChange(
                teamId = teamId,
                previousManagerIdentityId = managerIdentityId,
                occurredAt = replaceAt,
            )
        }

        assertTrue(
            gateway.isAllowed(managerCanManageTuple),
            "Old manager tuple should still be stale in FGA before projection.",
        )
        val replacementPendingService =
            serviceAt(
                at = replaceAt,
                authorization = authorization,
                sourceAuthority = sourceAuthority,
            )
        assertFalse(
            replacementPendingService.canManageMembership(
                managerIdentityId,
                teamId,
            ),
        )
        assertFalse(
            replacementPendingService.canManageMembership(
                replacementManagerIdentityId,
                teamId,
            ),
        )

        drain(
            processor = processor,
            at = replaceAt.plusSeconds(1),
        )

        val replacementAppliedService =
            serviceAt(
                at = replaceAt.plusSeconds(2),
                authorization = authorization,
                sourceAuthority = sourceAuthority,
            )
        assertFalse(
            replacementAppliedService.canManageMembership(
                managerIdentityId,
                teamId,
            ),
        )
        assertTrue(
            replacementAppliedService.canManageMembership(
                replacementManagerIdentityId,
                teamId,
            ),
        )

        proveStaleManagerGrantCannotReplay(
            jdbc = jdbc,
            transaction = transaction,
            bridge = bridge,
            processor = processor,
            gateway = gateway,
            authorization = authorization,
            sourceAuthority = sourceAuthority,
            organizationId = organizationId,
            firstManagerId = managerIdentityId,
            replacementManagerId = replacementManagerIdentityId,
            at = now.plusSeconds(90),
        )
    }

    @Test
    fun warehouseManagementDeniesStaleUserBindingAndStaleTeamMembership() {
        assumeTrue(enabled)
        Flyway.configure()
            .dataSource(url, user, password)
            .locations("filesystem:$migrationPath")
            .load()
            .migrate()

        val dataSource = DriverManagerDataSource(url, user, password)
        val jdbc = JdbcTemplate(dataSource)
        val manager = DataSourceTransactionManager(dataSource)
        val transaction = TransactionTemplate(manager)
        val properties = HiltechOpenFgaProperties(
            enabled = true,
            apiUrl = fgaApiUrl,
            storeId = fgaStoreId,
            authorizationModelId = fgaModelId,
        ).also { it.validateEnabledConfiguration() }
        val gateway = OpenFgaGateway(properties, JdkOpenFgaHttpTransport(properties))
        val checks = FailClosedAuthorizationAdapter(JdbcAuthorizationProjectionGuard(jdbc), gateway)
        val provider = StaticListableBeanFactory(mapOf("authorization" to checks))
            .getBeanProvider(AuthorizationCheckPort::class.java)
        val writer = JdbcAuthorizationProjectionIntentWriter(jdbc, properties)
        val roleTeamBridge = JdbcRoleTeamAuthorizationProjectionBridge(jdbc, writer)
        val processor = AuthorizationProjectionProcessor(
            JdbcAuthorizationProjectionStore(jdbc, manager, properties, AuthorizationProjectionRetryPolicy()),
            gateway,
            properties,
        )
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val source = JdbcRoleTeamSourceAuthority(jdbc)
        val persistence = JdbcWarehousePersistence(jdbc)
        val warehouseBridge = WarehouseAuthorizationProjectionBridge(persistence, writer, clock)
        val authorization = SpringWarehouseAuthorization(provider, source, persistence, clock)
        val org = UUID.randomUUID()
        val userManager = UUID.randomUUID()
        val teamManager = UUID.randomUUID()
        val teamId = UUID.randomUUID()
        val teamMembershipId = UUID.randomUUID()
        val warehouseId = UUID.randomUUID()
        val teamBindingId = UUID.randomUUID()
        val custodian = UUID.randomUUID()
        val locationViewer = UUID.randomUUID()
        val serviceOperator = UUID.randomUUID()
        seedOrganizationAndIdentities(jdbc, org, listOf(userManager, teamManager, custodian, locationViewer, serviceOperator), now.minusSeconds(60))
        transaction.executeWithoutResult {
            listOf(userManager, teamManager, custodian, locationViewer, serviceOperator).forEach { actor ->
                val membershipId = UUID.randomUUID()
                insertOrganizationMembership(jdbc, membershipId, org, actor, now.minusSeconds(60))
                roleTeamBridge.syncOrganizationMembership(membershipId, now)
            }
            jdbc.update(
                "INSERT INTO team (id, organization_id, code, name) VALUES (?, ?, ?, ?)",
                teamId, org, "WAREHOUSE-TEST", "Synthetic warehouse team",
            )
            jdbc.update(
                """
                INSERT INTO team_membership (id, team_id, user_identity_id, role_in_team, valid_from)
                VALUES (?, ?, ?, 'MEMBER', ?)
                """.trimIndent(),
                teamMembershipId, teamId, teamManager, now.minusSeconds(60).atOffset(ZoneOffset.UTC),
            )
            roleTeamBridge.syncTeamMembership(teamMembershipId, now)
        }
        val provisioner = WarehouseManagerProvisioner(
            jdbc, transaction, persistence, source, warehouseBridge, JdbcAuditEventWriter(jdbc), clock,
        )
        assertThrows<IllegalArgumentException> {
            provisioner.provision(org, warehouseId, userManager, UUID.randomUUID(), "WAREHOUSE-TEST", "Synthetic warehouse")
        }
        val userBindingId = provisioner.provision(
            org, warehouseId, userManager, userManager, "WAREHOUSE-TEST", "Synthetic warehouse",
        )
        assertTrue(userBindingId == provisioner.provision(
            org, warehouseId, userManager, userManager, "WAREHOUSE-TEST", "Synthetic warehouse",
        ))
        transaction.executeWithoutResult {
            persistence.insertWarehouseAuthorityBinding(
                teamBindingId, org, warehouseId, WarehouseAuthorityKey.WAREHOUSE_MANAGER,
                InventoryPrincipalType.TEAM, teamId, now.minusSeconds(60), userManager, now,
            )
            warehouseBridge.warehouseAuthority(teamBindingId)
        }

        val warehouse = requireNotNull(persistence.warehouse(warehouseId))
        assertFalse(authorization.canManageWarehouse(userManager, warehouse))
        drain(processor, now.plusSeconds(1))
        assertTrue(authorization.canManageWarehouse(userManager, warehouse))
        assertTrue(authorization.canManageWarehouse(teamManager, warehouse))
        assertFalse(authorization.canViewValue(userManager, warehouse))
        val operator = WarehouseImportOperatorScenario(
            persistence, authorization,
            JdbcIdempotentCommandExecutor(jdbc, manager, clock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {}),
            JdbcAuditEventWriter(jdbc), clock,
        )
        val importBatchId = operator.stageAndReview(org, userManager)
        val stockEvents = CopyOnWriteArrayList<StockMovementPosted>()
        val stock = StockLedgerOperatorScenario(
            jdbc,
            StockLedgerService(StockLedgerPersistence(jdbc), persistence, authorization, source,
                JdbcIdempotentCommandExecutor(jdbc, manager, clock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {}),
                JdbcAuditEventWriter(jdbc), clock, { event -> stockEvents.add(event as StockMovementPosted) }),
            clock, org, warehouseId, userManager, teamManager,
        )
        val stockItemId = stock.exercise { locations ->
            transaction.executeWithoutResult {
                locations.forEach { id -> warehouseBridge.storageCreated(requireNotNull(persistence.storageLocation(id))) }
            }
            drain(processor, now.plusSeconds(1))
        }
        assertTrue(stockEvents.size == 3 && stockEvents.all { it.stockItemId == stockItemId })

        val assetEvents = CopyOnWriteArrayList<AssetCustodyMovementPosted>()
        val passportRecords = AssetPassportPersistence(jdbc)
        val serviceRecords = AssetServicePersistence(jdbc)
        val assetCommands = JdbcIdempotentCommandExecutor(jdbc, manager, clock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {})
        val assetAudit = JdbcAuditEventWriter(jdbc)
        val passports = AssetPassportService(passportRecords, persistence, authorization, source, provider, writer,
            assetCommands, assetAudit, clock, { event -> assetEvents.add(event as AssetCustodyMovementPosted) }, serviceRecords)
        val assets = AssetPassportOperatorScenario(jdbc, passports, clock, org, warehouseId, userManager, teamManager, custodian)
        val passport = assets.exercise { drain(processor, now.plusSeconds(1)) }
        assertTrue(assetEvents.size == 4 && assetEvents.count { it.assetId == passport.identity.id } == 2)

        val serviceEvents = CopyOnWriteArrayList<AssetServiceChanged>()
        val service = AssetService(passports, passportRecords, serviceRecords, authorization, assetCommands, assetAudit, clock,
            { event -> serviceEvents.add(event as AssetServiceChanged) })
        val availability = AssetServiceOperatorScenario(jdbc, passports, serviceRecords, service, clock, warehouseId, userManager, custodian)
        availability.exercise(passport) { drain(processor, now.plusSeconds(1)) }
        assertTrue(serviceEvents.size == 13 && serviceEvents.all { it.assetId == passport.identity.id })

        val location = requireNotNull(passport.custody?.storageLocationId)
        val viewerBinding = UUID.randomUUID()
        val operatorBinding = UUID.randomUUID()
        transaction.executeWithoutResult {
            warehouseBridge.storageCreated(requireNotNull(persistence.storageLocation(location)))
            persistence.insertStorageAuthorityBinding(viewerBinding, org, location, StorageLocationAuthorityKey.LOCATION_VIEWER,
                InventoryPrincipalType.USER, locationViewer, now.minusSeconds(30), userManager, now)
            warehouseBridge.storageAuthority(viewerBinding)
            persistence.insertWarehouseAuthorityBinding(operatorBinding, org, warehouseId, WarehouseAuthorityKey.WAREHOUSE_OPERATOR,
                InventoryPrincipalType.USER, serviceOperator, now.minusSeconds(30), userManager, now)
            warehouseBridge.warehouseAuthority(operatorBinding)
        }
        drain(processor, now.plusSeconds(1))
        availability.exerciseLimitedRoles(passport, locationViewer, serviceOperator)
        assertTrue(persistence.endWarehouseAuthorityBinding(operatorBinding, warehouseId, 1, now))
        assertTrue(persistence.endStorageAuthorityBinding(viewerBinding, location, 1, now))
        assertTrue(gateway.isAllowed(WarehouseAuthorizationRelations.storageAction(locationViewer, location, "can_view")))
        availability.assertLocationRevoked(passport, locationViewer)
        availability.assertRevoked(passport.identity.id, serviceOperator)

        // Deliberately leave the last projected grants in place to exercise the source guards.
        assertTrue(persistence.endWarehouseAuthorityBinding(userBindingId, warehouseId, 1, now))
        jdbc.update(
            "UPDATE team_membership SET valid_until = ?, version = version + 1 WHERE id = ?",
            now.minusSeconds(1).atOffset(ZoneOffset.UTC), teamMembershipId,
        )
        for (actor in listOf(userManager, teamManager)) {
            assertTrue(gateway.isAllowed(WarehouseAuthorizationRelations.warehouseAction(actor, warehouseId, "can_manage")))
            assertFalse(authorization.canManageWarehouse(actor, warehouse))
            assertFalse(authorization.canManageImport(actor, org))
            operator.assertHidden(importBatchId, actor)
            stock.assertTransferDenied(stockItemId, actor)
            assets.assertRevoked(passport, actor)
            availability.assertRevoked(passport.identity.id, actor)
        }
        assertTrue(persistence.endWarehouseAuthorityBinding(teamBindingId, warehouseId, 1, now))
        assertThrows<IllegalArgumentException> {
            provisioner.provision(org, warehouseId, userManager, userManager, "WAREHOUSE-TEST", "Synthetic warehouse")
        }
    }

    private fun proveStaleManagerGrantCannotReplay(
        jdbc: JdbcTemplate,
        transaction: TransactionTemplate,
        bridge: JdbcRoleTeamAuthorizationProjectionBridge,
        processor: AuthorizationProjectionProcessor,
        gateway: OpenFgaGateway,
        authorization: AuthorizationCheckPort,
        sourceAuthority: RoleTeamSourceAuthorityPort,
        organizationId: UUID,
        firstManagerId: UUID,
        replacementManagerId: UUID,
        at: Instant,
    ) {
        val teamId = UUID.randomUUID()

        transaction.executeWithoutResult {
            jdbc.update(
                """
                INSERT INTO team (
                    id, organization_id, code, name,
                    parent_team_id, manager_user_identity_id,
                    active, version
                )
                VALUES (?, ?, ?, ?, NULL, ?, true, 1)
                """.trimIndent(),
                teamId,
                organizationId,
                "STALE-${UUID.randomUUID()}",
                "Stale Manager Projection Team",
                firstManagerId,
            )
            bridge.syncTeamManagerChange(
                teamId = teamId,
                previousManagerIdentityId = null,
                occurredAt = at,
            )
        }

        transaction.executeWithoutResult {
            jdbc.update(
                """
                UPDATE team
                SET manager_user_identity_id = ?,
                    version = version + 1
                WHERE id = ?
                """.trimIndent(),
                replacementManagerId,
                teamId,
            )
            bridge.syncTeamManagerChange(
                teamId = teamId,
                previousManagerIdentityId = firstManagerId,
                occurredAt = at.plusSeconds(1),
            )
        }

        drain(
            processor = processor,
            at = at.plusSeconds(2),
        )

        val oldManagerTuple = OpenFgaTuple(
            subjectType = "user",
            subjectId = firstManagerId.toString(),
            relation = "can_manage_membership",
            objectType = "team",
            objectId = teamId.toString(),
        )
        assertFalse(
            gateway.isAllowed(oldManagerTuple),
            "Stale v1 manager grant must never replay over v2 replacement.",
        )
        assertTrue(
            serviceAt(
                at = at.plusSeconds(3),
                authorization = authorization,
                sourceAuthority = sourceAuthority,
            ).canManageMembership(
                replacementManagerId,
                teamId,
            ),
        )
    }

    private fun seedOrganizationAndIdentities(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        identityIds: List<UUID>,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO organization (
                id, organization_code, legal_name, display_name,
                organization_type, status, created_at, version
            )
            VALUES (?, ?, ?, ?, 'HILTECH', 'ACTIVE', ?, 1)
            """.trimIndent(),
            organizationId,
            "AUTH-${UUID.randomUUID()}",
            "HILTECH Role Team Contract",
            "HILTECH Role Team Contract",
            at.atOffset(ZoneOffset.UTC),
        )

        identityIds.forEach { identityId ->
            jdbc.update(
                """
                INSERT INTO user_identity (
                    id, auth_provider, auth_subject, status,
                    primary_organization_id, created_at, version
                )
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, 1)
                """.trimIndent(),
                identityId,
                "https://id.hiltech.test/realms/hiltech",
                "role-team-${UUID.randomUUID()}",
                organizationId,
                at.atOffset(ZoneOffset.UTC),
            )
        }
    }

    private fun insertOrganizationMembership(
        jdbc: JdbcTemplate,
        membershipId: UUID,
        organizationId: UUID,
        identityId: UUID,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO organization_membership (
                id, organization_id, user_identity_id,
                membership_type, role_label, state,
                valid_from, valid_until, invited_by, version
            )
            VALUES (?, ?, ?, 'EMPLOYEE', ?, 'ACTIVE', ?, NULL, NULL, 1)
            """.trimIndent(),
            membershipId,
            organizationId,
            identityId,
            "descriptive-only-${UUID.randomUUID()}",
            at.minusSeconds(60).atOffset(ZoneOffset.UTC),
        )
    }

    private fun serviceAt(
        at: Instant,
        authorization: AuthorizationCheckPort,
        sourceAuthority: RoleTeamSourceAuthorityPort,
    ): RoleTeamAuthorizationService =
        RoleTeamAuthorizationService(
            authorization = authorization,
            sourceAuthority = sourceAuthority,
            clock = Clock.fixed(at, ZoneOffset.UTC),
        )

    private fun drain(
        processor: AuthorizationProjectionProcessor,
        at: Instant,
    ) {
        repeat(50) { index ->
            when (
                processor.processOne(
                    at.plusMillis(index.toLong()),
                )
            ) {
                AuthorizationProjectionProcessResult.NO_WORK ->
                    return

                AuthorizationProjectionProcessResult.RETRY_SCHEDULED,
                AuthorizationProjectionProcessResult.FAILED_PERMANENT ->
                    error("Authorization projection did not converge.")

                else ->
                    Unit
            }
        }
        error("Authorization projection queue did not drain.")
    }
}
