package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class FinanceBankReconciliationRulesTest {
    private val org=UUID.randomUUID()
    private val bank=UUID.randomUUID()
    private val statement=UUID.randomUUID()
    private val line=FinanceBankStatementLine(
        organizationId=org,bankAccountId=bank,statementId=statement,
        statementSha256="c".repeat(64),statementApprovedForImport=true,
        statementLineId=UUID.randomUUID(),
        externalTransactionId="EG-BANK-TXN-000099",
        bookedOn=LocalDate.parse("2026-11-23"),currency="EGP",
        flow=FinanceBankFlow.CREDIT,amount=BigDecimal("300.000000"),
        confirmedByBank=true,
    )
    private val allocation=FinanceBankLedgerAllocation(
        organizationId=org,allocationId=UUID.randomUUID(),
        sourceId=UUID.randomUUID(),sourceKind=FinanceCashSourceKind.CUSTOMER_RECEIVABLE,
        bankAccountId=bank,statementLineId=line.statementLineId,
        externalTransactionId=line.externalTransactionId,currency="EGP",
        flow=FinanceBankFlow.CREDIT,amount=BigDecimal("200.000000"),
        accountingPosted=true,independentlyApproved=true,
    )
    private val plan=FinanceBankReconciliationSnapshot(
        organizationId=org,bankAccountId=bank,statementId=statement,
        statementStart=LocalDate.parse("2026-11-01"),
        statementEnd=LocalDate.parse("2026-11-30"),
        openingBalance=BigDecimal("1000.000000"),
        closingBalance=BigDecimal("1300.000000"),
        lines=listOf(line),allocations=listOf(allocation),
        importedStatementIsComplete=true,bankAccountActive=true,
        independentReconcilerAuthorized=true,
    )
    private fun reject(p:FinanceBankReconciliationSnapshot) {
        assertThrows(IllegalArgumentException::class.java) {
            FinanceBankReconciliationRules.inspect(p)
        }
    }
    @Test fun partiallyAllocatedCollectionIsExceptionNotFakeMatch() {
        val result=FinanceBankReconciliationRules.inspect(plan)
        assertEquals(0,result.matchedStatementLineCount)
        assertEquals(listOf(line.statementLineId),result.unmatchedStatementLineIds)
        assertEquals(listOf(allocation.allocationId),result.unmatchedAllocationIds)
        assertFalse(result.reconciled)
    }
    @Test fun fullMatchStillRequiresSeparateReconciliationApproval() {
        val r=FinanceBankReconciliationRules.inspect(
            plan.copy(allocations=listOf(allocation.copy(amount=line.amount)))
        )
        assertEquals(1,r.matchedStatementLineCount)
        assertEquals(emptyList<UUID>(),r.unmatchedStatementLineIds)
        assertFalse(r.reconciled)
    }
    @Test fun neverMatchByAmountAloneAndNoForgedBankStatement() {
        reject(plan.copy(closingBalance=BigDecimal("1299")))
        reject(plan.copy(lines=listOf(line.copy(confirmedByBank=false))))
        reject(plan.copy(lines=listOf(line,line.copy(statementLineId=UUID.randomUUID()))))
        reject(plan.copy(allocations=listOf(
            allocation.copy(statementLineId=UUID.randomUUID()))))
        reject(plan.copy(allocations=listOf(
            allocation.copy(amount=BigDecimal("301")))))
        reject(plan.copy(allocations=listOf(
            allocation.copy(accountingPosted=false))))
        reject(plan.copy(allocations=listOf(
            allocation.copy(independentlyApproved=false))))
        reject(plan.copy(importedStatementIsComplete=false))
    }
    @Test fun supplierPaymentMustBeBankDebit() {
        reject(plan.copy(allocations=listOf(
            allocation.copy(sourceKind=FinanceCashSourceKind.SUPPLIER_PAYABLE))))
    }
}
