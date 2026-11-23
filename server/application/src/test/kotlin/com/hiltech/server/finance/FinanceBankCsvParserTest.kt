package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

class FinanceBankCsvParserTest {
 private fun encode(s:String)=Base64.getEncoder()
    .encodeToString(s.toByteArray(Charsets.UTF_8))
 private fun sha(s:String)=MessageDigest.getInstance("SHA-256")
    .digest(s.toByteArray(Charsets.UTF_8))
    .joinToString(""){"%02x".format(it)}
 private val header="external_transaction_id,booked_on,flow,currency,amount"
 private val bankId="EG-TXN-0000001"
 @Test fun uploadedCsvBytesMustExactlyMatchProtectedEvidence() {
   val row="$bankId,2026-11-22,CREDIT,EGP,250.000000"
   val doc="$header\r\n$row\r\n"
   val parsed=FinanceBankCsvParser.decodeAndValidate(encode(doc),sha(doc))
   assertEquals(1,parsed.lines.size)
   assertEquals(bankId,parsed.lines.first().externalTransactionId)
   assertEquals("EGP",parsed.lines.first().currency)
   assertEquals("250.000000",parsed.lines.first().amount)
   assertEquals(sha(row),parsed.lines.first().originalLineSha256)
   assertEquals(sha(doc),parsed.sourceSha256)
   val changed="$header\r\n$bankId,2026-11-22,CREDIT,EGP,251.000000\r\n"
   assertNotEquals(sha(doc),sha(changed))
   assertThrows(IllegalArgumentException::class.java){
     FinanceBankCsvParser.decodeAndValidate(encode(changed),sha(doc))
   }
 }
 @Test fun rejectDuplicateBankTransactionCurrencyFraudAndBadRow() {
   val row="$bankId,2026-11-22,DEBIT,EGP,25"
   val duplicates="$header\n$row\n$row\n"
   assertThrows(IllegalArgumentException::class.java){
     FinanceBankCsvParser.decodeAndValidate(
       encode(duplicates),sha(duplicates))
   }
   for(rowValue in listOf(
      "=HYPERLINK,2026-11-22,CREDIT,EGP,20",
      "$bankId,2026-11-22,MADEUP,EGP,20",
      "$bankId,2026-11-22,CREDIT,EGP,0",
      "$bankId,2026-11-22,CREDIT,EGP,20.0000001",
      "$bankId,2026-11-22,CREDIT,EGP,-20",
      "$bankId,2026-11-22,CREDIT,egp,20",
      "$bankId,2026-02-30,CREDIT,EGP,20",
      "$bankId,2026-11-22,CREDIT,EGP,20,INJECTED",
   )){
      val broken="$header\n$rowValue\n"
      assertThrows(IllegalArgumentException::class.java){
         FinanceBankCsvParser.decodeAndValidate(encode(broken),sha(broken))
      }
   }
 }
 @Test fun rejectMalformedUtf8OrForgedFileHash() {
   val corrupted=byteArrayOf(0xC3.toByte(),0x28.toByte())
   val hash=MessageDigest.getInstance("SHA-256").digest(corrupted)
     .joinToString(""){"%02x".format(it)}
   assertThrows(IllegalArgumentException::class.java){
      FinanceBankCsvParser.decodeAndValidate(
        Base64.getEncoder().encodeToString(corrupted),hash)
   }
   assertThrows(IllegalArgumentException::class.java){
      FinanceBankCsvParser.decodeAndValidate(
        "@bad-base64","a".repeat(64))
   }
 }
}
