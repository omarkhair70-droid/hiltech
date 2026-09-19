package com.hiltech.server.approval

import com.hiltech.server.security.AuthorizationCheckPort
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.OpenFgaTuple
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.util.UUID

interface ApprovalAuthorizationPort {
    fun canDecide(
        actorUserId: UUID,
        approvalRequestId: UUID,
        assignmentPrincipalType: ApprovalPrincipalType,
        assignmentPrincipalId: UUID,
    ): Boolean
}

@Component
class SpringApprovalAuthorization(
    private val authorizationProvider:
        ObjectProvider<AuthorizationCheckPort>,
) : ApprovalAuthorizationPort {
    override fun canDecide(
        actorUserId: UUID,
        approvalRequestId: UUID,
        assignmentPrincipalType: ApprovalPrincipalType,
        assignmentPrincipalId: UUID,
    ): Boolean {
        val authorization =
            authorizationProvider.ifAvailable
                ?: return false

        val projectedAssignment =
            ApprovalAuthorizationRelations
                .approver(
                    principalType =
                        assignmentPrincipalType,
                    principalId =
                        assignmentPrincipalId,
                    approvalRequestId =
                        approvalRequestId,
                )

        return authorization.isAllowed(
            AuthorizationCheckRequest(
                checkTuple =
                    OpenFgaTuple(
                        subjectType = "user",
                        subjectId =
                            actorUserId.toString(),
                        relation = "can_decide",
                        objectType =
                            "approval_request",
                        objectId =
                            approvalRequestId
                                .toString(),
                    ),
                failClosedGuardTuples =
                    listOf(
                        projectedAssignment,
                    ),
            ),
        )
    }
}

object ApprovalAuthorizationRelations {
    fun requester(
        actorUserId: UUID,
        approvalRequestId: UUID,
    ): OpenFgaTuple =
        OpenFgaTuple(
            subjectType = "user",
            subjectId = actorUserId.toString(),
            relation = "requester",
            objectType = "approval_request",
            objectId = approvalRequestId.toString(),
        )

    fun approver(
        principalType: ApprovalPrincipalType,
        principalId: UUID,
        approvalRequestId: UUID,
    ): OpenFgaTuple =
        when (principalType) {
            ApprovalPrincipalType.USER ->
                OpenFgaTuple(
                    subjectType = "user",
                    subjectId =
                        principalId.toString(),
                    relation = "approver",
                    objectType =
                        "approval_request",
                    objectId =
                        approvalRequestId
                            .toString(),
                )

            ApprovalPrincipalType.TEAM ->
                OpenFgaTuple(
                    subjectType = "team",
                    subjectId =
                        principalId
                            .toString() +
                            "#member",
                    relation = "approver",
                    objectType =
                        "approval_request",
                    objectId =
                        approvalRequestId
                            .toString(),
                )
        }
}
