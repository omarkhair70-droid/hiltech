package com.hiltech.server.work

import com.hiltech.server.security.AuthorizationCheckPort
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionIntent
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.security.OpenFgaTuple
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

object WorkAssignmentAuthorizationRelations {
    fun assigned(
        assignment: WorkAssignmentRecord,
    ): OpenFgaTuple =
        when (assignment.targetType) {
            AssignmentTargetType.USER ->
                OpenFgaTuple(
                    subjectType = "user",
                    subjectId = assignment.targetId.toString(),
                    relation = "assigned_user",
                    objectType = "work_order",
                    objectId = assignment.workOrderId.toString(),
                )

            AssignmentTargetType.TEAM ->
                OpenFgaTuple(
                    subjectType = "team",
                    subjectId = assignment.targetId.toString() + "#member",
                    relation = "assigned_team",
                    objectType = "work_order",
                    objectId = assignment.workOrderId.toString(),
                )

            AssignmentTargetType.CREW ->
                OpenFgaTuple(
                    subjectType = "crew",
                    subjectId = assignment.targetId.toString() + "#member",
                    relation = "assigned_crew",
                    objectType = "work_order",
                    objectId = assignment.workOrderId.toString(),
                )

            AssignmentTargetType.SUBCONTRACTOR_ORGANIZATION ->
                OpenFgaTuple(
                    subjectType = "organization",
                    subjectId = assignment.targetId.toString() + "#member",
                    relation = "assigned_subcontractor",
                    objectType = "work_order",
                    objectId = assignment.workOrderId.toString(),
                )
        }

    fun crewMember(
        crewId: UUID,
        userIdentityId: UUID,
    ) =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = userIdentityId.toString(),
            relation = "member",
            objectType = "crew",
            objectId = crewId.toString(),
        )

    fun assignedAction(
        actorUserId: UUID,
        workOrderId: UUID,
        relation: String,
    ) =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = actorUserId.toString(),
            relation = relation,
            objectType = "work_order",
            objectId = workOrderId.toString(),
        )
}

data class CrewMemberProjectionContext(
    val membershipId: UUID,
    val crewId: UUID,
    val userIdentityId: UUID,
    val version: Long,
)

@Component
class WorkAssignmentAuthorizationProjectionBridge(
    private val writer: AuthorizationProjectionIntentWriter,
    private val jdbc: JdbcTemplate,
) {
    fun assignment(
        assignment: WorkAssignmentRecord,
        desiredState: AuthorizationDesiredState,
        eventId: UUID,
        at: Instant,
    ) {
        writer.write(
            AuthorizationProjectionIntent(
                eventId = eventId,
                tuple =
                    WorkAssignmentAuthorizationRelations
                        .assigned(assignment),
                desiredState = desiredState,
                sourceType = "WorkAssignment",
                sourceId = assignment.assignmentId.toString(),
                sourceVersion = assignment.version,
                eventType =
                    if (
                        desiredState ==
                        AuthorizationDesiredState.PRESENT
                    ) {
                        "WORK_ASSIGNMENT_GRANTED"
                    } else {
                        "WORK_ASSIGNMENT_REVOKED"
                    },
                occurredAt = at,
            ),
        )

        if (
            assignment.targetType ==
            AssignmentTargetType.CREW &&
            desiredState ==
            AuthorizationDesiredState.PRESENT
        ) {
            crewMembers(
                assignment.targetId,
                at,
            ).forEachIndexed { index, member ->
                writer.write(
                    AuthorizationProjectionIntent(
                        eventId =
                            UUID.nameUUIDFromBytes(
                                "$eventId:crew:$index:${member.membershipId}"
                                    .toByteArray(),
                            ),
                        tuple =
                            WorkAssignmentAuthorizationRelations
                                .crewMember(
                                    member.crewId,
                                    member.userIdentityId,
                                ),
                        desiredState =
                            AuthorizationDesiredState.PRESENT,
                        sourceType = "WorkCrewMember",
                        sourceId =
                            member.membershipId.toString(),
                        sourceVersion = member.version,
                        eventType =
                            "WORK_CREW_MEMBERSHIP_GRANTED",
                        occurredAt = at,
                    ),
                )
            }
        }
    }

    private fun crewMembers(
        crewId: UUID,
        at: Instant,
    ): List<CrewMemberProjectionContext> =
        jdbc.query(
            """
            SELECT
                wcm.id,
                wcm.crew_id,
                ui.id AS user_identity_id,
                wcm.version
            FROM work_crew_member wcm
            JOIN employee e
              ON e.id = wcm.employee_id
             AND e.organization_id = wcm.organization_id
            JOIN user_identity ui
              ON ui.person_id = e.person_id
             AND ui.status = 'ACTIVE'
            JOIN organization_membership om
              ON om.user_identity_id = ui.id
             AND om.organization_id = wcm.organization_id
             AND om.state = 'ACTIVE'
             AND om.valid_from <= ?
             AND (om.valid_until IS NULL OR om.valid_until > ?)
            WHERE wcm.crew_id = ?
              AND wcm.effective_from <= ?
              AND (wcm.effective_to IS NULL OR wcm.effective_to > ?)
            ORDER BY wcm.id
            """.trimIndent(),
            { rs, _ ->
                CrewMemberProjectionContext(
                    membershipId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    crewId =
                        rs.getObject(
                            "crew_id",
                            UUID::class.java,
                        ),
                    userIdentityId =
                        rs.getObject(
                            "user_identity_id",
                            UUID::class.java,
                        ),
                    version = rs.getLong("version"),
                )
            },
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
            crewId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )
}

