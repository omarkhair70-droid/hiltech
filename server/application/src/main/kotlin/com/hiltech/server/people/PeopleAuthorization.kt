package com.hiltech.server.people

import com.hiltech.server.security.AuthorizationCheckPort
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionIntent
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.security.OpenFgaTuple
import com.hiltech.server.security.RoleTeamAuthorizationRelations
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

enum class PeopleAuthorityPrincipalType {
    USER,
    TEAM,
}

data class PeopleAuthorityBindingContext(
    val bindingId: UUID,
    val organizationId: UUID,
    val principalType:
        PeopleAuthorityPrincipalType,
    val principalId: UUID,
    val version: Long,
)

interface PeopleAuthoritySourcePort {
    fun currentAdminBindings(
        organizationId: UUID,
        at: Instant,
    ): List<PeopleAuthorityBindingContext>
}

@Component
class JdbcPeopleAuthoritySource(
    private val jdbc: JdbcTemplate,
) : PeopleAuthoritySourcePort {
    override fun currentAdminBindings(
        organizationId: UUID,
        at: Instant,
    ): List<PeopleAuthorityBindingContext> {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        return jdbc.query(
            """
            SELECT
                pab.id,
                pab.organization_id,
                pab.principal_type,
                pab.principal_user_id,
                pab.principal_team_id,
                pab.version
            FROM people_authority_binding pab
            JOIN organization o
              ON o.id = pab.organization_id
            LEFT JOIN team t
              ON t.id = pab.principal_team_id
            WHERE pab.organization_id = ?
              AND pab.authority_key = 'PEOPLE_ADMIN'
              AND pab.active = true
              AND o.status = 'ACTIVE'
              AND pab.effective_from <= ?
              AND (
                  pab.effective_to IS NULL
                  OR pab.effective_to >= ?
              )
              AND (
                  (
                      pab.principal_type = 'USER'
                      AND EXISTS (
                          SELECT 1
                          FROM user_identity ui
                          JOIN organization_membership om
                            ON om.user_identity_id = ui.id
                          WHERE ui.id = pab.principal_user_id
                            AND ui.status = 'ACTIVE'
                            AND om.organization_id = pab.organization_id
                            AND om.state = 'ACTIVE'
                            AND om.valid_from <= ?
                            AND (
                                om.valid_until IS NULL
                                OR om.valid_until >= ?
                            )
                      )
                  )
                  OR
                  (
                      pab.principal_type = 'TEAM'
                      AND t.organization_id = pab.organization_id
                      AND t.active = true
                  )
              )
            ORDER BY pab.id
            """.trimIndent(),
            { rs, _ ->
                val principalType =
                    PeopleAuthorityPrincipalType
                        .valueOf(
                            rs.getString(
                                "principal_type",
                            ),
                        )
                PeopleAuthorityBindingContext(
                    bindingId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    principalType =
                        principalType,
                    principalId =
                        when (
                            principalType
                        ) {
                            PeopleAuthorityPrincipalType.USER ->
                                rs.getObject(
                                    "principal_user_id",
                                    UUID::class.java,
                                )

                            PeopleAuthorityPrincipalType.TEAM ->
                                rs.getObject(
                                    "principal_team_id",
                                    UUID::class.java,
                                )
                        },
                    version =
                        rs.getLong("version"),
                )
            },
            organizationId,
            timestamp,
            timestamp,
            timestamp,
            timestamp,
        )
    }
}

object PeopleAuthorizationRelations {
    fun peopleAdmin(
        binding:
            PeopleAuthorityBindingContext,
    ): OpenFgaTuple =
        when (
            binding.principalType
        ) {
            PeopleAuthorityPrincipalType.USER ->
                OpenFgaTuple(
                    subjectType = "user",
                    subjectId =
                        binding.principalId
                            .toString(),
                    relation =
                        "people_admin",
                    objectType =
                        "organization",
                    objectId =
                        binding.organizationId
                            .toString(),
                )

            PeopleAuthorityPrincipalType.TEAM ->
                OpenFgaTuple(
                    subjectType = "team",
                    subjectId =
                        binding.principalId
                            .toString() +
                            "#member",
                    relation =
                        "people_admin",
                    objectType =
                        "organization",
                    objectId =
                        binding.organizationId
                            .toString(),
                )
        }

    fun canManagePeople(
        actorUserId: UUID,
        organizationId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "user",
            subjectId =
                actorUserId.toString(),
            relation =
                "can_manage_people",
            objectType =
                "organization",
            objectId =
                organizationId.toString(),
        )
}

object PeopleAuthorizationProjectionFactory {
    fun peopleAdmin(
        eventId: UUID,
        binding:
            PeopleAuthorityBindingContext,
        desiredState:
            AuthorizationDesiredState,
        occurredAt: Instant,
    ): AuthorizationProjectionIntent =
        AuthorizationProjectionIntent(
            eventId = eventId,
            tuple =
                PeopleAuthorizationRelations
                    .peopleAdmin(binding),
            desiredState =
                desiredState,
            sourceType =
                "PeopleAuthorityBinding",
            sourceId =
                binding.bindingId
                    .toString(),
            sourceVersion =
                binding.version,
            eventType =
                "PEOPLE_AUTHORITY_CHANGED",
            occurredAt =
                occurredAt,
        )
}

