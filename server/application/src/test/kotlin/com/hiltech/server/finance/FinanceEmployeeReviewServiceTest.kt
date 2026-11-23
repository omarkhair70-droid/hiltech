package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
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
import java.time.ZoneOffset
import java.util.UUID

class FinanceEmployeeReviewServiceTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val approval = Mockito.mock(ApprovalPersistencePort::class.java)

    private fun service(enabled: Boolean, key: String): FinanceEmployeeReviewService {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("test", mapOf(
            "hiltech.finance.employee-review.enabled" to enabled,
            "hiltech.finance.employee-review.authority-key" to key,
        )))
        return FinanceEmployeeReviewService(
            jdbc, approval,
            Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC),
            environment,
        )
    }

    @Test fun defaultOffKeepsAllEmployeeDraftsPrivate() {
        val denied = assertThrows<ProductApiException> {
            service(false, "").list(UUID.randomUUID(), UUID.randomUUID())
        }
        assertEquals("FINANCE_EMPLOYEE_REVIEW_UNAVAILABLE", denied.code)
        Mockito.verifyNoInteractions(jdbc, approval)
    }

    @Test fun noConfiguredCompanyReviewerMeansNoAccess() {
        val denied = assertThrows<ProductApiException> {
            service(true, "").list(UUID.randomUUID(), UUID.randomUUID())
        }
        assertEquals("FINANCE_EMPLOYEE_REVIEW_UNAVAILABLE", denied.code)
        Mockito.verifyNoInteractions(jdbc, approval)
    }

    @Test fun currentOrganizationMembershipIsMandatoryForReviewer() {
        val actor = UUID.randomUUID()
        val org = UUID.randomUUID()
        val denied = assertThrows<ProductApiException> {
            service(true, "COMPANY_CONFIGURED_EMPLOYEE_FINANCE_REVIEW").list(actor, org)
        }
        assertEquals("FINANCE_EMPLOYEE_REVIEW_UNAVAILABLE", denied.code)
        Mockito.verifyNoInteractions(jdbc)
        Mockito.verify(approval).isActiveOrganizationMember(
            org, actor, Instant.parse("2026-11-23T10:00:00Z"),
        )
    }
}
