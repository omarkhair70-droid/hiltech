package com.hiltech.server.payroll

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.math.BigDecimal
import java.util.UUID

class Payroll9cRunFinanceStatusServiceTest {
    private val org = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val runId = UUID.randomUUID()
    private val positiveEmployee = UUID.randomUUID()
    private val zeroEmployee = UUID.randomUUID()
    private val drafts = Mockito.mock(Payroll9bDraftService::class.java)
    private val authority = Mockito.mock(Payroll9aAuthorityPort::class.java)
    private val publication = Mockito.mock(PayrollRunPublicationPort::class.java)

    private fun line(employee: UUID, net: String) = Payroll9bRunLineDto(employee.toString(), "SYNTHETIC", "Synthetic employee",
        UUID.randomUUID().toString(), "EGP", net, "0", "0", net, "b".repeat(64))

    private fun service(lines: List<Payroll9bRunLineDto>, connected: Boolean = false): Payroll9cRunFinanceStatusService {
        val total = lines.fold(BigDecimal.ZERO) { amount, line -> amount + BigDecimal(line.net) }.toPlainString()
        val run = Payroll9bRunDto(runId.toString(), org.toString(), UUID.randomUUID().toString(), 1, "DRAFT", "EGP",
            total, "0", "0", total, "a".repeat(64), lines)
        Mockito.`when`(drafts.read(actor, runId)).thenReturn(run)
        Mockito.`when`(authority.canReview(actor, org)).thenReturn(true)
        Mockito.`when`(publication.canPublish(org, runId, run.sourceFingerprint)).thenReturn(true)
        val emptyFinance = object : PayrollFinancePaymentResultsPort {
            override fun expectedPayments(organizationId: UUID, runVersionId: UUID) = emptyList<PayrollExpectedPayment>()
            override fun paymentEvents(organizationId: UUID, runVersionId: UUID) = emptyList<PayrollFinanceResult>()
        }
        return Payroll9cRunFinanceStatusService(drafts, authority,
            StaticListableBeanFactory(mapOf("publication" to publication)).getBeanProvider(PayrollRunPublicationPort::class.java),
            StaticListableBeanFactory(if (connected) mapOf("finance" to emptyFinance) else emptyMap<String, Any>())
                .getBeanProvider(PayrollFinancePaymentResultsPort::class.java))
    }

    @Test fun absentInstructionsKeepZeroNetEmployeesOutOfUnresolvedPaymentCount() {
        val lines = listOf(line(positiveEmployee, "100.00"), line(zeroEmployee, "0.00"))
        val recorded = service(lines, connected = true).status(actor, runId)
        assertEquals("PAYMENT_NOT_RECORDED", recorded.status)
        assertTrue(recorded.financeConnected)
        assertEquals(1, recorded.unresolvedCount)
        assertEquals(mapOf(positiveEmployee.toString() to "PAYMENT_NOT_RECORDED", zeroEmployee.toString() to "NOT_APPLICABLE"),
            recorded.employeeOutcomes.associate { it.employeeId to it.outcome })
        val unavailable = service(lines).status(actor, runId)
        assertEquals("FINANCE_NOT_CONNECTED", unavailable.status)
        assertFalse(unavailable.financeConnected)
        assertEquals(1, unavailable.unresolvedCount)
        assertEquals(mapOf(positiveEmployee.toString() to "PAYMENT_STATUS_UNAVAILABLE", zeroEmployee.toString() to "NOT_APPLICABLE"),
            unavailable.employeeOutcomes.associate { it.employeeId to it.outcome })
    }

    @Test fun approvedZeroNetRunNeedsNoPaymentAdapterOrInventedInstruction() {
        val result = service(listOf(line(zeroEmployee, "0.00"))).status(actor, runId)
        assertEquals("NO_PAYMENT_REQUIRED", result.status)
        assertEquals(0, result.unresolvedCount)
        assertEquals(0, result.settledCount)
        assertEquals("NOT_APPLICABLE", result.employeeOutcomes.single().outcome)
        assertTrue(result.publicationApproved)
        assertFalse(result.financeConnected)
    }
}
