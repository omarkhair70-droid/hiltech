package com.hiltech.server.finance

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Employee Advance (loan), Company Imprest (custody) and Expense
 * Reimbursement are different legal/accounting source obligations.
 * Drafts or approvals alone NEVER prove that cash was disbursed.
 */
enum class FinanceEmployeeSettlementKind {
    EXPENSE_REIMBURSEMENT,
    EMPLOYEE_ADVANCE,
    FINANCIAL_IMPREST,
    PAYROLL_OVERPAYMENT,
}
enum class FinanceEmployeeSettlementAction {
    REIMBURSEMENT_CONFIRMED,
    ADVANCE_RECOVERY_CONFIRMED,
    IMPREST_RECEIPTS_ACCEPTED,
    IMPREST_CASH_RETURN_CONFIRMED,
    PAYROLL_RECOVERY_CONFIRMED,
}
data class FinanceEmployeeSettlementPlan(
    val organizationId: UUID,
    val employeeId: UUID,
    val financeDraftId: UUID,
    val kind: FinanceEmployeeSettlementKind,
    val action: FinanceEmployeeSettlementAction,
    val sourceAmount: BigDecimal,
    val currency: String,
    val sourceApproved: Boolean,
    val approvedFundingRecorded: Boolean,
    val sourceBankTransactionId: UUID?,
    val sourceBankEvidenceSha256: String?,
    val settlementOperationId: UUID,
    val settlementEvidenceId: UUID,
    val evidenceFinalized: Boolean,
    val settlementAmount: BigDecimal,
    val previouslySettled: BigDecimal,
    val independentReviewerApproved: Boolean,
    val recoveryMethod: String?,
    val settlementBankTransactionId: UUID? = null,
    val settlementBankEvidenceSha256: String? = null,
    val approvedPayrollDeductionEventId: UUID? = null,
    val openingSourceFingerprint:String?=null,
    val payrollRecoveryFingerprint:String?=null,
)
data class FinanceEmployeeSettlementValidation(
    val kind: FinanceEmployeeSettlementKind,
    val action: FinanceEmployeeSettlementAction,
    val settlementAmount: String,
    val unsettledAmount: String,
    val fingerprintSha256: String,
    val approvalSubjectVersion: Long,
    val posted: Boolean = false,
    val fundsTransferredBySystem: Boolean = false,
)

