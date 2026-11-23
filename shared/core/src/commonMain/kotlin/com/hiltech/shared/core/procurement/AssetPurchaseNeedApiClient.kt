package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class AssetPurchaseNeedDto(
    val id: String, val organizationId: String, val assetId: String,
    val assetCode: String, val assetVersion: Long,
    val issueType: String, val issueId: String, val issueVersion: Long,
    val deliveryStorageLocationId: String,
    val stockItemId: String, val stockItemVersion: Long,
    val requestedQuantity: String, val unitCode: String,
    val justification: String, val state: String, val version: Long,
    val assessmentCode: String, val visibleFreeQuantity: String? = null,
    val sourceCurrent: Boolean, val requestedBy: String,
    val canSubmit: Boolean, val canRevise: Boolean,
    val canWithdraw: Boolean, val canTechnicalReview: Boolean,
    val lastReviewReason: String? = null,
)
@Serializable
data class AssetPurchaseNeedReceiptDto(
    val need: AssetPurchaseNeedDto, val replayed: Boolean,
)
@Serializable
data class CreateAssetPurchaseNeedDto(
    val operationId: String, val assetVersion: Long,
    val incidentId: String? = null, val maintenanceId: String? = null,
    val issueVersion: Long, val deliveryStorageLocationId: String,
    val deliveryLocationVersion: Long, val stockItemId: String,
    val stockItemVersion: Long, val quantity: String, val unitCode: String,
    val justification: String,
)
@Serializable
data class ReviseAssetPurchaseNeedDto(
    val operationId: String, val expectedVersion: Long,
    val assetVersion: Long, val stockItemVersion: Long,
    val quantity: String, val unitCode: String, val justification: String,
)

/** Same authenticated, online-only, idempotent transport as Phase 7 S01. */
class AssetPurchaseNeedApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun create(assetId: String, input: CreateAssetPurchaseNeedDto,
                       installationId: String): AssetPurchaseNeedReceiptDto =
        post("/v1/assets/" + uuid(assetId) + "/purchase-needs", input.operationId,
            json.encodeToString(CreateAssetPurchaseNeedDto.serializer(), input),
            installationId, AssetPurchaseNeedReceiptDto.serializer())

    suspend fun list(assetId: String, installationId: String): List<AssetPurchaseNeedDto> =
        get("/v1/assets/" + uuid(assetId) + "/purchase-needs", installationId,
            ListSerializer(AssetPurchaseNeedDto.serializer()))

    suspend fun detail(id: String, installationId: String): AssetPurchaseNeedDto =
        get("/v1/asset-purchase-needs/" + uuid(id), installationId,
            AssetPurchaseNeedDto.serializer())

    suspend fun submit(id: String, input: SubmitPurchaseRequirementDraftDto,
                       installationId: String): AssetPurchaseNeedReceiptDto =
        post("/v1/asset-purchase-needs/" + uuid(id) + "/submit", input.operationId,
            json.encodeToString(SubmitPurchaseRequirementDraftDto.serializer(), input),
            installationId, AssetPurchaseNeedReceiptDto.serializer())

    suspend fun revise(id: String, input: ReviseAssetPurchaseNeedDto,
                       installationId: String): AssetPurchaseNeedReceiptDto =
        post("/v1/asset-purchase-needs/" + uuid(id) + "/revise", input.operationId,
            json.encodeToString(ReviseAssetPurchaseNeedDto.serializer(), input),
            installationId, AssetPurchaseNeedReceiptDto.serializer())

    suspend fun withdraw(id: String, input: WithdrawPurchaseRequirementDraftDto,
                         installationId: String): AssetPurchaseNeedReceiptDto =
        post("/v1/asset-purchase-needs/" + uuid(id) + "/withdraw", input.operationId,
            json.encodeToString(WithdrawPurchaseRequirementDraftDto.serializer(), input),
            installationId, AssetPurchaseNeedReceiptDto.serializer())

    suspend fun review(id: String, input: ReviewPurchaseRequirementDto,
                       installationId: String): AssetPurchaseNeedReceiptDto =
        post("/v1/asset-purchase-needs/" + uuid(id) + "/technical-review", input.operationId,
            json.encodeToString(ReviewPurchaseRequirementDto.serializer(), input),
            installationId, AssetPurchaseNeedReceiptDto.serializer())

    private suspend fun <T> get(path: String, installationId: String,
                                serializer: KSerializer<T>): T = api.request(
        method = HttpMethod.Get, path = path,
        options = HiltechRequestOptions(installationId = installationId),
        decode = { json.decodeFromString(serializer, it) },
    )
    private suspend fun <T> post(path: String, op: String, payload: String,
                                 installationId: String, serializer: KSerializer<T>): T =
        api.request(
            method = HttpMethod.Post, path = path,
            options = HiltechRequestOptions(
                installationId = installationId, idempotencyKey = uuid(op),
            ),
            requestBody = payload,
            decode = { json.decodeFromString(serializer, it) },
        )
    private fun uuid(s: String): String {
        require(Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$").matches(s))
        return s
    }
}
