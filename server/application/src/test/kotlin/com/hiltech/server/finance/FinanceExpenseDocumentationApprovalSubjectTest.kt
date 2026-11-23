package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalPolicySnapshot
import com.hiltech.server.approval.ApprovalSubjectRef
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FinanceExpenseDocumentationApprovalSubjectTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
    private val org = UUID.randomUUID()
    private val draft = UUID.randomUUID()
    private val date = Instant.parse("2026-11-23T12:00:00Z")
    private val policyKey = "EMPLOYEE_EXPENSE_DOCUMENTATION"

    private fun guard(
        enabled: Boolean,
        receipts: Boolean = false,
    ): FinanceExpenseDocumentationApprovalSubject {
        val env = StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("finance-policy-test", mapOf(
            "hiltech.finance.expenses.documentation-review.enabled" to enabled,
            "hiltech.finance.expenses.documentation-review.policy-key" to policyKey,
            "hiltech.finance.expense-receipts.canonical-upload-ready" to receipts,
            "hiltech.finance.expense-receipts.readiness-enabled" to receipts,
        )))
        return FinanceExpenseDocumentationApprovalSubject(
            jdbc, approvals, env, Clock.fixed(date, ZoneOffset.UTC),
        )
    }

    @Test fun noEnabledCanonicalReceiptTargetMeansNoDocumentationApproval() {
        val guard = guard(enabled = false)
        assertTrue(guard.supports(FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT))
        assertFalse(guard.supports("FINANCE_EMPLOYEE_ADVANCE"))
        assertNull(guard.current(org, draft))
        assertFalse(guard.isCurrent(ApprovalSubjectRef(
            org, FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT, draft, 1,
        )))
        Mockito.verifyNoInteractions(jdbc, approvals)
    }

    @Test fun receiptsMustBeBackedByRealCurrentCompanyPolicy() {
        val guard = guard(enabled = true, receipts = true)
        assertNull(guard.current(org, draft))
        Mockito.verify(approvals).loadActivePolicy(org, policyKey, date)
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test fun noApprovalRequiredCannotBeUsedForEmployeeDocumentReview() {
        Mockito.`when`(approvals.loadActivePolicy(org, policyKey, date)).thenReturn(
            ApprovalPolicySnapshot(
                organizationId = org, policyKey = policyKey,
                versionNumber = 1, approvalRequired = false,
                mode = ApprovalPolicyMode.NO_APPROVAL_REQUIRED,
                authorityKey = null, reasonRequired = false,
            ),
        )
        assertNull(guard(enabled = true, receipts = true).current(org, draft))
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test fun unsupportedSubjectTypesAreNeverGivenFinanceApproval() {
        assertFalse(guard(enabled = true, receipts = true).isCurrent(
            ApprovalSubjectRef(org, "FINANCE_PAYMENT_INSTRUCTION", draft, 1),
        ))
        Mockito.verifyNoInteractions(jdbc, approvals)
    }
}
