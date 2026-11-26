package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FinanceEmployeeFundingOriginTest {
 @Test fun canonicalFundedSourceChangesWithEmployeeAndOriginalApproval() {
  val org=UUID.randomUUID()
  val draft=UUID.randomUUID()
  val emp=UUID.randomUUID()
  val approval=UUID.randomUUID()
  fun hash(approve:UUID=approval,amount:BigDecimal=BigDecimal("300.000000"))=
    FinanceEmployeeFundingOrigin.derive(
      org,"FINANCIAL_IMPREST",draft,emp,amount,"EGP",approve,157L)
  assertEquals(hash(),hash(amount=BigDecimal("300")))
  assertNotEquals(hash(),hash(approve=UUID.randomUUID()))
  assertNotEquals(hash(),hash(amount=BigDecimal("301")))
 }
 @Test fun fundingReviewerPolicyAndBankVersionMustNotDrift() {
  val org=UUID.randomUUID()
  val draft=UUID.randomUUID()
  val emp=UUID.randomUUID()
  val bank=UUID.randomUUID()
  val reviewer=UUID.randomUUID()
  fun version(bankLine:UUID=bank,policy:Int=4)=
    FinanceEmployeeFundingOrigin.reviewVersion(
      org,"FINANCIAL_IMPREST",draft,emp,BigDecimal("300"),"EGP",
      "a".repeat(64),bankLine,"b".repeat(64),policy,reviewer,1L)
  assertEquals(version(),version())
  assertNotEquals(version(),version(bankLine=UUID.randomUUID()))
  assertNotEquals(version(),version(policy=5))
 }
}
