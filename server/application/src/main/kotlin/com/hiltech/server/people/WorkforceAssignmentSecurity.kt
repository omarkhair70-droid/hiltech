package com.hiltech.server.people

import com.hiltech.server.security.JdbcRoleTeamAuthorizationProjectionBridge
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class WorkforceAssignmentSecuritySyncResult(
    val assignmentId: UUID,
    val teamMembershipId: UUID,
    val created: Boolean,
)

@Component
class WorkforceAssignmentSecuritySynchronizer(
    private val jdbc: JdbcTemplate,
    private val roleTeamProjection:
        JdbcRoleTeamAuthorizationProjectionBridge,
    private val events:
        ApplicationEventPublisher,
) {
    @Transactional
    fun syncAssignment(
        assignmentId: UUID,
        occurredAt: Instant,
        correlationId: String?,
    ): WorkforceAssignmentSecuritySyncResult? {
        val context =
            loadContext(
                assignmentId =
                    assignmentId,
                occurredAt =
                    occurredAt,
            ) ?: return null

        val identityIds =
            currentIdentityIds(
                personId =
                    context.personId,
                organizationId =
                    context.organizationId,
                at = occurredAt,
            )

        if (identityIds.isEmpty()) {
            return null
        }

        check(identityIds.size == 1) {
            "Employee Person ${context.personId} has multiple current HILTECH identities in organization ${context.organizationId}."
        }

        val identityId =
            identityIds.single()

        val existing =
            existingMembership(
                assignmentId,
            )

        if (existing != null) {
            check(
                existing.teamId ==
                    context.teamId &&
                    existing.identityId ==
                    identityId,
            ) {
                "People-owned Team membership no longer matches its WorkforceAssignment."
            }

            return WorkforceAssignmentSecuritySyncResult(
                assignmentId =
                    assignmentId,
                teamMembershipId =
                    existing.membershipId,
                created = false,
            )
        }

        val membershipId =
            deterministicMembershipId(
                assignmentId,
            )

        jdbc.update(
            """
            INSERT INTO team_membership (
                id,
                team_id,
                user_identity_id,
                role_in_team,
                valid_from,
                valid_until,
                version,
                source_workforce_assignment_id
            )
            VALUES (
                ?, ?, ?,
                ?, ?, NULL,
                1, ?
            )
            """.trimIndent(),
            membershipId,
            context.teamId,
            identityId,
            context.roleCode,
            context.effectiveFrom
                .atOffset(
                    ZoneOffset.UTC,
                ),
            assignmentId,
        )

        roleTeamProjection
            .syncTeamMembership(
                teamMembershipId =
                    membershipId,
                occurredAt =
                    occurredAt,
            )

        events.publishEvent(
            WorkforceAssignmentSecuritySynchronized(
                assignmentId =
                    assignmentId,
                organizationId =
                    context.organizationId,
                employeeId =
                    context.employeeId,
                teamId =
                    context.teamId,
                userIdentityId =
                    identityId,
                teamMembershipId =
                    membershipId,
                sourceVersion =
                    context.version,
                occurredAt =
                    occurredAt,
                correlationId =
                    correlationId,
            ),
        )

        return WorkforceAssignmentSecuritySyncResult(
            assignmentId =
                assignmentId,
            teamMembershipId =
                membershipId,
            created = true,
        )
    }

    @Transactional
    fun replaceAssignment(
        previousAssignmentId: UUID,
        newAssignmentId: UUID,
        occurredAt: Instant,
        correlationId: String?,
    ) {
        val previousMembership =
            existingMembership(
                previousAssignmentId,
            )

        val newContext =
            loadContext(
                assignmentId =
                    newAssignmentId,
                occurredAt =
                    occurredAt,
            )

        var newMembershipId: UUID? = null
        var newIdentityId: UUID? = null

        if (newContext != null) {
            val identityIds =
                currentIdentityIds(
                    personId =
                        newContext.personId,
                    organizationId =
                        newContext.organizationId,
                    at = occurredAt,
                )

            if (identityIds.isNotEmpty()) {
                check(identityIds.size == 1) {
                    "Employee Person ${newContext.personId} has multiple current HILTECH identities in organization ${newContext.organizationId}."
                }

                newIdentityId =
                    identityIds.single()
                newMembershipId =
                    deterministicMembershipId(
                        newAssignmentId,
                    )

                jdbc.update(
                    """
                    INSERT INTO team_membership (
                        id,
                        team_id,
                        user_identity_id,
                        role_in_team,
                        valid_from,
                        valid_until,
                        version,
                        source_workforce_assignment_id
                    )
                    VALUES (
                        ?, ?, ?,
                        ?, ?, NULL,
                        1, ?
                    )
                    ON CONFLICT (
                        source_workforce_assignment_id
                    )
                    WHERE
                        source_workforce_assignment_id
                            IS NOT NULL
                    DO NOTHING
                    """.trimIndent(),
                    newMembershipId,
                    newContext.teamId,
                    newIdentityId,
                    newContext.roleCode,
                    newContext.effectiveFrom
                        .atOffset(
                            ZoneOffset.UTC,
                        ),
                    newAssignmentId,
                )
            }
        }

        previousMembership?.let {
            membership ->
            jdbc.update(
                """
                UPDATE team_membership
                SET valid_until = ?,
                    version = version + 1
                WHERE id = ?
                  AND (
                      valid_until IS NULL
                      OR valid_until > ?
                  )
                """.trimIndent(),
                occurredAt.atOffset(
                    ZoneOffset.UTC,
                ),
                membership.membershipId,
                occurredAt.atOffset(
                    ZoneOffset.UTC,
                ),
            )
        }

        // Team membership validity uses an inclusive valid_until boundary.
        // Evaluate the aggregate just after the revision boundary so the
        // old source is expired and the new source is current.
        val projectionAt =
            occurredAt.plusMillis(1)

        previousMembership?.let {
            roleTeamProjection.syncTeamMembership(
                teamMembershipId =
                    it.membershipId,
                occurredAt =
                    projectionAt,
            )
        }

        newMembershipId?.let {
            membershipId ->
            roleTeamProjection.syncTeamMembership(
                teamMembershipId =
                    membershipId,
                occurredAt =
                    projectionAt,
            )

            val context =
                requireNotNull(newContext)
            events.publishEvent(
                WorkforceAssignmentSecuritySynchronized(
                    assignmentId =
                        newAssignmentId,
                    organizationId =
                        context.organizationId,
                    employeeId =
                        context.employeeId,
                    teamId =
                        context.teamId,
                    userIdentityId =
                        requireNotNull(
                            newIdentityId,
                        ),
                    teamMembershipId =
                        membershipId,
                    sourceVersion =
                        context.version,
                    occurredAt =
                        occurredAt,
                    correlationId =
                        correlationId,
                ),
            )
        }
    }

    private fun loadContext(
        assignmentId: UUID,
        occurredAt: Instant,
    ): SecurityContext? =
        jdbc.query(
            """
            SELECT
                wa.id,
                wa.organization_id,
                wa.employee_id,
                wa.team_id,
                wa.role_code,
                wa.effective_from,
                wa.version,
                e.person_id
            FROM workforce_assignment wa
            JOIN employee e
              ON e.id = wa.employee_id
            JOIN team t
              ON t.id = wa.team_id
            WHERE wa.id = ?
              AND wa.state = 'ACTIVE'
              AND wa.effective_from <= ?
              AND t.active = true
              AND t.organization_id =
                  wa.organization_id
            FOR UPDATE OF wa
            """.trimIndent(),
            { rs, _ ->
                SecurityContext(
                    assignmentId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    employeeId =
                        rs.getObject(
                            "employee_id",
                            UUID::class.java,
                        ),
                    personId =
                        rs.getObject(
                            "person_id",
                            UUID::class.java,
                        ),
                    teamId =
                        rs.getObject(
                            "team_id",
                            UUID::class.java,
                        ),
                    roleCode =
                        rs.getString(
                            "role_code",
                        ),
                    effectiveFrom =
                        rs.getObject(
                            "effective_from",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    version =
                        rs.getLong("version"),
                )
            },
            assignmentId,
            occurredAt.atOffset(
                ZoneOffset.UTC,
            ),
        ).singleOrNull()

    private fun currentIdentityIds(
        personId: UUID,
        organizationId: UUID,
        at: Instant,
    ): List<UUID> {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        return jdbc.query(
            """
            SELECT DISTINCT ui.id
            FROM user_identity ui
            JOIN organization_membership om
              ON om.user_identity_id = ui.id
            WHERE ui.person_id = ?
              AND ui.status = 'ACTIVE'
              AND om.organization_id = ?
              AND om.state = 'ACTIVE'
              AND om.valid_from <= ?
              AND (
                  om.valid_until IS NULL
                  OR om.valid_until >= ?
              )
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
            timestamp,
            timestamp,
        )
    }

    private fun existingMembership(
        assignmentId: UUID,
    ): ExistingMembership? =
        jdbc.query(
            """
            SELECT
                id,
                team_id,
                user_identity_id
            FROM team_membership
            WHERE source_workforce_assignment_id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                ExistingMembership(
                    membershipId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    teamId =
                        rs.getObject(
                            "team_id",
                            UUID::class.java,
                        ),
                    identityId =
                        rs.getObject(
                            "user_identity_id",
                            UUID::class.java,
                        ),
                )
            },
            assignmentId,
        ).singleOrNull()

    private fun deterministicMembershipId(
        assignmentId: UUID,
    ): UUID =
        UUID.nameUUIDFromBytes(
            (
                "workforce-team-membership:" +
                    assignmentId
            ).toByteArray(
                StandardCharsets.UTF_8,
            ),
        )

    private data class SecurityContext(
        val assignmentId: UUID,
        val organizationId: UUID,
        val employeeId: UUID,
        val personId: UUID,
        val teamId: UUID,
        val roleCode: String,
        val effectiveFrom: Instant,
        val version: Long,
    )

    private data class ExistingMembership(
        val membershipId: UUID,
        val teamId: UUID,
        val identityId: UUID,
    )
}

@Component
class WorkforceAssignmentIdentityLinkedListener(
    private val persistence:
        WorkforceAssignmentPersistencePort,
    private val synchronizer:
        WorkforceAssignmentSecuritySynchronizer,
) {
    @ApplicationModuleListener
    fun on(
        event: EmployeeIdentityLinked,
    ) {
        val assignmentId =
            persistence
                .currentAssignmentIdForEmployee(
                    employeeId =
                        event.employeeId,
                    at = event.occurredAt,
                )
                ?: return

        synchronizer.syncAssignment(
            assignmentId =
                assignmentId,
            occurredAt =
                event.occurredAt,
            correlationId =
                event.correlationId,
        )
    }
}
