package com.hiltech.server.people

import com.hiltech.server.security.AuthorizationCheckPort
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.OpenFgaTuple
import com.hiltech.server.security.RoleTeamAuthorizationRelations
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

interface PeopleAuthorizationPort {
    fun canManagePeople(
        actorUserId: UUID,
        organizationId: UUID,
    ): Boolean

    fun canViewDirectory(
        actorUserId: UUID,
        organizationId: UUID,
    ): Boolean
}

@Component
class SpringPeopleAuthorization(
    private val authorizationProvider:
        ObjectProvider<AuthorizationCheckPort>,
    private val sourceAuthority:
        RoleTeamSourceAuthorityPort,
    private val clock: Clock,
) : PeopleAuthorizationPort {
    override fun canManagePeople(
        actorUserId: UUID,
        organizationId: UUID,
    ): Boolean {
        val authorization =
            authorizationProvider.ifAvailable
                ?: return false
        val at =
            clock.instant()

        if (
            !sourceAuthority
                .isOrganizationMemberCurrent(
                    identityId = actorUserId,
                    organizationId =
                        organizationId,
                    at = at,
                )
        ) {
            return false
        }

        val membership =
            RoleTeamAuthorizationRelations
                .organizationMember(
                    identityId = actorUserId,
                    organizationId =
                        organizationId,
                )

        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple =
                    OpenFgaTuple(
                        subjectType = "user",
                        subjectId =
                            actorUserId.toString(),
                        relation = "admin",
                        objectType =
                            "organization",
                        objectId =
                            organizationId.toString(),
                    ),
                failClosedGuardTuples =
                    listOf(membership),
            ),
        )
    }

    override fun canViewDirectory(
        actorUserId: UUID,
        organizationId: UUID,
    ): Boolean {
        val authorization =
            authorizationProvider.ifAvailable
                ?: return false
        val at =
            clock.instant()

        if (
            !sourceAuthority
                .isOrganizationMemberCurrent(
                    identityId = actorUserId,
                    organizationId =
                        organizationId,
                    at = at,
                )
        ) {
            return false
        }

        val membership =
            RoleTeamAuthorizationRelations
                .organizationMember(
                    identityId = actorUserId,
                    organizationId =
                        organizationId,
                )

        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple = membership,
                failClosedGuardTuples =
                    listOf(membership),
            ),
        )
    }
}
