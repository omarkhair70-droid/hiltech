package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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

class FinanceGlPeriodCloseServiceTest {
    private val jdbc=Mockito.mock(JdbcTemplate::class.java)
    private val persistence=Mockito.mock(ApprovalPersistencePort::class.java)
    private val subject=Mockito.mock(FinanceGlPeriodCloseApprovalSubject::class.java)
    private val engine=Mockito.mock(ApprovalEngineService::class.java)
    private val commands=Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit=Mockito.mock(AuditEventWriter::class.java)
    private val now=Instant.parse("2027-01-07T12:00:00Z")
    private val clock=Clock.fixed(now,ZoneOffset.UTC)
    private val org=UUID.randomUUID()
    private val period=UUID.randomUUID()
    private val actor=UUID.randomUUID()
    private val operation=UUID.randomUUID()

    private fun service():FinanceGlPeriodCloseService {
        val env=StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("period-close-test",
            mapOf(
                "hiltech.finance.gl-period-close.preparer-authority-key"
                    to "FINANCE_GL_PREPARER",
                "hiltech.finance.gl-period-close.operator-authority-key"
                    to "FINANCE_GL_OPERATOR",
            )))
        return FinanceGlPeriodCloseService(
            jdbc,subject,persistence,engine,commands,audit,env,clock,
        )
    }

    @Test fun noActiveFinancePeriodCloseFeatureAllowsNoReadOrWrite() {
        assertThrows(ProductApiException::class.java) {
            service().requestReview(actor,org,period,operation,"test")
        }
        assertThrows(ProductApiException::class.java) {
            service().close(actor,org,period,operation,"test")
        }
        assertThrows(ProductApiException::class.java) {
            service().status(actor,org,period)
        }
        Mockito.verifyNoInteractions(
            jdbc,persistence,engine,commands,audit,
        )
    }

    @Test fun noCurrentFinanceRoleMeansNoPeriodAndNoJournalLeak() {
        Mockito.`when`(subject.enabled()).thenReturn(true)
        assertThrows(ProductApiException::class.java) {
            service().requestReview(actor,org,period,operation,"test")
        }
        Mockito.verifyNoInteractions(
            jdbc,engine,commands,audit,
        )
    }

    @Test fun dulyAuthorizedEmployeeStillCannotCloseAbsentSourceOrPolicy() {
        Mockito.`when`(subject.enabled()).thenReturn(true)
        val key="FINANCE_GL_PREPARER"
        val binding=ApprovalAuthorityBindingSnapshot(
            UUID.randomUUID(),org,key,ApprovalPrincipalType.USER,actor,
            now.minusSeconds(3600),null,1,
        )
        Mockito.`when`(persistence.isActiveOrganizationMember(org,actor,now))
            .thenReturn(true)
        Mockito.`when`(persistence.resolveCurrentAuthority(org,key,now))
            .thenReturn(binding)
        Mockito.`when`(persistence.actorMatchesAuthority(actor,binding,now))
            .thenReturn(true)
        assertThrows(ProductApiException::class.java) {
            service().requestReview(actor,org,period,operation,"test")
        }
        Mockito.verify(subject).current(org,period)
        Mockito.verifyNoInteractions(jdbc,engine,commands,audit)
    }
}

class FinanceGlPeriodCloseApprovalSubjectTest {
    private val jdbc=Mockito.mock(JdbcTemplate::class.java)
    private val approvals=Mockito.mock(ApprovalPersistencePort::class.java)
    private val clock=Clock.fixed(
        Instant.parse("2027-02-01T12:00:00Z"),ZoneOffset.UTC,
    )
    private val org=UUID.randomUUID()
    private val period=UUID.randomUUID()

    private fun subject(enabled:Boolean,fullyReady:Boolean,policyKey:String):
        FinanceGlPeriodCloseApprovalSubject {
        val env=StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("gl-close-test",mapOf(
            "hiltech.finance.gl-period-close.enabled" to enabled,
            "hiltech.finance.gl-period-close.full-ledger-ready.enabled" to fullyReady,
            "hiltech.finance.gl-period-close.policy-key" to policyKey,
        )))
        return FinanceGlPeriodCloseApprovalSubject(
            jdbc,approvals,env,clock,
        )
    }

    @Test fun featureOffEvenWhenCompanyPolicyKeyProvided() {
        val s=subject(false,true,"COMPANY_GL_CLOSE_POLICY")
        assertFalse(s.enabled())
        assertNull(s.current(org,period))
        Mockito.verifyNoInteractions(jdbc,approvals)
    }

    @Test fun cannotCloseCompanyPeriodOnSupplierOnlyLedger() {
        val s=subject(true,false,"COMPANY_GL_CLOSE_POLICY")
        assertFalse(s.enabled())
        assertNull(s.current(org,period))
        Mockito.verifyNoInteractions(jdbc,approvals)
    }

    @Test fun noRealCompanyApprovalPolicyDoesNotReadAnyFinancePeriod() {
        val s=subject(true,true,"COMPANY_GL_CLOSE_POLICY")
        assertTrue(s.enabled())
        assertNull(s.current(org,period))
        Mockito.verify(approvals).loadActivePolicy(
            org,"COMPANY_GL_CLOSE_POLICY",clock.instant(),
        )
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test fun blankReviewPolicyKeyUsesCompanyDefaultAndStillRequiresAnActivePolicy() {
        val s=subject(true,true,"")
        assertNull(s.current(org,period))
        Mockito.verify(approvals).loadActivePolicy(org,"FINANCE_GL_PERIOD_CLOSE_REVIEW",clock.instant())
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test fun malformedExplicitReviewPolicyKeyFailsBeforeReadingSources() {
        assertNull(subject(true,true,"invalid configured policy").current(org,period))
        Mockito.verifyNoInteractions(jdbc,approvals)
    }
}
