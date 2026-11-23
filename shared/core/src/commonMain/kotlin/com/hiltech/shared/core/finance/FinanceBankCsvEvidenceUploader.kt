package com.hiltech.shared.core.finance

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import com.hiltech.shared.core.evidence.FinalizeEvidenceRequestDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadRequestDto
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Selected by a native OS file picker. No bank file bytes are logged,
 * attached to public CI artifacts, or treated as a user-entered transaction.
 */
data class FinanceBankCsvSelectedFile(
    val bytes: ByteArray,
    val sha256: String,
    val originalFileName: String,
    val capturedAt: String,
)

data class FinanceBankCsvEvidenceUploadInput(
    val organizationId: String,
    val bankAccountId: String,
    val bankIntakeOperationId: String,
    val bankProposalId: String,
    val reserveOperationId: String,
    val finalizeOperationId: String,
    val file: FinanceBankCsvSelectedFile,
)

data class VerifiedFinanceBankCsvEvidence(
    val organizationId: String,
    val bankAccountId: String,
    val bankIntakeOperationId: String,
    val bankProposalId: String,
    val evidenceId: String,
    val sha256: String,
    val sizeBytes: Long,
)

class FinanceBankCsvEvidenceNotReady(val actualState: String) :
    IllegalStateException("Original bank CSV Evidence did not reach READY ($actualState)")

/**
 * Real source-bound Finance bank CSV:
 * native file -> private Evidence reserve -> signed binary PUT -> verified
 * finalize -> server-only canonical parse (separate explicit action).
 *
 * Bank proposal UUID MUST be derived by a host from the SAME immutable
 * bankIntakeOperationId passed to prepareCsv. Server checks the binding.
 */
