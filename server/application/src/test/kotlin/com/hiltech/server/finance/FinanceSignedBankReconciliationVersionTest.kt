package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FinanceSignedBankReconciliationVersionTest {
 @Test fun signingReviewInvalidatesOnAnyAccountingStatementOrAuthorityChange() {
   val org=UUID.randomUUID()
   val intake=UUID.randomUUID()
   val account=UUID.randomUUID()
   val reviewer=UUID.randomUUID()
   fun derive(
     statement:String="a".repeat(64),
     match:String="b".repeat(64),
     matched:BigDecimal=BigDecimal("100"),
     policy:Int=1,
     reviewerVersion:Long=1,
   )=FinanceSignedBankReconciliationVersion.derive(
     org,intake,account,statement,match,"EGP",1,
     BigDecimal("100"),BigDecimal.ZERO,matched,
     "COMPANY-SIGNED-BANK-RECON-2026",
     policy,reviewer,reviewerVersion,
   )
   val verified=derive()
   assertEquals(verified,derive(matched=BigDecimal("100.000000")))
   assertNotEquals(verified,derive(statement="c".repeat(64)))
   assertNotEquals(verified,derive(match="d".repeat(64)))
   assertNotEquals(verified,derive(matched=BigDecimal("99")))
   assertNotEquals(verified,derive(policy=2))
   assertNotEquals(verified,derive(reviewerVersion=2))
 }
}
