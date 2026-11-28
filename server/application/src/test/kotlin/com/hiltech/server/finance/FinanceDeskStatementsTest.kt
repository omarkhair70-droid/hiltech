package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FinanceDeskStatementsTest {
    private fun balance(kind:String,openingDebit:String="0",openingCredit:String="0",
        debit:String="0",credit:String="0",closingDebit:String="0",closingCredit:String="0") =
        FinanceDeskAccountBalance(UUID.randomUUID(),kind,kind,kind,"EGP",openingDebit,openingCredit,debit,credit,closingDebit,closingCredit)

    @Test fun positionKeepsCumulativeIncomeSeparateFromCapital() {
        val rows=listOf(balance("BANK_ASSET",openingDebit="100",debit="30",credit="10",closingDebit="120"),
            balance("EQUITY",openingCredit="100",closingCredit="100"),
            balance("SALES_REVENUE",credit="30",closingCredit="30"),
            balance("EXPENSE",debit="10",closingDebit="10"))
        val position=financeDeskPosition("EGP",rows)
        assertTrue(position.equationBalances)
        assertEquals("120",position.assets)
        assertEquals("100",position.equity)
        assertEquals("20",position.cumulativeRecognizedResult)
        assertFalse(financeDeskPosition("EGP",rows+balance("UNKNOWN",closingDebit="1")).equationBalances)
        assertFalse(financeDeskPosition("EGP",rows+balance("UNKNOWN")).equationBalances)
        val treasury=financeDeskTreasury("EGP","BANK_ASSET",rows.take(1))
        assertTrue(treasury.movementBalances)
        assertEquals("100",treasury.carriedNet)
        assertEquals("20",treasury.netMovement)
        assertEquals("120",treasury.closingNet)
    }
    @Test fun negativeLedgerCashStaysNegativeWithoutInventedFunding() {
        val row=balance("BANK_ASSET",openingCredit="2.50",debit="1.00",credit="4.00",closingCredit="5.50")
        val treasury=financeDeskTreasury("EGP","BANK_ASSET",listOf(row))
        assertTrue(treasury.movementBalances)
        assertEquals(0,BigDecimal("-5.50").compareTo(BigDecimal(treasury.closingNet)))
    }
}
