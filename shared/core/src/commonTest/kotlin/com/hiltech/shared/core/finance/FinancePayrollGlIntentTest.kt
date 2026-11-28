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
    @Test fun clearanceBindsDistinctRunsAndRequiresExplicitReviewedSource() {
        val input=FinancePayrollClearanceInputDto(a,b,c,"NO_PRIOR_TRANSFER_VERIFIED","synthetic-hr-correction","synthetic-finance-register")
        assertTrue(payrollClearanceIntentValid(input))
        assertTrue(payrollClearanceIntentValid(input.copy(disposition="REVERSAL_VERIFIED")))
        assertFalse(payrollClearanceIntentValid(input.copy(replacementRunVersionId=b)))
        assertFalse(payrollClearanceIntentValid(input.copy(financeReference="")))
        assertFalse(payrollClearanceIntentValid(input.copy(disposition="SETTLED_DIFFERENTIAL_AUTHORIZED")))
    }
    @Test fun accrualAndRecordedResultsHaveDistinctSourceContracts() {
        assertTrue(payrollGlIntentValid(post))
        assertTrue(payrollGlIntentValid(post.copy(originalJournalId=c)))
        assertFalse(payrollGlIntentValid(post.copy(entryType="PAYROLL_PAYMENT",sourceEventId=b,originalJournalId=c)))
        assertFalse(payrollGlIntentValid(post.copy(sourceEventId=a)))
        assertFalse(payrollGlIntentValid(post.copy(entryType="PAYROLL_PAYMENT")))
        assertTrue(payrollGlIntentValid(post.copy(entryType="PAYROLL_PAYMENT",sourceEventId=b)))
        assertFalse(payrollGlIntentValid(post.copy(entryType="PAYROLL_VOID",sourceEventId=b)))
        assertTrue(payrollGlIntentValid(post.copy(entryType="PAYROLL_VOID",sourceEventId=b,originalJournalId=c)))
        assertFalse(payrollGlIntentValid(post.copy(amount="0")))
        assertFalse(payrollGlIntentValid(post.copy(creditAccountId=c)))
        val component=FinancePayrollGlAllocationDto("DEDUCTION","FINANCE","SYNTHETIC_TAX",d,"SYNTHETIC-TREATMENT")
        assertTrue(payrollGlIntentValid(post.copy(allocations=listOf(component))))
        assertFalse(payrollGlIntentValid(post.copy(allocations=listOf(component,component))))
        assertFalse(payrollGlIntentValid(post.copy(allocations=listOf(component.copy(reference="")))))
        assertFalse(payrollGlIntentValid(post.copy(entryType="PAYROLL_PAYMENT",sourceEventId=b,allocations=listOf(component))))
    }
}
