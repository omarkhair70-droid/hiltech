package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
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

class FinanceApLedgerEvidenceReportServiceTest {
    private val jdbc=Mockito.mock(JdbcTemplate::class.java)
    private val approvals=Mockito.mock(ApprovalPersistencePort::class.java)
    private val now=Instant.parse("2026-11-23T12:00:00Z")
    private val org=UUID.randomUUID()
    private val actor=UUID.randomUUID()
    private val bindingKey="FINANCE_AP_REPORT_VIEWER"
    private val sourceCheck="""SELECT
                to_regclass('public.finance_ap_posted_journal') IS NOT NULL
                AND to_regclass('public.finance_ap_posting_proposal') IS NOT NULL""".trimIndent()

    private fun service(enabled:Boolean,key:String=bindingKey):
        FinanceApLedgerEvidenceReportService {
        val env=StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource(
            "finance-ap-report-test",mapOf(
                "hiltech.finance.ap-reporting.enabled" to enabled,
                "hiltech.finance.ap-reporting.viewer-authority-key" to key,
            )))
        return FinanceApLedgerEvidenceReportService(
            jdbc,approvals,env,Clock.fixed(now,ZoneOffset.UTC),
        )
    }
    private fun allow() {
        val binding=ApprovalAuthorityBindingSnapshot(
            UUID.randomUUID(),org,bindingKey,
            ApprovalPrincipalType.USER,actor,
            now.minusSeconds(100),null,1,
        )
        Mockito.`when`(approvals.isActiveOrganizationMember(org,actor,now))
            .thenReturn(true)
        Mockito.`when`(approvals.resolveCurrentAuthority(org,bindingKey,now))
            .thenReturn(binding)
        Mockito.`when`(approvals.actorMatchesAuthority(actor,binding,now))
            .thenReturn(true)
    }

    @Test fun noCompanyFinancialViewerCanSeeSupplierFiguresByDefault() {
        assertThrows(ProductApiException::class.java) {
            service(false).get(actor,org,null)
        }
        Mockito.verifyNoInteractions(jdbc,approvals)
    }

    @Test fun missingCompanyAuthorityDeniesBeforeAnyLedgerRead() {
        assertThrows(ProductApiException::class.java) {
            service(true).get(actor,org,null)
        }
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test fun missingSourceIsNotReportedAsZeroPayables() {
        allow()
        Mockito.`when`(jdbc.queryForObject(
            sourceCheck,Boolean::class.java,
        )).thenReturn(false)
        val result=service(true).get(actor,org,null)
        assertFalse(result.sourceAvailable)
        assertEquals("POSTED_AP_LEDGER_SOURCE_NOT_INTEGRATED",result.sourceStatus)
        assertTrue(result.groups.isEmpty())
        assertNull(result.companyOutstandingPayables)
        assertNull(result.companySupplierPayments)
        assertNull(result.companyBankReconciledTotal)
        assertFalse(result.isCompanyWideTrialBalance)
    }

    @Test fun anUnknownOrganizationIsNotAReportForTheCurrentUser() {
        allow()
        assertThrows(ProductApiException::class.java) {
            service(true).get(actor,UUID.randomUUID(),null)
        }
        Mockito.verifyNoInteractions(jdbc)
    }
}
