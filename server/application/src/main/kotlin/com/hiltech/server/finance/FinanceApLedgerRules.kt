package com.hiltech.server.finance

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** No account codes, VAT rates, legal due dates or exchange rates are invented. */
enum class FinanceApAccountKind {
    SUPPLIER_PAYABLE,
    EXPENSE,
    INVENTORY_ASSET,
    FIXED_ASSET,
    TAX_RECOVERABLE,
    OTHER_ASSET,
}

enum class FinanceJournalSide { DEBIT, CREDIT }

/**
 * Exactly one approved AP control credit; all remaining debit allocations
 * MUST be explicitly entered and independently reviewed by company Finance.
 * No inferred VAT split, foreign exchange or undocumented rounding.
 */
data class FinanceApLedgerLine(
    val accountId: UUID,
    val accountKind: FinanceApAccountKind,
    val side: FinanceJournalSide,
    val amount: BigDecimal,
)

data class FinanceApLedgerPlan(
    val organizationId: UUID,
    val candidateId: UUID,
    val sourceSha256: String,
    val sourceVersion: Long,
    val fiscalPeriodId: UUID,
    val grossAmount: BigDecimal,
    val currency: String,
    val accountingTreatmentReference: String,
    val accountingTaxDisposition: String,
    val taxEvidenceId: UUID?,
    val lines: List<FinanceApLedgerLine>,
)

data class FinanceApLedgerValidation(
    val grossAmount: String,
    val debitTotal: String,
    val creditTotal: String,
    val lineCount: Int,
    val fingerprintSha256: String,
    val approvalSubjectVersion: Long,
)

object FinanceApLedgerRules {
    private val allowedTaxDispositions = setOf(
        "TAX_REVIEWED_NO_RECOVERABLE_TAX",
        "TAX_REVIEWED_RECOVERABLE_WITH_EVIDENCE",
    )

    fun validate(plan: FinanceApLedgerPlan): FinanceApLedgerValidation {
        require(Regex("^[a-f0-9]{64}$").matches(plan.sourceSha256)) {
            "Canonical source fingerprint required."
        }
        require(plan.sourceVersion > 0L)
        require(Regex("^[A-Z]{3}$").matches(plan.currency))
        require(precisePositive(plan.grossAmount))
        require(plan.accountingTreatmentReference.isNotBlank() &&
            plan.accountingTreatmentReference.length in 8..160) {
            "Company Finance accounting treatment reference required."
        }
        require(plan.accountingTaxDisposition in allowedTaxDispositions) {
            "Explicit reviewed tax disposition required; cannot infer VAT."
        }
        val taxDebit = plan.lines.any {
            it.accountKind == FinanceApAccountKind.TAX_RECOVERABLE
        }
        require(taxDebit ==
            (plan.accountingTaxDisposition == "TAX_REVIEWED_RECOVERABLE_WITH_EVIDENCE")) {
            "Tax debit must match verified company tax disposition."
        }
        if (taxDebit) require(plan.taxEvidenceId != null) {
            "Recoverable tax requires specific accounting evidence reference."
        } else require(plan.taxEvidenceId == null) {
            "No unsupported tax evidence or inferred recovery."
        }
        require(plan.lines.size in 2..32) { "2 to 32 exact ledger allocations required." }
        require(plan.lines.map { it.accountId }.distinct().size == plan.lines.size) {
            "Duplicate account allocation can hide unreviewed offsets."
        }
        require(plan.lines.all { precisePositive(it.amount) })
        val payable = plan.lines.filter {
            it.accountKind == FinanceApAccountKind.SUPPLIER_PAYABLE &&
                it.side == FinanceJournalSide.CREDIT
        }
        require(payable.size == 1 && payable.single().amount.compareTo(
            plan.grossAmount,
        ) == 0) { "Exactly one AP control credit must equal gross source amount." }
        require(plan.lines.all {
            it.side == FinanceJournalSide.DEBIT ||
                (it.side == FinanceJournalSide.CREDIT &&
                    it.accountKind == FinanceApAccountKind.SUPPLIER_PAYABLE)
        }) { "Supplier payable recognition cannot post other credits." }
        require(plan.lines.none {
            it.side == FinanceJournalSide.DEBIT &&
                it.accountKind == FinanceApAccountKind.SUPPLIER_PAYABLE
        }) { "AP control account must be credited for a payable." }
        val debit = plan.lines.filter { it.side == FinanceJournalSide.DEBIT }
            .fold(BigDecimal.ZERO) { a, line -> a.add(line.amount) }
        val credit = plan.lines.filter { it.side == FinanceJournalSide.CREDIT }
            .fold(BigDecimal.ZERO) { a, line -> a.add(line.amount) }
        require(debit.compareTo(credit) == 0 &&
            credit.compareTo(plan.grossAmount) == 0) {
            "AP recognition must balance exactly in source currency."
        }
        val canonical = buildString {
            append("FINANCE_AP_JOURNAL_V1").append('|')
            append(plan.organizationId).append('|')
            append(plan.candidateId).append('|')
            append(plan.sourceSha256).append('|')
            append(plan.sourceVersion).append('|')
            append(plan.fiscalPeriodId).append('|')
            append(plan.grossAmount.stripTrailingZeros().toPlainString()).append('|')
            append(plan.currency).append('|')
            append(plan.accountingTreatmentReference.trim()).append('|')
            append(plan.accountingTaxDisposition).append('|')
            append(plan.taxEvidenceId ?: "-")
            plan.lines.forEachIndexed { index, line ->
                append('|').append(index + 1).append(':')
                append(line.accountId).append(':').append(line.accountKind)
                    .append(':').append(line.side).append(':')
                    .append(line.amount.stripTrailingZeros().toPlainString())
            }
        }
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return FinanceApLedgerValidation(
            grossAmount = plan.grossAmount.toPlainString(),
            debitTotal = debit.toPlainString(),
            creditTotal = credit.toPlainString(),
            lineCount = plan.lines.size,
            fingerprintSha256 = sha,
            approvalSubjectVersion =
                (ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toByteArray(Charsets.UTF_8)), 0, 8).long
                    and Long.MAX_VALUE).coerceAtLeast(1L),
        )
    }

    private fun precisePositive(value: BigDecimal): Boolean {
        val exact = value.stripTrailingZeros()
        // PostgreSQL numeric(24,6) holds at most 18 integer digits and
        // six fractional digits; total precision<=24 is insufficient.
        val integerDigits = (exact.precision() - exact.scale()).coerceAtLeast(0)
        return exact.signum() > 0 && exact.scale() <= 6 &&
            integerDigits <= 18 && exact.precision() <= 24
    }
}
