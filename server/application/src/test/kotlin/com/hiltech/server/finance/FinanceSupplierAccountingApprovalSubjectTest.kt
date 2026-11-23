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

class FinanceSupplierAccountingApprovalSubjectTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
    private val org = UUID.randomUUID()
    private val candidate = UUID.randomUUID()
    private val clock = Clock.fixed(
        Instant.parse("2026-11-23T13:00:00Z"), ZoneOffset.UTC,
    )
    private val key = "FINANCE_SUPPLIER_ACCOUNTING"

    private fun source(
        enabled: Boolean = false,
        candidates: Boolean = false,
        policyKey: String = key,
    ): FinanceSupplierAccountingApprovalSubject {
        val env = StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("supplier-policy", mapOf(
            "hiltech.finance.supplier-accounting-review.enabled" to enabled,
            "hiltech.finance.payable-candidates.enabled" to candidates,
            "hiltech.finance.supplier-accounting-review.policy-key" to policyKey,
        )))
        return FinanceSupplierAccountingApprovalSubject(jdbc, approvals, env, clock)
    }

    @Test fun financeCompanyAccountingReviewIsOffUntilP8APayableSourceEnabled() {
        val service = source()
        assertTrue(service.supports(FINANCE_SUPPLIER_ACCOUNTING_REVIEW_SUBJECT))
        assertFalse(service.supports("FINANCE_SUPPLIER_PAYMENT"))
        assertNull(service.current(org, candidate))
        assertFalse(service.isCurrent(ApprovalSubjectRef(
            org, FINANCE_SUPPLIER_ACCOUNTING_REVIEW_SUBJECT, candidate, 1,
        )))
        Mockito.verifyNoInteractions(jdbc, approvals)
    }

    @Test fun enabledReviewStillCannotReadInvoicesWithoutP8ASourceGate() {
        assertNull(source(enabled = true).current(org, candidate))
        Mockito.verifyNoInteractions(jdbc, approvals)
    }

    @Test fun invalidPolicyKeyBlocksApprovalAndSourceReading() {
        assertNull(source(enabled = true, candidates = true, policyKey = "")
            .current(org, candidate))
        Mockito.verifyNoInteractions(jdbc, approvals)
    }

    @Test fun canonicalCompanyPolicyIsRequiredBeforeAnySourceCanBeReviewed() {
        assertNull(source(enabled = true, candidates = true).current(org, candidate))
        Mockito.verify(approvals).loadActivePolicy(org, key, clock.instant())
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test fun noApprovalRequiredPolicyCannotConfirmSupplierAccountingReview() {
        Mockito.`when`(approvals.loadActivePolicy(org, key, clock.instant()))
            .thenReturn(ApprovalPolicySnapshot(
                organizationId = org, policyKey = key, versionNumber = 2,
                approvalRequired = false, mode = ApprovalPolicyMode.NO_APPROVAL_REQUIRED,
                authorityKey = null, reasonRequired = false,
            ))
        assertNull(source(enabled = true, candidates = true).current(org, candidate))
        Mockito.verifyNoInteractions(jdbc)
    }
}
