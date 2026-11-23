package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FinanceCashVoidReviewVersionTest {
 @Test fun originalMatchReviewerOrAmountChangeInvalidatesApproval() {
  val org=UUID.randomUUID()
  val original=UUID.randomUUID()
  val journal=UUID.randomUUID()
  val bank=UUID.randomUUID()
  val reviewer=UUID.randomUUID()
  fun version(
      record:UUID=original,bankLine:UUID=bank,
      amount:BigDecimal=BigDecimal("30.000000"),policy:Int=7,
  )=FinanceCashVoidReviewVersion.derive(
       org,record,"AR",journal,bankLine,
       "a".repeat(64),"b".repeat(64),amount,"EGP",
       "Reviewed mistaken match",policy,reviewer,2L)
  assertEquals(version(),version(amount=BigDecimal("30")))
  assertNotEquals(version(),version(record=UUID.randomUUID()))
  assertNotEquals(version(),version(bankLine=UUID.randomUUID()))
  assertNotEquals(version(),version(amount=BigDecimal("29")))
  assertNotEquals(version(),version(policy=8))
 }
}
