package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalRequestResult
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.util.UUID

class FinanceExpenseDocumentationReviewServiceTest {
    private val employeeDrafts = Mockito.mock(FinanceEmployeeDraftService::class.java)
    private val subject = Mockito.mock(FinanceExpenseDocumentationApprovalSubject::class.java)
    private val engine = Mockito.mock(ApprovalEngineService::class.java)
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val draft = UUID.randomUUID()
    private val employee = UUID.randomUUID()
    private val receipt = UUID.randomUUID()
    private val operationId = UUID.randomUUID()
    private val policy = "TEST_EXPENSE_DOCUMENTS"

    private fun service(enabled: Boolean = false) : FinanceExpenseDocumentationReviewService {
        val env = StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("review-test",
            mapOf(
                "hiltech.finance.expenses.documentation-review.enabled" to enabled,
                "hiltech.finance.expenses.documentation-review.policy-key" to policy,
            )))
        return FinanceExpenseDocumentationReviewService(
            employeeDrafts, subject, engine, env,
        )
    }

    private fun own() = FinanceEmployeeDraftView(
        id = draft, organizationId = org, employeeId = employee,
        kind = "EXPENSE_REIMBURSEMENT", amount = "125.50",
        currency = "EGP", purpose = "Employee materials purchase",
        state = "DRAFT_RECEIPT_PENDING", version = 1,
        projectId = null, createdAt = "2026-11-23T09:00:00Z",
    )

    private fun source(creator: UUID = actor) = FinanceExpenseDocumentationSnapshot(
        organizationId = org, expenseDraftId = draft,
        createdByUserId = creator, employeeId = employee,
        amount = "125.50", currency = "EGP",
        verifiedEvidenceIds = listOf(receipt),
        policyVersion = 3,
        sourceVersion = 70001L,
    )

    @Test fun offByDefaultAndNoEmployeeOrApprovalLookup() {
        assertThrows<ProductApiException> {
            service().request(actor, org, draft, operationId, "review-test")
        }
        Mockito.verifyNoInteractions(employeeDrafts, subject, engine)
    }

    @Test fun neverSubmitsReviewForMissingOrChangedReceiptSource() {
        Mockito.`when`(employeeDrafts.expense(actor, org, draft)).thenReturn(own())
        assertThrows<ProductApiException> {
            service(true).request(actor, org, draft, operationId, "review-test")
        }
        Mockito.verifyNoInteractions(engine)
    }

    @Test fun anotherIdentityCannotSubmitOriginalEmployeesReceipt() {
        Mockito.`when`(employeeDrafts.expense(actor, org, draft)).thenReturn(own())
        Mockito.`when`(subject.current(org, draft)).thenReturn(source(UUID.randomUUID()))
        assertThrows<ProductApiException> {
            service(true).request(actor, org, draft, operationId, "review-test")
        }
        Mockito.verifyNoInteractions(engine)
    }

    @Test fun receiptReviewCreatesRealPendingExistingEngineRequestButNeverPays() {
        val subjectSnapshot = source()
        val approvalId = UUID.randomUUID()
        Mockito.`when`(employeeDrafts.expense(actor, org, draft)).thenReturn(own())
        Mockito.`when`(subject.current(org, draft)).thenReturn(subjectSnapshot)
        Mockito.`when`(engine.requestException(
            actor, operationId,
            ApprovalSubjectRef(org, FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT,
                draft, subjectSnapshot.sourceVersion),
            policy, "FINANCE_EXPENSE_RECEIPT_DOCUMENTATION_REVIEW",
            null, "review-test", false,
        )).thenReturn(ApprovalRequestResult(
            approvalRequired = true, approvalRequestId = approvalId,
            state = ApprovalRequestState.PENDING,
            policyKey = policy, policyVersion = 3, authorityKey = "FINANCE_REVIEWER",
            replayed = false,
        ))
        val result = service(true).request(actor, org, draft, operationId, "review-test")
        assertEquals(approvalId, result.documentationReviewRequestId)
        assertEquals("PENDING", result.documentationReviewState)
        assertEquals(listOf(receipt), result.verifiedReceiptEvidenceIds)
        assertEquals(70001L, result.sourceVersion)
        assertFalse(result.expenseApproved)
        assertFalse(result.payableRecognized)
        assertFalse(result.reimbursementAuthorized)
        assertFalse(result.payrollDeductionAuthorized)
        assertFalse(result.cashMovementRecorded)
    }

    @Test fun noApprovalRequiredPolicyCannotBeMisrepresentedAsAReview() {
        Mockito.`when`(employeeDrafts.expense(actor, org, draft)).thenReturn(own())
        Mockito.`when`(subject.current(org, draft)).thenReturn(source())
        // Unexpected NO_APPROVAL_REQUIRED engine result (e.g., policy changed).
        Mockito.`when`(engine.requestException(
            actor, operationId,
            ApprovalSubjectRef(org, FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT, draft, 70001L),
            policy, "FINANCE_EXPENSE_RECEIPT_DOCUMENTATION_REVIEW",
            null, "review-test", false,
        )).thenReturn(ApprovalRequestResult(
            approvalRequired = false, approvalRequestId = null, state = null,
            policyKey = policy, policyVersion = 3, authorityKey = null, replayed = false,
        ))
        assertThrows<ProductApiException> {
            service(true).request(actor, org, draft, operationId, "review-test")
        }
        Mockito.verify(engine).requestException(
            actor, operationId,
            ApprovalSubjectRef(org, FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT, draft, 70001L),
            policy, "FINANCE_EXPENSE_RECEIPT_DOCUMENTATION_REVIEW",
            null, "review-test", false,
        )
    }
}
