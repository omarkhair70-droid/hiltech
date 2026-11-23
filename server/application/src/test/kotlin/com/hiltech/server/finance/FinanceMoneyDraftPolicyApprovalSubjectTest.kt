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

class FinanceMoneyDraftPolicyApprovalSubjectTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
    private val org = UUID.randomUUID()
    private val draft = UUID.randomUUID()
    private val clock = Clock.fixed(
        Instant.parse("2026-11-23T16:00:00Z"), ZoneOffset.UTC,
    )

    private fun service(
        kind: FinanceEmployeeMoneyDraftKind,
        enabled: Boolean = false,
        employeeRequests: Boolean = false,
    ): FinanceMoneyDraftPolicyApprovalSubject {
        val env = StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("finance-policy-test",
            mapOf(
                kind.enabledKey to enabled,
                kind.policyKeySetting to "FINANCE_REVIEWER_POLICY",
                "hiltech.finance.employee-requests.enabled" to employeeRequests,
            )))
        return FinanceMoneyDraftPolicyApprovalSubject(jdbc, approvals, env, clock)
    }

    @Test fun bothKindsRefuseUnconfiguredPolicyBeforeDatabaseAccess() {
        for (kind in FinanceEmployeeMoneyDraftKind.entries) {
            val port = service(kind)
            assertTrue(port.supports(kind.subjectType))
            assertNull(port.current(kind, org, draft))
            assertFalse(port.isCurrent(ApprovalSubjectRef(
                org, kind.subjectType, draft, 5L,
            )))
        }
        Mockito.verifyNoInteractions(jdbc, approvals)
    }

    @Test fun employeeDraftSourceMustAlsoBeEnabled() {
        val kind = FinanceEmployeeMoneyDraftKind.IMPREST
        assertNull(service(kind, enabled = true).current(kind, org, draft))
        Mockito.verifyNoInteractions(jdbc, approvals)
    }

    @Test fun absentActivePolicyDeniesCompanyMoneyReview() {
        val kind = FinanceEmployeeMoneyDraftKind.ADVANCE
        val service = service(kind, enabled = true, employeeRequests = true)
        assertNull(service.current(kind, org, draft))
        Mockito.verify(approvals).loadActivePolicy(
            org, "FINANCE_REVIEWER_POLICY", clock.instant(),
        )
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test fun noApprovalRequiredIsNotAcceptableForMoneyRequestReview() {
        val kind = FinanceEmployeeMoneyDraftKind.ADVANCE
        Mockito.`when`(approvals.loadActivePolicy(
            org, "FINANCE_REVIEWER_POLICY", clock.instant(),
        )).thenReturn(ApprovalPolicySnapshot(
            organizationId = org, policyKey = "FINANCE_REVIEWER_POLICY",
            versionNumber = 1, approvalRequired = false,
            mode = ApprovalPolicyMode.NO_APPROVAL_REQUIRED,
            authorityKey = null, reasonRequired = false,
        ))
        assertNull(service(kind, true, true).current(kind, org, draft))
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test fun irrelevantApprovalSubjectNeverReadsAdvanceOrImprestData() {
        val kind = FinanceEmployeeMoneyDraftKind.IMPREST
        val service = service(kind, true, true)
        assertFalse(service.supports("FINANCE_EMPLOYEE_PAYROLL_DEDUCTION"))
        assertFalse(service.isCurrent(ApprovalSubjectRef(
            org, "FINANCE_EMPLOYEE_PAYROLL_DEDUCTION", draft, 5L,
        )))
        Mockito.verifyNoInteractions(jdbc, approvals)
    }
}
