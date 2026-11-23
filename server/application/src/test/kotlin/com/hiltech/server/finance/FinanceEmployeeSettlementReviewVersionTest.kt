package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FinanceEmployeeSettlementReviewVersionTest {
 @Test fun differentReceiptBankOrPolicyInvalidatesCurrentApproval() {
  val org=UUID.randomUUID()
  val funding=UUID.randomUUID()
  val employee=UUID.randomUUID()
  val evidence=UUID.randomUUID()
  val bank=UUID.randomUUID()
  val reviewer=UUID.randomUUID()
  fun version(
      evidenceHash:String="a".repeat(64),bankId:UUID?=bank,policy:Int=5
  )=FinanceEmployeeSettlementReviewVersion.derive(
       org,funding,employee,"IMPREST_CASH_RETURN_CONFIRMED",
       evidence,evidenceHash,bankId,BigDecimal("150.000000"),
       "EGP",policy,reviewer,1L)
  assertEquals(version(),version())
  assertNotEquals(version(),version(evidenceHash="b".repeat(64)))
  assertNotEquals(version(),version(bankId=UUID.randomUUID()))
  assertNotEquals(version(),version(policy=6))
  assertNotEquals(version(),version(bankId=null))
 }
}
