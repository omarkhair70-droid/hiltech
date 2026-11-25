package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FinanceApReversalProposeDto(
    val operationId: String,
    val targetPeriodId: String,
    val reasonReference: String,
)

@Serializable
data class FinanceApReversalOperationDto(val operationId: String)

@Serializable
data class FinanceApReversalReceiptDto(
    val proposalId: String,
    val originalJournalId: String,
    val targetPeriodId: String,
    val sourceVersion: Long,
    val state: String,
    val currency: String,
    val grossAmount: String,
    val approvalRequestId: String? = null,
    val reversalJournalId: String? = null,
    val replayed: Boolean = false,
    val financialLedgerReversed: Boolean = false,
    val bankPaymentReversed: Boolean = false,
    val moneyTransferred: Boolean = false,
)

/**
 * Typed transport for the existing guarded AP journal correction endpoints.
 * No native screen is mounted by this client. The server owns current authority,
 * independent approval, source/period checks and accounting effects.
 * Callers retain the operation ID and payload when retrying an unknown outcome.
 */
class FinanceApReversalApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun propose(
        organizationId: String,
        originalJournalId: String,
        targetPeriodId: String,
        reasonReference: String,
        operationId: String,
        installationId: String,
    ): FinanceApReversalReceiptDto {
        val path = organization(organizationId) + "/ap-journals/" + uuid(originalJournalId) +
            "/reversal-proposals"
        val reason = reasonReference.trim()
        require(reason.length in 8..180) { "A correction reason reference is required." }
        val body = FinanceApReversalProposeDto(uuid(operationId), uuid(targetPeriodId), reason)
        return validateReceipt(
            postBody(path, operationId, installationId,
                json.encodeToString(FinanceApReversalProposeDto.serializer(), body)),
            expectedOriginalJournalId = originalJournalId,
            expectedTargetPeriodId = targetPeriodId,
            expectedState = "PROPOSED",
        )
    }

    suspend fun requestApproval(
        organizationId: String,
        proposalId: String,
        operationId: String,
        installationId: String,
    ): FinanceApReversalReceiptDto = command(
        organizationId, proposalId, operationId, installationId, "request-approval", "PENDING",
    )

    suspend fun post(
        organizationId: String,
        proposalId: String,
        operationId: String,
        installationId: String,
    ): FinanceApReversalReceiptDto = command(
        organizationId, proposalId, operationId, installationId, "post", "REVERSED",
    )

    suspend fun status(
        organizationId: String,
        proposalId: String,
        installationId: String,
    ): FinanceApReversalReceiptDto = validateReceipt(
        api.request(
            method = HttpMethod.Get,
            path = organization(organizationId) + "/ap-reversal-proposals/" + uuid(proposalId),
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(FinanceApReversalReceiptDto.serializer(), it) },
        ),
        expectedProposalId = proposalId,
    )

    private suspend fun command(
        organizationId: String,
        proposalId: String,
        operationId: String,
        installationId: String,
        action: String,
        expectedState: String,
    ): FinanceApReversalReceiptDto {
        require(action == "post" || action == "request-approval")
        val path = organization(organizationId) + "/ap-reversal-proposals/" + uuid(proposalId) + "/" + action
        val body = FinanceApReversalOperationDto(uuid(operationId))
        return validateReceipt(
            postBody(path, operationId, installationId,
                json.encodeToString(FinanceApReversalOperationDto.serializer(), body)),
            expectedProposalId = proposalId,
            expectedState = expectedState,
        )
    }

    private suspend fun postBody(
        path: String,
        operationId: String,
        installationId: String,
        body: String,
    ): FinanceApReversalReceiptDto = api.request(
        method = HttpMethod.Post,
        path = path,
        options = HiltechRequestOptions(installationId = installationId, idempotencyKey = uuid(operationId)),
        requestBody = body,
        decode = { json.decodeFromString(FinanceApReversalReceiptDto.serializer(), it) },
    )

    private fun validateReceipt(
        receipt: FinanceApReversalReceiptDto,
        expectedProposalId: String? = null,
        expectedOriginalJournalId: String? = null,
        expectedTargetPeriodId: String? = null,
        expectedState: String? = null,
    ): FinanceApReversalReceiptDto {
        uuid(receipt.proposalId)
        uuid(receipt.originalJournalId)
        uuid(receipt.targetPeriodId)
        receipt.approvalRequestId?.let(::uuid)
        receipt.reversalJournalId?.let(::uuid)
        require(expectedProposalId == null || receipt.proposalId.equals(expectedProposalId, ignoreCase = true))
        require(expectedOriginalJournalId == null || receipt.originalJournalId.equals(expectedOriginalJournalId, ignoreCase = true))
        require(expectedTargetPeriodId == null || receipt.targetPeriodId.equals(expectedTargetPeriodId, ignoreCase = true))
        require(receipt.sourceVersion > 0 && Regex("^[A-Z]{3}$").matches(receipt.currency))
        require(Regex("^(0|[1-9][0-9]*)(\\.[0-9]{1,6})?$").matches(receipt.grossAmount) &&
            receipt.grossAmount.any { it in '1'..'9' })
        require(receipt.state in setOf(
            "PROPOSED", "PENDING", "APPROVED", "REJECTED", "CHANGE_REQUESTED", "SUPERSEDED", "CANCELLED", "REVERSED",
        )) { "Unknown AP reversal response state." }
        require(expectedState == null || receipt.state == expectedState) { "AP reversal response does not match the command." }
        val reversed = receipt.state == "REVERSED"
        require(receipt.financialLedgerReversed == reversed && (receipt.reversalJournalId != null) == reversed &&
            (receipt.approvalRequestId != null) == (receipt.state != "PROPOSED")) {
            "Inconsistent AP reversal receipt."
        }
        require(!receipt.bankPaymentReversed && !receipt.moneyTransferred) {
            "An AP journal correction cannot acknowledge a bank refund or money transfer."
        }
        return receipt
    }

    private fun organization(id: String) = "/v1/finance/organizations/" + uuid(id)

    private fun uuid(value: String): String {
        require(Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$").matches(value)) {
            "A valid record reference is required."
        }
        return value
    }
}
