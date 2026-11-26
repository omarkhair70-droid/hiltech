package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FinanceEmployeeDraftDto(
    val id: String, val organizationId: String, val employeeId: String,
    val kind: String, val amount: String, val currency: String,
    val purpose: String, val state: String, val version: Long,
    val projectId: String? = null, val createdAt: String,
    val merchant: String? = null, val spentOn: String? = null,
    val proposedSettlementNote: String? = null,
    val proposedSettlementOn: String? = null,
    val financialActionAllowed: Boolean = false,
    val replayed: Boolean = false,
)
@Serializable
data class ExpenseDraftCommandDto(
    val operationId: String, val amount: String, val currency: String,
    val purpose: String, val merchant: String, val spentOn: String,
    val projectId: String? = null,
)
@Serializable
data class AdvanceDraftCommandDto(
    val operationId: String, val requestedAmount: String,
    val currency: String, val purpose: String, val projectId: String? = null,
    val proposedSettlementNote: String? = null,
)
@Serializable
data class ImprestDraftCommandDto(
    val operationId: String, val requestedAmount: String,
    val currency: String, val purpose: String, val projectId: String? = null,
    val proposedSettlementOn: String? = null,
)

/** Private, online-only employee self-service; no offline financial commands. */
class FinanceEmployeeDraftApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun expenses(orgId: String, installationId: String): List<FinanceEmployeeDraftDto> =
        list("expenses", orgId, installationId)
    suspend fun advances(orgId: String, installationId: String): List<FinanceEmployeeDraftDto> =
        list("advances", orgId, installationId)
    suspend fun imprests(orgId: String, installationId: String): List<FinanceEmployeeDraftDto> =
        list("imprests", orgId, installationId)

    suspend fun createExpense(orgId: String, req: ExpenseDraftCommandDto,
                              installationId: String): FinanceEmployeeDraftDto =
        post("expenses", orgId, req.operationId,
            json.encodeToString(ExpenseDraftCommandDto.serializer(), req), installationId)

    suspend fun createAdvance(orgId: String, req: AdvanceDraftCommandDto,
                              installationId: String): FinanceEmployeeDraftDto =
        post("advances", orgId, req.operationId,
            json.encodeToString(AdvanceDraftCommandDto.serializer(), req), installationId)

    suspend fun createImprest(orgId: String, req: ImprestDraftCommandDto,
                             installationId: String): FinanceEmployeeDraftDto =
        post("imprests", orgId, req.operationId,
            json.encodeToString(ImprestDraftCommandDto.serializer(), req), installationId)

    private suspend fun list(kind: String, orgId: String, installationId: String) =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/finance/me/organizations/" + uuid(orgId) + "/$kind",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(
                ListSerializer(FinanceEmployeeDraftDto.serializer()), it,
            ) },
        )

    private suspend fun post(kind: String, orgId: String, operationId: String,
                             payload: String, installationId: String): FinanceEmployeeDraftDto =
        api.request(
            method = HttpMethod.Post,
            path = "/v1/finance/me/organizations/" + uuid(orgId) + "/$kind",
            options = HiltechRequestOptions(
                installationId = installationId, idempotencyKey = uuid(operationId),
            ),
            requestBody = payload,
            decode = { json.decodeFromString(FinanceEmployeeDraftDto.serializer(), it) },
        )

    private fun uuid(value: String): String {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(value)) { "Valid UUID required" }
        return value
    }
}
