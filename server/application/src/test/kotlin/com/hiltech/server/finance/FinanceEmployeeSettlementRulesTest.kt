package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FinanceEmployeeSettlementRulesTest {
    private val base=FinanceEmployeeSettlementPlan(
        organizationId=UUID.randomUUID(),employeeId=UUID.randomUUID(),
        financeDraftId=UUID.randomUUID(),
        kind=FinanceEmployeeSettlementKind.FINANCIAL_IMPREST,
        action=FinanceEmployeeSettlementAction.IMPREST_RECEIPTS_ACCEPTED,
        sourceAmount=BigDecimal("1000.000000"),currency="EGP",
        sourceApproved=true,approvedFundingRecorded=true,
        sourceBankTransactionId=UUID.randomUUID(),
        sourceBankEvidenceSha256="b".repeat(64),
        settlementOperationId=UUID.randomUUID(),
        settlementEvidenceId=UUID.randomUUID(),evidenceFinalized=true,
        settlementAmount=BigDecimal("250.000000"),
        previouslySettled=BigDecimal("100.000000"),
        independentReviewerApproved=true,recoveryMethod=null,
    )
    private fun rejects(v:FinanceEmployeeSettlementPlan) {
        assertThrows(IllegalArgumentException::class.java) {
            FinanceEmployeeSettlementRules.validate(v)
        }
    }
    @Test fun companyImprestReceiptsOnlyReduceUnsettledCustody() {
        val v=FinanceEmployeeSettlementRules.validate(base)
        assertEquals("650.000000",v.unsettledAmount)
        assertFalse(v.posted)
        assertFalse(v.fundsTransferredBySystem)
    }
    @Test fun custodyReturnRequiresConfirmedBankCredit() {
        rejects(base.copy(action=
            FinanceEmployeeSettlementAction.IMPREST_CASH_RETURN_CONFIRMED))
        FinanceEmployeeSettlementRules.validate(base.copy(
            action=FinanceEmployeeSettlementAction.IMPREST_CASH_RETURN_CONFIRMED,
            recoveryMethod="CONFIRMED_BANK_CREDIT",
        ))
    }
    @Test fun advanceIsNotAnImprestAndNoAutomaticDeduction() {
        val advance=base.copy(kind=FinanceEmployeeSettlementKind.EMPLOYEE_ADVANCE,
            action=FinanceEmployeeSettlementAction.ADVANCE_RECOVERY_CONFIRMED,
            recoveryMethod="CONFIRMED_BANK_CREDIT")
        FinanceEmployeeSettlementRules.validate(advance)
        rejects(advance.copy(recoveryMethod="AUTOMATIC_PAYROLL_DEDUCTION"))
        rejects(advance.copy(action=
            FinanceEmployeeSettlementAction.IMPREST_RECEIPTS_ACCEPTED))
    }
    @Test fun refuseUnfundedDraftsAndOverSettlement() {
        rejects(base.copy(approvedFundingRecorded=false))
        rejects(base.copy(sourceBankTransactionId=null))
        rejects(base.copy(sourceBankEvidenceSha256=null))
        rejects(base.copy(independentReviewerApproved=false))
        rejects(base.copy(evidenceFinalized=false))
        rejects(base.copy(previouslySettled=BigDecimal("900")))
        rejects(base.copy(settlementAmount=BigDecimal("0.0000001")))
    }
    @Test fun reimbursedExpenseNeedsConfirmedBankDebitNotLoan() {
        val paid=base.copy(kind=FinanceEmployeeSettlementKind.EXPENSE_REIMBURSEMENT,
            action=FinanceEmployeeSettlementAction.REIMBURSEMENT_CONFIRMED)
        FinanceEmployeeSettlementRules.validate(paid)
        rejects(paid.copy(recoveryMethod="SEPARATELY_APPROVED_PAYROLL_DEDUCTION"))
    }
}
