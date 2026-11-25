package com.hiltech.server.operationalaccess

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalPolicySnapshot
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.documents.FinanceExpenseReceiptEvidenceTarget
import com.hiltech.server.finance.FinanceBankStatementIntakeApprovalSubject
import com.hiltech.server.finance.JdbcFinanceBankStatementEvidenceTarget
import com.hiltech.server.finance.JdbcFinanceExpenseReceiptEvidenceTarget
import org.junit.jupiter.api.Assertions.assertFalse
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

class OperationalDocumentAccessTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
    private val source = Mockito.mock(OperationalAuthorityPort::class.java)
    private val intake = Mockito.mock(FinanceBankStatementIntakeApprovalSubject::class.java)
    private val at = Instant.parse("2026-11-25T12:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()

    @Test fun bankUploadUsesSameExplicitPreparerSourceAndStillNeedsAnIndependentConfiguredReviewer() {
        val role = OperationalResponsibility.FINANCE_BANK_STATEMENT_PREPARE
        allow(role)
        Mockito.`when`(intake.enabled()).thenReturn(true)
        Mockito.`when`(intake.policyKey()).thenReturn("SYNTH_BANK_REVIEW")
        val target = JdbcFinanceBankStatementEvidenceTarget(jdbc, approvals, intake, StandardEnvironment(), clock, source)
        assertFalse(target.canAccess(actor, organization, actor))
        val policy = ApprovalPolicySnapshot(organization, "SYNTH_BANK_REVIEW", 1, true, ApprovalPolicyMode.SINGLE,
            "SYNTH_BANK_REVIEWER", true)
        val reviewer = ApprovalAuthorityBindingSnapshot(UUID.randomUUID(), organization, "SYNTH_BANK_REVIEWER",
            ApprovalPrincipalType.USER, UUID.randomUUID(), at.minusSeconds(60), null, 1)
        Mockito.`when`(approvals.loadActivePolicy(organization, policy.policyKey, at)).thenReturn(policy)
        Mockito.`when`(approvals.resolveCurrentAuthority(organization, reviewer.authorityKey, at)).thenReturn(reviewer)
        assertTrue(target.canAccess(actor, organization, actor))
        assertFalse(target.canAccess(actor, organization, UUID.randomUUID()))
        Mockito.`when`(approvals.actorMatchesAuthority(actor, reviewer, at)).thenReturn(true)
        assertFalse(target.canAccess(actor, organization, actor))
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test fun employeeReceiptReviewerAccessUsesExactCurrentRoleWhileUploadRemainsEmployeeOwned() {
        allow(OperationalResponsibility.FINANCE_EMPLOYEE_REVIEW)
        val environment = StandardEnvironment().also { it.propertySources.addFirst(MapPropertySource("synthetic-expense-access", mapOf(
            "hiltech.finance.expense-receipts.canonical-upload-ready" to true,
            "hiltech.finance.employee-review.enabled" to true,
        ))) }
        val target = FinanceExpenseReceiptEvidenceTarget(UUID.randomUUID(), organization, UUID.randomUUID(), UUID.randomUUID())
        val adapter = JdbcFinanceExpenseReceiptEvidenceTarget(jdbc, approvals, clock, environment, source)
        assertTrue(adapter.canAccess(actor, target))
        assertFalse(adapter.canReserve(actor, target))
        Mockito.`when`(source.current(actor, organization, OperationalResponsibility.FINANCE_EMPLOYEE_REVIEW, at)).thenReturn(null)
        assertFalse(adapter.canAccess(actor, target))
        Mockito.verifyNoInteractions(jdbc)
    }

    private fun allow(role: OperationalResponsibility) {
        Mockito.`when`(approvals.isActiveOrganizationMember(organization, actor, at)).thenReturn(true)
        Mockito.`when`(source.current(actor, organization, role, at)).thenReturn(
            OperationalAuthorityProof(UUID.randomUUID(), organization, role.name, 1, OperationalAuthorityOrigin.OPERATIONAL))
    }
}
