package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FinanceExpenseReimbursementVersionTest {
 @Test fun changingReceiptApprovalBankMovementOrReviewerInvalidatesApproval() {
  val org=UUID.randomUUID()
  val expense=UUID.randomUUID()
  val employee=UUID.randomUUID()
  val doc=UUID.randomUUID()
  val bank=UUID.randomUUID()
  val reviewer=UUID.randomUUID()
  fun version(
    document:UUID=doc,bankLine:UUID=bank,policyVersion:Int=1
  )=FinanceExpenseReimbursementVersion.derive(
    org,expense,employee,document,55L,bankLine,
    "a".repeat(64),BigDecimal("130.000000"),"EGP",
    policyVersion,reviewer,2L)
  assertEquals(version(),version())
  assertNotEquals(version(),version(document=UUID.randomUUID()))
  assertNotEquals(version(),version(bankLine=UUID.randomUUID()))
  assertNotEquals(version(),version(policyVersion=2))
 }
}
