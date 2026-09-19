package com.hiltech.server.inbox

import com.hiltech.server.approval.ApprovalApproved
import com.hiltech.server.approval.ApprovalChangeRequested
import com.hiltech.server.approval.ApprovalDecisionEvent
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalRejected
import com.hiltech.server.approval.ApprovalRequested
import com.hiltech.server.approval.ApprovalSuperseded
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ApprovalInboxProjectionListener(
    private val approval:
        ApprovalPersistencePort,
    private val projection:
        InboxProjectionPort,
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
                "Approval request disappeared before Inbox projection."
            }

        projection.projectApproval(
            snapshot = snapshot,
            sourceEventId =
                event.eventId,
            sourceOccurredAt =
                event.occurredAt,
            correlationId =
                event.correlationId,
        )
    }

    @ApplicationModuleListener
    fun on(
        event: ApprovalApproved,
    ) = projectTerminal(event)

    @ApplicationModuleListener
    fun on(
        event: ApprovalRejected,
    ) = projectTerminal(event)

    @ApplicationModuleListener
    fun on(
        event: ApprovalChangeRequested,
    ) = projectTerminal(event)

    @ApplicationModuleListener
    fun on(
        event: ApprovalSuperseded,
    ) = projectTerminal(event)

    private fun projectTerminal(
        event: ApprovalDecisionEvent,
    ) {
        val snapshot =
            requireNotNull(
                approval.loadAttentionSnapshot(
                    event.approvalRequestId,
                ),
            ) {
                "Approval request disappeared before Inbox resolution."
            }

        projection.projectApproval(
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
