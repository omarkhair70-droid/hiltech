package com.hiltech.server.finance

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

const val FINANCE_PAYROLL_RESULT_SUBJECT = "FINANCE_PAYROLL_RESULT"
const val FINANCE_PAYROLL_RESULT_POLICY = "FINANCE_PAYROLL_RESULT_REVIEW"
const val FINANCE_PAYROLL_RESULT_REVIEWER = "FINANCE_PAYROLL_RESULT_REVIEWER"

data class FinancePayrollCapability(val organizationId: UUID, val canPrepare: Boolean, val canReview: Boolean, val reviewConfigured: Boolean)
data class FinancePayrollOperation(val operationId: UUID)
data class FinancePayrollPrepareResult(
    val operationId: UUID, val baseResultVersion: Long, val action: String,
    val bankStatementLineId: UUID? = null, val correctsEventId: UUID? = null,
    val beneficiaryConfirmed: Boolean = false, val reason: String,
)
data class FinancePayrollReviewResult(val operationId: UUID, val decision: String, val comment: String? = null)
data class FinancePayrollCommandReceipt(
    val operationId: UUID, val organizationId: UUID, val targetId: UUID, val state: String,
    val replayed: Boolean, val bankExecutionInitiated: Boolean = false, val ledgerPosted: Boolean = false,
)
data class FinancePayrollBatchView(
    val reviewBatchId: UUID, val runVersionId: UUID, val periodStart: LocalDate, val periodEnd: LocalDate,
    val generation: Int, val currency: String, val payableTotal: String, val employeeCount: Int,
    val transferCount: Int, val prepared: Boolean, val sourceFingerprint: String,
)
data class FinancePayrollInstructionView(
    val instructionId: UUID, val employeeId: UUID, val employeeName: String, val employeeCode: String,
    val amount: String, val currency: String, val resultVersion: Long, val outcome: String,
    val resultEventId: UUID?, val resultAction: String?, val recordedAt: Instant?,
)
data class FinancePayrollBankLineView(
    val bankStatementLineId: UUID, val reference: String, val bookedOn: LocalDate,
    val bankName: String, val maskedAccount: String, val amount: String, val currency: String,
)
data class FinancePayrollProposalView(
    val proposalId: UUID, val instructionId: UUID, val employeeName: String, val amount: String, val currency: String,
    val action: String, val reason: String, val state: String, val preparedByName: String,
    val preparedAt: Instant, val bankReference: String?, val bookedOn: LocalDate?, val bankName: String?,
    val evidenceId: UUID, val canReview: Boolean,
    val canOpenEvidence: Boolean = false, val evidenceViewed: Boolean = false,
    val canCloseStale: Boolean = false,
)
data class FinancePayrollRunView(
    val organizationId: UUID, val runVersionId: UUID,
    val instructions: List<FinancePayrollInstructionView>, val proposals: List<FinancePayrollProposalView>,
)

internal data class FinancePayrollInstructionSource(
    val id: UUID, val organizationId: UUID, val runId: UUID, val employeeId: UUID,
    val amount: BigDecimal, val currency: String, val fingerprint: String,
)
internal data class FinancePayrollBankSource(
    val id: UUID, val amount: BigDecimal, val currency: String, val flow: String,
    val evidenceId: UUID, val evidenceSha256: String, val remaining: BigDecimal,
    val reconciled: Boolean = false,
)
internal data class FinancePayrollResultSource(
    val id: UUID, val version: Long, val action: String, val outcome: String,
    val evidenceId: UUID, val evidenceSha256: String,
)
internal data class FinancePayrollProposalSource(
    val id: UUID, val organizationId: UUID, val instructionId: UUID, val baseVersion: Long,
    val action: String, val bankLineId: UUID?, val correctsEventId: UUID?, val evidenceId: UUID,
    val evidenceSha256: String, val reason: String, val reviewVersion: Long, val preparedBy: UUID,
)

data class FinancePayrollEvidenceDocument(
    val organizationId: UUID, val proposalId: UUID, val evidenceId: UUID, val viewReceiptId: UUID,
    val fileName: String, val contentType: String, val sizeBytes: Long, val sha256: String,
    val matchedTransactionReference: String, val contentUtf8: String,
)

internal data class FinancePayrollEvidenceSource(
    val evidenceId: UUID, val evidenceSha256: String, val targetId: UUID, val uploader: UUID,
    val objectKey: String, val fileName: String, val contentType: String, val sizeBytes: Long,
    val reference: String, val bookedOn: LocalDate, val flow: String, val currency: String,
    val amount: BigDecimal, val originalLineSha256: String,
)
