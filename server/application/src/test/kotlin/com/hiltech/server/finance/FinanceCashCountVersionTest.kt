package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class FinanceCashCountVersionTest {
 @Test fun reviewerPolicyAndSourceSnapshotChangesInvalidateSignature() {
   val org=UUID.randomUUID()
   val box=UUID.randomUUID()
   val reviewer=UUID.randomUUID()
   val day=LocalDate.of(2026,11,23)
   fun version(
      counted:BigDecimal=BigDecimal("100.000000"),
      ledger:BigDecimal=BigDecimal("98.000000"),
      vouchers:Long=3,policy:Int=1,authority:UUID=reviewer,
      date:LocalDate=day,
   )=FinanceCashCountVersion.derive(
      org,box,"EGP",date,counted,ledger,vouchers,
      "COUNT-DAILY-2026-11-23",policy,authority,1L)
   assertEquals(version(),version(counted=BigDecimal("100"),ledger=BigDecimal("98")))
   assertNotEquals(version(),version(counted=BigDecimal("99")))
   assertNotEquals(version(),version(ledger=BigDecimal("100")))
   assertNotEquals(version(),version(vouchers=4))
   assertNotEquals(version(),version(policy=2))
   assertNotEquals(version(),version(authority=UUID.randomUUID()))
   assertNotEquals(version(),version(date=day.plusDays(1)))
 }
}
