package com.hiltech.server.approval

import java.time.Instant
import java.util.UUID

const val OWNER_FINAL_AUTHORITY_KEY: String = "OWNER_FINAL"

enum class ApprovalPrincipalType {
    USER,
    TEAM,
}

enum class ApprovalPolicyMode {
    NO_APPROVAL_REQUIRED,
    SINGLE,
}

enum class ApprovalRequestState {
    PENDING,
    APPROVED,
    REJECTED,
    CHANGE_REQUESTED,
    SUPERSEDED,
    CANCELLED,
}

enum class ApprovalDecisionType {
    APPROVE,
    REJECT,
    REQUEST_CHANGE,
}

data class ApprovalSubjectRef(
    val organizationId: UUID,
    val subjectType: String,
    val subjectId: UUID,
    val subjectVersion: Long,
)

data class ApprovalAuthorityPrincipal(
    val principalType: ApprovalPrincipalType,
    val principalId: UUID,
    val authorityKey: String,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
)
