package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class FinanceBankStatementReviewVersionTest {
 @Test fun everyDocumentLinePolicyOrReviewerChangeInvalidatesApproval() {
  val org=UUID.randomUUID()
  val account=UUID.randomUUID()
  val evidence=UUID.randomUUID()
  val reviewer=UUID.randomUUID()
  fun version(
      sha:String="a".repeat(64),lineHash:String="b".repeat(64),
      count:Int=2,policy:Int=4
  )=FinanceBankStatementReviewVersion.derive(
       org,account,evidence,sha,lineHash,
       LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-30"),
       BigDecimal("100.000000"),BigDecimal("150.000000"),
       count,policy,reviewer,1L,
  )
  val approved=version()
  assertEquals(approved,version())
  assertNotEquals(approved,version(sha="c".repeat(64)))
  assertNotEquals(approved,version(lineHash="d".repeat(64)))
  assertNotEquals(approved,version(count=3))
  assertNotEquals(approved,version(policy=5))
 }
}
