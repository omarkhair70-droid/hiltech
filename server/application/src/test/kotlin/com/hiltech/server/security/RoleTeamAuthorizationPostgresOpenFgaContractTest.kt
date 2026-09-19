package com.hiltech.server.security

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

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
