package com.hiltech.server.approval

import java.time.Instant
import java.util.UUID

data class ApprovalRequested(
    val eventId: UUID,
    val approvalRequestId: UUID,
    val organizationId: UUID,
    val subjectType: String,
    val subjectId: UUID,
    val subjectVersion: Long,
    val policyKey: String,
    val policyVersion: Int,
    val authorityKey: String,
    val requesterIdentityId: UUID,
    val occurredAt: Instant,
    val correlationId: String,
)

sealed interface ApprovalDecisionEvent {
    val eventId: UUID
    val approvalRequestId: UUID
    val organizationId: UUID
    val subjectType: String
    val subjectId: UUID
    val subjectVersion: Long
    val actorIdentityId: UUID
    val occurredAt: Instant
    val correlationId: String
}

data class ApprovalApproved(
    override val eventId: UUID,
    override val approvalRequestId: UUID,
    override val organizationId: UUID,
    override val subjectType: String,
    override val subjectId: UUID,
    override val subjectVersion: Long,
    override val actorIdentityId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : ApprovalDecisionEvent

data class ApprovalRejected(
    override val eventId: UUID,
    override val approvalRequestId: UUID,
    override val organizationId: UUID,
    override val subjectType: String,
    override val subjectId: UUID,
    override val subjectVersion: Long,
    override val actorIdentityId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : ApprovalDecisionEvent

data class ApprovalChangeRequested(
    override val eventId: UUID,
    override val approvalRequestId: UUID,
    override val organizationId: UUID,
    override val subjectType: String,
    override val subjectId: UUID,
    override val subjectVersion: Long,
    override val actorIdentityId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : ApprovalDecisionEvent

data class ApprovalSuperseded(
    override val eventId: UUID,
    override val approvalRequestId: UUID,
    override val organizationId: UUID,
    override val subjectType: String,
    override val subjectId: UUID,
    override val subjectVersion: Long,
    override val actorIdentityId: UUID,
    override val occurredAt: Instant,
    override val correlationId: String,
) : ApprovalDecisionEvent
