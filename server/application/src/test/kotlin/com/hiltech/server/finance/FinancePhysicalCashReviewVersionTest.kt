package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FinancePhysicalCashReviewVersionTest {
 @Test fun physicalVoucherReviewerOrAmountChangeInvalidatesApprovedSnapshot() {
   val org=UUID.randomUUID()
   val box=UUID.randomUUID()
   val journal=UUID.randomUUID()
   val reviewer=UUID.randomUUID()
   fun version(
      amount:BigDecimal=BigDecimal("55.000000"),
      policy:Int=1,source:UUID=journal,
   )=FinanceCashVoucherVersion.derive(
      org,box,"AR",source,"a".repeat(64),"EGP",
      amount,"CASH-RECEIPT-000101",
      "Confirmed personal cash handover",policy,reviewer,1L)
   assertEquals(version(),version(amount=BigDecimal("55")))
   assertNotEquals(version(),version(amount=BigDecimal("60")))
   assertNotEquals(version(),version(policy=2))
   assertNotEquals(version(),version(source=UUID.randomUUID()))
 }
 @Test fun newCashboxCustodianOrManagerChangeInvalidatesOpeningReview() {
   val org=UUID.randomUUID()
   val custodian=UUID.randomUUID()
   val binding=UUID.randomUUID()
   fun version(
     person:UUID=custodian,authority:UUID=binding,
   )=FinanceCashboxOpeningVersion.derive(
     org,"HQ-CASH-01","EGP",person,
     "SIGNED-APPROVED-COMPANY-BOX",1,authority,1)
   assertEquals(version(),version())
   assertNotEquals(version(),version(person=UUID.randomUUID()))
   assertNotEquals(version(),version(authority=UUID.randomUUID()))
 }
}
