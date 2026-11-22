package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FinanceExpenseReceiptReadinessDto(
    val expenseDraftId: String,
    val organizationId: String,
    val currency: String,
    val expenseAmount: String,
    val receiptEvidenceTargetType: String,
    val verifiedReceiptEvidenceIds: List<String>,
    val verifiedReceiptCount: Int,
    val uploadedReceiptReady: Boolean,
    val observedAt: String,
    val reviewerActionAllowed: Boolean = false,
    val paymentActionAllowed: Boolean = false,
    val blockingReasons: List<String>,
)

/** Online-only read model. Binary stays inside existing secure Evidence. */
class FinanceExpenseReceiptReadinessApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun mine(orgId: String, installationId: String):
        List<FinanceExpenseReceiptReadinessDto> = get(
        "/v1/finance/me/organizations/" + uuid(orgId) + "/expense-receipt-readiness",
        installationId,
    )

    suspend fun forFinanceReviewer(orgId: String, installationId: String):
        List<FinanceExpenseReceiptReadinessDto> = get(
        "/v1/finance/organizations/" + uuid(orgId) + "/expense-receipt-review",
        installationId,
    )

    private suspend fun get(path: String, installationId: String):
        List<FinanceExpenseReceiptReadinessDto> = api.request(
        method = HttpMethod.Get,
        path = path,
        options = HiltechRequestOptions(installationId = installationId),
        decode = {
            json.decodeFromString(
                ListSerializer(FinanceExpenseReceiptReadinessDto.serializer()), it,
            )
        },
    )

    private fun uuid(value: String): String {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(value)) { "Valid UUID required" }
        return value
    }
}
