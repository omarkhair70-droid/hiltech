package com.hiltech.server.identity

import com.hiltech.server.security.JdbcRoleTeamAuthorizationProjectionBridge
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class EmploymentAccessSnapshot(
    val identityIds: List<UUID>,
    val activeOrganizationMemberships: Int,
    val activeSessions: Int,
) {
    val clear: Boolean
        get() =
            activeOrganizationMemberships == 0 &&
                activeSessions == 0
}

interface EmploymentAccessRevocationPort {
    fun snapshot(
        personId: UUID,
        organizationId: UUID,
        at: Instant,
    ): EmploymentAccessSnapshot

    fun revokeForEmployment(
        personId: UUID,
        organizationId: UUID,
        at: Instant,
    ): EmploymentAccessSnapshot
}

@Component
class JdbcEmploymentAccessRevocation(
    private val jdbc: JdbcTemplate,
    private val roleTeamProjection:
        JdbcRoleTeamAuthorizationProjectionBridge,
) : EmploymentAccessRevocationPort {
    override fun snapshot(
        personId: UUID,
        organizationId: UUID,
        at: Instant,
    ): EmploymentAccessSnapshot {
        val identityIds =
            identityIds(
                personId =
                    personId,
                organizationId =
                    organizationId,
            )
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        if (identityIds.isEmpty()) {
            return EmploymentAccessSnapshot(
                identityIds = emptyList(),
                activeOrganizationMemberships = 0,
                activeSessions = 0,
            )
        }

        val activeMemberships =
            jdbc.queryForObject(
                """
                SELECT count(*)
                FROM organization_membership om
                JOIN user_identity ui
                  ON ui.id = om.user_identity_id
                WHERE ui.person_id = ?
                  AND om.organization_id = ?
                  AND om.state = 'ACTIVE'
                  AND om.valid_from <= ?
                  AND (
                      om.valid_until IS NULL
                      OR om.valid_until >= ?
                  )
                """.trimIndent(),
                Int::class.java,
                personId,
                organizationId,
                timestamp,
                timestamp,
            ) ?: 0

        val activeSessions =
            jdbc.queryForObject(
                """
                SELECT count(*)
                FROM identity_session s
                JOIN user_identity ui
                  ON ui.id = s.user_identity_id
                WHERE ui.person_id = ?
                  AND EXISTS (
                      SELECT 1
                      FROM organization_membership om
                      WHERE om.user_identity_id = ui.id
                        AND om.organization_id = ?
                  )
                  AND s.revoked_at IS NULL
                  AND s.expires_at > ?
                """.trimIndent(),
                Int::class.java,
                personId,
                organizationId,
                timestamp,
            ) ?: 0

        return EmploymentAccessSnapshot(
            identityIds = identityIds,
            activeOrganizationMemberships =
                activeMemberships,
            activeSessions = activeSessions,
        )
    }

    override fun revokeForEmployment(
        personId: UUID,
        organizationId: UUID,
        at: Instant,
    ): EmploymentAccessSnapshot {
        val identities =
            identityIds(
                personId =
                    personId,
                organizationId =
                    organizationId,
            )

        if (identities.isEmpty()) {
            return snapshot(
                personId =
                    personId,
                organizationId =
                    organizationId,
                at = at,
            )
        }

        val timestamp =
            at.atOffset(ZoneOffset.UTC)
        val membershipIds =
            activeMembershipIds(
                personId =
                    personId,
                organizationId =
                    organizationId,
                at = at,
            )

        jdbc.update(
            """
            UPDATE organization_membership om
            SET state = 'ENDED',
                valid_until =
                    CASE
                        WHEN om.valid_until IS NULL
                             OR om.valid_until > ?
                        THEN ?
                        ELSE om.valid_until
                    END,
                version = om.version + 1
            FROM user_identity ui
            WHERE ui.id = om.user_identity_id
              AND ui.person_id = ?
              AND om.organization_id = ?
              AND om.state = 'ACTIVE'
              AND om.valid_from <= ?
              AND (
                  om.valid_until IS NULL
                  OR om.valid_until >= ?
              )
            """.trimIndent(),
            timestamp,
            timestamp,
            personId,
            organizationId,
            timestamp,
            timestamp,
        )

        membershipIds.forEach {
            membershipId ->
            roleTeamProjection
                .syncOrganizationMembership(
                    membershipId =
                        membershipId,
                    occurredAt =
                        at.plusMillis(1),
                )
        }

        jdbc.update(
            """
            UPDATE identity_session s
            SET revoked_at = ?,
                version = s.version + 1
            FROM user_identity ui
            WHERE ui.id = s.user_identity_id
              AND ui.person_id = ?
              AND EXISTS (
                  SELECT 1
                  FROM organization_membership om
                  WHERE om.user_identity_id = ui.id
                    AND om.organization_id = ?
              )
              AND s.revoked_at IS NULL
            """.trimIndent(),
            timestamp,
            personId,
            organizationId,
        )

        return snapshot(
            personId = personId,
            organizationId =
                organizationId,
            at = at,
        )
    }

    private fun activeMembershipIds(
        personId: UUID,
        organizationId: UUID,
        at: Instant,
    ): List<UUID> {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        return jdbc.query(
            """
            SELECT om.id
            FROM organization_membership om
            JOIN user_identity ui
              ON ui.id = om.user_identity_id
            WHERE ui.person_id = ?
              AND om.organization_id = ?
              AND om.state = 'ACTIVE'
              AND om.valid_from <= ?
              AND (
                  om.valid_until IS NULL
                  OR om.valid_until >= ?
              )
            ORDER BY om.id
            FOR UPDATE OF om
            """.trimIndent(),
            { rs, _ ->
                rs.getObject(
                    "id",
                    UUID::class.java,
                )
            },
            personId,
            organizationId,
            timestamp,
            timestamp,
        )
    }

    private fun identityIds(
        personId: UUID,
        organizationId: UUID,
    ): List<UUID> =
        jdbc.query(
            """
            SELECT DISTINCT ui.id
            FROM user_identity ui
            JOIN organization_membership om
              ON om.user_identity_id = ui.id
            WHERE ui.person_id = ?
              AND om.organization_id = ?
            ORDER BY ui.id
            """.trimIndent(),
            { rs, _ ->
                rs.getObject(
                    "id",
                    UUID::class.java,
                )
            },
            personId,
            organizationId,
        )
}
