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
}
