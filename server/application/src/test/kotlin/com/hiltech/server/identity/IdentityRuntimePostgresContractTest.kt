package com.hiltech.server.identity

import com.hiltech.server.organizations.JdbcOrganizationIdentityContext
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class IdentityRuntimePostgresContractTest {
    private val enabled =
        System.getenv("HILTECH_DB_CONTRACT_TEST") == "1"
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

    @Test
    fun realPostgresEnforcesIdentityMembershipAndDeviceBootstrap() {
        assumeTrue(enabled)

        Flyway.configure()
            .dataSource(url, user, password)
            .locations("filesystem:$migrationPath")
            .load()
            .migrate()

        val dataSource =
            DriverManagerDataSource(url, user, password)
        val jdbc = JdbcTemplate(dataSource)
        val identityRepository =
            JdbcIdentityRuntimeRepository(jdbc)
        val organizationContext =
            JdbcOrganizationIdentityContext(jdbc)

        val now =
            Instant.parse("2026-09-19T03:00:00Z")
        val nowOffset =
            now.atOffset(ZoneOffset.UTC)

        val organizationId = UUID.randomUUID()
        val identityId = UUID.randomUUID()
        val secondIdentityId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val teamId = UUID.randomUUID()
        val teamMembershipId = UUID.randomUUID()
        val installationId = UUID.randomUUID()
        val issuer =
            "https://id.hiltech.test/realms/hiltech"
        val subject =
            "phase1-${UUID.randomUUID()}"

        jdbc.update(
            """
            INSERT INTO organization (
                id, organization_code, legal_name, display_name,
                organization_type, status, created_at, version
            )
            VALUES (?, ?, ?, ?, 'HILTECH', 'ACTIVE', ?, 1)
            """.trimIndent(),
            organizationId,
            "PHASE1-${UUID.randomUUID()}",
            "HILTECH Phase 1 Contract",
            "HILTECH",
            nowOffset,
        )

        jdbc.update(
            """
            INSERT INTO user_identity (
                id, auth_provider, auth_subject, status,
                primary_organization_id, created_at, version
            )
            VALUES (?, ?, ?, 'ACTIVE', ?, ?, 1)
            """.trimIndent(),
            identityId,
            issuer,
            subject,
            organizationId,
            nowOffset,
        )

        jdbc.update(
            """
            INSERT INTO user_identity (
                id, auth_provider, auth_subject, status,
                primary_organization_id, created_at, version
            )
            VALUES (?, ?, ?, 'ACTIVE', ?, ?, 1)
            """.trimIndent(),
            secondIdentityId,
            issuer,
            "second-${UUID.randomUUID()}",
            organizationId,
            nowOffset,
        )

        jdbc.update(
            """
            INSERT INTO organization_membership (
                id, organization_id, user_identity_id,
                membership_type, role_label, state,
                valid_from, valid_until, invited_by, version
            )
            VALUES (?, ?, ?, 'EMPLOYEE', 'FIELD', 'ACTIVE', ?, NULL, NULL, 1)
            """.trimIndent(),
            membershipId,
            organizationId,
            identityId,
            nowOffset.minusDays(1),
        )

        jdbc.update(
            """
            INSERT INTO organization_membership (
                id, organization_id, user_identity_id,
                membership_type, role_label, state,
                valid_from, valid_until, invited_by, version
            )
            VALUES (?, ?, ?, 'OLD', 'OLD', 'ENDED', ?, ?, NULL, 1)
            """.trimIndent(),
            UUID.randomUUID(),
            organizationId,
            identityId,
            nowOffset.minusDays(10),
            nowOffset.minusDays(5),
        )

        jdbc.update(
            """
            INSERT INTO team (
                id, organization_id, code, name,
                parent_team_id, manager_user_identity_id,
                active, version
            )
            VALUES (?, ?, 'FIELD', 'Field', NULL, NULL, true, 1)
            """.trimIndent(),
            teamId,
            organizationId,
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
            identityId,
            nowOffset.minusDays(1),
        )

        val identity =
            identityRepository.findByOidcSubject(
                issuer = issuer,
                subject = subject,
            )
        assertNotNull(identity)
        assertEquals(identityId, identity!!.id)
        assertEquals("ACTIVE", identity.status)

        val activeMemberships =
            organizationContext.activeMemberships(
                identityId = identityId,
                at = now,
            )
        assertEquals(1, activeMemberships.size)
        assertEquals(
            membershipId,
            activeMemberships.single().membershipId,
        )

        val activeTeams =
            organizationContext.activeTeams(
                identityId = identityId,
                at = now,
            )
        assertEquals(1, activeTeams.size)
        assertEquals(
            teamMembershipId,
            activeTeams.single().teamMembershipId,
        )

        val service = IdentityBootstrapService(
            identityRepository = identityRepository,
            organizationContext = organizationContext,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val bootstrap = service.bootstrap(
            subject = AuthenticatedOidcSubject(
                issuer = issuer,
                subject = subject,
            ),
            installationId = null,
        )
        assertEquals(identityId.toString(), bootstrap.identityId)
        assertEquals(1, bootstrap.organizations.size)
        assertTrue(bootstrap.organizations.single().primary)

        val firstRegistration =
            identityRepository.registerOrTouchDevice(
                identityId = identityId,
                registration = DeviceRegistration(
                    installationId = installationId,
                    platform = "ANDROID",
                    deviceName = "Phase 1 Phone",
                    appVersion = "0.1.0",
                    osVersion = "16",
                ),
                seenAt = now,
            )
        assertTrue(
            firstRegistration
                is DeviceRegistrationOutcome.Active,
        )
        val firstDevice =
            (firstRegistration
                as DeviceRegistrationOutcome.Active).device
        assertEquals(1, firstDevice.version)

        val secondRegistration =
            identityRepository.registerOrTouchDevice(
                identityId = identityId,
                registration = DeviceRegistration(
                    installationId = installationId,
                    platform = "ANDROID",
                    deviceName = "Phase 1 Phone",
                    appVersion = "0.1.1",
                    osVersion = "16",
                ),
                seenAt = now.plusSeconds(60),
            )
        assertTrue(
            secondRegistration
                is DeviceRegistrationOutcome.Active,
        )
        val secondDevice =
            (secondRegistration
                as DeviceRegistrationOutcome.Active).device
        assertEquals(2, secondDevice.version)
        assertEquals("0.1.1", secondDevice.appVersion)

        val foreignClaim =
            identityRepository.registerOrTouchDevice(
                identityId = secondIdentityId,
                registration = DeviceRegistration(
                    installationId = installationId,
                    platform = "ANDROID",
                    deviceName = "Foreign",
                    appVersion = "0.1.0",
                    osVersion = "16",
                ),
                seenAt = now.plusSeconds(120),
            )
        assertEquals(
            DeviceRegistrationOutcome.OwnedByAnotherIdentity,
            foreignClaim,
        )

        jdbc.update(
            """
            UPDATE device
            SET revoked_at = ?,
                version = version + 1
            WHERE installation_id = ?
            """.trimIndent(),
            nowOffset.plusMinutes(3),
            installationId,
        )

        val revokedRetry =
            identityRepository.registerOrTouchDevice(
                identityId = identityId,
                registration = DeviceRegistration(
                    installationId = installationId,
                    platform = "ANDROID",
                    deviceName = "Phase 1 Phone",
                    appVersion = "0.1.2",
                    osVersion = "16",
                ),
                seenAt = now.plusSeconds(240),
            )
        assertEquals(
            DeviceRegistrationOutcome.Revoked,
            revokedRetry,
        )

        val lastAuthenticatedAt =
            jdbc.queryForObject(
                """
                SELECT last_authenticated_at
                FROM user_identity
                WHERE id = ?
                """.trimIndent(),
                OffsetDateTime::class.java,
                identityId,
            )
        assertEquals(
            now,
            lastAuthenticatedAt?.toInstant(),
        )
    }
}
