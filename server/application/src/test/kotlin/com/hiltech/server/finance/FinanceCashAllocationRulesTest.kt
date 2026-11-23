package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class FinanceCashAllocationRulesTest {
    private val plan=FinanceCashAllocationPlan(
        organizationId=UUID.randomUUID(),sourceId=UUID.randomUUID(),
        sourceKind=FinanceCashSourceKind.CUSTOMER_RECEIVABLE,
        sourcePostAmount=BigDecimal("1000.000000"),
        sourceRecognized=true,sourceCurrency="EGP",
        bankAccountId=UUID.randomUUID(),bankTransactionId=UUID.randomUUID(),
        externalBankTransactionId="EG-BANK-TXN-2026-0001",
        bankVerifiedSourceSha256="a".repeat(64),bankEvidenceFinalized=true,
        bankTransactionAmount=BigDecimal("300.000000"),
        bankFlow=FinanceBankFlow.CREDIT,bankCurrency="EGP",
        bankValueDate=LocalDate.parse("2026-11-23"),
        allocationAmount=BigDecimal("200.000000"),
        priorSourceAllocated=BigDecimal("100.000000"),
        priorBankAllocated=BigDecimal.ZERO,
        separateApprovalConfirmed=true,
    )
    private fun rejects(x:FinanceCashAllocationPlan) {
        assertThrows(IllegalArgumentException::class.java) {
            FinanceCashAllocationRules.validate(x)
        }
    }
    @Test fun partialCustomerCollectionDoesNotProveBankReconciliation() {
        val result=FinanceCashAllocationRules.validate(plan)
        assertEquals("700.000000",result.remainingSourceAfter)
        assertEquals("100.000000",result.remainingBankAfter)
        assertFalse(result.bankReconciled)
        assertFalse(result.moneyTransferredBySystem)
    }
    @Test fun noEstimatedOrWrongDirectionOrDuplicateBankAllocation() {
        rejects(plan.copy(sourceRecognized=false))
        rejects(plan.copy(bankEvidenceFinalized=false))
        rejects(plan.copy(bankVerifiedSourceSha256=""))
        rejects(plan.copy(bankFlow=FinanceBankFlow.DEBIT))
        rejects(plan.copy(bankCurrency="USD"))
        rejects(plan.copy(separateApprovalConfirmed=false))
        rejects(plan.copy(priorBankAllocated=BigDecimal("200")))
        rejects(plan.copy(priorSourceAllocated=BigDecimal("900")))
        rejects(plan.copy(allocationAmount=BigDecimal("0.0000001")))
    }
    @Test fun paymentMustBeBankDebitAndImprestIsNotBorrowing() {
        val outgoing=plan.copy(
            sourceKind=FinanceCashSourceKind.SUPPLIER_PAYABLE,
            bankFlow=FinanceBankFlow.DEBIT,
        )
        assertEquals("700.000000",FinanceCashAllocationRules.validate(
            outgoing).remainingSourceAfter)
        rejects(outgoing.copy(bankFlow=FinanceBankFlow.CREDIT))
        val imprest=outgoing.copy(sourceKind=FinanceCashSourceKind.FINANCIAL_IMPREST)
        FinanceCashAllocationRules.validate(imprest)
    }
}
