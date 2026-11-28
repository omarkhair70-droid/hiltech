package com.hiltech.shared.core.finance

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FinancePayrollGlIntentTest {
    private val a="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val b="11111111-2222-4333-8444-555555555555"
    private val c="22222222-3333-4444-8555-666666666666"
    private val d="33333333-4444-4555-8666-777777777777"
    private val post=FinancePayrollGlPostDto(a,"PAYROLL_ACCRUAL",periodId=b,debitAccountId=c,creditAccountId=d,amount="100.00",currency="EGP")
    @Test fun accrualAndRecordedResultsHaveDistinctSourceContracts() {
        assertTrue(payrollGlIntentValid(post))
        assertFalse(payrollGlIntentValid(post.copy(sourceEventId=a)))
        assertFalse(payrollGlIntentValid(post.copy(entryType="PAYROLL_PAYMENT")))
        assertTrue(payrollGlIntentValid(post.copy(entryType="PAYROLL_PAYMENT",sourceEventId=b)))
        assertFalse(payrollGlIntentValid(post.copy(entryType="PAYROLL_VOID",sourceEventId=b)))
        assertTrue(payrollGlIntentValid(post.copy(entryType="PAYROLL_VOID",sourceEventId=b,originalJournalId=c)))
        assertFalse(payrollGlIntentValid(post.copy(amount="0")))
        assertFalse(payrollGlIntentValid(post.copy(creditAccountId=c)))
    }
}
