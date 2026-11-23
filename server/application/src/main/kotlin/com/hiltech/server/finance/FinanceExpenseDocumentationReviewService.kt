package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

data class RequestExpenseDocumentationReview(val operationId: UUID)

data class ExpenseDocumentationReviewReceipt(
    val expenseDraftId: UUID,
    val sourceVersion: Long,
    val verifiedReceiptEvidenceIds: List<UUID>,
    val documentationReviewRequestId: UUID,
    val documentationReviewState: String,
    val policyKey: String,
    val policyVersion: Int,
    val replayed: Boolean,
    val expenseApproved: Boolean = false,
    val payableRecognized: Boolean = false,
    val reimbursementAuthorized: Boolean = false,
    val payrollDeductionAuthorized: Boolean = false,
    val cashMovementRecorded: Boolean = false,
)

/**
 * This sends a RECEIPT-DOCUMENTATION review into the EXISTING approval
 * engine with a company-activated SINGLE reviewer policy. This does not
 * approve an expense, book a liability, fund an advance or move money.
 */
@Service
class FinanceExpenseDocumentationReviewService(
    private val employeeDrafts: FinanceEmployeeDraftService,
    private val source: FinanceExpenseDocumentationApprovalSubject,
    private val approvalEngine: ApprovalEngineService,
    private val environment: Environment,
) {
    fun request(
        actor: UUID,
        org: UUID,
        expenseDraftId: UUID,
        operationId: UUID,
        correlationId: String,
    ): ExpenseDocumentationReviewReceipt {
        if (!environment.getProperty(
                "hiltech.finance.expenses.documentation-review.enabled",
                Boolean::class.java, false,
            )) unavailable()
        val key = environment.getProperty(
            "hiltech.finance.expenses.documentation-review.policy-key", "",
        ).trim()
        if (!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) unavailable()

        // Current user identity must still be the original author/employee.
        // Employee role alone, Finance reviewer privileges or another
        // person's organization membership never suffice.
        val ownDraft = employeeDrafts.expense(actor, org, expenseDraftId)
        val snapshot = source.current(org, expenseDraftId) ?: unavailable()
        if (snapshot.createdByUserId != actor ||
            ownDraft.id != snapshot.expenseDraftId ||
            ownDraft.organizationId != snapshot.organizationId ||
            ownDraft.employeeId != snapshot.employeeId ||
            ownDraft.kind != "EXPENSE_REIMBURSEMENT" ||
            ownDraft.state != "DRAFT_RECEIPT_PENDING" ||
            ownDraft.currency != snapshot.currency ||
            BigDecimal(ownDraft.amount).compareTo(BigDecimal(snapshot.amount)) != 0
        ) unavailable()

        val result = approvalEngine.requestException(
            actorUserId = actor,
            operationId = operationId,
            subject = ApprovalSubjectRef(
                organizationId = org,
                subjectType = FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT,
                subjectId = expenseDraftId,
                subjectVersion = snapshot.sourceVersion,
            ),
            policyKey = key,
            reasonCode = "FINANCE_EXPENSE_RECEIPT_DOCUMENTATION_REVIEW",
            safeReasonSummary = null,
            correlationId = correlationId,
            allowAuthorityBypass = false,
        )
        // Fail closed. A NO_APPROVAL_REQUIRED policy never counts as review.
        if (!result.approvalRequired ||
            result.approvalRequestId == null ||
            result.state?.name != "PENDING" ||
            result.policyKey != key
        ) unavailable()

        return ExpenseDocumentationReviewReceipt(
            expenseDraftId = expenseDraftId,
            sourceVersion = snapshot.sourceVersion,
            verifiedReceiptEvidenceIds = snapshot.verifiedEvidenceIds,
            documentationReviewRequestId = result.approvalRequestId,
            documentationReviewState = result.state.name,
            policyKey = result.policyKey,
            policyVersion = result.policyVersion,
            replayed = result.replayed,
        )
    }

    private fun unavailable(): Nothing = throw ProductApiException(
        "FINANCE_EXPENSE_DOCUMENTATION_REVIEW_UNAVAILABLE",
        "Expense documentation review is not configured or currently eligible.",
        HttpStatus.NOT_FOUND,
    )
}

@RestController
class FinanceExpenseDocumentationReviewController(
    private val reviews: FinanceExpenseDocumentationReviewService,
) {
    @PostMapping(
        "/v1/finance/me/organizations/{orgId}/expenses/{draftId}/request-documentation-review",
    )
    fun request(
        http: HttpServletRequest,
        @PathVariable orgId: UUID,
        @PathVariable draftId: UUID,
        @RequestBody body: RequestExpenseDocumentationReview,
    ): ExpenseDocumentationReviewReceipt {
        val ctx = HiltechRequestContext.current(http)
        val actor = ctx.identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication required.", HttpStatus.UNAUTHORIZED,
            )
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey, body.operationId)
        return reviews.request(
            actor = actor, org = orgId, expenseDraftId = draftId,
            operationId = body.operationId, correlationId = ctx.correlationId,
        )
    }
}
