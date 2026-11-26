package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class FinanceEmployeeDraftServiceTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val approval = Mockito.mock(ApprovalPersistencePort::class.java)
    private val commands = Mockito.mock(IdempotentCommandExecutor::class.java)
    private val audit = Mockito.mock(AuditEventWriter::class.java)
    private val now = Instant.parse("2026-11-23T10:00:00Z")

    private fun service(enabled: Boolean = false): FinanceEmployeeDraftService {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("test",
            mapOf("hiltech.finance.employee-requests.enabled" to enabled)))
        return FinanceEmployeeDraftService(
            jdbc, approval, commands, audit,
            Clock.fixed(now, ZoneOffset.UTC), environment,
        )
    }

    @Test fun employeeDraftsOffByDefaultWithoutDatabaseAccess() {
        val denied = assertThrows<ProductApiException> {
            service().expenses(UUID.randomUUID(), UUID.randomUUID())
        }
        assertEquals("FINANCE_EMPLOYEE_DRAFT_UNAVAILABLE", denied.code)
        Mockito.verifyNoInteractions(jdbc, approval, commands, audit)
    }

    @Test fun cannotReadOtherEmployeeDraftsWithoutCurrentMembership() {
        val denied = assertThrows<ProductApiException> {
            service(enabled = true).advances(UUID.randomUUID(), UUID.randomUUID())
        }
        assertEquals("FINANCE_EMPLOYEE_DRAFT_UNAVAILABLE", denied.code)
        Mockito.verifyNoInteractions(jdbc, commands, audit)
    }

    @Test fun noAdvanceDraftCanBeIssuedWithoutCurrentEmployment() {
        val denied = assertThrows<ProductApiException> {
            service(enabled = true).createAdvance(
                UUID.randomUUID(), UUID.randomUUID(), "negative-advance",
                UUID.randomUUID().toString(),
                CreateEmployeeAdvanceDraftRequest(
                    UUID.randomUUID(), "100", "EGP", "Personal request",
                ),
            )
        }
        assertEquals("FINANCE_EMPLOYEE_DRAFT_UNAVAILABLE", denied.code)
        Mockito.verifyNoInteractions(jdbc, commands, audit)
    }

    @Test fun invalidAdvanceAmountNeverCreatesDebtOrCallsDatabase() {
        val denied = assertThrows<ProductApiException> {
            service(enabled = true).createAdvance(
                UUID.randomUUID(), UUID.randomUUID(), "bad-amount", null,
                CreateEmployeeAdvanceDraftRequest(
                    UUID.randomUUID(), "-100", "EGP", "Personal request",
                ),
            )
        }
        assertEquals("FINANCE_EMPLOYEE_DRAFT_INVALID", denied.code)
        Mockito.verifyNoInteractions(jdbc, approval, commands, audit)
    }

    @Test fun invalidImprestCurrencyNeverCreatesCashCustody() {
        val denied = assertThrows<ProductApiException> {
            service(enabled = true).createImprest(
                UUID.randomUUID(), UUID.randomUUID(), "bad-currency", null,
                CreateFinancialImprestDraftRequest(
                    UUID.randomUUID(), "100", "UNKNOWN", "Field materials",
                ),
            )
        }
        assertEquals("FINANCE_EMPLOYEE_DRAFT_INVALID", denied.code)
        Mockito.verifyNoInteractions(jdbc, approval, commands, audit)
    }

    @Test fun expenseDraftRequiresMerchantAndRealDate() {
        val denied = assertThrows<ProductApiException> {
            service(enabled = true).createExpense(
                UUID.randomUUID(), UUID.randomUUID(), "bad-merchant", null,
                CreateExpenseClaimDraftRequest(
                    UUID.randomUUID(), "80", "EGP", "Work supplies",
                    " ", LocalDate.parse("2026-11-22"),
                ),
            )
        }
        assertEquals("FINANCE_EMPLOYEE_DRAFT_INVALID", denied.code)
        Mockito.verifyNoInteractions(jdbc, approval, commands, audit)
    }
}
