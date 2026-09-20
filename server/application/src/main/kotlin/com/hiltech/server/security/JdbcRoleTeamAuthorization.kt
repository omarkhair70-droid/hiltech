package com.hiltech.server.security

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Component
class JdbcRoleTeamSourceAuthority(
    private val jdbc: JdbcTemplate,
) : RoleTeamSourceAuthorityPort {
    override fun isOrganizationMemberCurrent(
        identityId: UUID,
        organizationId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM organization_membership m
                JOIN organization o
                  ON o.id = m.organization_id
                JOIN user_identity u
                  ON u.id = m.user_identity_id
                WHERE m.user_identity_id = ?
                  AND m.organization_id = ?
                  AND m.state = 'ACTIVE'
                  AND o.status = 'ACTIVE'
                  AND u.status = 'ACTIVE'
                  AND m.valid_from <= ?
                  AND (m.valid_until IS NULL OR m.valid_until >= ?)
            )
            """.trimIndent(),
            Boolean::class.java,
            identityId,
            organizationId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        ) == true

    override fun isTeamMemberCurrent(
        identityId: UUID,
        teamId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM team_membership tm
                JOIN team t
                  ON t.id = tm.team_id
                JOIN organization o
                  ON o.id = t.organization_id
                JOIN user_identity u
                  ON u.id = tm.user_identity_id
                WHERE tm.user_identity_id = ?
                  AND tm.team_id = ?
                  AND t.active = true
                  AND o.status = 'ACTIVE'
                  AND u.status = 'ACTIVE'
                  AND tm.valid_from <= ?
                  AND (tm.valid_until IS NULL OR tm.valid_until >= ?)
                  AND EXISTS (
                      SELECT 1
                      FROM organization_membership om
                      WHERE om.organization_id = t.organization_id
                        AND om.user_identity_id = tm.user_identity_id
                        AND om.state = 'ACTIVE'
                        AND om.valid_from <= ?
                        AND (om.valid_until IS NULL OR om.valid_until >= ?)
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            identityId,
            teamId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        ) == true

    override fun isTeamManagerCurrent(
        identityId: UUID,
        teamId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM team t
                JOIN organization o
                  ON o.id = t.organization_id
                JOIN user_identity u
                  ON u.id = t.manager_user_identity_id
                WHERE t.id = ?
                  AND t.manager_user_identity_id = ?
                  AND t.active = true
                  AND o.status = 'ACTIVE'
                  AND u.status = 'ACTIVE'
                  AND EXISTS (
                      SELECT 1
                      FROM organization_membership om
                      WHERE om.organization_id = t.organization_id
                        AND om.user_identity_id = t.manager_user_identity_id
                        AND om.state = 'ACTIVE'
                        AND om.valid_from <= ?
                        AND (om.valid_until IS NULL OR om.valid_until >= ?)
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            teamId,
            identityId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        ) == true
}

@Component
class JdbcRoleTeamAuthorizationProjectionBridge(
    private val jdbc: JdbcTemplate,
    private val projectionWriter: AuthorizationProjectionIntentWriter,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun syncOrganizationMembership(
        membershipId: UUID,
        occurredAt: Instant,
        eventId: UUID = UUID.randomUUID(),
    ) {
        val at = occurredAt.atOffset(ZoneOffset.UTC)
        val row = jdbc.query(
            """
            SELECT
                m.user_identity_id,
                m.organization_id,
                m.version,
                (
                    m.state = 'ACTIVE'
                    AND o.status = 'ACTIVE'
                    AND u.status = 'ACTIVE'
                    AND m.valid_from <= ?
                    AND (m.valid_until IS NULL OR m.valid_until >= ?)
                ) AS authority_current
            FROM organization_membership m
            JOIN organization o
              ON o.id = m.organization_id
            JOIN user_identity u
              ON u.id = m.user_identity_id
            WHERE m.id = ?
            FOR UPDATE OF m
            """.trimIndent(),
            { rs, _ ->
                OrganizationMembershipAuthorityRow(
                    identityId = rs.getObject(
                        "user_identity_id",
                        UUID::class.java,
                    ),
                    organizationId = rs.getObject(
                        "organization_id",
                        UUID::class.java,
                    ),
                    version = rs.getLong("version"),
                    current = rs.getBoolean("authority_current"),
                )
            },
            at,
            at,
            membershipId,
        ).singleOrNull()
            ?: error(
                "Organization membership $membershipId does not exist.",
            )

        projectionWriter.write(
            RoleTeamAuthorizationProjectionFactory.organizationMembership(
                eventId = eventId,
                identityId = row.identityId,
                membershipId = membershipId,
                organizationId = row.organizationId,
                membershipVersion = row.version,
                desiredState = if (row.current) {
                    AuthorizationDesiredState.PRESENT
                } else {
                    AuthorizationDesiredState.ABSENT
                },
                occurredAt = occurredAt,
            ),
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun syncTeamMembership(
        teamMembershipId: UUID,
        occurredAt: Instant,
        eventId: UUID = UUID.randomUUID(),
    ) {
        val at =
            occurredAt.atOffset(
                ZoneOffset.UTC,
            )

        val membership =
            jdbc.query(
                """
                SELECT
                    tm.user_identity_id,
                    tm.team_id
                FROM team_membership tm
                WHERE tm.id = ?
                FOR UPDATE OF tm
                """.trimIndent(),
                { rs, _ ->
                    TeamMembershipIdentityRow(
                        identityId =
                            rs.getObject(
                                "user_identity_id",
                                UUID::class.java,
                            ),
                        teamId =
                            rs.getObject(
                                "team_id",
                                UUID::class.java,
                            ),
                    )
                },
                teamMembershipId,
            ).singleOrNull()
                ?: error(
                    "Team membership $teamMembershipId does not exist.",
                )

        val aggregateVersion =
            jdbc.queryForObject(
                """
                INSERT INTO team_membership_authority_aggregate (
                    team_id,
                    user_identity_id,
                    generation,
                    updated_at
                )
                VALUES (?, ?, 1, ?)
                ON CONFLICT (
                    team_id,
                    user_identity_id
                )
                DO UPDATE SET
                    generation =
                        team_membership_authority_aggregate.generation + 1,
                    updated_at =
                        EXCLUDED.updated_at
                RETURNING generation
                """.trimIndent(),
                Long::class.java,
                membership.teamId,
                membership.identityId,
                at,
            ) ?: error(
                "Team membership authority aggregate did not return a generation.",
            )

        val current =
            jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM team_membership tm
                    JOIN team t
                      ON t.id = tm.team_id
                    JOIN organization o
                      ON o.id = t.organization_id
                    JOIN user_identity u
                      ON u.id = tm.user_identity_id
                    WHERE tm.team_id = ?
                      AND tm.user_identity_id = ?
                      AND t.active = true
                      AND o.status = 'ACTIVE'
                      AND u.status = 'ACTIVE'
                      AND tm.valid_from <= ?
                      AND (
                          tm.valid_until IS NULL
                          OR tm.valid_until >= ?
                      )
                      AND EXISTS (
                          SELECT 1
                          FROM organization_membership om
                          WHERE om.organization_id = t.organization_id
                            AND om.user_identity_id = tm.user_identity_id
                            AND om.state = 'ACTIVE'
                            AND om.valid_from <= ?
                            AND (
                                om.valid_until IS NULL
                                OR om.valid_until >= ?
                            )
                      )
                )
                """.trimIndent(),
                Boolean::class.java,
                membership.teamId,
                membership.identityId,
                at,
                at,
                at,
                at,
            ) == true

        projectionWriter.write(
            RoleTeamAuthorizationProjectionFactory
                .teamMembershipAggregate(
                    eventId = eventId,
                    identityId =
                        membership.identityId,
                    teamId =
                        membership.teamId,
                    aggregateVersion =
                        aggregateVersion,
                    desiredState =
                        if (current) {
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

    @Transactional(propagation = Propagation.MANDATORY)
    fun syncTeamManagerChange(
        teamId: UUID,
        previousManagerIdentityId: UUID?,
        occurredAt: Instant,
    ) {
        val at = occurredAt.atOffset(ZoneOffset.UTC)
        val row = jdbc.query(
            """
            SELECT
                t.manager_user_identity_id,
                t.version,
                (
                    t.manager_user_identity_id IS NOT NULL
                    AND t.active = true
                    AND o.status = 'ACTIVE'
                    AND manager.status = 'ACTIVE'
                    AND EXISTS (
                        SELECT 1
                        FROM organization_membership om
                        WHERE om.organization_id = t.organization_id
                          AND om.user_identity_id = t.manager_user_identity_id
                          AND om.state = 'ACTIVE'
                          AND om.valid_from <= ?
                          AND (om.valid_until IS NULL OR om.valid_until >= ?)
                    )
                ) AS manager_authority_current
            FROM team t
            JOIN organization o
              ON o.id = t.organization_id
            LEFT JOIN user_identity manager
              ON manager.id = t.manager_user_identity_id
            WHERE t.id = ?
            FOR UPDATE OF t
            """.trimIndent(),
            { rs, _ ->
                TeamManagerAuthorityRow(
                    managerIdentityId = rs.getObject(
                        "manager_user_identity_id",
                        UUID::class.java,
                    ),
                    version = rs.getLong("version"),
                    current = rs.getBoolean(
                        "manager_authority_current",
                    ),
                )
            },
            at,
            at,
            teamId,
        ).singleOrNull()
            ?: error("Team $teamId does not exist.")

        if (
            previousManagerIdentityId != null &&
            previousManagerIdentityId != row.managerIdentityId
        ) {
            projectionWriter.write(
                RoleTeamAuthorizationProjectionFactory.teamManager(
                    eventId = UUID.randomUUID(),
                    managerIdentityId = previousManagerIdentityId,
                    teamId = teamId,
                    teamVersion = row.version,
                    desiredState = AuthorizationDesiredState.ABSENT,
                    occurredAt = occurredAt,
                ),
            )
        }

        row.managerIdentityId?.let { currentManagerId ->
            projectionWriter.write(
                RoleTeamAuthorizationProjectionFactory.teamManager(
                    eventId = UUID.randomUUID(),
                    managerIdentityId = currentManagerId,
                    teamId = teamId,
                    teamVersion = row.version,
                    desiredState = if (row.current) {
                        AuthorizationDesiredState.PRESENT
                    } else {
                        AuthorizationDesiredState.ABSENT
                    },
                    occurredAt = occurredAt,
                ),
            )
        }
    }

    private data class OrganizationMembershipAuthorityRow(
        val identityId: UUID,
        val organizationId: UUID,
        val version: Long,
        val current: Boolean,
    )

    private data class TeamMembershipIdentityRow(
        val identityId: UUID,
        val teamId: UUID,
    )

    private data class TeamManagerAuthorityRow(
        val managerIdentityId: UUID?,
        val version: Long,
        val current: Boolean,
    )
}

@Configuration(proxyBeanMethods = false)
class RoleTeamAuthorizationConfiguration {
    @Bean
    @ConditionalOnProperty(
        prefix = "hiltech.authorization.openfga",
        name = ["enabled"],
        havingValue = "true",
    )
    fun roleTeamAuthorizationService(
        authorization: AuthorizationCheckPort,
        sourceAuthority: RoleTeamSourceAuthorityPort,
    ): RoleTeamAuthorizationService =
        RoleTeamAuthorizationService(
            authorization = authorization,
            sourceAuthority = sourceAuthority,
        )
}
