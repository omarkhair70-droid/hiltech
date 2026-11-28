package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FinanceEmployeeLedgerRulesTest {
    @Test fun employeeKindsDoNotInheritUnrelatedAccountingSourceRules() {
        assertTrue(settlementSourcePair("EMPLOYEE_FUNDING","EA"))
        assertTrue(settlementSourcePair("EMPLOYEE_RETURN","EI"))
        assertFalse(settlementSourcePair("EMPLOYEE_FUNDING","AP"))
        assertFalse(settlementSourcePair("IMPREST_EXPENSE","EA"))
        assertFalse(settlementSourcePair("BANK_ALLOCATION","EI"))
        assertTrue(settlementDebitKindAllowed("EMPLOYEE_IMPREST_ASSET","EMPLOYEE_IMPREST_ASSET"))
        assertFalse(settlementDebitKindAllowed("EMPLOYEE_IMPREST_ASSET","EXPENSE"))
        assertFalse(settlementDebitKindAllowed("COST","BANK_ASSET"))
        assertTrue(settlementDebitKindAllowed("COST","FIXED_ASSET"))
        assertFalse(settlementTreatmentValid("EXPENSE_REIMBURSEMENT",null))
        assertFalse(settlementTreatmentValid("EXPENSE_REIMBURSEMENT","synthetic\nreference"))
        assertTrue(settlementTreatmentValid("EXPENSE_REIMBURSEMENT","SYNTHETIC-TREATMENT"))
        assertFalse(settlementTreatmentValid("EMPLOYEE_RETURN","UNEXPECTED-TREATMENT"))
    }
}
