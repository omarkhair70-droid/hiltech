package com.hiltech.shared.core.payroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmployeePayrollWorkOrderTest {
    private val employee = "employee-a"
    private fun row(domain: String, state: String, owner: String = employee) =
        PayrollSourceRecordDto("record-$domain-$owner", "period-a", domain, owner, 1, null,
            "Synthetic approved source", "EGP", domain != "COMPENSATION",
            if (domain == "COMPENSATION") listOf(PayrollSourceComponentDto("BASE", "EARNING", "100", description = "Base pay")) else emptyList(),
            null, null, null, null, null, "test-preparer", "2026-11-26T00:00:00Z", state, null)

    @Test fun missingFinancialFactsArePrioritizedWithoutCountingOtherEmployees() {
        assertEquals("COMPENSATION", nextEmployeePayrollSource(listOf(row("COMPENSATION", "APPROVED", "employee-b")), employee))
        assertEquals("APPROVED_TIME", nextEmployeePayrollSource(listOf(row("COMPENSATION", "APPROVED")), employee))
        assertEquals("FINANCE", nextEmployeePayrollSource(listOf(row("COMPENSATION", "DRAFT"), row("APPROVED_TIME", "PENDING")), employee))
    }

    @Test fun reviewedSourcesDoNotGenerateNewPreparationAndDraftsRemainActionable() {
        val records = listOf(row("COMPENSATION", "APPROVED"), row("APPROVED_TIME", "PENDING"), row("FINANCE", "APPROVED"))
        assertNull(nextEmployeePayrollSource(records, employee))
        assertEquals("FINANCE", nextEmployeePayrollSource(records.map {
            if (it.domain == "FINANCE") it.copy(approvalState = "CHANGE_REQUESTED") else it
        }, employee))
    }
}
