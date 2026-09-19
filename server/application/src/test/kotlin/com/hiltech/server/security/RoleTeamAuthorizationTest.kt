package com.hiltech.server.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
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
    fun teamChecksUseOpenFgaActionsAndDirectRelationshipGuards() {
        val captured = mutableListOf<AuthorizationCheckRequest>()
        val service = RoleTeamAuthorizationService(
            authorization = AuthorizationCheckPort { request ->
                captured += request
                true
            },
        )

        assertTrue(service.canViewTeam(identityId, teamId))
        assertTrue(service.canManageMembership(identityId, teamId))

        val view = captured[0]
        assertEquals("can_view", view.checkTuple.relation)
        assertEquals(
            listOf("member", "manager"),
            view.failClosedGuardTuples.map { it.relation },
        )

        val manage = captured[1]
        assertEquals("can_manage_membership", manage.checkTuple.relation)
        assertEquals(
            listOf("manager"),
            manage.failClosedGuardTuples.map { it.relation },
        )
    }

    @Test
    fun organizationMembershipCheckIsDirectAndFailClosedGuarded() {
        var captured: AuthorizationCheckRequest? = null
        val service = RoleTeamAuthorizationService(
            authorization = AuthorizationCheckPort { request ->
                captured = request
                true
            },
        )

        assertTrue(
            service.isOrganizationMember(
                identityId = identityId,
                organizationId = organizationId,
            ),
        )

        val request = requireNotNull(captured)
        assertEquals("member", request.checkTuple.relation)
        assertEquals(
            listOf(request.checkTuple),
            request.failClosedGuardTuples,
        )
    }
}
