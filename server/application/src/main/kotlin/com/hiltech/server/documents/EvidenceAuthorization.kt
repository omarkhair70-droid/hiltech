package com.hiltech.server.documents

import com.hiltech.server.security.AuthorizationCheckPort
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionIntent
import com.hiltech.server.security.OpenFgaTuple
import com.hiltech.server.security.RoleTeamAuthorizationRelations
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

object EvidenceAuthorizationRelations {
    fun evidenceWorkOrder(
        workOrderId: UUID,
        evidenceId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "work_order",
            subjectId = workOrderId.toString(),
            relation = "work_order",
            objectType = "evidence",
            objectId = evidenceId.toString(),
        )

    fun evidenceCreator(
        identityId: UUID,
        evidenceId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = identityId.toString(),
            relation = "creator",
            objectType = "evidence",
            objectId = evidenceId.toString(),
        )

    fun workOrderAssignedUser(
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

    fun workOrderAssignedTeam(
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

    fun workOrderCanSubmit(
        identityId: UUID,
        workOrderId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = identityId.toString(),
            relation = "can_submit_completion",
            objectType = "work_order",
            objectId = workOrderId.toString(),
        )
}

object EvidenceAuthorizationProjectionFactory {
    fun workOrder(
        evidenceId: UUID,
        workOrderId: UUID,
        evidenceVersion: Long,
        occurredAt: Instant,
        eventId: UUID = UUID.randomUUID(),
    ): AuthorizationProjectionIntent =
        AuthorizationProjectionIntent(
            eventId = eventId,
            tuple = EvidenceAuthorizationRelations.evidenceWorkOrder(
                workOrderId = workOrderId,
                evidenceId = evidenceId,
            ),
            desiredState = AuthorizationDesiredState.PRESENT,
            sourceType = "Evidence",
            sourceId = evidenceId.toString(),
            sourceVersion = evidenceVersion,
            eventType = "EVIDENCE_WORK_ORDER_AUTHORITY_CHANGED",
            occurredAt = occurredAt,
        )

    fun creator(
        evidenceId: UUID,
        identityId: UUID,
        evidenceVersion: Long,
        occurredAt: Instant,
        eventId: UUID = UUID.randomUUID(),
    ): AuthorizationProjectionIntent =
        AuthorizationProjectionIntent(
            eventId = eventId,
            tuple = EvidenceAuthorizationRelations.evidenceCreator(
                identityId = identityId,
                evidenceId = evidenceId,
            ),
            desiredState = AuthorizationDesiredState.PRESENT,
            sourceType = "Evidence",
            sourceId = evidenceId.toString(),
            sourceVersion = evidenceVersion,
            eventType = "EVIDENCE_CREATOR_AUTHORITY_CHANGED",
            occurredAt = occurredAt,
        )
}

interface EvidenceTargetAuthorizationPort {
    fun canReserveForWorkOrder(
        identityId: UUID,
        workOrderId: UUID,
    ): Boolean

    fun canFinalizeEvidence(
        identityId: UUID,
        evidenceId: UUID,
        workOrderId: UUID,
        creatorIdentityId: UUID,
    ): Boolean
}

@Component
class JdbcEvidenceTargetAuthorization(
    private val jdbc: JdbcTemplate,
    private val authorization: AuthorizationCheckPort,
    private val teamAuthority: RoleTeamSourceAuthorityPort,
    private val clock: Clock,
) : EvidenceTargetAuthorizationPort {
    override fun canReserveForWorkOrder(
        identityId: UUID,
        workOrderId: UUID,
    ): Boolean =
        canSubmitCompletion(
            identityId = identityId,
            workOrderId = workOrderId,
            at = clock.instant(),
        )

    override fun canFinalizeEvidence(
        identityId: UUID,
        evidenceId: UUID,
        workOrderId: UUID,
        creatorIdentityId: UUID,
    ): Boolean {
        if (identityId == creatorIdentityId) {
            val creator =
                EvidenceAuthorizationRelations.evidenceCreator(
                    identityId = identityId,
                    evidenceId = evidenceId,
                )

            if (
                authorization.isAllowed(
                    AuthorizationCheckRequest(
                        checkTuple = creator,
                        failClosedGuardTuples = listOf(creator),
                    ),
                )
            ) {
                return true
            }
        }

        return canSubmitCompletion(
            identityId = identityId,
            workOrderId = workOrderId,
            at = clock.instant(),
        )
    }

    private fun canSubmitCompletion(
        identityId: UUID,
        workOrderId: UUID,
        at: Instant,
    ): Boolean {
        val assignments =
            currentAssignments(
                workOrderId = workOrderId,
                at = at,
            )

        assignments
            .filter {
                it.targetType == "USER" &&
                    it.targetId == identityId
            }
            .forEach {
                val assignedUser =
                    EvidenceAuthorizationRelations
                        .workOrderAssignedUser(
                            identityId = identityId,
                            workOrderId = workOrderId,
                        )

                if (
                    authorization.isAllowed(
                        AuthorizationCheckRequest(
                            checkTuple =
                                EvidenceAuthorizationRelations
                                    .workOrderCanSubmit(
                                        identityId = identityId,
                                        workOrderId = workOrderId,
                                    ),
                            failClosedGuardTuples =
                                listOf(assignedUser),
                        ),
                    )
                ) {
                    return true
                }
            }

        assignments
            .filter { it.targetType == "TEAM" }
            .forEach { assignment ->
                val teamId = assignment.targetId

                if (
                    !teamAuthority.isTeamMemberCurrent(
                        identityId = identityId,
                        teamId = teamId,
                        at = at,
                    )
                ) {
                    return@forEach
                }

                val assignedTeam =
                    EvidenceAuthorizationRelations
                        .workOrderAssignedTeam(
                            teamId = teamId,
                            workOrderId = workOrderId,
                        )
                val teamMember =
                    RoleTeamAuthorizationRelations
                        .teamMember(
                            identityId = identityId,
                            teamId = teamId,
                        )

                if (
                    authorization.isAllowed(
                        AuthorizationCheckRequest(
                            checkTuple =
                                EvidenceAuthorizationRelations
                                    .workOrderCanSubmit(
                                        identityId = identityId,
                                        workOrderId = workOrderId,
                                    ),
                            failClosedGuardTuples =
                                listOf(
                                    assignedTeam,
                                    teamMember,
                                ),
                        ),
                    )
                ) {
                    return true
                }
            }

        return false
    }

    private fun currentAssignments(
        workOrderId: UUID,
        at: Instant,
    ): List<CurrentAssignment> {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        return jdbc.query(
            """
            SELECT target_type, target_id
            FROM work_assignment
            WHERE work_order_id = ?
              AND state = 'ACTIVE'
              AND valid_from <= ?
              AND (valid_until IS NULL OR valid_until >= ?)
              AND target_type IN ('USER','TEAM')
            """.trimIndent(),
            { rs, _ ->
                CurrentAssignment(
                    targetType =
                        rs.getString("target_type"),
                    targetId =
                        rs.getObject(
                            "target_id",
                            UUID::class.java,
                        ),
                )
            },
            workOrderId,
            timestamp,
            timestamp,
        )
    }

    private data class CurrentAssignment(
        val targetType: String,
        val targetId: UUID,
    )
}
