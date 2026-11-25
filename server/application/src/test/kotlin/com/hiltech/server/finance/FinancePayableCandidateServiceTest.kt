package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.procurement.PurchaseOrderIssuePersistence
import com.hiltech.server.procurement.SupplierInvoiceMatchingService
import org.junit.jupiter.api.Assertions.assertEquals
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

class FinancePayableCandidateServiceTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val approval = Mockito.mock(ApprovalPersistencePort::class.java)
    private val purchases = Mockito.mock(PurchaseOrderIssuePersistence::class.java)
    private val invoices = Mockito.mock(SupplierInvoiceMatchingService::class.java)
    private val commands = Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)

    private fun service(enabled: Boolean = false, key: String = ""): FinancePayableCandidateService {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("test", mapOf(
            "hiltech.finance.payable-candidates.enabled" to enabled,
            "hiltech.finance.payables.review-authority-key" to key,
        )))
        return FinancePayableCandidateService(
            jdbc, approval, purchases, invoices, commands, audit,
            Clock.fixed(Instant.parse("2026-11-22T20:00:00Z"), ZoneOffset.UTC),
            environment,
        )
    }

    @Test fun defaultOffDoesNotReadSensitiveFinancialData() {
        val error = assertThrows<ProductApiException> {
            service().handoffs(UUID.randomUUID(), UUID.randomUUID())
        }
        assertEquals("FINANCE_PAYABLE_UNAVAILABLE", error.code)
        Mockito.verifyNoInteractions(jdbc, approval, purchases, invoices, commands, audit)
    }

    @Test fun enabledWithoutAnAssignedCompanyReviewerFailsClosed() {
        val actor = UUID.randomUUID()
        val org = UUID.randomUUID()
        val now = Instant.parse("2026-11-22T20:00:00Z")
        Mockito.`when`(approval.isActiveOrganizationMember(org, actor, now)).thenReturn(true)
        val error = assertThrows<ProductApiException> {
            service(enabled = true).candidates(actor, org)
        }
        assertEquals("FINANCE_PAYABLE_UNAVAILABLE", error.code)
        Mockito.verify(approval).isActiveOrganizationMember(org, actor, now)
        Mockito.verify(approval).resolveCurrentAuthority(org, "FINANCE_SUPPLIER_REVIEW", now)
        Mockito.verifyNoMoreInteractions(approval)
        Mockito.verifyNoInteractions(jdbc, purchases, invoices, commands, audit)
    }

    @Test fun currentCompanyMembershipIsMandatoryBeforeQuery() {
        val actor = UUID.randomUUID()
        val org = UUID.randomUUID()
        val error = assertThrows<ProductApiException> {
            service(enabled = true, key = "COMPANY_CONFIGURED_FINANCE_REVIEW")
                .handoffs(actor, org)
        }
        assertEquals("FINANCE_PAYABLE_UNAVAILABLE", error.code)
        Mockito.verifyNoInteractions(jdbc, purchases, invoices, commands, audit)
        Mockito.verify(approval).isActiveOrganizationMember(
            org, actor, Instant.parse("2026-11-22T20:00:00Z"),
        )
    }

    @Test fun rejectedPermissionKeyNeverEnablesFinance() {
        val actor = UUID.randomUUID()
        val org = UUID.randomUUID()
        val now = Instant.parse("2026-11-22T20:00:00Z")
        Mockito.`when`(approval.isActiveOrganizationMember(org, actor, now)).thenReturn(true)
        val error = assertThrows<ProductApiException> {
            service(enabled = true, key = "finance guessed")
                .candidates(actor, org)
        }
        assertEquals("FINANCE_PAYABLE_UNAVAILABLE", error.code)
        Mockito.verify(approval).isActiveOrganizationMember(org, actor, now)
        Mockito.verifyNoMoreInteractions(approval)
        Mockito.verifyNoInteractions(jdbc, purchases, invoices, commands, audit)
    }
}
