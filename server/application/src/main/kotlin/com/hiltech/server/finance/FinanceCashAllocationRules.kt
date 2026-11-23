package com.hiltech.server.finance

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/**
 * Recording a cash allocation requires externally confirmed, authenticated
 * bank evidence. A payment instruction, approval, expense request or employee
 * advance draft is NEVER a transfer or settled balance.
 */
enum class FinanceCashSourceKind {
    SUPPLIER_PAYABLE, CUSTOMER_RECEIVABLE, EXPENSE_REIMBURSEMENT,
    EMPLOYEE_ADVANCE, FINANCIAL_IMPREST,
}
enum class FinanceBankFlow { DEBIT, CREDIT }

data class FinanceCashAllocationPlan(
    val organizationId:UUID,
    val sourceId:UUID,
    val sourceKind:FinanceCashSourceKind,
    val sourcePostAmount:BigDecimal,
    val sourceRecognized:Boolean,
    val sourceCurrency:String,
    val bankAccountId:UUID,
    val bankTransactionId:UUID,
    val externalBankTransactionId:String,
    val bankVerifiedSourceSha256:String,
    val bankEvidenceFinalized:Boolean,
    val bankTransactionAmount:BigDecimal,
    val bankFlow:FinanceBankFlow,
    val bankCurrency:String,
    val bankValueDate:LocalDate,
    val allocationAmount:BigDecimal,
    val priorSourceAllocated:BigDecimal,
    val priorBankAllocated:BigDecimal,
    val separateApprovalConfirmed:Boolean,
)
data class FinanceCashAllocationValidation(
    val allocationAmount:String,
    val remainingSourceAfter:String,
    val remainingBankAfter:String,
    val fingerprintSha256:String,
    val approvalSubjectVersion:Long,
    val bankReconciled:Boolean=false,
    val moneyTransferredBySystem:Boolean=false,
)

object FinanceCashAllocationRules {
    fun validate(p:FinanceCashAllocationPlan):FinanceCashAllocationValidation {
        require(p.sourceRecognized) {"UNPOSTED_SOURCE_CANNOT_SETTLE"}
        require(p.bankEvidenceFinalized &&
            p.bankVerifiedSourceSha256.matches(Regex("^[a-f0-9]{64}$"))) {
            "BANK_TRANSACTION_NOT_AUTHENTICATED"
        }
        require(p.externalBankTransactionId.trim().length in 5..180) {
            "CANONICAL_BANK_TRANSACTION_REFERENCE_REQUIRED"
        }
        require(p.sourceCurrency.matches(Regex("^[A-Z]{3}$")) &&
            p.bankCurrency==p.sourceCurrency) {"CURRENCY_MISMATCH"}
        require(p.separateApprovalConfirmed) {"SETTLEMENT_APPROVAL_REQUIRED"}
        val expected=when(p.sourceKind) {
            FinanceCashSourceKind.CUSTOMER_RECEIVABLE->FinanceBankFlow.CREDIT
            FinanceCashSourceKind.SUPPLIER_PAYABLE,
            FinanceCashSourceKind.EXPENSE_REIMBURSEMENT,
            FinanceCashSourceKind.EMPLOYEE_ADVANCE,
            FinanceCashSourceKind.FINANCIAL_IMPREST->FinanceBankFlow.DEBIT
        }
        require(p.bankFlow==expected) {"WRONG_CASH_DIRECTION"}
        val amounts=listOf(p.sourcePostAmount,p.bankTransactionAmount,p.allocationAmount)
        require(amounts.all { precisePositive(it) }) {"INVALID_CASH_AMOUNT"}
        require(p.priorSourceAllocated>=BigDecimal.ZERO &&
            p.priorBankAllocated>=BigDecimal.ZERO &&
            p.priorSourceAllocated.scale()<=6 &&
            p.priorBankAllocated.scale()<=6) {"INVALID_PREVIOUS_ALLOCATION"}
        val remainingSource=p.sourcePostAmount.subtract(p.priorSourceAllocated)
        val remainingBank=p.bankTransactionAmount.subtract(p.priorBankAllocated)
        require(remainingSource>=p.allocationAmount &&
            remainingBank>=p.allocationAmount) {"OVER_ALLOCATION"}
        val canonical=listOf(
            "FINANCE_CASH_ALLOCATION_V1",p.organizationId,p.sourceId,
            p.sourceKind,p.sourceCurrency,p.bankAccountId,
            p.bankTransactionId,p.externalBankTransactionId.trim(),
            p.bankVerifiedSourceSha256,p.bankValueDate,p.bankFlow,
            p.sourcePostAmount.stripTrailingZeros().toPlainString(),
            p.bankTransactionAmount.stripTrailingZeros().toPlainString(),
            p.allocationAmount.stripTrailingZeros().toPlainString(),
            p.priorSourceAllocated.stripTrailingZeros().toPlainString(),
            p.priorBankAllocated.stripTrailingZeros().toPlainString(),
        ).joinToString("|")
        val bytes=MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return FinanceCashAllocationValidation(
            allocationAmount=p.allocationAmount.toPlainString(),
            remainingSourceAfter=remainingSource.subtract(p.allocationAmount)
                .toPlainString(),
            remainingBankAfter=remainingBank.subtract(p.allocationAmount)
                .toPlainString(),
            fingerprintSha256=bytes.joinToString(""){"%02x".format(it)},
            approvalSubjectVersion=(ByteBuffer.wrap(bytes,0,8).long and Long.MAX_VALUE)
                .coerceAtLeast(1L),
        )
    }
    private fun precisePositive(x:BigDecimal):Boolean {
        val v=x.stripTrailingZeros()
        return v.signum()>0 && v.scale()<=6 &&
            v.precision()<=24 && (v.precision()-v.scale()).coerceAtLeast(0)<=18
    }
}
