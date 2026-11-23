package com.hiltech.server.finance

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/**
 * Matching proposals ONLY. No transaction may be declared reconciled by
 * amount/date similarity; exact bank evidence and source ownership required.
 */
data class FinanceBankStatementLine(
    val organizationId: UUID,
    val bankAccountId: UUID,
    val statementId: UUID,
    val statementSha256: String,
    val statementApprovedForImport: Boolean,
    val statementLineId: UUID,
    val externalTransactionId: String,
    val bookedOn: LocalDate,
    val currency: String,
    val flow: FinanceBankFlow,
    val amount: BigDecimal,
    val confirmedByBank: Boolean,
)
data class FinanceBankLedgerAllocation(
    val organizationId: UUID,
    val allocationId: UUID,
    val sourceId: UUID,
    val sourceKind: FinanceCashSourceKind,
    val bankAccountId: UUID,
    val statementLineId: UUID,
    val externalTransactionId: String,
    val currency: String,
    val flow: FinanceBankFlow,
    val amount: BigDecimal,
    val accountingPosted: Boolean,
    val independentlyApproved: Boolean,
)
data class FinanceBankReconciliationSnapshot(
    val organizationId: UUID,
    val bankAccountId: UUID,
    val statementId: UUID,
    val statementStart: LocalDate,
    val statementEnd: LocalDate,
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal,
    val lines: List<FinanceBankStatementLine>,
    val allocations: List<FinanceBankLedgerAllocation>,
    val importedStatementIsComplete: Boolean,
    val bankAccountActive: Boolean,
    val independentReconcilerAuthorized: Boolean,
)
data class FinanceBankReconciliationResult(
    val matchedStatementLineCount: Int,
    val unmatchedStatementLineIds: List<UUID>,
    val unmatchedAllocationIds: List<UUID>,
    val verifiedStatementClosingBalance: String,
    val fingerprintSha256: String,
    val reviewVersion: Long,
    val reconciled: Boolean = false,
)

