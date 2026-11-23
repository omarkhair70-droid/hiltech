package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class WarehouseReplenishmentDto(
    val id: String, val organizationId: String, val storageLocationId: String,
    val storageLocationVersion: Long, val stockItemId: String,
    val stockItemVersion: Long, val requestedQuantity: String,
    val unitCode: String, val justification: String, val state: String,
    val assessmentCode: String, val visibleFreeQuantity: String? = null,
    val sourceCurrent: Boolean, val version: Long, val requestedBy: String,
    val canSubmit: Boolean, val canRevise: Boolean,
    val canWithdraw: Boolean, val canTechnicalReview: Boolean,
    val lastReviewReason: String? = null,
)
@Serializable
data class WarehouseReplenishmentReceiptDto(
    val requirement: WarehouseReplenishmentDto, val replayed: Boolean,
)
@Serializable
data class CreateWarehouseReplenishmentDto(
    val operationId: String, val baseLocationVersion: Long,
    val stockItemId: String, val stockItemVersion: Long,
    val quantity: String, val unitCode: String, val justification: String,
)
@Serializable
data class ReviseWarehouseReplenishmentDto(
    val operationId: String, val expectedVersion: Long,
    val baseLocationVersion: Long, val stockItemVersion: Long,
    val quantity: String, val unitCode: String, val justification: String,
)

/** Real Warehouse source, same authenticated command transport as procurement. */
class WarehouseReplenishmentApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun create(locationId: String, request: CreateWarehouseReplenishmentDto,
                       installationId: String): WarehouseReplenishmentReceiptDto =
        post("/v1/warehouse/storage-locations/" + uuid(locationId) + "/purchase-requirements",
            request.operationId,
            json.encodeToString(CreateWarehouseReplenishmentDto.serializer(), request),
            installationId, WarehouseReplenishmentReceiptDto.serializer())

    suspend fun list(locationId: String, installationId: String): List<WarehouseReplenishmentDto> =
        get("/v1/warehouse/storage-locations/" + uuid(locationId) + "/purchase-requirements",
            installationId, ListSerializer(WarehouseReplenishmentDto.serializer()))

    suspend fun detail(id: String, installationId: String): WarehouseReplenishmentDto =
        get("/v1/warehouse-replenishment/" + uuid(id), installationId,
            WarehouseReplenishmentDto.serializer())

    suspend fun submit(id: String, request: SubmitPurchaseRequirementDraftDto,
                       installationId: String): WarehouseReplenishmentReceiptDto =
        post("/v1/warehouse-replenishment/" + uuid(id) + "/submit", request.operationId,
            json.encodeToString(SubmitPurchaseRequirementDraftDto.serializer(), request),
            installationId, WarehouseReplenishmentReceiptDto.serializer())

    suspend fun revise(id: String, request: ReviseWarehouseReplenishmentDto,
                       installationId: String): WarehouseReplenishmentReceiptDto =
        post("/v1/warehouse-replenishment/" + uuid(id) + "/revise", request.operationId,
            json.encodeToString(ReviseWarehouseReplenishmentDto.serializer(), request),
            installationId, WarehouseReplenishmentReceiptDto.serializer())

    suspend fun withdraw(id: String, request: WithdrawPurchaseRequirementDraftDto,
                         installationId: String): WarehouseReplenishmentReceiptDto =
        post("/v1/warehouse-replenishment/" + uuid(id) + "/withdraw", request.operationId,
            json.encodeToString(WithdrawPurchaseRequirementDraftDto.serializer(), request),
            installationId, WarehouseReplenishmentReceiptDto.serializer())

    suspend fun review(id: String, request: ReviewPurchaseRequirementDto,
                       installationId: String): WarehouseReplenishmentReceiptDto =
        post("/v1/warehouse-replenishment/" + uuid(id) + "/technical-review",
            request.operationId,
            json.encodeToString(ReviewPurchaseRequirementDto.serializer(), request),
            installationId, WarehouseReplenishmentReceiptDto.serializer())

    private suspend fun <T> get(path: String, installationId: String,
                                serializer: KSerializer<T>): T = api.request(
        method = HttpMethod.Get, path = path,
        options = HiltechRequestOptions(installationId = installationId),
        decode = { json.decodeFromString(serializer, it) },
    )
    private suspend fun <T> post(path: String, op: String, body: String,
                                 installationId: String, serializer: KSerializer<T>): T =
        api.request(method = HttpMethod.Post, path = path,
            options = HiltechRequestOptions(
                installationId = installationId, idempotencyKey = uuid(op),
            ),
            requestBody = body,
            decode = { json.decodeFromString(serializer, it) },
        )

    private fun uuid(value: String): String {
        require(Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$").matches(value))
        return value
    }
}
