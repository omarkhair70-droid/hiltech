package com.hiltech.server.notifications

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalRequested
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
class ApprovalNotificationProjectionListener(
    private val approval:
        ApprovalPersistencePort,
    private val projection:
        NotificationProjectionPort,
) {
    @ApplicationModuleListener
    fun on(
        event: ApprovalRequested,
    ) {
        val snapshot =
            requireNotNull(
                approval.loadAttentionSnapshot(
                    event.approvalRequestId,
                ),
            ) {
                "Approval request disappeared before Notification projection."
            }

        if (
            snapshot.requestState !=
                ApprovalRequestState.PENDING ||
            snapshot.assignmentState !=
                "ASSIGNED" ||
            snapshot.assignmentPrincipalType !=
                ApprovalPrincipalType.USER
        ) {
            return
        }

        projection.projectApprovalRequested(
            snapshot = snapshot,
            sourceEventId =
                event.eventId,
            sourceOccurredAt =
                event.occurredAt,
            correlationId =
                event.correlationId,
        )
    }
}
