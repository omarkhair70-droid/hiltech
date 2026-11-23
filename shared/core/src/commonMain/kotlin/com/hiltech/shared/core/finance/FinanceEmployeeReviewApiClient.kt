package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FinanceEmployeeReviewDto(
    val id: String, val organizationId: String,
    val employeeId: String, val employeeCode: String,
    val kind: String, val amount: String, val currency: String,
    val purpose: String, val state: String, val projectId: String? = null,
    val createdAt: String, val approvable: Boolean = false,
    val fundable: Boolean = false,
)

/** Online-only, finance-role protected, read-only operator queue. */
class FinanceEmployeeReviewApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun drafts(orgId: String, installationId: String): List<FinanceEmployeeReviewDto> {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(orgId))
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/finance/organizations/$orgId/employee-draft-review",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(
                ListSerializer(FinanceEmployeeReviewDto.serializer()), it,
            ) },
        )
    }
}
