package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FinanceMoneyPolicyReviewRequestDto(val operationId: String)

@Serializable
data class FinanceMoneyPolicyReviewStatusDto(
    val draftId: String,
    val kind: String,
    val sourceVersion: Long,
    val policyKey: String,
    val policyVersion: Int,
    val approvalRequestId: String? = null,
    val requestState: String,
    val existingRequest: Boolean = false,
    val replayed: Boolean = false,
    val moneyIssued: Boolean = false,
    val personalLoanRecognized: Boolean = false,
    val cashCustodyRecorded: Boolean = false,
    val payrollDeductionAuthorized: Boolean = false,
    val settlementRecorded: Boolean = false,
)

/**
 * Separate immutable employee Advance and Imprest policy reviews; no money
 * disbursement or company custody is created by either endpoint.
 */
class FinanceMoneyPolicyReviewApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun request(
        organizationId: String,
        kind: String,
        draftId: String,
        operationId: String,
        installationId: String,
    ): FinanceMoneyPolicyReviewStatusDto {
        val body = FinanceMoneyPolicyReviewRequestDto(uuid(operationId))
        return api.request(
            method = HttpMethod.Post,
            path = route(organizationId, kind, draftId) + "/request-policy-review",
            options = HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = body.operationId,
            ),
            requestBody = json.encodeToString(
                FinanceMoneyPolicyReviewRequestDto.serializer(), body,
            ),
            decode = {
                json.decodeFromString(
                    FinanceMoneyPolicyReviewStatusDto.serializer(), it,
                )
            },
        )
    }

    suspend fun mine(
        organizationId: String,
        kind: String,
        draftId: String,
        installationId: String,
    ): FinanceMoneyPolicyReviewStatusDto =
        api.request(
            method = HttpMethod.Get,
            path = route(organizationId, kind, draftId) + "/policy-review",
            options = HiltechRequestOptions(installationId = installationId),
            decode = {
                json.decodeFromString(
                    FinanceMoneyPolicyReviewStatusDto.serializer(), it,
                )
            },
        )

    private fun route(org: String, kind: String, id: String): String {
        require(kind == "advances" || kind == "imprests") {
            "Money policy review requires the real advance or imprest kind."
        }
        return "/v1/finance/me/organizations/" + uuid(org) + "/" + kind +
            "/" + uuid(id)
    }

    private fun uuid(value: String): String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(value)) { "Valid UUID required" }
        return value
    }
}
