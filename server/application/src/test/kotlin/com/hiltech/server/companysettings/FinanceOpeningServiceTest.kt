package com.hiltech.server.companysettings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class FinanceOpeningServiceTest {
    @Test fun onlyBalancedUniqueExactAmountsCanReachPosting() {
        val a=UUID.randomUUID();val b=UUID.randomUUID();val c=UUID.randomUUID()
        val lines=listOf(FinanceOpeningLine(a,"DEBIT","0.1","SYNTHETIC-SOURCE"),
            FinanceOpeningLine(b,"DEBIT","0.2","SYNTHETIC-SOURCE"),
            FinanceOpeningLine(c,"CREDIT","0.3","SYNTHETIC-SOURCE"))
        val input=FinanceOpeningInput(UUID.randomUUID(),UUID.randomUUID(),"EGP","SYNTHETIC-AUTH","SYNTHETIC-SCHEDULE",lines)
        assertTrue(validFinanceOpening(input))
        assertFalse(validFinanceOpening(input.copy(lines=lines.dropLast(1)+lines.last().copy(amount="0.300001"))))
        assertFalse(validFinanceOpening(input.copy(lines=listOf(lines.first(),lines.first(),lines.last()))))
        assertFalse(validFinanceOpening(input.copy(lines=lines.dropLast(1)+lines.last().copy(amount="3e-1"))))
    }
    @Test fun verifiedStockValuationRequiresExactTotalsAndDistinctMovements() {
        val stock=FinanceOpeningStockValue(UUID.randomUUID(),"10.123456","PC","a".repeat(64),"125.50","SYNTHETIC-VALUATION-SCHEDULE")
        val account=FinanceOpeningLine(UUID.randomUUID(),"DEBIT","125.50","SYNTHETIC-STOCK-SCHEDULE",stockValues=listOf(stock))
        val capital=FinanceOpeningLine(UUID.randomUUID(),"CREDIT","125.50","SYNTHETIC-CAPITAL-SCHEDULE")
        val input=FinanceOpeningInput(UUID.randomUUID(),UUID.randomUUID(),"EGP","SYNTHETIC-AUTH","SYNTHETIC-SCHEDULE",listOf(account,capital))
        assertTrue(validFinanceOpening(input))
        assertFalse(validFinanceOpening(input.copy(lines=listOf(account.copy(stockValues=listOf(stock.copy(amount="125.49"))),capital))))
        assertFalse(validFinanceOpening(input.copy(lines=listOf(account.copy(stockValues=listOf(stock.copy(sourceFingerprint="invented"))),capital))))
        assertFalse(validFinanceOpening(input.copy(lines=listOf(account.copy(stockValues=listOf(stock,stock)),capital))))
        assertFalse(validFinanceOpening(input.copy(lines=listOf(account.copy(bankStatementId=UUID.randomUUID()),capital))))
    }
}
