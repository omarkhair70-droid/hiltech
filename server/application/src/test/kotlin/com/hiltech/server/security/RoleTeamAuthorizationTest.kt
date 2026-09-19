package com.hiltech.server.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class RoleTeamAuthorizationTest {
    private val identityId = UUID.randomUUID()
    private val organizationId = UUID.randomUUID()
    private val teamId = UUID.randomUUID()
    private val now = Instant.parse("2026-09-19T04:30:00Z")

    @Test
    fun organizationMembershipProjectsOnlyStructuralMemberAuthority() {
        val membershipId = UUID.randomUUID()

        val intent =
            RoleTeamAuthorizationProjectionFactory.organizationMembership(
                eventId = UUID.randomUUID(),
                identityId = identityId,
                membershipId = membershipId,
                organizationId = organizationId,
                membershipVersion = 7,
                desiredState = AuthorizationDesiredState.PRESENT,
                occurredAt = now,
            )

        assertEquals("user", intent.tuple.subjectType)
        assertEquals(identityId.toString(), intent.tuple.subjectId)
        assertEquals("member", intent.tuple.relation)
        assertEquals("organization", intent.tuple.objectType)
        assertEquals(organizationId.toString(), intent.tuple.objectId)
        assertEquals("OrganizationMembership", intent.sourceType)
        assertEquals(membershipId.toString(), intent.sourceId)
        assertEquals(7, intent.sourceVersion)
    }

    @Test
    fun teamMembershipAndManagerRemainSeparateRelationships() {
        val membership =
            RoleTeamAuthorizationProjectionFactory.teamMembership(
                eventId = UUID.randomUUID(),
                identityId = identityId,
                teamMembershipId = UUID.randomUUID(),
                teamId = teamId,
                teamMembershipVersion = 3,
                desiredState = AuthorizationDesiredState.PRESENT,
                occurredAt = now,
            )
        val manager =
            RoleTeamAuthorizationProjectionFactory.teamManager(
                eventId = UUID.randomUUID(),
                managerIdentityId = identityId,
                teamId = teamId,
                teamVersion = 9,
                desiredState = AuthorizationDesiredState.PRESENT,
                occurredAt = now,
            )

        assertEquals("member", membership.tuple.relation)
        assertEquals("manager", manager.tuple.relation)
        assertEquals("TeamMembership", membership.sourceType)
        assertEquals("Team", manager.sourceType)
    }

    @Test
    fun teamChecksUseOnlyCurrentlyValidStructuralGuardRelations() {
        val captured = mutableListOf<AuthorizationCheckRequest>()
        val source = FakeSourceAuthority(
            teamMemberCurrent = true,
            teamManagerCurrent = false,
        )
        val service = service(
            source = source,
            authorization = AuthorizationCheckPort { request ->
                captured += request
                true
            },
        )

        assertTrue(service.canViewTeam(identityId, teamId))

        val view = captured.single()
        assertEquals("can_view", view.checkTuple.relation)
        assertEquals(
            listOf("member"),
            view.failClosedGuardTuples.map { it.relation },
        )
    }

    @Test
    fun sourceRevocationOrExpiryDeniesBeforeOpenFgaIsConsulted() {
        var authorizationCalls = 0
        val service = service(
            source = FakeSourceAuthority(
                organizationMemberCurrent = false,
                teamMemberCurrent = false,
                teamManagerCurrent = false,
            ),
            authorization = AuthorizationCheckPort {
                authorizationCalls += 1
                true
            },
        )

        assertFalse(
            service.isOrganizationMember(
                identityId = identityId,
                organizationId = organizationId,
            ),
        )
        assertFalse(service.canViewTeam(identityId, teamId))
        assertFalse(service.canManageMembership(identityId, teamId))
        assertEquals(0, authorizationCalls)
    }

    @Test
    fun managerActionIsDirectAndFailClosedGuarded() {
        var captured: AuthorizationCheckRequest? = null
        val service = service(
            source = FakeSourceAuthority(
                teamMemberCurrent = false,
                teamManagerCurrent = true,
            ),
            authorization = AuthorizationCheckPort { request ->
                captured = request
                true
            },
        )

        assertTrue(
            service.canManageMembership(
                identityId = identityId,
                teamId = teamId,
            ),
        )

        val request = requireNotNull(captured)
        assertEquals(
            "can_manage_membership",
            request.checkTuple.relation,
        )
        assertEquals(
            listOf("manager"),
            request.failClosedGuardTuples.map { it.relation },
        )
    }

    private fun service(
        source: RoleTeamSourceAuthorityPort =
            FakeSourceAuthority(),
        authorization: AuthorizationCheckPort =
            AuthorizationCheckPort { true },
    ): RoleTeamAuthorizationService =
        RoleTeamAuthorizationService(
            authorization = authorization,
            sourceAuthority = source,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private class FakeSourceAuthority(
        private val organizationMemberCurrent: Boolean = true,
        private val teamMemberCurrent: Boolean = true,
        private val teamManagerCurrent: Boolean = true,
    ) : RoleTeamSourceAuthorityPort {
        override fun isOrganizationMemberCurrent(
            identityId: UUID,
            organizationId: UUID,
            at: Instant,
        ): Boolean =
            organizationMemberCurrent

        override fun isTeamMemberCurrent(
            identityId: UUID,
            teamId: UUID,
            at: Instant,
        ): Boolean =
            teamMemberCurrent

        override fun isTeamManagerCurrent(
            identityId: UUID,
            teamId: UUID,
            at: Instant,
        ): Boolean =
            teamManagerCurrent
    }
}