object FinanceEmployeeSettlementRules {
    fun validate(p: FinanceEmployeeSettlementPlan): FinanceEmployeeSettlementValidation {
        require(p.currency.matches(Regex("^[A-Z]{3}$"))) { "INVALID_CURRENCY" }
        require(p.sourceApproved && p.independentReviewerApproved &&
            p.evidenceFinalized) { "MISSING_COMPANY_SETTLEMENT_APPROVAL_OR_EVIDENCE" }
        require(positive(p.sourceAmount) && positive(p.settlementAmount) &&
            p.previouslySettled>=BigDecimal.ZERO &&
            validPrecision(p.previouslySettled)) {
            "INVALID_SETTLEMENT_AMOUNT"
        }
        require(p.settlementAmount.add(p.previouslySettled)<=p.sourceAmount) {
            "EMPLOYEE_SETTLEMENT_OVERALLOCATED"
        }
        require(p.kind==FinanceEmployeeSettlementKind.PAYROLL_OVERPAYMENT || p.payrollRecoveryFingerprint==null) {
            "PAYROLL_RECOVERY_IS_NOT_ADVANCE_OR_CUSTODY"
        }
        when(p.kind) {
            FinanceEmployeeSettlementKind.PAYROLL_OVERPAYMENT -> {
                require(p.action==FinanceEmployeeSettlementAction.PAYROLL_RECOVERY_CONFIRMED &&
                    !p.approvedFundingRecorded && p.sourceBankTransactionId==null &&
                    p.sourceBankEvidenceSha256==null && p.openingSourceFingerprint==null &&
                    p.payrollRecoveryFingerprint?.matches(Regex("^[a-f0-9]{64}$"))==true &&
                    p.recoveryMethod=="CONFIRMED_BANK_CREDIT" && p.approvedPayrollDeductionEventId==null) {
                    "POSTED_PAYROLL_RECEIVABLE_AND_SEPARATE_BANK_CREDIT_REQUIRED"
                }
                verifiedSettlementBank(p)
            }
            FinanceEmployeeSettlementKind.EXPENSE_REIMBURSEMENT -> {
                require(p.action==FinanceEmployeeSettlementAction.REIMBURSEMENT_CONFIRMED) {
                    "EXPENSE_REIMBURSEMENT_IS_NOT_A_LOAN_OR_CUSTODY"
                }
                // Employee paid the merchant with personal money.
                // No prior company funding is needed or allowed here.
                require(!p.approvedFundingRecorded && p.openingSourceFingerprint==null &&
                    p.sourceBankTransactionId==null &&
                    p.sourceBankEvidenceSha256==null) {
                    "PERSONAL_EXPENSE_MUST_NOT_INVENT_EARLIER_COMPANY_FUNDING"
                }
                require(p.recoveryMethod==null &&
                    p.approvedPayrollDeductionEventId==null) {
                    "EXPENSE_IS_NOT_PAYROLL_LOAN_RECOVERY"
                }
                verifiedSettlementBank(p)
            }
            FinanceEmployeeSettlementKind.EMPLOYEE_ADVANCE -> {
                require(p.action==FinanceEmployeeSettlementAction.ADVANCE_RECOVERY_CONFIRMED) {
                    "ADVANCE_REQUIRES_CONFIRMED_RECOVERY"
                }
                confirmedFunding(p)
                require(p.recoveryMethod in setOf(
                    "CONFIRMED_BANK_CREDIT","SEPARATELY_APPROVED_PAYROLL_DEDUCTION"
                )) { "NO_AUTOMATIC_PAYROLL_DEDUCTION" }
                if(p.recoveryMethod=="CONFIRMED_BANK_CREDIT") {
                    verifiedSettlementBank(p)
                    require(p.approvedPayrollDeductionEventId==null) {
                        "BANK_RECOVERY_CANNOT_DOUBLE_COUNT_PAYROLL"
                    }
                } else {
                    require(p.approvedPayrollDeductionEventId!=null &&
                        p.settlementBankTransactionId==null &&
                        p.settlementBankEvidenceSha256==null) {
                        "SIGNED_PAYROLL_DEDUCTION_SOURCE_REQUIRED"
                    }
                }
            }
            FinanceEmployeeSettlementKind.FINANCIAL_IMPREST -> {
                require(p.action in setOf(
                    FinanceEmployeeSettlementAction.IMPREST_RECEIPTS_ACCEPTED,
                    FinanceEmployeeSettlementAction.IMPREST_CASH_RETURN_CONFIRMED,
                )) { "IMPREST_IS_CUSTODY_NOT_EMPLOYEE_BORROWING" }
                confirmedFunding(p)
                if(p.action==FinanceEmployeeSettlementAction.IMPREST_CASH_RETURN_CONFIRMED)
                    require(p.recoveryMethod=="CONFIRMED_BANK_CREDIT") {
                        "IMPREST_CASH_RETURN_REQUIRES_BANK_RECEIPT"
                    }
                if(p.action==FinanceEmployeeSettlementAction.IMPREST_CASH_RETURN_CONFIRMED)
                    verifiedSettlementBank(p)
                else require(p.recoveryMethod==null &&
                    p.settlementBankTransactionId==null &&
                    p.settlementBankEvidenceSha256==null) {
                    "IMPREST_RECEIPTS_DO_NOT_IMPLY_CASH_MOVEMENT"
                }
                require(p.approvedPayrollDeductionEventId==null) {
                    "IMPREST_IS_NOT_PERSONAL_PAYROLL_DEBT"
                }
            }
        }
        val canonical=(listOf(
            "FINANCE_EMPLOYEE_SETTLEMENT_V1",p.organizationId,p.employeeId,
            p.financeDraftId,p.kind,p.action,p.currency,p.settlementOperationId,
            p.settlementEvidenceId,p.sourceBankTransactionId,p.sourceBankEvidenceSha256,
            p.recoveryMethod ?: "-",
            p.settlementBankTransactionId ?: "-",
            p.settlementBankEvidenceSha256 ?: "-",
            p.approvedPayrollDeductionEventId ?: "-",
            p.sourceAmount.stripTrailingZeros().toPlainString(),
            p.settlementAmount.stripTrailingZeros().toPlainString(),
            p.previouslySettled.stripTrailingZeros().toPlainString(),
        )+listOfNotNull(p.openingSourceFingerprint,p.payrollRecoveryFingerprint)).joinToString("|")
        val sha=MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return FinanceEmployeeSettlementValidation(
            kind=p.kind,action=p.action,
            settlementAmount=p.settlementAmount.toPlainString(),
            unsettledAmount=p.sourceAmount.subtract(p.previouslySettled)
                .subtract(p.settlementAmount).toPlainString(),
            fingerprintSha256=sha.joinToString(""){"%02x".format(it)},
            approvalSubjectVersion=(ByteBuffer.wrap(sha,0,8).long and Long.MAX_VALUE)
                .coerceAtLeast(1L),
        )
    }
    private fun verifiedSettlementBank(p:FinanceEmployeeSettlementPlan) {
        require(p.settlementBankTransactionId!=null &&
            p.settlementBankEvidenceSha256?.matches(Regex("^[a-f0-9]{64}$"))==true) {
            "SETTLEMENT_NEEDS_SEPARATE_CONFIRMED_BANK_MOVEMENT"
        }
    }
    private fun confirmedFunding(p:FinanceEmployeeSettlementPlan) {
        val actual=p.approvedFundingRecorded && p.sourceBankTransactionId!=null &&
            p.sourceBankEvidenceSha256?.matches(Regex("^[a-f0-9]{64}$"))==true && p.openingSourceFingerprint==null
        val opening=!p.approvedFundingRecorded && p.sourceBankTransactionId==null && p.sourceBankEvidenceSha256==null &&
            p.openingSourceFingerprint?.matches(Regex("^[a-f0-9]{64}$"))==true
        require(actual||opening) {
            "NO_SETTLEMENT_OF_UNFUNDED_OR_UNVERIFIED_EMPLOYEE_DRAFT"
        }
    }
    private fun validPrecision(v:BigDecimal):Boolean {
        val n=v.stripTrailingZeros()
        return n.scale()<=6 && n.precision()<=24 &&
            (n.precision()-n.scale()).coerceAtLeast(0)<=18
    }
    private fun positive(v:BigDecimal):Boolean=v.signum()>0 && validPrecision(v)
}
