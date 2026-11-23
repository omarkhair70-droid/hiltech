package com.hiltech.server.finance

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** Canonical line fingerprint used by the PostgreSQL deferred intake guard. */
data class FinanceBankIntakeSourceLine(
    val externalTransactionId:String,
    val bookedOn:LocalDate,
    val flow:FinanceBankFlow,
    val currency:String,
    val amount:BigDecimal,
    val originalLineSha256:String,
)
data class FinanceBankIntakeSource(
    val organizationId:UUID,
    val bankAccountId:UUID,
    val evidenceId:UUID,
    val evidenceSha256:String,
    val currency:String,
    val statementStart:LocalDate,
    val statementEnd:LocalDate,
    val openingBalance:BigDecimal,
    val closingBalance:BigDecimal,
    val lines:List<FinanceBankIntakeSourceLine>,
    val companyBankAccountApproved:Boolean,
    val finalizedEvidenceReviewed:Boolean,
)
data class FinanceBankIntakeFingerprint(
    val sourceLinesSha256:String,val lineCount:Int,val closingBalance:String,
)
/** No network access and no bank-provider authentication is implied. */
object FinanceBankIntakeRules {
    fun validate(s:FinanceBankIntakeSource):FinanceBankIntakeFingerprint {
        require(s.companyBankAccountApproved && s.finalizedEvidenceReviewed) {
            "BANK_SOURCE_EVIDENCE_NOT_APPROVED"
        }
        require(s.evidenceSha256.matches(Regex("^[a-f0-9]{64}$"))) {
            "FINAL_BANK_EVIDENCE_FINGERPRINT_REQUIRED"
        }
        require(s.currency.matches(Regex("^[A-Z]{3}$"))) {"BANK_CURRENCY_INVALID"}
        require(!s.statementStart.isAfter(s.statementEnd) &&
            s.lines.size in 1..10000) {"BANK_STATEMENT_RANGE_INVALID"}
        require(s.lines.map { it.externalTransactionId }.distinct().size==s.lines.size) {
            "DUPLICATE_BANK_EXTERNAL_TRANSACTION_ID"
        }
        require(precise(s.openingBalance) && precise(s.closingBalance)) {
            "BANK_BALANCE_INVALID"
        }
        val movement=s.lines.fold(BigDecimal.ZERO) {total,line->
            require(line.bookedOn in s.statementStart..s.statementEnd &&
                line.currency==s.currency &&
                line.externalTransactionId.trim().length in 5..180 &&
                line.originalLineSha256.matches(Regex("^[a-f0-9]{64}$")) &&
                line.amount.signum()>0 && precise(line.amount)) {
                "BANK_STATEMENT_LINE_INVALID"
            }
            if(line.flow==FinanceBankFlow.CREDIT) total.add(line.amount)
            else total.subtract(line.amount)
        }
        require(s.openingBalance.add(movement).compareTo(s.closingBalance)==0) {
            "BANK_STATEMENT_OPENING_CLOSING_MISMATCH"
        }
        val text=s.lines.sortedBy { it.externalTransactionId }.joinToString("|") {
            listOf(it.externalTransactionId,it.bookedOn,it.flow.name,
                it.currency,it.amount.setScale(6).toPlainString(),
                it.originalLineSha256).joinToString(":")
        }
        val digest=MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(""){"%02x".format(it)}
        return FinanceBankIntakeFingerprint(
            sourceLinesSha256=digest,
            lineCount=s.lines.size,closingBalance=s.closingBalance.toPlainString(),
        )
    }
    private fun precise(v:BigDecimal):Boolean {
        val n=v.stripTrailingZeros()
        return n.scale()<=6 && n.precision()<=24 &&
            (n.precision()-n.scale()).coerceAtLeast(0)<=18
    }
}
