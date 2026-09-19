package com.hiltech.server.organizations

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class ActiveOrganizationMembership(
    val membershipId: UUID,
    val organizationId: UUID,
    val organizationCode: String,
    val organizationDisplayName: String,
    val organizationType: String,
    val membershipType: String,
    val roleLabel: String?,
    val membershipVersion: Long,
)

data class ActiveTeamMembership(
    val teamMembershipId: UUID,
    val teamId: UUID,
    val organizationId: UUID,
    val code: String,
    val name: String,
    val roleInTeam: String?,
)

interface OrganizationIdentityContextPort {
    fun activeMemberships(
        identityId: UUID,
        at: Instant,
    ): List<ActiveOrganizationMembership>

    fun activeTeams(
        identityId: UUID,
        at: Instant,
    ): List<ActiveTeamMembership>
}

@Component
class JdbcOrganizationIdentityContext(
    private val jdbc: JdbcTemplate,
) : OrganizationIdentityContextPort {
    override fun activeMemberships(
        identityId: UUID,
        at: Instant,
    ): List<ActiveOrganizationMembership> =
        jdbc.query(
            """
            SELECT
                m.id AS membership_id,
                m.organization_id,
                o.organization_code,
                o.display_name,
                o.organization_type,
                m.membership_type,
                m.role_label,
                m.version AS membership_version
            FROM organization_membership m
            JOIN organization o
              ON o.id = m.organization_id
            WHERE m.user_identity_id = ?
              AND m.state = 'ACTIVE'
              AND o.status = 'ACTIVE'
              AND m.valid_from <= ?
              AND (m.valid_until IS NULL OR m.valid_until >= ?)
            ORDER BY o.organization_code, m.id
            """.trimIndent(),
            { rs, _ ->
                ActiveOrganizationMembership(
                    membershipId = rs.getObject(
                        "membership_id",
                        UUID::class.java,
                    ),
                    organizationId = rs.getObject(
                        "organization_id",
                        UUID::class.java,
                    ),
                    organizationCode = rs.getString(
                        "organization_code",
                    ),
                    organizationDisplayName = rs.getString(
                        "display_name",
                    ),
                    organizationType = rs.getString(
                        "organization_type",
                    ),
                    membershipType = rs.getString(
                        "membership_type",
                    ),
                    roleLabel = rs.getString("role_label"),
                    membershipVersion = rs.getLong(
                        "membership_version",
                    ),
                )
            },
            identityId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )

    override fun activeTeams(
        identityId: UUID,
        at: Instant,
    ): List<ActiveTeamMembership> =
        jdbc.query(
            """
            SELECT
                tm.id AS team_membership_id,
                t.id AS team_id,
                t.organization_id,
                t.code,
                t.name,
                tm.role_in_team
            FROM team_membership tm
            JOIN team t
              ON t.id = tm.team_id
            JOIN organization o
              ON o.id = t.organization_id
            WHERE tm.user_identity_id = ?
              AND t.active = true
              AND o.status = 'ACTIVE'
              AND tm.valid_from <= ?
              AND (tm.valid_until IS NULL OR tm.valid_until >= ?)
            ORDER BY t.code, tm.id
            """.trimIndent(),
            { rs, _ ->
                ActiveTeamMembership(
                    teamMembershipId = rs.getObject(
                        "team_membership_id",
                        UUID::class.java,
                    ),
                    teamId = rs.getObject(
                        "team_id",
                        UUID::class.java,
                    ),
                    organizationId = rs.getObject(
                        "organization_id",
                        UUID::class.java,
                    ),
                    code = rs.getString("code"),
                    name = rs.getString("name"),
                    roleInTeam = rs.getString("role_in_team"),
                )
            },
            identityId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )
}