/** Strict reconciliation. Unmatched amounts remain exceptions, not 'paid'. */
object FinanceBankReconciliationRules {
    fun inspect(p: FinanceBankReconciliationSnapshot): FinanceBankReconciliationResult {
        require(p.bankAccountActive && p.importedStatementIsComplete &&
            p.independentReconcilerAuthorized) { "BANK_STATEMENT_AUTHORITY_REQUIRED" }
        require(!p.statementEnd.isBefore(p.statementStart)) { "INVALID_STATEMENT_DATES" }
        require(p.lines.isNotEmpty() && p.lines.size<=10000) {
            "CANONICAL_BANK_LINES_REQUIRED"
        }
        require(p.lines.map { it.statementLineId }.distinct().size==p.lines.size &&
            p.lines.map { it.externalTransactionId }.distinct().size==p.lines.size) {
            "DUPLICATE_BANK_TRANSACTION"
        }
        require(p.allocations.map { it.allocationId }.distinct().size==p.allocations.size) {
            "DUPLICATE_LEDGER_ALLOCATION"
        }
        require(monetary(p.openingBalance) && monetary(p.closingBalance)) {
            "INVALID_BANK_BALANCE_PRECISION"
        }
        for(line in p.lines) {
            require(line.organizationId==p.organizationId &&
                line.bankAccountId==p.bankAccountId &&
                line.statementId==p.statementId &&
                line.bookedOn in p.statementStart..p.statementEnd &&
                line.statementSha256.matches(Regex("^[a-f0-9]{64}$")) &&
                line.statementApprovedForImport && line.confirmedByBank &&
                line.externalTransactionId.trim().length in 5..180 &&
                line.currency.matches(Regex("^[A-Z]{3}$")) &&
                positive(line.amount)) { "UNVERIFIED_BANK_STATEMENT_LINE" }
        }
        require(p.lines.map { it.statementSha256 }.distinct().size==1) {
            "MULTIPLE_UNVERIFIED_STATEMENT_FINGERPRINTS"
        }
        val expectedClose=p.lines.fold(p.openingBalance) {balance,line->
            if(line.flow==FinanceBankFlow.CREDIT) balance.add(line.amount)
            else balance.subtract(line.amount)
        }
        require(expectedClose.compareTo(p.closingBalance)==0) {
            "BANK_OPENING_AND_CLOSING_BALANCES_DO_NOT_TIE"
        }
        val byId=p.lines.associateBy { it.statementLineId }
        for(a in p.allocations) {
            val bank=byId[a.statementLineId]
            require(a.organizationId==p.organizationId &&
                a.bankAccountId==p.bankAccountId && positive(a.amount) &&
                a.accountingPosted && a.independentlyApproved &&
                bank!=null && a.externalTransactionId==bank.externalTransactionId &&
                a.currency==bank.currency && a.flow==bank.flow) {
                "UNAPPROVED_OR_UNVERIFIED_CASH_ALLOCATION"
            }
            val expected=when(a.sourceKind) {
                FinanceCashSourceKind.CUSTOMER_RECEIVABLE->FinanceBankFlow.CREDIT
                else->FinanceBankFlow.DEBIT
            }
            require(a.flow==expected) { "WRONG_SETTLEMENT_DIRECTION" }
        }
        val grouped=p.allocations.groupBy { it.statementLineId }
        for((id,parts) in grouped) {
            val bank=byId[id] ?: error("Bank source missing")
            val total=parts.fold(BigDecimal.ZERO) {sum,a->sum.add(a.amount)}
            require(total.compareTo(bank.amount)<=0) {
                "BANK_TRANSACTION_OVERALLOCATED"
            }
        }
        val fullMatched=p.lines.filter {line->
            grouped[line.statementLineId]?.fold(BigDecimal.ZERO) {s,a->s.add(a.amount)}
                ?.compareTo(line.amount)==0
        }.map { it.statementLineId }
        val unmatched=p.lines.map { it.statementLineId }-fullMatched.toSet()
        val uncategorized=p.allocations.filter {
            it.statementLineId !in fullMatched.toSet()
        }.map { it.allocationId }
        val canonical=buildString {
            append("FINANCE_BANK_RECONCILIATION_V1|")
            append(p.organizationId).append('|').append(p.bankAccountId)
            append('|').append(p.statementId).append('|')
            append(p.statementStart).append('|').append(p.statementEnd).append('|')
            append(p.openingBalance.stripTrailingZeros().toPlainString()).append('|')
            append(p.closingBalance.stripTrailingZeros().toPlainString())
            p.lines.sortedBy { it.statementLineId.toString() }.forEach {
                append('|').append(it.statementLineId).append(':')
                append(it.externalTransactionId).append(':')
                append(it.statementSha256).append(':')
                append(it.currency).append(':').append(it.flow).append(':')
                append(it.amount.stripTrailingZeros().toPlainString())
            }
            p.allocations.sortedBy { it.allocationId.toString() }.forEach {
                append('|').append(it.allocationId).append(':')
                append(it.statementLineId).append(':').append(it.sourceId).append(':')
                append(it.sourceKind).append(':')
                append(it.amount.stripTrailingZeros().toPlainString())
            }
        }
        val bytes=MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return FinanceBankReconciliationResult(
            matchedStatementLineCount=fullMatched.size,
            unmatchedStatementLineIds=unmatched,
            unmatchedAllocationIds=uncategorized,
            verifiedStatementClosingBalance=p.closingBalance.toPlainString(),
            fingerprintSha256=bytes.joinToString(""){"%02x".format(it)},
            reviewVersion=(ByteBuffer.wrap(bytes,0,8).long and Long.MAX_VALUE)
                .coerceAtLeast(1),
            reconciled=false,
        )
    }
    private fun monetary(v:BigDecimal):Boolean {
        val n=v.stripTrailingZeros()
        return n.scale()<=6 && n.precision()<=24 &&
            (n.precision()-n.scale()).coerceAtLeast(0)<=18
    }
    private fun positive(v:BigDecimal)=v.signum()>0 && monetary(v)
}
