package com.hiltech.shared.core.finance

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import com.hiltech.shared.core.evidence.FinalizeEvidenceRequestDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadRequestDto

/**
 * Native Windows/Android selected file bytes can use the existing signed
 * Evidence HTTP upload. No new Finance object store or binary transport.
 */
data class FinanceSelectedExpenseReceipt(
    val expenseDraftId: String,
    val reserveOperationId: String,
    val finalizeOperationId: String,
    val contentType: String,
    val originalFileName: String?,
    val bytes: ByteArray,
    val sha256: String,
    val capturedAt: String,
)

data class FinanceExpenseReceiptUploadResult(
    val expenseDraftId: String,
    val evidenceId: String,
    val storageState: String,
    val uploadedReceiptReady: Boolean,
    val pendingSecurityScan: Boolean,
)

/**
 * Uploading is evidence acquisition ONLY. Even READY cannot approve expenses
 * or release reimbursement. The canonical scanner may return QUARANTINED.
 */
class FinanceExpenseReceiptUploader(
    private val evidence: EvidenceApiClient,
) {
    suspend fun upload(
        input: FinanceSelectedExpenseReceipt,
        installationId: String,
    ): FinanceExpenseReceiptUploadResult {
        require(input.bytes.size in 1..(16 * 1024 * 1024)) {
            "Receipt PDF/image must be 1 byte to 16 MiB."
        }
        require(input.contentType in setOf(
            "application/pdf", "image/png", "image/jpeg",
        )) { "Only PDF, PNG or JPEG expense receipt bytes are permitted." }
        require(Regex("^[0-9a-f]{64}$").matches(input.sha256)) {
            "SHA-256 of selected receipt bytes required."
        }
        require(input.reserveOperationId != input.finalizeOperationId) {
            "Reserve and finalize must have distinct idempotency IDs."
        }
        val reserved = evidence.reserve(
            ReserveEvidenceUploadRequestDto(
                operationId = input.reserveOperationId,
                targetType = "FINANCE_EXPENSE_RECEIPT",
                targetId = input.expenseDraftId,
                evidenceRequirementKey = "FINANCE_EXPENSE_RECEIPT_BINARY",
                evidenceTypeCode = "FINANCE_EXPENSE_RECEIPT",
                contentType = input.contentType,
                originalFileName = input.originalFileName,
                sizeBytes = input.bytes.size.toLong(),
                expectedSha256 = input.sha256,
                capturedAt = input.capturedAt,
            ),
            installationId,
        )
        checkScope(reserved.evidence, input)
        require(reserved.evidence.storageState == "RESERVED") {
            "Receipt reservation is no longer writable; re-check Evidence."
        }
        evidence.uploadBytes(reserved.upload, input.contentType, input.bytes)
        val finalized = evidence.finalize(
            reserved.evidence.evidenceId,
            FinalizeEvidenceRequestDto(
                operationId = input.finalizeOperationId,
                uploadSessionId = reserved.evidence.uploadSessionId,
                expectedSha256 = input.sha256,
                expectedSizeBytes = input.bytes.size.toLong(),
            ),
            installationId,
        )
        checkScope(finalized.evidence, input)
        return result(finalized.evidence, input.expenseDraftId)
    }

    suspend fun refresh(
        evidenceId: String,
        expenseDraftId: String,
        installationId: String,
    ): FinanceExpenseReceiptUploadResult {
        val item = evidence.metadata(evidenceId, installationId)
        check(
            item.targetType == "FINANCE_EXPENSE_RECEIPT" &&
                item.targetId == expenseDraftId &&
                item.evidenceRequirementKey == "FINANCE_EXPENSE_RECEIPT_BINARY" &&
                item.evidenceTypeCode == "FINANCE_EXPENSE_RECEIPT" &&
                item.classificationCode == "HIGHLY_RESTRICTED" &&
                item.clientVisibilityMode == "INTERNAL_ONLY"
        ) { "Evidence is not a private receipt bound to this expense draft." }
        return result(item, expenseDraftId)
    }

    private fun result(
        metadata: EvidenceMetadataDto,
        expenseDraftId: String,
    ): FinanceExpenseReceiptUploadResult =
        FinanceExpenseReceiptUploadResult(
            expenseDraftId = expenseDraftId,
            evidenceId = metadata.evidenceId,
            storageState = metadata.storageState,
            uploadedReceiptReady = metadata.storageState == "READY",
            pendingSecurityScan = metadata.storageState == "QUARANTINED",
        )

    private fun checkScope(
        e: EvidenceMetadataDto,
        input: FinanceSelectedExpenseReceipt,
    ) {
        require(e.targetType == "FINANCE_EXPENSE_RECEIPT" &&
            e.targetId == input.expenseDraftId &&
            e.workOrderId == null &&
            e.evidenceRequirementKey == "FINANCE_EXPENSE_RECEIPT_BINARY" &&
            e.evidenceTypeCode == "FINANCE_EXPENSE_RECEIPT" &&
            e.contentType == input.contentType &&
            e.sha256 == input.sha256 &&
            e.sizeBytes == input.bytes.size.toLong() &&
            e.classificationCode == "HIGHLY_RESTRICTED" &&
            e.clientVisibilityMode == "INTERNAL_ONLY"
        ) { "Receipt upload is not bound to the exact private Finance Evidence target." }
    }
}
