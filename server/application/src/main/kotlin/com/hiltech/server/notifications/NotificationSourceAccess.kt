package com.hiltech.server.notifications

import com.hiltech.server.approval.ApprovalAuthorizationPort
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.ApprovalRequestState
import org.springframework.stereotype.Component
import java.time.Instant

interface NotificationSourceAccessPort {
    fun canDispatch(
        intent: NotificationIntentRecord,
        at: Instant,
    ): Boolean
}

@Component
class ApprovalNotificationSourceAccess(
    private val approval:
        ApprovalPersistencePort,
    private val authorization:
        ApprovalAuthorizationPort,
) : NotificationSourceAccessPort {
    override fun canDispatch(
        intent: NotificationIntentRecord,
        at: Instant,
    ): Boolean {
        if (
            intent.sourceType !=
                "APPROVAL_REQUEST" ||
            intent.deepLinkType !=
                "APPROVAL_REQUEST" ||
            intent.deepLinkId !=
                intent.sourceId ||
            intent.notificationClass !=
                "ACTION_REQUIRED"
        ) {
            return false
        }

        val snapshot =
            approval.loadAttentionSnapshot(
                intent.sourceId,
            ) ?: return false

        if (
            snapshot.requestId !=
                intent.sourceId ||
            snapshot.organizationId !=
                intent.organizationId ||
            snapshot.requestVersion !=
                intent.sourceVersion ||
            snapshot.requestState !=
                ApprovalRequestState.PENDING ||
            snapshot.assignmentState !=
                "ASSIGNED" ||
            snapshot.assignmentPrincipalType !=
                ApprovalPrincipalType.USER ||
            snapshot.assignmentPrincipalId !=
                intent.recipientUserIdentityId
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
                ApprovalPrincipalType.USER ||
            authority.principalId !=
                snapshot.assignmentPrincipalId
        ) {
            return false
        }

        if (
            !approval.actorMatchesAuthority(
                actorUserId =
                    intent.recipientUserIdentityId,
                authority = authority,
                at = at,
            )
        ) {
            return false
        }

        return authorization.canDecide(
            actorUserId =
                intent.recipientUserIdentityId,
            approvalRequestId =
                snapshot.requestId,
            assignmentPrincipalType =
                snapshot.assignmentPrincipalType,
            assignmentPrincipalId =
                snapshot.assignmentPrincipalId,
        )
    }
}
