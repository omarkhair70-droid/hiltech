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

    fun projectManagerTeam(
        teamId: UUID,
        projectId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "team",
            subjectId = "$teamId#member",
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
            context.projectManagerIdentityId ==
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

        context.projectManagerTeamId
            ?.let { teamId ->
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
                            .projectManagerTeam(
                                teamId = teamId,
                                projectId =
                                    context.projectId,
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
                    CASE
                        WHEN pr.principal_type = 'EMPLOYEE'
                        THEN manager_identity.id
                        ELSE NULL
                    END AS project_manager_identity_id,
                    CASE
                        WHEN pr.principal_type = 'TEAM'
                        THEN pr.principal_team_id
                        ELSE NULL
                    END AS project_manager_team_id
                FROM work_order wo
                JOIN project p
                  ON p.id = wo.project_id
                JOIN organization o
                  ON o.id = p.organization_id
                LEFT JOIN project_responsibility pr
                  ON pr.project_id = p.id
                 AND pr.responsibility_key = 'PROJECT_MANAGER'
                 AND pr.state = 'ACTIVE'
                LEFT JOIN employee manager_employee
                  ON manager_employee.id =
                        pr.principal_employee_id
                 AND manager_employee.organization_id =
                        p.organization_id
                 AND manager_employee.state IN (
                        'PREBOARDING',
                        'ACTIVE'
                 )
                LEFT JOIN LATERAL (
                    SELECT ui.id
                    FROM user_identity ui
                    JOIN organization_membership om
                      ON om.user_identity_id = ui.id
                    WHERE ui.person_id =
                            manager_employee.person_id
                      AND ui.status = 'ACTIVE'
                      AND om.organization_id =
                            p.organization_id
                      AND om.state = 'ACTIVE'
                      AND om.valid_from <= ?
                      AND (
                          om.valid_until IS NULL
                          OR om.valid_until >= ?
                      )
                    ORDER BY ui.id
                    LIMIT 1
                ) manager_identity
                  ON true
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
                        projectManagerIdentityId =
                            rs.getObject(
                                "project_manager_identity_id",
                                UUID::class.java,
                            ),
                        projectManagerTeamId =
                            rs.getObject(
                                "project_manager_team_id",
                                UUID::class.java,
                            ),
                    )
                },
                at.atOffset(ZoneOffset.UTC),
                at.atOffset(ZoneOffset.UTC),
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
            projectManagerIdentityId =
                base.projectManagerIdentityId,
            projectManagerTeamId =
                base.projectManagerTeamId,
            assignedUserCurrent =
                assignedUserCurrent,
            assignedTeamIds =
                assignedTeamIds,
        )
    }

    private data class BaseContext(
        val projectId: UUID,
        val organizationId: UUID,
        val projectManagerIdentityId: UUID?,
        val projectManagerTeamId: UUID?,
    )

    private data class WorkOrderAuthorityContext(
        val organizationId: UUID,
        val projectId: UUID,
        val projectManagerIdentityId: UUID?,
        val projectManagerTeamId: UUID?,
        val assignedUserCurrent: Boolean,
        val assignedTeamIds: List<UUID>,
    )
}
