package com.hiltech.server.security

import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Phase 1 structural authority mapping.
 *
 * Human-facing role/title labels are intentionally absent from this API.
 * They are descriptive product data, not authorization truth.
 */
object RoleTeamAuthorizationRelations {
    fun organizationMember(
        identityId: UUID,
        organizationId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = identityId.toString(),
            relation = "member",
            objectType = "organization",
            objectId = organizationId.toString(),
        )

    fun teamMember(
        identityId: UUID,
        teamId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = identityId.toString(),
            relation = "member",
            objectType = "team",
            objectId = teamId.toString(),
        )

    fun teamManager(
        identityId: UUID,
        teamId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = identityId.toString(),
            relation = "manager",
            objectType = "team",
            objectId = teamId.toString(),
        )
}

object RoleTeamAuthorizationProjectionFactory {
    fun organizationMembership(
        eventId: UUID,
        identityId: UUID,
        membershipId: UUID,
        organizationId: UUID,
        membershipVersion: Long,
        desiredState: AuthorizationDesiredState,
        occurredAt: Instant,
    ): AuthorizationProjectionIntent =
        AuthorizationProjectionIntent(
            eventId = eventId,
            tuple = RoleTeamAuthorizationRelations.organizationMember(
                identityId = identityId,
                organizationId = organizationId,
            ),
            desiredState = desiredState,
            sourceType = "OrganizationMembership",
            sourceId = membershipId.toString(),
            sourceVersion = membershipVersion,
            eventType = "ORGANIZATION_MEMBERSHIP_AUTHORITY_CHANGED",
            occurredAt = occurredAt,
        )

    fun teamMembership(
        eventId: UUID,
        identityId: UUID,
        teamMembershipId: UUID,
        teamId: UUID,
        teamMembershipVersion: Long,
        desiredState: AuthorizationDesiredState,
        occurredAt: Instant,
    ): AuthorizationProjectionIntent =
        AuthorizationProjectionIntent(
            eventId = eventId,
            tuple = RoleTeamAuthorizationRelations.teamMember(
                identityId = identityId,
                teamId = teamId,
            ),
            desiredState = desiredState,
            sourceType = "TeamMembership",
            sourceId = teamMembershipId.toString(),
            sourceVersion = teamMembershipVersion,
            eventType = "TEAM_MEMBERSHIP_AUTHORITY_CHANGED",
            occurredAt = occurredAt,
        )

    fun teamMembershipAggregate(
        eventId: UUID,
        identityId: UUID,
        teamId: UUID,
        aggregateVersion: Long,
        desiredState: AuthorizationDesiredState,
        occurredAt: Instant,
    ): AuthorizationProjectionIntent =
        AuthorizationProjectionIntent(
            eventId = eventId,
            tuple =
                RoleTeamAuthorizationRelations
                    .teamMember(
                        identityId =
                            identityId,
                        teamId = teamId,
                    ),
            desiredState = desiredState,
            sourceType =
                "TeamMembershipAuthority",
            sourceId =
                "$teamId:$identityId",
            sourceVersion =
                aggregateVersion,
            eventType =
                "TEAM_MEMBERSHIP_AUTHORITY_CHANGED",
            occurredAt = occurredAt,
        )

    fun teamManager(
        eventId: UUID,
        managerIdentityId: UUID,
        teamId: UUID,
        teamVersion: Long,
        desiredState: AuthorizationDesiredState,
        occurredAt: Instant,
    ): AuthorizationProjectionIntent =
        AuthorizationProjectionIntent(
            eventId = eventId,
            tuple = RoleTeamAuthorizationRelations.teamManager(
                identityId = managerIdentityId,
                teamId = teamId,
            ),
            desiredState = desiredState,
            sourceType = "Team",
            sourceId = teamId.toString(),
            sourceVersion = teamVersion,
            eventType = "TEAM_MANAGER_AUTHORITY_CHANGED",
            occurredAt = occurredAt,
        )
}

interface RoleTeamSourceAuthorityPort {
    fun isOrganizationMemberCurrent(
        identityId: UUID,
        organizationId: UUID,
        at: Instant,
    ): Boolean

    fun isTeamMemberCurrent(
        identityId: UUID,
        teamId: UUID,
        at: Instant,
    ): Boolean

    fun isTeamManagerCurrent(
        identityId: UUID,
        teamId: UUID,
        at: Instant,
    ): Boolean
}

class RoleTeamAuthorizationService(
    private val authorization: AuthorizationCheckPort,
    private val sourceAuthority: RoleTeamSourceAuthorityPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun isOrganizationMember(
        identityId: UUID,
        organizationId: UUID,
    ): Boolean {
        val at = clock.instant()
        if (
            !sourceAuthority.isOrganizationMemberCurrent(
                identityId = identityId,
                organizationId = organizationId,
                at = at,
            )
        ) {
            return false
        }

        val member = RoleTeamAuthorizationRelations.organizationMember(
            identityId = identityId,
            organizationId = organizationId,
        )
        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple = member,
                failClosedGuardTuples = listOf(member),
            ),
        )
    }

    fun canViewTeam(
        identityId: UUID,
        teamId: UUID,
    ): Boolean {
        val at = clock.instant()
        val memberCurrent =
            sourceAuthority.isTeamMemberCurrent(
                identityId = identityId,
                teamId = teamId,
                at = at,
            )
        val managerCurrent =
            sourceAuthority.isTeamManagerCurrent(
                identityId = identityId,
                teamId = teamId,
                at = at,
            )

        if (!memberCurrent && !managerCurrent) {
            return false
        }

        val member = RoleTeamAuthorizationRelations.teamMember(
            identityId = identityId,
            teamId = teamId,
        )
        val manager = RoleTeamAuthorizationRelations.teamManager(
            identityId = identityId,
            teamId = teamId,
        )

        val guards = buildList {
            if (memberCurrent) {
                add(member)
            }
            if (managerCurrent) {
                add(manager)
            }
        }

        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple = OpenFgaTuple(
                    subjectType = "user",
                    subjectId = identityId.toString(),
                    relation = "can_view",
                    objectType = "team",
                    objectId = teamId.toString(),
                ),
                failClosedGuardTuples = guards,
            ),
        )
    }

    fun canManageMembership(
        identityId: UUID,
        teamId: UUID,
    ): Boolean {
        val at = clock.instant()
        if (
            !sourceAuthority.isTeamManagerCurrent(
                identityId = identityId,
                teamId = teamId,
                at = at,
            )
        ) {
            return false
        }

        val manager = RoleTeamAuthorizationRelations.teamManager(
            identityId = identityId,
            teamId = teamId,
        )

        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple = OpenFgaTuple(
                    subjectType = "user",
                    subjectId = identityId.toString(),
                    relation = "can_manage_membership",
                    objectType = "team",
                    objectId = teamId.toString(),
                ),
                failClosedGuardTuples = listOf(manager),
            ),
        )
    }
}
