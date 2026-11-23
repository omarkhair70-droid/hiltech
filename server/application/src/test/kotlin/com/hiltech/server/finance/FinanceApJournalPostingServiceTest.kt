package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.procurement.PurchaseOrderIssuePersistence
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FinanceApJournalPostingServiceTest {
    private val jdbc=Mockito.mock(JdbcTemplate::class.java)
    private val purchases=Mockito.mock(PurchaseOrderIssuePersistence::class.java)
    private val source=Mockito.mock(FinanceSupplierAccountingApprovalSubject::class.java)
    private val subject=Mockito.mock(FinanceApJournalPostingApprovalSubject::class.java)
    private val approval=Mockito.mock(ApprovalPersistencePort::class.java)
    private val engine=Mockito.mock(ApprovalEngineService::class.java)
    private val commands=Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit=Mockito.mock(AuditEventWriter::class.java)
    private val clock=Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"),ZoneOffset.UTC)
    private val org=UUID.randomUUID()
    private val actor=UUID.randomUUID()
    private val candidate=UUID.randomUUID()
    private val proposal=UUID.randomUUID()
    private val operation=UUID.randomUUID()
    private val account=UUID.randomUUID()
    private val payableAccount=UUID.randomUUID()
    private val period=UUID.randomUUID()
    private val prepKey="FINANCE_AP_PREPARER"

    private fun service()=FinanceApJournalPostingService(
        jdbc,purchases,source,subject,approval,engine,commands,audit,clock,
        StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("posting-test",mapOf(
                "hiltech.finance.ap-posting.preparer-authority-key" to prepKey,
                "hiltech.finance.ap-posting.operator-authority-key" to "FINANCE_AP_OPERATOR",
            )))
        },
    )

    private fun proposalRequest()=FinanceApProposeJournalRequest(
        operationId=operation,
        fiscalPeriodId=period,
        accountingTreatmentReference="APPROVED-ACCOUNTING-2026",
        accountingTaxDisposition="TAX_REVIEWED_NO_RECOVERABLE_TAX",
        lines=listOf(
            FinanceApJournalLineCommand(account,FinanceJournalSide.DEBIT,"125.00"),
            FinanceApJournalLineCommand(payableAccount,FinanceJournalSide.CREDIT,"125.00"),
        ),
    )

    @Test fun allFinancePostingMutationsStayOffByDefaultBeforeAnySourceOrSQL() {
        assertThrows(ProductApiException::class.java) {
            service().propose(actor,org,candidate,proposalRequest(),"test")
        }
        assertThrows(ProductApiException::class.java) {
            service().requestApproval(actor,org,proposal,operation,"test")
        }
        assertThrows(ProductApiException::class.java) {
            service().post(actor,org,proposal,operation,"test")
        }
        Mockito.verifyNoInteractions(jdbc,purchases,source,engine,commands,audit,approval)
    }

    @Test fun enabledPostingWithoutActualCompanyFinanceBindingFailsBeforeLedgerAccess() {
        Mockito.`when`(subject.enabled()).thenReturn(true)
        assertThrows(ProductApiException::class.java) {
            service().propose(actor,org,candidate,proposalRequest(),"test")
        }
        Mockito.verify(approval).isActiveOrganizationMember(
            org,actor,clock.instant(),
        )
        Mockito.verifyNoInteractions(jdbc,purchases,source,engine,commands,audit)
    }

    @Test fun eligiblePreparerWithUnreviewedSupplierInvoiceCannotProposeGLPosting() {
        Mockito.`when`(subject.enabled()).thenReturn(true)
        val binding=ApprovalAuthorityBindingSnapshot(
            UUID.randomUUID(),org,prepKey,
            ApprovalPrincipalType.USER,actor,
            clock.instant().minusSeconds(600),null,1,
        )
        Mockito.`when`(approval.isActiveOrganizationMember(
            org,actor,clock.instant(),
        )).thenReturn(true)
        Mockito.`when`(approval.resolveCurrentAuthority(
            org,prepKey,clock.instant(),
        )).thenReturn(binding)
        Mockito.`when`(approval.actorMatchesAuthority(
            actor,binding,clock.instant(),
        )).thenReturn(true)
        assertThrows(ProductApiException::class.java) {
            service().propose(actor,org,candidate,proposalRequest(),"test")
        }
        Mockito.verify(source).current(org,candidate)
        Mockito.verifyNoInteractions(jdbc,purchases,engine,commands,audit)
    }
}
