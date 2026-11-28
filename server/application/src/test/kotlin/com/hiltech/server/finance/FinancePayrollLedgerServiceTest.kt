package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.env.StandardEnvironment
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.util.UUID

class FinancePayrollLedgerServiceTest {
    @Test fun noPayrollOrAccountingAuthorityDoesNotReadSensitiveRows() {
        val jdbc=Mockito.mock(JdbcTemplate::class.java)
        val payroll=Mockito.mock(FinancePayrollAuthorityPort::class.java)
        val approvals=Mockito.mock(ApprovalPersistencePort::class.java)
        val commands=Mockito.mock(IdempotentCommandExecutor::class.java)
        val service=FinancePayrollLedgerService(jdbc,payroll,approvals,StandardEnvironment(),Clock.systemUTC(),
            commands,Mockito.mock(AuditEventWriter::class.java))
        val actor=UUID.randomUUID();val org=UUID.randomUUID();val run=UUID.randomUUID()
        assertThrows(ProductApiException::class.java){service.view(actor,org,run,0)}
        Mockito.`when`(payroll.canPrepare(actor,org)).thenReturn(true)
        assertThrows(ProductApiException::class.java){service.view(actor,org,run,0)}
        assertThrows(ProductApiException::class.java){service.post(actor,org,run,
            FinancePayrollGlPost(UUID.randomUUID(),"PAYROLL_ACCRUAL",periodId=UUID.randomUUID(),
                debitAccountId=UUID.randomUUID(),creditAccountId=UUID.randomUUID(),amount="100",currency="EGP"),"synthetic")}
        Mockito.verifyNoInteractions(jdbc,commands)
    }
}
