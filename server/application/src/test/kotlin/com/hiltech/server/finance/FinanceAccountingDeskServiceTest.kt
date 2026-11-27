package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.util.UUID

class FinanceAccountingDeskServiceTest {
    @Test fun disabledOrUnauthorizedReportNeverReadsFinancialRows() {
        val jdbc=Mockito.mock(JdbcTemplate::class.java)
        val approvals=Mockito.mock(ApprovalPersistencePort::class.java)
        val environment=StandardEnvironment()
        val actor=UUID.randomUUID()
        val org=UUID.randomUUID()
        val service=FinanceAccountingDeskService(jdbc,approvals,environment,Clock.systemUTC())
        assertThrows(ProductApiException::class.java) { service.exposure(actor,org,"AP",0) }
        Mockito.verifyNoInteractions(jdbc,approvals)
        environment.propertySources.addFirst(MapPropertySource("test",mapOf(
            "hiltech.finance.ap-ar-balances.enabled" to true,
            "hiltech.finance.ap-reporting.enabled" to true)))
        assertThrows(ProductApiException::class.java) { service.ledger(actor,org,UUID.randomUUID(),0) }
        assertThrows(ProductApiException::class.java) { service.exposure(actor,org,"AR",0) }
        Mockito.verifyNoInteractions(jdbc)
    }
}
