package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

class FinanceClientAwardSourceServiceTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val approvals = Mockito.mock(ApprovalPersistencePort::class.java)
    private val org = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val bindingId = UUID.randomUUID()
    private val now = Instant.parse("2026-11-23T12:00:00Z")
    private val authorityKey = "FINANCE_AR_SOURCE_REVIEWER"

    private fun service(enabled: Boolean, key: String = authorityKey): FinanceClientAwardSourceService {
        val env = StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("finance-ar-test", mapOf(
            "hiltech.finance.receivables.sales-award-source-readiness.enabled" to enabled,
            "hiltech.finance.receivables.sales-award-source-review-authority-key" to key,
        )))
        return FinanceClientAwardSourceService(
            jdbc, approvals, env, Clock.fixed(now, ZoneOffset.UTC),
        )
    }

    private fun authorize() {
        val binding = ApprovalAuthorityBindingSnapshot(
            bindingId, org, authorityKey,
            ApprovalPrincipalType.USER, actor,
            now.minusSeconds(100), null, 1,
        )
        Mockito.`when`(approvals.isActiveOrganizationMember(org, actor, now))
            .thenReturn(true)
        Mockito.`when`(approvals.resolveCurrentAuthority(org, authorityKey, now))
            .thenReturn(binding)
        Mockito.`when`(approvals.actorMatchesAuthority(actor, binding, now))
            .thenReturn(true)
    }

    @Test fun disabledByDefaultNeverLooksAtCommercialOrOtherCompanySources() {
        assertThrows<ProductApiException> {
            service(false).list(actor, org)
        }
        Mockito.verifyNoInteractions(jdbc, approvals)
    }

    @Test fun noRealFinanceAuthorityCannotSeeCommercialPricing() {
        assertThrows<ProductApiException> {
            service(true).list(actor, org)
        }
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test fun blankCompanyReviewAuthorityNeverFallsBackToSalesOrAdmin() {
        assertThrows<ProductApiException> {
            service(true, "").list(actor, org)
        }
        Mockito.verifyNoInteractions(jdbc, approvals)
    }

    @Test fun missingPhase10ViewIsNotMisreportedAsZeroCustomerReceivables() {
        authorize()
        Mockito.`when`(jdbc.queryForObject(
            "SELECT to_regclass('public.commercial_finance_award_source') IS NOT NULL",
            Boolean::class.java,
        )).thenReturn(false)
        val report = service(true).list(actor, org)
        assertFalse(report.sourceAvailable)
        assertEquals("COMMERCIAL_FINANCE_AWARD_SOURCE_NOT_INTEGRATED", report.sourceStatus)
        assertTrue(report.rows.isEmpty())
        assertNull(report.reportedCompanyReceivableTotal)
        assertNull(report.reportedCompanyRevenueTotal)
        assertNull(report.reportedCustomerCollectionsTotal)
    }

    @Test fun absentPhase10SourceCannotExposeAnyCustomerSourceDetail() {
        authorize()
        Mockito.`when`(jdbc.queryForObject(
            "SELECT to_regclass('public.commercial_finance_award_source') IS NOT NULL",
            Boolean::class.java,
        )).thenReturn(false)
        assertThrows<ProductApiException> {
            service(true).detail(actor, org, UUID.randomUUID())
        }
    }
}
