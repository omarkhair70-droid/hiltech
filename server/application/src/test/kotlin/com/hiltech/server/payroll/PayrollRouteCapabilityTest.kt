package com.hiltech.server.payroll

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.env.MockEnvironment
import java.time.Clock
import java.util.UUID

class PayrollRouteCapabilityTest {
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val repository = Mockito.mock(PayrollSourceRepository::class.java)
    private val authority = object : Payroll9aAuthorityPort {
        override fun canRead(actorId: UUID, organizationId: UUID) = actorId == actor && organizationId == org
        override fun canPrepare(actorId: UUID, organizationId: UUID) = false
        override fun canReview(actorId: UUID, organizationId: UUID) = canRead(actorId, organizationId)
    }

    @Test
    fun routesDefaultAvailableButNeverImplyPrepareAuthority() {
        val result = service().capabilities(actor, org)
        assertFalse(result.canPrepare)
        assertTrue(result.canReview)
        assertTrue(result.periodsAvailable && result.runsAvailable && result.runApprovalAvailable && result.releaseAvailable)
        assertTrue(result.financeHandoffAvailable && result.financeStatusAvailable)
        assertFalse(result.runReviewConfigured)
        assertThrows<ProductApiException> { service().capabilities(UUID.randomUUID(), org) }
    }

    @Test
    fun explicitIncidentOverridesRemainVisibleWithoutRevokingResponsibilities() {
        val result = service(MockEnvironment().withProperty("hiltech.payroll.draft-runs-enabled", "false")
            .withProperty("hiltech.payroll.finance-results-enabled", "false")
            .withProperty("hiltech.payroll.periods-enabled", "false")).capabilities(actor, org)
        assertFalse(result.runsAvailable || result.financeStatusAvailable || result.periodsAvailable)
        assertTrue(result.canReview && result.releaseAvailable)
    }

    @Test
    fun blankNewDeploymentUsesNamedCompanySetupWhileInstalledCustomKeyIsPreserved() {
        assertEquals("PAYROLL_RUN_REVIEW", PayrollRunPolicyConfiguration(" ").key)
        val configured = PayrollRunPolicyConfiguration("LEGACY_COMPANY_RUN_POLICY")
        Mockito.`when`(repository.runReviewPolicyReady(org, configured.key)).thenReturn(true)
        assertTrue(service(policy = configured).capabilities(actor, org).runReviewConfigured)
        Mockito.verify(repository).runReviewPolicyReady(org, "LEGACY_COMPANY_RUN_POLICY")
    }

    private fun service(environment: MockEnvironment = MockEnvironment(), policy: PayrollRunPolicyConfiguration = PayrollRunPolicyConfiguration("")): PayrollSourceService {
        val intake = object : PayrollFinanceReviewIntakePort {
            override fun acceptForReview(manifest: PayrollFinanceReviewManifest): PayrollFinanceReviewReceipt = error("No write expected")
        }
        return PayrollSourceService(Mockito.mock(JdbcTemplate::class.java), Mockito.mock(Payroll9aService::class.java), authority,
            repository, Mockito.mock(PayrollSourceApprovalVersionSource::class.java), Mockito.mock(ApprovalEngineService::class.java),
            AuditEventWriter.NOOP, Clock.systemUTC(), environment = environment, runPolicy = policy,
            financeIntake = StaticListableBeanFactory(mapOf("finance" to intake)).getBeanProvider(PayrollFinanceReviewIntakePort::class.java))
    }
}
