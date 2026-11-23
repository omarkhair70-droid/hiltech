package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RequestExpenseDocumentationReviewDto(
    val operationId: String,
)

@Serializable
data class FinanceExpenseDocumentationReviewReceiptDto(
    val expenseDraftId: String,
    val sourceVersion: Long,
    val verifiedReceiptEvidenceIds: List<String>,
    val documentationReviewRequestId: String,
    val documentationReviewState: String,
    val policyKey: String,
    val policyVersion: Int,
    val replayed: Boolean,
    val existingReview: Boolean = false,
    val expenseApproved: Boolean = false,
    val payableRecognized: Boolean = false,
    val reimbursementAuthorized: Boolean = false,
    val payrollDeductionAuthorized: Boolean = false,
    val cashMovementRecorded: Boolean = false,
)

@Serializable
data class FinanceExpenseDocumentationReviewStatusDto(
    val expenseDraftId: String,
    val sourceVersion: Long,
    val verifiedReceiptCount: Int,
    val documentationReviewRequestId: String? = null,
    val documentationReviewState: String,
    val policyKey: String,
    val currentSource: Boolean = true,
    val expenseApproved: Boolean = false,
    val payableRecognized: Boolean = false,
    val reimbursementAuthorized: Boolean = false,
    val cashMovementRecorded: Boolean = false,
)

/** Existing ApprovalEngine request only. No endpoint for finance payout. */
class FinanceExpenseDocumentationReviewApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun request(
        organizationId: String,
        expenseDraftId: String,
        operationId: String,
        installationId: String,
    ): FinanceExpenseDocumentationReviewReceiptDto {
        val body = RequestExpenseDocumentationReviewDto(uuid(operationId))
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/finance/me/organizations/" + uuid(organizationId) +
                "/expenses/" + uuid(expenseDraftId) + "/request-documentation-review",
            options = HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = body.operationId,
            ),
            requestBody = json.encodeToString(
                RequestExpenseDocumentationReviewDto.serializer(), body,
            ),
            decode = {
                json.decodeFromString(
                    FinanceExpenseDocumentationReviewReceiptDto.serializer(), it,
                )
            },
        )
    }

    suspend fun status(
        organizationId: String,
        expenseDraftId: String,
        installationId: String,
    ): FinanceExpenseDocumentationReviewStatusDto =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/finance/me/organizations/" + uuid(organizationId) +
                "/expenses/" + uuid(expenseDraftId) + "/documentation-review",
            options = HiltechRequestOptions(installationId = installationId),
            decode = {
                json.decodeFromString(
                    FinanceExpenseDocumentationReviewStatusDto.serializer(), it,
                )
            },
        )

    private fun uuid(value: String): String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(value)) { "Valid UUID required" }
        return value
    }
}
