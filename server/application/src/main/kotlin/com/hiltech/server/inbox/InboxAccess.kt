package com.hiltech.server.inbox

import com.hiltech.server.approval.ApprovalAuthorizationPort
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalRequestState
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

interface InboxSourceAccessPort {
    fun canRead(
        actorUserId: UUID,
        item: InboxItemRecord,
        at: Instant,
    ): Boolean

    fun canAct(
        actorUserId: UUID,
        item: InboxItemRecord,
        at: Instant,
    ): Boolean
}

@Component
class ApprovalInboxSourceAccess(
    private val approval:
        ApprovalPersistencePort,
    private val authorization:
        ApprovalAuthorizationPort,
) : InboxSourceAccessPort {
    override fun canRead(
        actorUserId: UUID,
        item: InboxItemRecord,
        at: Instant,
    ): Boolean {
        val snapshot =
            loadMatchingApproval(
                item,
            ) ?: return false

        val authority =
            approval.resolveCurrentAuthority(
                organizationId =
                    snapshot.organizationId,
                authorityKey =
                    snapshot.authorityKey,
                at = at,
            ) ?: return false

        if (
            authority.principalType !=
                snapshot.assignmentPrincipalType ||
            authority.principalId !=
                snapshot.assignmentPrincipalId
        ) {
            return false
        }

        if (
            !approval.actorMatchesAuthority(
                actorUserId =
                    actorUserId,
                authority = authority,
                at = at,
            )
        ) {
            return false
        }

        return when (
            item.state
        ) {
            InboxItemState.OPEN ->
                canAct(
                    actorUserId =
                        actorUserId,
                    item = item,
                    at = at,
                )

            InboxItemState.RESOLVED ->
                snapshot.requestState !=
                    ApprovalRequestState.PENDING
        }
    }

    override fun canAct(
        actorUserId: UUID,
        item: InboxItemRecord,
        at: Instant,
    ): Boolean {
        if (
            item.state !=
            InboxItemState.OPEN
        ) {
            return false
        }

        val snapshot =
            loadMatchingApproval(
                item,
            ) ?: return false

        if (
            snapshot.requestState !=
                ApprovalRequestState.PENDING ||
            snapshot.assignmentState !=
                "ASSIGNED"
        ) {
            return false
        }

        val authority =
            approval.resolveCurrentAuthority(
                organizationId =
                    snapshot.organizationId,
                authorityKey =
                    snapshot.authorityKey,
                at = at,
            ) ?: return false

        if (
            authority.principalType !=
                snapshot.assignmentPrincipalType ||
            authority.principalId !=
                snapshot.assignmentPrincipalId
        ) {
            return false
        }

        if (
            !approval.actorMatchesAuthority(
                actorUserId =
                    actorUserId,
                authority = authority,
                at = at,
            )
        ) {
            return false
        }

        return authorization.canDecide(
            actorUserId =
                actorUserId,
            approvalRequestId =
                snapshot.requestId,
            assignmentPrincipalType =
                snapshot
                    .assignmentPrincipalType,
            assignmentPrincipalId =
                snapshot
                    .assignmentPrincipalId,
        )
    }

    private fun loadMatchingApproval(
        item: InboxItemRecord,
    ) =
        if (
            item.sourceType ==
                "APPROVAL_REQUEST" &&
            item.actionKey ==
                "DECIDE_APPROVAL"
        ) {
            approval.loadAttentionSnapshot(
                item.sourceId,
            )?.takeIf { snapshot ->
                snapshot.requestId ==
                    item.sourceId &&
                    snapshot.organizationId ==
                    item.organizationId &&
                    snapshot
                        .assignmentPrincipalType
                        .name ==
                    item.targetPrincipalType
                        .name &&
                    snapshot
                        .assignmentPrincipalId ==
                    item.targetPrincipalId
            }
        } else {
            null
        }
}
