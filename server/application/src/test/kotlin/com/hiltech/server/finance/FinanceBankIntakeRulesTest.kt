package com.hiltech.server.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class FinanceBankIntakeRulesTest {
    private val line=FinanceBankIntakeSourceLine(
        "BANK-EG-000056",LocalDate.parse("2026-11-23"),
        FinanceBankFlow.CREDIT,"EGP",BigDecimal("250.000000"),
        "e".repeat(64))
    private val s=FinanceBankIntakeSource(
        organizationId=UUID.randomUUID(),bankAccountId=UUID.randomUUID(),
        evidenceId=UUID.randomUUID(),evidenceSha256="a".repeat(64),
        currency="EGP",
        statementStart=LocalDate.parse("2026-11-01"),
        statementEnd=LocalDate.parse("2026-11-30"),
        openingBalance=BigDecimal("1000"),closingBalance=BigDecimal("1250"),
        lines=listOf(line),
        companyBankAccountApproved=true,finalizedEvidenceReviewed=true,
    )
    @Test fun canonicalSortedFingerprintEqualsRegardlessOfInputOrder() {
        val extra=FinanceBankIntakeSourceLine(
            "BANK-EG-000057",line.bookedOn,FinanceBankFlow.DEBIT,
            "EGP",BigDecimal("20"),"f".repeat(64))
        val x=s.copy(lines=listOf(line,extra),closingBalance=BigDecimal("1230"))
        val y=x.copy(lines=listOf(extra,line))
        val hash=FinanceBankIntakeRules.validate(x)
        assertEquals(hash.sourceLinesSha256,
            FinanceBankIntakeRules.validate(y).sourceLinesSha256)
        assertNotEquals(hash.sourceLinesSha256,
            FinanceBankIntakeRules.validate(y.copy(
                lines=listOf(extra.copy(originalLineSha256="b".repeat(64)),line)
            )).sourceLinesSha256)
    }
    @Test fun denyUnreviewedFilesDuplicateTransactionsOrWrongBalance() {
        fun reject(x:FinanceBankIntakeSource) {
            assertThrows(IllegalArgumentException::class.java) {
                FinanceBankIntakeRules.validate(x)
            }
        }
        FinanceBankIntakeRules.validate(s)
        reject(s.copy(finalizedEvidenceReviewed=false))
        reject(s.copy(companyBankAccountApproved=false))
        reject(s.copy(lines=listOf(line,line)))
        reject(s.copy(closingBalance=BigDecimal("1249")))
        reject(s.copy(lines=listOf(line.copy(currency="USD"))))
        reject(s.copy(lines=listOf(line.copy(bookedOn=LocalDate.parse("2026-12-01")))))
        reject(s.copy(lines=listOf(line.copy(amount=BigDecimal("250.0000001")))))
    }
}
