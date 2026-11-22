package com.hiltech.server.finance

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FinanceExpenseReceiptReadinessServiceTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val employees = Mockito.mock(FinanceEmployeeDraftService::class.java)
    private val reviewers = Mockito.mock(FinanceEmployeeReviewService::class.java)
    private val at = Instant.parse("2026-11-23T12:00:00Z")
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val expense = UUID.randomUUID()

    private fun service(enabled: Boolean, supported: Boolean = false):
        FinanceExpenseReceiptReadinessService {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("finance-receipt-test",
            mapOf(
                "hiltech.finance.expense-receipts.readiness-enabled" to enabled,
                "hiltech.finance.expense-receipts.canonical-upload-ready" to supported,
            )))
        return FinanceExpenseReceiptReadinessService(
            jdbc, employees, reviewers,
            Clock.fixed(at, ZoneOffset.UTC), environment,
        )
    }

    @Test fun disabledByDefaultBeforeAnyEmployeeOrEvidenceRead() {
        assertThrows<ProductApiException> {
            service(false).mine(actor, org)
        }
        Mockito.verifyNoInteractions(jdbc, employees, reviewers)
    }

    @Test fun absentCanonicalTargetCannotBePassedAsAReceipt() {
        val output = service(true).checkExpenseEvidence(
            expense, org, "250.00", "EGP", actor,
        )
        assertFalse(output.uploadedReceiptReady)
        assertEquals(0, output.verifiedReceiptCount)
        assertFalse(output.reviewerActionAllowed)
        assertFalse(output.paymentActionAllowed)
        assertTrue(output.blockingReasons.contains(
            "CANONICAL_EXPENSE_RECEIPT_UPLOAD_NOT_ENABLED",
        ))
        assertTrue(output.blockingReasons.contains(
            "NO_VERIFIED_READY_RECEIPT_EVIDENCE",
        ))
    }

    @Test fun claimedVerifiedReceiptDoesNotAllowExpenseApprovalOrMoneyMovement() {
        // This verifies authorization semantics, not a forged database row.
        // Only Evidence's canonical READY+finalized+scan+owner/target query
        // may populate receipt IDs, and source enablement is separate.
        val output = service(true, true).checkExpenseEvidence(
            expense, org, "250.00", "EGP", actor,
        )
        assertFalse(output.uploadedReceiptReady)
        assertEquals(0, output.verifiedReceiptCount)
        assertTrue(output.blockingReasons.contains(
            "EXPENSE_REVIEW_AND_APPROVAL_POLICY_NOT_BOUND",
        ))
        assertTrue(output.blockingReasons.contains(
            "EXPENSE_REIMBURSEMENT_PAYMENT_NOT_AUTHORIZED",
        ))
    }

    @Test fun selfServiceDelegatesToPrivateCurrentEmployeeGuard() {
        Mockito.`when`(employees.expenses(actor, org)).thenReturn(emptyList())
        assertTrue(service(true).mine(actor, org).isEmpty())
        Mockito.verify(employees).expenses(actor, org)
        Mockito.verifyNoInteractions(reviewers, jdbc)
    }

    @Test fun reviewerQueueDelegatesToExplicitFinanceAuthority() {
        Mockito.`when`(reviewers.list(actor, org)).thenReturn(emptyList())
        assertTrue(service(true).reviewerQueue(actor, org).isEmpty())
        Mockito.verify(reviewers).list(actor, org)
        Mockito.verifyNoInteractions(employees, jdbc)
    }

    @Test fun reviewerDeniedWhenCurrentFinanceAuthorityRejects() {
        Mockito.`when`(reviewers.list(actor, org)).thenThrow(
            ProductApiException(
                "FINANCE_EMPLOYEE_REVIEW_UNAVAILABLE",
                "Not authorized", org.springframework.http.HttpStatus.NOT_FOUND,
            ),
        )
        assertThrows<ProductApiException> {
            service(true).reviewerQueue(actor, org)
        }
        Mockito.verifyNoInteractions(jdbc)
    }
}