class FinanceBankCsvEvidenceUploader(
    private val evidence: EvidenceApiClient,
    private val bankIntake: FinanceBankStatementIntakeApiClient,
) {
    suspend fun upload(
        input: FinanceBankCsvEvidenceUploadInput,
        installationId: String,
    ): VerifiedFinanceBankCsvEvidence {
        val file = input.file
        require(file.bytes.size in 1..(2 * 1024 * 1024)) {
            "Canonical bank CSV must be 1 byte to 2 MiB."
        }
        require(file.sha256.matches(Regex("^[a-f0-9]{64}$"))) {
            "Original selected bytes require SHA-256."
        }
        require(file.originalFileName.endsWith(".csv", ignoreCase = true)) {
            "Only canonical CSV bank statements are accepted."
        }
        require(listOf(
            input.organizationId, input.bankAccountId,
            input.bankIntakeOperationId, input.bankProposalId,
            input.reserveOperationId, input.finalizeOperationId,
        ).all(::isUuid)) { "Original bank account, proposal and operation UUIDs required." }
        require(setOf(
            input.bankIntakeOperationId, input.reserveOperationId,
            input.finalizeOperationId,
        ).size == 3) { "Intake, Evidence reserve and finalize operation IDs must differ." }

        val reserved = evidence.reserve(
            ReserveEvidenceUploadRequestDto(
                operationId = input.reserveOperationId,
                targetType = "FINANCE_BANK_STATEMENT_INTAKE",
                targetId = input.bankProposalId,
                evidenceRequirementKey = "FINANCE_BANK_STATEMENT_INTAKE_BINARY",
                evidenceTypeCode = "FINANCE_BANK_STATEMENT_INTAKE",
                contentType = "text/csv",
                originalFileName = file.originalFileName,
                sizeBytes = file.bytes.size.toLong(),
                expectedSha256 = file.sha256,
                capturedAt = file.capturedAt,
                bankIntakeOperationId = input.bankIntakeOperationId,
                bankAccountId = input.bankAccountId,
            ),
            installationId,
        )
        verifyPrivateBankScope(
            reserved.evidence, input.bankProposalId, file.sha256,
            file.bytes.size.toLong(),
        )
        if (reserved.evidence.storageState != "READY") {
            if (reserved.evidence.storageState != "RESERVED") {
                throw FinanceBankCsvEvidenceNotReady(reserved.evidence.storageState)
            }
            evidence.uploadBytes(reserved.upload, "text/csv", file.bytes)
        }
        val finalized = if (reserved.evidence.storageState == "READY") {
            reserved.evidence
        } else {
            evidence.finalize(
                reserved.evidence.evidenceId,
                FinalizeEvidenceRequestDto(
                    operationId = input.finalizeOperationId,
                    uploadSessionId = reserved.evidence.uploadSessionId,
                    expectedSha256 = file.sha256,
                    expectedSizeBytes = file.bytes.size.toLong(),
                ),
                installationId,
            ).evidence
        }
        verifyPrivateBankScope(
            finalized, input.bankProposalId, file.sha256,
            file.bytes.size.toLong(),
        )
        if (finalized.storageState != "READY") {
            throw FinanceBankCsvEvidenceNotReady(finalized.storageState)
        }
        return VerifiedFinanceBankCsvEvidence(
            organizationId = input.organizationId,
            bankAccountId = input.bankAccountId,
            bankIntakeOperationId = input.bankIntakeOperationId,
            bankProposalId = input.bankProposalId,
            evidenceId = finalized.evidenceId,
            sha256 = finalized.sha256,
            sizeBytes = finalized.sizeBytes,
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun prepareSource(
        verified: VerifiedFinanceBankCsvEvidence,
        selectedFile: FinanceBankCsvSelectedFile,
        periodStart: String,
        periodEnd: String,
        openingBalance: String,
        closingBalance: String,
        installationId: String,
    ): FinanceBankIntakeReceiptDto {
        require(selectedFile.bytes.size.toLong() == verified.sizeBytes &&
            selectedFile.sha256 == verified.sha256
        ) { "Selected source bytes changed after private Evidence finalize." }
        require(verified.organizationId.isNotBlank() &&
            isUuid(verified.bankIntakeOperationId) &&
            isUuid(verified.bankAccountId) &&
            isUuid(verified.evidenceId)
        ) { "Original approved company bank source required." }
        val receipt = bankIntake.prepareCsv(
            verified.organizationId,
            FinanceBankCsvIntakePrepareDto(
                operationId = verified.bankIntakeOperationId,
                bankAccountId = verified.bankAccountId,
                statementEvidenceId = verified.evidenceId,
                statementSha256 = verified.sha256,
                periodStart = periodStart,
                periodEnd = periodEnd,
                openingBalance = openingBalance,
                closingBalance = closingBalance,
                csvUtf8Base64 = Base64.Default.encode(selectedFile.bytes),
            ),
            installationId,
        )
        check(receipt.organizationId.equals(verified.organizationId, ignoreCase = true) &&
            receipt.proposalId.equals(verified.bankProposalId, ignoreCase = true) &&
            receipt.bankAccountId.equals(verified.bankAccountId, ignoreCase = true) &&
            receipt.statementHash == verified.sha256
        ) { "Bank intake must reflect original uploaded private Evidence source." }
        return receipt
    }

    private fun verifyPrivateBankScope(
        value: EvidenceMetadataDto,
        proposalId: String,
        sha256: String,
        size: Long,
    ) {
        check(value.targetType == "FINANCE_BANK_STATEMENT_INTAKE" &&
            value.targetId.equals(proposalId, ignoreCase = true) &&
            value.evidenceRequirementKey == "FINANCE_BANK_STATEMENT_INTAKE_BINARY" &&
            value.evidenceTypeCode == "FINANCE_BANK_STATEMENT_INTAKE" &&
            value.classificationCode == "HIGHLY_RESTRICTED" &&
            value.clientVisibilityMode == "INTERNAL_ONLY" &&
            value.contentType == "text/csv" &&
            value.sizeBytes == size &&
            value.sha256 == sha256
        ) { "Private bank Evidence did not bind to the original Finance proposal." }
    }

    private fun isUuid(value: String): Boolean =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(value)
}
