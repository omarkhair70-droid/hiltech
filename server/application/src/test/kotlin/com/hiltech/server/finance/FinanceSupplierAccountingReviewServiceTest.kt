package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalPolicySnapshot
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.ApprovalRequestResult
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectStatus
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FinanceSupplierAccountingReviewServiceTest {
    private val sources = Mockito.mock(FinancePayableCandidateService::class.java)
    private val snapshot = Mockito.mock(FinanceSupplierAccountingApprovalSubject::class.java)
    private val approvals = Mockito.mock(ApprovalEngineService::class.java)
    private val authority = Mockito.mock(ApprovalPersistencePort::class.java)
    private val time = Instant.parse("2026-11-23T12:00:00Z")
    private val clock = Clock.fixed(time, ZoneOffset.UTC)
    private val org = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val reviewer = UUID.randomUUID()
    private val candidate = UUID.randomUUID()
    private val invoice = UUID.randomUUID()
    private val supplier = UUID.randomUUID()
    private val evidence = UUID.randomUUID()
    private val op = UUID.randomUUID()
    private val policyKey = "FINANCE_SUPPLIER_ACCOUNTING"
    private val reviewerKey = "FINANCE_ACCOUNTING_APPROVER"
    private val sha = "a".repeat(64)

    private fun service() = FinanceSupplierAccountingReviewService(
        sources, snapshot, approvals, authority, clock,
    )

    private fun candidate(current: Boolean = true) = SupplierPayableCandidateView(
        id = candidate,
        handoffId = UUID.randomUUID(),
        invoiceId = invoice,
        supplierOrganizationId = supplier,
        invoiceNumber = "INV-001",
        amount = "2100.000000",
        currency = "EGP",
        sourceSha256 = sha,
        state = "ACCOUNTING_REVIEW_REQUIRED",
        sourceCurrent = current,
        organizationId = org,
    )

    private fun source() = FinanceSupplierAccountingSnapshot(
        candidateId = candidate, organizationId = org,
        supplierOrganizationId = supplier,
        supplierInvoiceId = invoice, invoiceEvidenceId = evidence,
        invoiceNumber = "INV-001",
        currency = "EGP", amount = BigDecimal("2100"),
        sourceSha256 = sha, sourceVersion = 198372L,
        policyKey = policyKey, policyVersion = 3,
    )

    private fun setup(current: Boolean = true): ApprovalAuthorityBindingSnapshot {
        Mockito.`when`(sources.detail(actor, candidate)).thenReturn(candidate(current))
        Mockito.`when`(snapshot.current(org, candidate)).thenReturn(source())
        Mockito.`when`(authority.loadActivePolicy(org, policyKey, time))
            .thenReturn(ApprovalPolicySnapshot(
                organizationId = org, policyKey = policyKey,
                versionNumber = 3, approvalRequired = true,
                mode = ApprovalPolicyMode.SINGLE,
                authorityKey = reviewerKey,
                reasonRequired = false,
            ))
        val binding = ApprovalAuthorityBindingSnapshot(
            UUID.randomUUID(), org, reviewerKey,
            ApprovalPrincipalType.USER, reviewer,
            time.minusSeconds(60), null, 1L,
        )
        Mockito.`when`(authority.resolveCurrentAuthority(org, reviewerKey, time))
            .thenReturn(binding)
        Mockito.`when`(authority.isActiveOrganizationMember(org, actor, time))
            .thenReturn(true)
        return binding
    }

    @Test fun matchedSourceMustStillBeCurrentToBeginAccountingReview() {
        setup(false)
        assertThrows<ProductApiException> {
            service().request(actor, candidate, op, "test")
        }
        Mockito.verifyNoInteractions(approvals)
    }

    @Test fun companyPolicyAndRealReviewerMustBePresent() {
        Mockito.`when`(sources.detail(actor, candidate)).thenReturn(candidate())
        Mockito.`when`(snapshot.current(org, candidate)).thenReturn(source())
        assertThrows<ProductApiException> {
            service().request(actor, candidate, op, "test")
        }
        Mockito.verifyNoInteractions(approvals)
    }

    @Test fun nonReviewerCanSendRealMatchedInvoiceToExistingApprovalEngine() {
        setup()
        val subject = ApprovalSubjectRef(
            org, FINANCE_SUPPLIER_ACCOUNTING_REVIEW_SUBJECT, candidate, 198372L,
        )
        val approvalId = UUID.randomUUID()
        Mockito.`when`(approvals.requestException(
            actor, op, subject, policyKey,
            "FINANCE_SUPPLIER_ACCOUNTING_REVIEW", null, "test", false,
        )).thenReturn(ApprovalRequestResult(
            approvalRequired = true, approvalRequestId = approvalId,
            state = ApprovalRequestState.PENDING,
            policyKey = policyKey, policyVersion = 3,
            authorityKey = reviewerKey, replayed = false,
        ))
        val result = service().request(actor, candidate, op, "test")
        assertEquals(approvalId, result.approvalRequestId)
        assertEquals("PENDING", result.reviewState)
        assertEquals("INV-001", result.invoiceNumber)
        assertEquals(0, BigDecimal(result.amount).compareTo(BigDecimal("2100")))
        assertFalse(result.bookedPayable)
        assertFalse(result.taxCredited)
        assertFalse(result.paymentApproved)
        assertFalse(result.bankPaymentSent)
        assertFalse(result.paymentReconciled)
    }

    @Test fun existingPendingCannotCreateDuplicateReviewRequest() {
        setup()
        val ref = ApprovalSubjectRef(
            org, FINANCE_SUPPLIER_ACCOUNTING_REVIEW_SUBJECT, candidate, 198372L,
        )
        val id = UUID.randomUUID()
        Mockito.`when`(approvals.subjectStatus(ref, policyKey))
            .thenReturn(ApprovalSubjectStatus(
                id, ApprovalRequestState.PENDING, 198372L, policyKey,
            ))
        val result = service().request(actor, candidate, op, "test")
        assertEquals(id, result.approvalRequestId)
        assertTrue(result.existingRequest)
        Mockito.verify(approvals).subjectStatus(ref, policyKey)
        Mockito.verifyNoMoreInteractions(approvals)
    }

    @Test fun approvedAccountingDocumentationNeverMeansAPWasBookedOrPaymentSent() {
        setup()
        val ref = ApprovalSubjectRef(
            org, FINANCE_SUPPLIER_ACCOUNTING_REVIEW_SUBJECT, candidate, 198372L,
        )
        Mockito.`when`(approvals.subjectStatus(ref, policyKey))
            .thenReturn(ApprovalSubjectStatus(
                UUID.randomUUID(), ApprovalRequestState.APPROVED,
                198372L, policyKey,
            ))
        val result = service().mine(actor, candidate)
        assertEquals("APPROVED", result.reviewState)
        assertFalse(result.bookedPayable)
        assertFalse(result.paymentApproved)
        assertFalse(result.bankPaymentSent)
        assertThrows<ProductApiException> {
            service().request(actor, candidate, op, "test")
        }
    }

    @Test fun requesterWhoIsTheCurrentAccountingApproverCannotSelfSubmit() {
        val binding = setup()
        // Do not use Mockito.any() for a Kotlin non-null authority data
        // class: it returns null and throws before stubbing the method.
        Mockito.`when`(authority.actorMatchesAuthority(actor, binding, time))
            .thenReturn(true)
        assertThrows<ProductApiException> {
            service().request(actor, candidate, op, "test")
        }
        Mockito.verifyNoInteractions(approvals)
    }
}
