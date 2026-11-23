package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

data class RequestFinanceMoneyDraftPolicyReview(val operationId: UUID)

data class FinanceMoneyDraftPolicyReviewStatus(
    val draftId: UUID,
    val kind: String,
    val sourceVersion: Long,
    val policyKey: String,
    val policyVersion: Int,
    val approvalRequestId: UUID?,
    val requestState: String,
    val existingRequest: Boolean = false,
    val replayed: Boolean = false,
    val moneyIssued: Boolean = false,
    val personalLoanRecognized: Boolean = false,
    val cashCustodyRecorded: Boolean = false,
    val payrollDeductionAuthorized: Boolean = false,
    val settlementRecorded: Boolean = false,
)

@Service
class FinanceMoneyDraftPolicyReviewService(
    private val drafts: FinanceEmployeeDraftService,
    private val sources: FinanceMoneyDraftPolicyApprovalSubject,
    private val approvals: ApprovalEngineService,
) {
    fun request(
        actor: UUID, org: UUID, kind: FinanceEmployeeMoneyDraftKind,
        id: UUID, operationId: UUID, correlationId: String,
    ): FinanceMoneyDraftPolicyReviewStatus {
        val pair = authorized(actor, org, kind, id)
        val (source, subject) = pair
        val existing = approvals.subjectStatus(subject, source.policyKey)
        if (existing != null) {
            // Unique subject/version/policy: don't duplicate an existing
            // pending request. A finalized request cannot be reopened
            // against the same immutable source.
            if (existing.state != ApprovalRequestState.PENDING ||
                existing.subjectVersion != source.sourceVersion) unavailable()
            return status(source, existing.approvalRequestId, existing.state.name,
                existingRequest = true)
        }

        val result = approvals.requestException(
            actorUserId = actor,
            operationId = operationId,
            subject = subject,
            policyKey = source.policyKey,
            reasonCode = kind.reasonCode,
            safeReasonSummary = null,
            correlationId = correlationId,
            allowAuthorityBypass = false,
        )
        if (!result.approvalRequired || result.approvalRequestId == null ||
            result.state != ApprovalRequestState.PENDING ||
            result.policyKey != source.policyKey ||
            result.policyVersion != source.policyVersion) unavailable()

        return status(source, result.approvalRequestId, result.state.name,
            replayed = result.replayed)
    }

    fun mine(
        actor: UUID, org: UUID, kind: FinanceEmployeeMoneyDraftKind, id: UUID,
    ): FinanceMoneyDraftPolicyReviewStatus {
        val (source, subject) = authorized(actor, org, kind, id)
        val existing = approvals.subjectStatus(subject, source.policyKey)
        return status(source, existing?.approvalRequestId,
            existing?.state?.name ?: "NOT_REQUESTED",
            existingRequest = existing != null)
    }

    private fun authorized(
        actor: UUID, org: UUID, kind: FinanceEmployeeMoneyDraftKind, id: UUID,
    ): Pair<FinanceMoneyDraftPolicySnapshot, ApprovalSubjectRef> {
        val personalDraft = when (kind) {
            FinanceEmployeeMoneyDraftKind.ADVANCE -> drafts.advance(actor, org, id)
            FinanceEmployeeMoneyDraftKind.IMPREST -> drafts.imprest(actor, org, id)
        }
        val source = sources.current(kind, org, id) ?: unavailable()
        if (source.createdByUserId != actor || source.id != personalDraft.id ||
            source.organizationId != personalDraft.organizationId ||
            source.employeeId != personalDraft.employeeId ||
            personalDraft.kind != kind.kindCode ||
            personalDraft.state != kind.stateCode ||
            source.currency != personalDraft.currency ||
            source.purpose != personalDraft.purpose ||
            source.projectId != personalDraft.projectId ||
            source.amount.compareTo(BigDecimal(personalDraft.amount)) != 0) unavailable()
        return source to ApprovalSubjectRef(
            organizationId = org, subjectType = kind.subjectType,
            subjectId = id, subjectVersion = source.sourceVersion,
        )
    }

    private fun status(
        source: FinanceMoneyDraftPolicySnapshot, id: UUID?, state: String,
        existingRequest: Boolean = false,
        replayed: Boolean = false,
    ) = FinanceMoneyDraftPolicyReviewStatus(
        draftId = source.id,
        kind = source.kind.kindCode,
        sourceVersion = source.sourceVersion,
        policyKey = source.policyKey,
        policyVersion = source.policyVersion,
        approvalRequestId = id,
        requestState = state,
        existingRequest = existingRequest,
        replayed = replayed,
    )

    private fun unavailable(): Nothing = throw ProductApiException(
        "FINANCE_MONEY_POLICY_REVIEW_UNAVAILABLE",
        "Draft or current Finance review policy is unavailable.",
        HttpStatus.NOT_FOUND,
    )
}

@RestController
class FinanceMoneyDraftPolicyReviewController(
    private val service: FinanceMoneyDraftPolicyReviewService,
) {
    @PostMapping(
        "/v1/finance/me/organizations/{orgId}/{kind}/{draftId}/request-policy-review",
    )
    fun request(
        http: HttpServletRequest,
        @PathVariable orgId: UUID,
        @PathVariable kind: String,
        @PathVariable draftId: UUID,
        @RequestBody body: RequestFinanceMoneyDraftPolicyReview,
    ): FinanceMoneyDraftPolicyReviewStatus {
        val ctx = HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey, body.operationId)
        return service.request(
            actor(http), orgId, kindOf(kind), draftId, body.operationId,
            ctx.correlationId,
        )
    }

    @GetMapping(
        "/v1/finance/me/organizations/{orgId}/{kind}/{draftId}/policy-review",
    )
    fun mine(
        http: HttpServletRequest,
        @PathVariable orgId: UUID,
        @PathVariable kind: String,
        @PathVariable draftId: UUID,
    ): FinanceMoneyDraftPolicyReviewStatus =
        service.mine(actor(http), orgId, kindOf(kind), draftId)

    private fun kindOf(value: String): FinanceEmployeeMoneyDraftKind =
        FinanceEmployeeMoneyDraftKind.entries.singleOrNull { it.path == value }
            ?: throw ProductApiException(
                "FINANCE_MONEY_POLICY_REVIEW_UNAVAILABLE",
                "Unsupported money draft kind.", HttpStatus.NOT_FOUND,
            )

    private fun actor(http: HttpServletRequest): UUID =
        HiltechRequestContext.current(http).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication required.", HttpStatus.UNAUTHORIZED,
            )
}
