package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Uses the canonical authenticated transport on Android and Windows.
 * Commands are ONLINE ONLY. Do not enqueue purchase operations in the field
 * offline queue: both stock preflight and current identity authority matter.
 */
class PurchaseRequirementApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    suspend fun create(
        workOrderId: String,
        request: CreatePurchaseRequirementDraftDto,
        installationId: String,
    ): PurchaseRequirementDraftReceiptDto =
        post(
            "/v1/work-orders/" + uuid(workOrderId) + "/purchase-requirements",
            request.operationId,
            json.encodeToString(CreatePurchaseRequirementDraftDto.serializer(), request),
            installationId,
            PurchaseRequirementDraftReceiptDto.serializer(),
        )

    suspend fun forWorkOrder(
        workOrderId: String,
        installationId: String,
    ): List<PurchaseRequirementDraftDto> =
        get(
            "/v1/work-orders/" + uuid(workOrderId) + "/purchase-requirements",
            installationId,
            ListSerializer(PurchaseRequirementDraftDto.serializer()),
        )

    suspend fun detail(
        requirementId: String,
        installationId: String,
    ): PurchaseRequirementDraftDto =
        get(
            "/v1/purchase-requirements/" + uuid(requirementId),
            installationId,
            PurchaseRequirementDraftDto.serializer(),
        )

    suspend fun withdraw(
        requirementId: String,
        request: WithdrawPurchaseRequirementDraftDto,
        installationId: String,
    ): PurchaseRequirementDraftReceiptDto =
        post(
            "/v1/purchase-requirements/" + uuid(requirementId) + "/withdraw",
            request.operationId,
            json.encodeToString(WithdrawPurchaseRequirementDraftDto.serializer(), request),
            installationId,
            PurchaseRequirementDraftReceiptDto.serializer(),
        )

    private suspend fun <T> get(
        path: String,
        installationId: String,
        serializer: KSerializer<T>,
    ): T = api.request(
        method = HttpMethod.Get,
        path = path,
        options = HiltechRequestOptions(installationId = installationId),
        decode = { json.decodeFromString(serializer, it) },
    )

    private suspend fun <T> post(
        path: String,
        operationId: String,
        body: String,
        installationId: String,
        serializer: KSerializer<T>,
    ): T = api.request(
        method = HttpMethod.Post,
        path = path,
        options = HiltechRequestOptions(
            installationId = installationId,
            idempotencyKey = uuid(operationId),
        ),
        requestBody = body,
        decode = { json.decodeFromString(serializer, it) },
    )

    private fun uuid(id: String): String {
        require(
            id.length == 36 &&
                Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                    .matches(id),
        ) { "A valid UUID is required." }
        return id
    }
}
