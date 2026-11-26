package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectStatus
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.util.UUID

class FinanceExpenseDocumentationStatusServiceTest {
    private val employees = Mockito.mock(FinanceEmployeeDraftService::class.java)
    private val snapshots = Mockito.mock(FinanceExpenseDocumentationApprovalSubject::class.java)
    private val engine = Mockito.mock(ApprovalEngineService::class.java)
    private val org = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val employee = UUID.randomUUID()
    private val expense = UUID.randomUUID()
    private val evidenceId = UUID.randomUUID()
    private val policyKey = "FINANCE_EXPENSE_DOCUMENTATION"

    private fun service(enabled: Boolean): FinanceExpenseDocumentationStatusService {
        val env = StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("status-test",
            mapOf(
                "hiltech.finance.expenses.documentation-review.enabled" to enabled,
                "hiltech.finance.expenses.documentation-review.policy-key" to policyKey,
            )))
        return FinanceExpenseDocumentationStatusService(
            employees, snapshots, engine, env,
        )
    }

    private fun draft() = FinanceEmployeeDraftView(
        id = expense, organizationId = org, employeeId = employee,
        kind = "EXPENSE_REIMBURSEMENT", amount = "100.0000", currency = "EGP",
        purpose = "Actual employee receipt", state = "DRAFT_RECEIPT_PENDING",
        version = 1, projectId = null, createdAt = "2026-11-23T10:00:00Z",
    )

    private fun source(owner: UUID = actor) = FinanceExpenseDocumentationSnapshot(
        organizationId = org, expenseDraftId = expense,
        createdByUserId = owner, employeeId = employee,
        amount = "100", currency = "EGP",
        verifiedEvidenceIds = listOf(evidenceId),
        policyVersion = 1, sourceVersion = 12345,
    )

    @Test fun offByDefaultChecksNoPrivateEmployeeOrApprovalData() {
        assertThrows<ProductApiException> {
            service(false).mine(actor, org, expense)
        }
        Mockito.verifyNoInteractions(employees, snapshots, engine)
    }

    @Test fun employeeOnlySeesOwnCurrentReviewStateFromCanonicalEngine() {
        Mockito.`when`(employees.expense(actor, org, expense)).thenReturn(draft())
        Mockito.`when`(snapshots.current(org, expense)).thenReturn(source())
        val approvalId = UUID.randomUUID()
        val ref = ApprovalSubjectRef(
            org, FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT, expense, 12345,
        )
        Mockito.`when`(engine.subjectStatus(ref, policyKey)).thenReturn(
            ApprovalSubjectStatus(approvalId, ApprovalRequestState.REJECTED, 12345, policyKey),
        )
        val result = service(true).mine(actor, org, expense)
        assertEquals("REJECTED", result.documentationReviewState)
        assertEquals(approvalId, result.documentationReviewRequestId)
        assertEquals(1, result.verifiedReceiptCount)
        assertFalse(result.expenseApproved)
        assertFalse(result.payableRecognized)
        assertFalse(result.reimbursementAuthorized)
        assertFalse(result.cashMovementRecorded)
    }

    @Test fun currentEvidenceWithNoApprovalIsNotFalselyMarkedPending() {
        Mockito.`when`(employees.expense(actor, org, expense)).thenReturn(draft())
        Mockito.`when`(snapshots.current(org, expense)).thenReturn(source())
        val result = service(true).mine(actor, org, expense)
        assertEquals("NOT_REQUESTED", result.documentationReviewState)
        assertNull(result.documentationReviewRequestId)
    }

    @Test fun foreignEmployeeOwnerIsNeverAllowedToReadReview() {
        Mockito.`when`(employees.expense(actor, org, expense)).thenReturn(draft())
        Mockito.`when`(snapshots.current(org, expense))
            .thenReturn(source(UUID.randomUUID()))
        assertThrows<ProductApiException> {
            service(true).mine(actor, org, expense)
        }
        Mockito.verifyNoInteractions(engine)
    }

    @Test fun alteredOrRevokedSourceCannotExposeOldApprovalResult() {
        Mockito.`when`(employees.expense(actor, org, expense)).thenReturn(draft())
        assertThrows<ProductApiException> {
            service(true).mine(actor, org, expense)
        }
        Mockito.verifyNoInteractions(engine)
    }
}
