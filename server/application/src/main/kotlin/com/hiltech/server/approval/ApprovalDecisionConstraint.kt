package com.hiltech.server.approval

import java.util.UUID

/** Source-owned business restrictions also apply through the generic decision endpoint. */
interface ApprovalDecisionConstraint {
    fun supports(subjectType: String): Boolean
    fun canDecide(actor: UUID, context: ApprovalDecisionContext, decision: ApprovalDecisionType): Boolean
}
