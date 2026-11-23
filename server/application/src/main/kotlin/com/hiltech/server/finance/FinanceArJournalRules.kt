package com.hiltech.server.finance

import java.math.BigDecimal
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Finance AR recognition requires an internally recorded, independently
 * approved invoice and an entirely separate signed company GL allocation.
 * An award, candidate or issued invoice ALONE is never a ledger posting.
 */
enum class FinanceArAccountKind {
    CUSTOMER_RECEIVABLE,
    SALES_REVENUE,
    TAX_PAYABLE,
}
data class FinanceArJournalAllocation(
    val accountId:UUID,
    val accountKind:FinanceArAccountKind,
    val side:FinanceJournalSide,
    val amount:BigDecimal,
)
data class FinanceArJournalPlan(
    val organizationId:UUID,
    val issuedInvoiceId:UUID,
    val issuedInvoiceFingerprint:String,
    val fiscalPeriodId:UUID,
    val grossAmount:BigDecimal,
    val currency:String,
    val accountingTreatmentReference:String,
    val taxDispositionReference:String,
    val taxEvidenceId:UUID?,
    val lines:List<FinanceArJournalAllocation>,
)
data class FinanceArJournalValidation(
    val fingerprintSha256:String,
    val approvalSubjectVersion:Long,
    val grossAmount:String,
    val balanced:Boolean=true,
)
object FinanceArJournalRules {
    fun validate(p:FinanceArJournalPlan):FinanceArJournalValidation {
        require(p.issuedInvoiceFingerprint.matches(Regex("^[a-f0-9]{64}$"))) {
            "ISSUED_INVOICE_SOURCE_HASH_REQUIRED"
        }
        require(p.currency.matches(Regex("^[A-Z]{3}$"))) {"INVALID_CURRENCY"}
        require(precise(p.grossAmount)) {"INVALID_GROSS"}
        require(p.accountingTreatmentReference.trim().length in 8..160) {
            "COMPANY_GL_POLICY_REQUIRED"
        }
        require(p.taxDispositionReference.trim().length in 8..160) {
            "COMPANY_TAX_TREATMENT_REQUIRED"
        }
        require(p.lines.size in 2..32) {"INVALID_ALLOCATION_COUNT"}
        require(p.lines.map { it.accountId }.distinct().size==p.lines.size) {
            "DUPLICATE_ACCOUNT_ALLOCATION"
        }
        require(p.lines.all { precise(it.amount) }) {"INVALID_LINE_AMOUNT"}
        val ar=p.lines.filter {
            it.accountKind==FinanceArAccountKind.CUSTOMER_RECEIVABLE &&
                it.side==FinanceJournalSide.DEBIT
        }
        require(ar.size==1 && ar.single().amount.compareTo(p.grossAmount)==0) {
            "SINGLE_FULL_GROSS_AR_DEBIT_REQUIRED"
        }
        require(p.lines.all {
            (it.accountKind==FinanceArAccountKind.CUSTOMER_RECEIVABLE &&
                it.side==FinanceJournalSide.DEBIT) ||
            (it.accountKind!=FinanceArAccountKind.CUSTOMER_RECEIVABLE &&
                it.side==FinanceJournalSide.CREDIT)
        }) {"NO_UNREVIEWED_AR_OFFSET"}
        val taxLines=p.lines.filter {
            it.accountKind==FinanceArAccountKind.TAX_PAYABLE
        }
        require((p.taxEvidenceId==null && taxLines.isEmpty()) ||
            (p.taxEvidenceId!=null && taxLines.isNotEmpty())) {
            "TAX_ALLOCATION_EVIDENCE_MISMATCH"
        }
        val dr=p.lines.filter { it.side==FinanceJournalSide.DEBIT }
            .fold(BigDecimal.ZERO){a,b->a.add(b.amount)}
        val cr=p.lines.filter { it.side==FinanceJournalSide.CREDIT }
            .fold(BigDecimal.ZERO){a,b->a.add(b.amount)}
        require(dr.compareTo(cr)==0 && dr.compareTo(p.grossAmount)==0) {
            "UNBALANCED_AR_POSTING"
        }
        val canonical=listOf(
            "FINANCE_AR_POSTING_V1",p.organizationId,p.issuedInvoiceId,
            p.issuedInvoiceFingerprint,p.fiscalPeriodId,
            p.grossAmount.stripTrailingZeros().toPlainString(),p.currency,
            p.accountingTreatmentReference.trim(),p.taxDispositionReference.trim(),
            p.taxEvidenceId ?: "-",
            p.lines.mapIndexed { n,l ->
                listOf(n+1,l.accountId,l.accountKind,l.side,
                    l.amount.stripTrailingZeros().toPlainString()).joinToString(":")
            }.joinToString(";"),
        ).joinToString("|")
        val hash=MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return FinanceArJournalValidation(
            hash.joinToString("") { "%02x".format(it) },
            (ByteBuffer.wrap(hash,0,8).long and Long.MAX_VALUE).coerceAtLeast(1L),
            p.grossAmount.toPlainString(),
        )
    }
    private fun precise(x:BigDecimal):Boolean {
        val n=x.stripTrailingZeros()
        return n.signum()>0 && n.scale()<=6 &&
            n.precision()<=24 &&
            (n.precision()-n.scale()).coerceAtLeast(0)<=18
    }
}
