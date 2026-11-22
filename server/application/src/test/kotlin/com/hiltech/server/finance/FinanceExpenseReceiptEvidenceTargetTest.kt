package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.documents.FinanceExpenseReceiptEvidenceTarget
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FinanceExpenseReceiptEvidenceTargetTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val approval = Mockito.mock(ApprovalPersistencePort::class.java)
    private val actor = UUID.randomUUID()
    private val outsider = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val target = FinanceExpenseReceiptEvidenceTarget(
        expenseDraftId = UUID.randomUUID(),
        organizationId = org,
        createdByUserId = actor,
        employeeId = UUID.randomUUID(),
    )

    private fun adapter(enabled: Boolean) : JdbcFinanceExpenseReceiptEvidenceTarget {
        val env = StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("test",
            mapOf("hiltech.finance.expense-receipts.canonical-upload-ready" to enabled)))
        return JdbcFinanceExpenseReceiptEvidenceTarget(
            jdbc, approval,
            Clock.fixed(Instant.parse("2026-11-23T14:00:00Z"), ZoneOffset.UTC),
            env,
        )
    }

    @Test fun defaultOffBeforeAnyIdentityOrSourceLookup() {
        val port = adapter(false)
        assertFalse(port.canReserve(actor, target))
        assertFalse(port.canAccess(actor, target))
        Mockito.verifyNoInteractions(jdbc, approval)
    }

    @Test fun outsiderCannotReserveSomeoneElsesReceipt() {
        assertFalse(adapter(true).canReserve(outsider, target))
        Mockito.verifyNoInteractions(jdbc, approval)
    }

    @Test fun membershipRevocationBlocksOwnerAndReviewer() {
        val port = adapter(true)
        assertFalse(port.canReserve(actor, target))
        assertFalse(port.canAccess(actor, target))
        Mockito.verify(approval, Mockito.atLeastOnce())
            .isActiveOrganizationMember(
                org, actor, Instant.parse("2026-11-23T14:00:00Z"),
            )
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test fun nonexistentExpenseDraftCannotBecomeAnEvidenceTarget() {
        assertNull(adapter(false).loadTarget(UUID.randomUUID()))
    }
}
