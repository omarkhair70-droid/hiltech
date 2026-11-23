package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalRequestResult
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectStatus
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.util.UUID

class FinanceMoneyDraftPolicyReviewServiceTest {
    private val drafts = Mockito.mock(FinanceEmployeeDraftService::class.java)
    private val sources = Mockito.mock(FinanceMoneyDraftPolicyApprovalSubject::class.java)
    private val engine = Mockito.mock(ApprovalEngineService::class.java)
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val employee = UUID.randomUUID()
    private val draft = UUID.randomUUID()
    private val operation = UUID.randomUUID()

    private fun service() = FinanceMoneyDraftPolicyReviewService(
        drafts, sources, engine,
    )

    private fun personal(kind: FinanceEmployeeMoneyDraftKind) =
        FinanceEmployeeDraftView(
            id = draft, organizationId = org, employeeId = employee,
            kind = kind.kindCode, amount = "150.00", currency = "EGP",
            purpose = "Request for company work", state = kind.stateCode,
            version = 1, projectId = null,
            createdAt = "2026-11-23T10:00:00Z",
        )

    private fun source(kind: FinanceEmployeeMoneyDraftKind, creator: UUID = actor) =
        FinanceMoneyDraftPolicySnapshot(
            id = draft, organizationId = org, employeeId = employee,
            createdByUserId = creator, kind = kind, amount = BigDecimal("150"),
            currency = "EGP", purpose = "Request for company work", projectId = null,
            policyKey = "FINANCE_${kind.kindCode}_REVIEW",
            policyVersion = 2, sourceVersion = 100007L,
        )

    private fun stub(kind: FinanceEmployeeMoneyDraftKind) {
        when (kind) {
            FinanceEmployeeMoneyDraftKind.ADVANCE ->
                Mockito.`when`(drafts.advance(actor, org, draft)).thenReturn(personal(kind))
            FinanceEmployeeMoneyDraftKind.IMPREST ->
                Mockito.`when`(drafts.imprest(actor, org, draft)).thenReturn(personal(kind))
        }
        Mockito.`when`(sources.current(kind, org, draft)).thenReturn(source(kind))
    }

    @Test fun companyPolicyAbsentMeansNoApprovalsOrMoney() {
        val kind = FinanceEmployeeMoneyDraftKind.ADVANCE
        Mockito.`when`(drafts.advance(actor, org, draft)).thenReturn(personal(kind))
        assertThrows<ProductApiException> {
            service().request(actor, org, kind, draft, operation, "test")
        }
        Mockito.verifyNoInteractions(engine)
    }

    @Test fun advanceUsesOriginalEmployeeAndSeparatePolicy() {
        val kind = FinanceEmployeeMoneyDraftKind.ADVANCE
        stub(kind)
        val snapshot = source(kind)
        val requestId = UUID.randomUUID()
        val subject = ApprovalSubjectRef(org, kind.subjectType, draft, 100007L)
        Mockito.`when`(engine.requestException(
            actor, operation, subject, snapshot.policyKey,
            kind.reasonCode, null, "test", false,
        )).thenReturn(ApprovalRequestResult(
            approvalRequired = true, approvalRequestId = requestId,
            state = ApprovalRequestState.PENDING,
            policyKey = snapshot.policyKey, policyVersion = 2,
            authorityKey = "FINANCE_ADVANCE_REVIEWER", replayed = false,
        ))
        val outcome = service().request(actor, org, kind, draft, operation, "test")
        assertEquals(requestId, outcome.approvalRequestId)
        assertEquals(kind.kindCode, outcome.kind)
        assertEquals("PENDING", outcome.requestState)
        assertFalse(outcome.moneyIssued)
        assertFalse(outcome.personalLoanRecognized)
        assertFalse(outcome.payrollDeductionAuthorized)
        assertFalse(outcome.cashCustodyRecorded)
        assertFalse(outcome.settlementRecorded)
        Mockito.verify(drafts).advance(actor, org, draft)
        Mockito.verify(drafts, Mockito.never()).imprest(actor, org, draft)
    }

    @Test fun imprestPolicyReviewCannotBecomePersonalBorrowingOrPayrollDeduction() {
        val kind = FinanceEmployeeMoneyDraftKind.IMPREST
        stub(kind)
        val requestId = UUID.randomUUID()
        Mockito.`when`(engine.subjectStatus(
            ApprovalSubjectRef(org, kind.subjectType, draft, 100007L),
            source(kind).policyKey,
        )).thenReturn(ApprovalSubjectStatus(
            requestId, ApprovalRequestState.PENDING, 100007L,
            source(kind).policyKey,
        ))
        val outcome = service().request(actor, org, kind, draft, operation, "test")
        assertTrue(outcome.existingRequest)
        assertEquals(requestId, outcome.approvalRequestId)
        assertEquals(kind.kindCode, outcome.kind)
        assertFalse(outcome.personalLoanRecognized)
        assertFalse(outcome.cashCustodyRecorded)
        assertFalse(outcome.payrollDeductionAuthorized)
        Mockito.verify(engine).subjectStatus(
            ApprovalSubjectRef(org, kind.subjectType, draft, 100007L),
            source(kind).policyKey,
        )
        Mockito.verifyNoMoreInteractions(engine)
    }

    @Test fun approvedPolicyReviewNeverIssuesMoneyOrLetsSameSourceReapprove() {
        val kind = FinanceEmployeeMoneyDraftKind.IMPREST
        stub(kind)
        val approvalId = UUID.randomUUID()
        Mockito.`when`(engine.subjectStatus(
            ApprovalSubjectRef(org, kind.subjectType, draft, 100007L),
            source(kind).policyKey,
        )).thenReturn(ApprovalSubjectStatus(
            approvalId, ApprovalRequestState.APPROVED, 100007L,
            source(kind).policyKey,
        ))
        val status = service().mine(actor, org, kind, draft)
        assertEquals("APPROVED", status.requestState)
        assertFalse(status.moneyIssued)
        assertFalse(status.cashCustodyRecorded)
        assertFalse(status.settlementRecorded)
        assertThrows<ProductApiException> {
            service().request(actor, org, kind, draft, operation, "test")
        }
    }

    @Test fun identityDifferentFromOriginalRequesterCannotSeeReview() {
        val kind = FinanceEmployeeMoneyDraftKind.ADVANCE
        Mockito.`when`(drafts.advance(actor, org, draft)).thenReturn(personal(kind))
        Mockito.`when`(sources.current(kind, org, draft))
            .thenReturn(source(kind, UUID.randomUUID()))
        assertThrows<ProductApiException> {
            service().mine(actor, org, kind, draft)
        }
        Mockito.verifyNoInteractions(engine)
    }

    @Test fun noReviewIsNotFalselyMarkedApproved() {
        val kind = FinanceEmployeeMoneyDraftKind.ADVANCE
        stub(kind)
        val status = service().mine(actor, org, kind, draft)
        assertEquals("NOT_REQUESTED", status.requestState)
        assertNull(status.approvalRequestId)
        assertFalse(status.moneyIssued)
        assertFalse(status.personalLoanRecognized)
    }

    @Test fun policiesAndSubjectsRemainDistinctForTwoTypes() {
        val advance = FinanceEmployeeMoneyDraftKind.ADVANCE
        val imprest = FinanceEmployeeMoneyDraftKind.IMPREST
        assertFalse(advance.subjectType == imprest.subjectType)
        assertFalse(advance.policyKeySetting == imprest.policyKeySetting)
        assertFalse(advance.enabledKey == imprest.enabledKey)
        assertFalse(advance.reasonCode == imprest.reasonCode)
    }
}
