package com.hiltech.server.finance

import com.hiltech.server.approval.CompanyReviewPolicyKeys

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/** Source-derived documentation-review result, NOT financial authorization. */
data class ExpenseDocumentationReviewStatus(
    val expenseDraftId: UUID,
    val sourceVersion: Long,
    val verifiedReceiptCount: Int,
    val documentationReviewRequestId: UUID?,
    val documentationReviewState: String,
    val policyKey: String,
    val currentSource: Boolean = true,
    val expenseApproved: Boolean = false,
    val payableRecognized: Boolean = false,
    val reimbursementAuthorized: Boolean = false,
    val cashMovementRecorded: Boolean = false,
)

@Service
class FinanceExpenseDocumentationStatusService(
    private val employees: FinanceEmployeeDraftService,
    private val source: FinanceExpenseDocumentationApprovalSubject,
    private val approvals: ApprovalEngineService,
    private val environment: Environment,
) {
    fun mine(actor: UUID, org: UUID, expenseId: UUID): ExpenseDocumentationReviewStatus {
        if (!environment.getProperty(
                "hiltech.finance.expenses.documentation-review.enabled",
                Boolean::class.java, false,
            )) unavailable()
        val key = CompanyReviewPolicyKeys.resolve(environment, "hiltech.finance.expenses.documentation-review.policy-key")
        if (!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) unavailable()

        val own = employees.expense(actor, org, expenseId)
        val current = source.current(org, expenseId) ?: unavailable()
        if (current.expenseDraftId != own.id ||
            current.organizationId != own.organizationId ||
            current.createdByUserId != actor ||
            current.employeeId != own.employeeId ||
            own.kind != "EXPENSE_REIMBURSEMENT" ||
            own.state != "DRAFT_RECEIPT_PENDING" ||
            current.currency != own.currency ||
            BigDecimal(current.amount).compareTo(BigDecimal(own.amount)) != 0
        ) unavailable()

        val review = approvals.subjectStatus(
            ApprovalSubjectRef(
                organizationId = org,
                subjectType = FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT,
                subjectId = expenseId,
                subjectVersion = current.sourceVersion,
            ),
            key,
        )
        return ExpenseDocumentationReviewStatus(
            expenseDraftId = expenseId,
            sourceVersion = current.sourceVersion,
            verifiedReceiptCount = current.verifiedEvidenceIds.size,
            documentationReviewRequestId = review?.approvalRequestId,
            documentationReviewState = review?.state?.name ?: "NOT_REQUESTED",
            policyKey = key,
        )
    }

    private fun unavailable(): Nothing = throw ProductApiException(
        "FINANCE_EXPENSE_DOCUMENTATION_STATUS_UNAVAILABLE",
        "Expense review status is not available to this current employee.",
        HttpStatus.NOT_FOUND,
    )
}

@RestController
class FinanceExpenseDocumentationStatusController(
    private val statuses: FinanceExpenseDocumentationStatusService,
) {
    @GetMapping(
        "/v1/finance/me/organizations/{orgId}/expenses/{draftId}/documentation-review",
    )
    fun mine(
        http: HttpServletRequest,
        @PathVariable orgId: UUID,
        @PathVariable draftId: UUID,
    ): ExpenseDocumentationReviewStatus {
        val actor = HiltechRequestContext.current(http).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication required.", HttpStatus.UNAUTHORIZED,
            )
        return statuses.mine(actor, orgId, draftId)
    }
}
