package com.hiltech.server.activity

import com.hiltech.server.security.AuthorizationCheckPort
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.OpenFgaTuple
import com.hiltech.server.security.RoleTeamAuthorizationRelations
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

fun interface WorkOrderActivityAuthorizationPort {
    fun canViewWorkOrder(
        identityId: UUID,
        workOrderId: UUID,
    ): Boolean
}

object ActivityAuthorizationRelations {
    fun organizationMember(
        identityId: UUID,
        organizationId: UUID,
    ): OpenFgaTuple =
        RoleTeamAuthorizationRelations
            .organizationMember(
                identityId = identityId,
                organizationId = organizationId,
            )

    fun workOrderCanView(
        identityId: UUID,
        workOrderId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = identityId.toString(),
            relation = "can_view",
            objectType = "work_order",
            objectId = workOrderId.toString(),
        )

    fun assignedUser(
        identityId: UUID,
        workOrderId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = identityId.toString(),
            relation = "assigned_user",
            objectType = "work_order",
            objectId = workOrderId.toString(),
        )

    fun assignedTeam(
        teamId: UUID,
        workOrderId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "team",
            subjectId = "$teamId#member",
            relation = "assigned_team",
            objectType = "work_order",
            objectId = workOrderId.toString(),
        )

    fun projectManager(
        identityId: UUID,
        projectId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = identityId.toString(),
            relation = "project_manager",
            objectType = "project",
            objectId = projectId.toString(),
        )
}

@Component
class JdbcWorkOrderActivityAuthorization(
    private val jdbc: JdbcTemplate,
    private val authorization: AuthorizationCheckPort,
    private val sourceAuthority:
        RoleTeamSourceAuthorityPort,
    private val clock: Clock,
) : WorkOrderActivityAuthorizationPort {
    override fun canViewWorkOrder(
        identityId: UUID,
        workOrderId: UUID,
    ): Boolean {
        val at = clock.instant()
        val context =
            loadContext(
                identityId = identityId,
                workOrderId = workOrderId,
                at = at,
            ) ?: return false

        if (
            !sourceAuthority
                .isOrganizationMemberCurrent(
                    identityId =
                        identityId,
                    organizationId =
                        context.organizationId,
                    at = at,
                )
        ) {
            return false
        }

        val guards =
            mutableListOf(
                ActivityAuthorizationRelations
                    .organizationMember(
                        identityId =
                            identityId,
                        organizationId =
                            context.organizationId,
                    ),
            )
        var supportedCurrentSource = false

        if (
            context.projectManagerId ==
            identityId
        ) {
            supportedCurrentSource = true
            guards +=
                ActivityAuthorizationRelations
                    .projectManager(
                        identityId = identityId,
                        projectId =
                            context.projectId,
                    )
        }

        if (context.assignedUserCurrent) {
            supportedCurrentSource = true
            guards +=
                ActivityAuthorizationRelations
                    .assignedUser(
                        identityId = identityId,
                        workOrderId =
                            workOrderId,
                    )
        }

        context.assignedTeamIds
            .forEach { teamId ->
                if (
                    sourceAuthority
                        .isTeamMemberCurrent(
                            identityId =
                                identityId,
                            teamId = teamId,
                            at = at,
                        )
                ) {
                    supportedCurrentSource = true
                    guards +=
                        ActivityAuthorizationRelations
                            .assignedTeam(
                                teamId = teamId,
                                workOrderId =
                                    workOrderId,
                            )
                    guards +=
                        RoleTeamAuthorizationRelations
                            .teamMember(
                                identityId =
                                    identityId,
                                teamId = teamId,
                            )
                }
            }

        if (!supportedCurrentSource) {
            return false
        }

        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple =
                    ActivityAuthorizationRelations
                        .workOrderCanView(
                            identityId =
                                identityId,
                            workOrderId =
                                workOrderId,
                        ),
                failClosedGuardTuples =
                    guards.distinctBy {
                        it.relationKey
                    },
            ),
        )
    }

    private fun loadContext(
        identityId: UUID,
        workOrderId: UUID,
        at: Instant,
    ): WorkOrderAuthorityContext? {
        val base =
            jdbc.query(
                """
                SELECT
                    wo.project_id,
                    p.organization_id,
                    p.project_manager_id
                FROM work_order wo
                JOIN project p
                  ON p.id = wo.project_id
                JOIN organization o
                  ON o.id = p.organization_id
                WHERE wo.id = ?
                  AND o.status = 'ACTIVE'
                  AND p.lifecycle_state <> 'CLOSED'
                """.trimIndent(),
                { rs, _ ->
                    BaseContext(
                        projectId =
                            rs.getObject(
                                "project_id",
                                UUID::class.java,
                            ),
                        organizationId =
                            rs.getObject(
                                "organization_id",
                                UUID::class.java,
                            ),
                        projectManagerId =
                            rs.getObject(
                                "project_manager_id",
                                UUID::class.java,
                            ),
                    )
                },
                workOrderId,
            ).singleOrNull()
                ?: return null

        val timestamp =
            at.atOffset(ZoneOffset.UTC)
        val assignedUserCurrent =
            jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM work_assignment wa
                    WHERE wa.work_order_id = ?
                      AND wa.target_type = 'USER'
                      AND wa.target_id = ?
                      AND wa.state = 'ACTIVE'
                      AND wa.valid_from <= ?
                      AND (
                          wa.valid_until IS NULL
                          OR wa.valid_until >= ?
                      )
                )
                """.trimIndent(),
                Boolean::class.java,
                workOrderId,
                identityId,
                timestamp,
                timestamp,
            ) == true

        val assignedTeamIds =
            jdbc.query(
                """
                SELECT wa.target_id
                FROM work_assignment wa
                WHERE wa.work_order_id = ?
                  AND wa.target_type = 'TEAM'
                  AND wa.state = 'ACTIVE'
                  AND wa.valid_from <= ?
                  AND (
                      wa.valid_until IS NULL
                      OR wa.valid_until >= ?
                  )
                """.trimIndent(),
                { rs, _ ->
                    rs.getObject(
                        "target_id",
                        UUID::class.java,
                    )
                },
                workOrderId,
                timestamp,
                timestamp,
            )

        return WorkOrderAuthorityContext(
            organizationId =
                base.organizationId,
            projectId = base.projectId,
            projectManagerId =
                base.projectManagerId,
            assignedUserCurrent =
                assignedUserCurrent,
            assignedTeamIds =
                assignedTeamIds,
        )
    }

    private data class BaseContext(
        val projectId: UUID,
        val organizationId: UUID,
        val projectManagerId: UUID?,
    )

    private data class WorkOrderAuthorityContext(
        val organizationId: UUID,
        val projectId: UUID,
        val projectManagerId: UUID?,
        val assignedUserCurrent: Boolean,
        val assignedTeamIds: List<UUID>,
    )
}