@Component
class JdbcPeopleAuthorizationProjectionBridge(
    private val jdbc: JdbcTemplate,
    private val projectionWriter:
        AuthorizationProjectionIntentWriter,
) {
    @Transactional(
        propagation =
            Propagation.MANDATORY,
    )
    fun syncBinding(
        bindingId: UUID,
        occurredAt: Instant,
        eventId: UUID =
            UUID.randomUUID(),
    ) {
        val timestamp =
            occurredAt
                .atOffset(ZoneOffset.UTC)
        val row =
            jdbc.query(
                """
                SELECT
                    pab.id,
                    pab.organization_id,
                    pab.principal_type,
                    pab.principal_user_id,
                    pab.principal_team_id,
                    pab.version,
                    (
                        pab.active = true
                        AND o.status = 'ACTIVE'
                        AND pab.effective_from <= ?
                        AND (
                            pab.effective_to IS NULL
                            OR pab.effective_to >= ?
                        )
                        AND (
                            (
                                pab.principal_type = 'USER'
                                AND EXISTS (
                                    SELECT 1
                                    FROM user_identity ui
                                    JOIN organization_membership om
                                      ON om.user_identity_id = ui.id
                                    WHERE ui.id = pab.principal_user_id
                                      AND ui.status = 'ACTIVE'
                                      AND om.organization_id = pab.organization_id
                                      AND om.state = 'ACTIVE'
                                      AND om.valid_from <= ?
                                      AND (
                                          om.valid_until IS NULL
                                          OR om.valid_until >= ?
                                      )
                                )
                            )
                            OR
                            (
                                pab.principal_type = 'TEAM'
                                AND t.organization_id = pab.organization_id
                                AND t.active = true
                            )
                        )
                    ) AS authority_current
                FROM people_authority_binding pab
                JOIN organization o
                  ON o.id = pab.organization_id
                LEFT JOIN team t
                  ON t.id = pab.principal_team_id
                WHERE pab.id = ?
                FOR UPDATE OF pab
                """.trimIndent(),
                { rs, _ ->
                    val principalType =
                        PeopleAuthorityPrincipalType
                            .valueOf(
                                rs.getString(
                                    "principal_type",
                                ),
                            )
                    PeopleAuthorityProjectionRow(
                        binding =
                            PeopleAuthorityBindingContext(
                                bindingId =
                                    rs.getObject(
                                        "id",
                                        UUID::class.java,
                                    ),
                                organizationId =
                                    rs.getObject(
                                        "organization_id",
                                        UUID::class.java,
                                    ),
                                principalType =
                                    principalType,
                                principalId =
                                    when (
                                        principalType
                                    ) {
                                        PeopleAuthorityPrincipalType.USER ->
                                            rs.getObject(
                                                "principal_user_id",
                                                UUID::class.java,
                                            )

                                        PeopleAuthorityPrincipalType.TEAM ->
                                            rs.getObject(
                                                "principal_team_id",
                                                UUID::class.java,
                                            )
                                    },
                                version =
                                    rs.getLong(
                                        "version",
                                    ),
                            ),
                        current =
                            rs.getBoolean(
                                "authority_current",
                            ),
                    )
                },
                timestamp,
                timestamp,
                timestamp,
                timestamp,
                bindingId,
            ).singleOrNull()
                ?: error(
                    "People authority binding $bindingId does not exist.",
                )

        projectionWriter.write(
            PeopleAuthorizationProjectionFactory
                .peopleAdmin(
                    eventId = eventId,
                    binding =
                        row.binding,
                    desiredState =
                        if (row.current) {
                            AuthorizationDesiredState
                                .PRESENT
                        } else {
                            AuthorizationDesiredState
                                .ABSENT
                        },
                    occurredAt =
                        occurredAt,
                ),
        )
    }

    private data class PeopleAuthorityProjectionRow(
        val binding:
            PeopleAuthorityBindingContext,
        val current: Boolean,
    )
}

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
    private val peopleAuthoritySource:
        PeopleAuthoritySourcePort,
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
                    identityId =
                        actorUserId,
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
                    identityId =
                        actorUserId,
                    organizationId =
                        organizationId,
                )

        val binding =
            peopleAuthoritySource
                .currentAdminBindings(
                    organizationId =
                        organizationId,
                    at = at,
                )
                .firstOrNull {
                    when (
                        it.principalType
                    ) {
                        PeopleAuthorityPrincipalType.USER ->
                            it.principalId ==
                                actorUserId

                        PeopleAuthorityPrincipalType.TEAM ->
                            sourceAuthority
                                .isTeamMemberCurrent(
                                    identityId =
                                        actorUserId,
                                    teamId =
                                        it.principalId,
                                    at = at,
                                )
                    }
                }
                ?: return false

        val guards =
            mutableListOf(
                membership,
                PeopleAuthorizationRelations
                    .peopleAdmin(binding),
            )

        if (
            binding.principalType ==
            PeopleAuthorityPrincipalType.TEAM
        ) {
            guards +=
                RoleTeamAuthorizationRelations
                    .teamMember(
                        identityId =
                            actorUserId,
                        teamId =
                            binding.principalId,
                    )
        }

        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple =
                    PeopleAuthorizationRelations
                        .canManagePeople(
                            actorUserId =
                                actorUserId,
                            organizationId =
                                organizationId,
                        ),
                failClosedGuardTuples =
                    guards,
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
                    identityId =
                        actorUserId,
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
                    identityId =
                        actorUserId,
                    organizationId =
                        organizationId,
                )

        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple =
                    membership,
                failClosedGuardTuples =
                    listOf(membership),
            ),
        )
    }
}