interface WorkAssignmentSourceAuthorityPort {
    fun isCurrentAssignedUser(
        actorUserId: UUID,
        workOrderId: UUID,
        at: Instant,
    ): Boolean

    fun guardTuples(
        actorUserId: UUID,
        workOrderId: UUID,
        at: Instant,
    ): List<OpenFgaTuple>
}

@Component
class JdbcWorkAssignmentSourceAuthority(
    private val jdbc: JdbcTemplate,
) : WorkAssignmentSourceAuthorityPort {
    override fun isCurrentAssignedUser(
        actorUserId: UUID,
        workOrderId: UUID,
        at: Instant,
    ): Boolean =
        currentAssignmentRows(
            actorUserId,
            workOrderId,
            at,
        ).isNotEmpty()

    override fun guardTuples(
        actorUserId: UUID,
        workOrderId: UUID,
        at: Instant,
    ): List<OpenFgaTuple> {
        val assignment =
            currentAssignmentRows(
                actorUserId,
                workOrderId,
                at,
            ).singleOrNull() ?: return emptyList()
        return buildList {
            add(
                WorkAssignmentAuthorizationRelations
                    .assigned(assignment.record),
            )
            if (
                assignment.record.targetType ==
                AssignmentTargetType.CREW
            ) {
                add(
                    WorkAssignmentAuthorizationRelations
                        .crewMember(
                            assignment.record.targetId,
                            actorUserId,
                        ),
                )
            }
        }
    }

    private fun currentAssignmentRows(
        actorUserId: UUID,
        workOrderId: UUID,
        at: Instant,
    ): List<CurrentAssignmentRow> {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)
        return jdbc.query(
            """
            SELECT
                wa.id,
                wa.work_order_id,
                wa.target_type,
                wa.target_id,
                wa.assigned_at,
                wa.assigned_by,
                wa.valid_from,
                wa.valid_until,
                wa.state,
                wa.supersedes_assignment_id,
                wa.reason,
                wa.version,
                CASE wa.target_type
                    WHEN 'USER' THEN COALESCE(p.display_name, wa.target_id::text)
                    WHEN 'TEAM' THEN COALESCE(t.name, wa.target_id::text)
                    WHEN 'CREW' THEN COALESCE(c.name, wa.target_id::text)
                    WHEN 'SUBCONTRACTOR_ORGANIZATION' THEN COALESCE(o.display_name, wa.target_id::text)
                    ELSE wa.target_id::text
                END AS target_label
            FROM work_assignment wa
            LEFT JOIN user_identity target_ui
              ON wa.target_type = 'USER'
             AND target_ui.id = wa.target_id
            LEFT JOIN person p
              ON p.id = target_ui.person_id
            LEFT JOIN team t
              ON wa.target_type = 'TEAM'
             AND t.id = wa.target_id
            LEFT JOIN work_crew c
              ON wa.target_type = 'CREW'
             AND c.id = wa.target_id
            LEFT JOIN organization o
              ON wa.target_type = 'SUBCONTRACTOR_ORGANIZATION'
             AND o.id = wa.target_id
            WHERE wa.work_order_id = ?
              AND wa.state = 'ACTIVE'
              AND wa.valid_from <= ?
              AND (wa.valid_until IS NULL OR wa.valid_until > ?)
              AND (
                  (
                      wa.target_type = 'USER'
                      AND wa.target_id = ?
                      AND EXISTS (
                          SELECT 1
                          FROM user_identity ui_guard
                          JOIN organization_membership om_guard
                            ON om_guard.user_identity_id = ui_guard.id
                           AND om_guard.organization_id = wa.organization_id
                           AND om_guard.state = 'ACTIVE'
                           AND om_guard.valid_from <= ?
                           AND (om_guard.valid_until IS NULL OR om_guard.valid_until > ?)
                          WHERE ui_guard.id = wa.target_id
                            AND ui_guard.status = 'ACTIVE'
                      )
                  )
                  OR
                  (
                      wa.target_type = 'TEAM'
                      AND EXISTS (
                          SELECT 1
                          FROM team_membership tm
                          JOIN user_identity ui_guard
                            ON ui_guard.id = tm.user_identity_id
                           AND ui_guard.status = 'ACTIVE'
                          JOIN organization_membership om_guard
                            ON om_guard.user_identity_id = tm.user_identity_id
                           AND om_guard.organization_id = wa.organization_id
                           AND om_guard.state = 'ACTIVE'
                           AND om_guard.valid_from <= ?
                           AND (om_guard.valid_until IS NULL OR om_guard.valid_until > ?)
                          WHERE tm.team_id = wa.target_id
                            AND tm.user_identity_id = ?
                            AND tm.valid_from <= ?
                            AND (tm.valid_until IS NULL OR tm.valid_until > ?)
                      )
                  )
                  OR
                  (
                      wa.target_type = 'CREW'
                      AND EXISTS (
                          SELECT 1
                          FROM work_crew_member wcm
                          JOIN employee e
                            ON e.id = wcm.employee_id
                           AND e.organization_id = wcm.organization_id
                          JOIN user_identity ui
                            ON ui.person_id = e.person_id
                           AND ui.status = 'ACTIVE'
                          JOIN organization_membership om_guard
                            ON om_guard.user_identity_id = ui.id
                           AND om_guard.organization_id = wa.organization_id
                           AND om_guard.state = 'ACTIVE'
                           AND om_guard.valid_from <= ?
                           AND (om_guard.valid_until IS NULL OR om_guard.valid_until > ?)
                          WHERE wcm.crew_id = wa.target_id
                            AND ui.id = ?
                            AND wcm.effective_from <= ?
                            AND (wcm.effective_to IS NULL OR wcm.effective_to > ?)
                      )
                  )
                  OR
                  (
                      wa.target_type = 'SUBCONTRACTOR_ORGANIZATION'
                      AND EXISTS (
                          SELECT 1
                          FROM organization_membership om
                          JOIN user_identity ui_guard
                            ON ui_guard.id = om.user_identity_id
                           AND ui_guard.status = 'ACTIVE'
                          WHERE om.organization_id = wa.target_id
                            AND om.user_identity_id = ?
                            AND om.state = 'ACTIVE'
                            AND om.valid_from <= ?
                            AND (om.valid_until IS NULL OR om.valid_until > ?)
                      )
                  )
              )
            """.trimIndent(),
            { rs, _ ->
                CurrentAssignmentRow(
                    record =
                        WorkAssignmentRecord(
                            assignmentId =
                                rs.getObject(
                                    "id",
                                    UUID::class.java,
                                ),
                            workOrderId =
                                rs.getObject(
                                    "work_order_id",
                                    UUID::class.java,
                                ),
                            targetType =
                                AssignmentTargetType.valueOf(
                                    rs.getString("target_type"),
                                ),
                            targetId =
                                rs.getObject(
                                    "target_id",
                                    UUID::class.java,
                                ),
                            targetLabel =
                                rs.getString("target_label"),
                            lead = true,
                            state = rs.getString("state"),
                            assignedAt =
                                rs.getObject(
                                    "assigned_at",
                                    java.time.OffsetDateTime::class.java,
                                ).toInstant(),
                            assignedBy =
                                rs.getObject(
                                    "assigned_by",
                                    UUID::class.java,
                                ),
                            validFrom =
                                rs.getObject(
                                    "valid_from",
                                    java.time.OffsetDateTime::class.java,
                                ).toInstant(),
                            validUntil =
                                rs.getObject(
                                    "valid_until",
                                    java.time.OffsetDateTime::class.java,
                                )?.toInstant(),
                            supersedesAssignmentId =
                                rs.getObject(
                                    "supersedes_assignment_id",
                                    UUID::class.java,
                                ),
                            reason = rs.getString("reason"),
                            version = rs.getLong("version"),
                        ),
                )
            },
            workOrderId,
            timestamp,
            timestamp,
            actorUserId,
            timestamp,
            timestamp,
            timestamp,
            timestamp,
            actorUserId,
            timestamp,
            timestamp,
            timestamp,
            timestamp,
            actorUserId,
            timestamp,
            timestamp,
            actorUserId,
            timestamp,
            timestamp,
        )
    }

    private data class CurrentAssignmentRow(
        val record: WorkAssignmentRecord,
    )
}

interface WorkAssignmentAuthorizationPort {
    fun canUseAssignedWork(
        actorUserId: UUID,
        workOrderId: UUID,
        relation: String,
    ): Boolean
}

@Component
class SpringWorkAssignmentAuthorization(
    private val authorizationProvider:
        ObjectProvider<AuthorizationCheckPort>,
    private val source:
        WorkAssignmentSourceAuthorityPort,
    private val clock: Clock,
) : WorkAssignmentAuthorizationPort {
    override fun canUseAssignedWork(
        actorUserId: UUID,
        workOrderId: UUID,
        relation: String,
    ): Boolean {
        val at = clock.instant()
        if (
            !source.isCurrentAssignedUser(
                actorUserId,
                workOrderId,
                at,
            )
        ) {
            return false
        }
        val authorization =
            authorizationProvider.ifAvailable
                ?: return false
        val guards =
            source.guardTuples(
                actorUserId,
                workOrderId,
                at,
            )
        if (guards.isEmpty()) {
            return false
        }
        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple =
                    WorkAssignmentAuthorizationRelations
                        .assignedAction(
                            actorUserId,
                            workOrderId,
                            relation,
                        ),
                failClosedGuardTuples = guards,
            ),
        )
    }
}
