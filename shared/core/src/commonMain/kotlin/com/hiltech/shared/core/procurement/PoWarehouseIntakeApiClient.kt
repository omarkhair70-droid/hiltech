package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import com.hiltech.shared.core.warehouse.ReceivingRecordResultDto
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class PoWarehouseIntakeDto(
    val purchaseOrderId: String,
    val poCode: String,
    val stockItemId: String,
    val unitCode: String,
    val destinationStorageLocationId: String,
    val expectedQuantity: String,
    val physicallyReceivedQuantity: String,
    val remainingQuantity: String,
    val effectiveOrderVersion: Long,
)
@Serializable
data class StartPoWarehouseIntakeDto(val operationId: String)

/** One source-bound PO queue for Windows and Android warehouse operators. */
class PoWarehouseIntakeApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun queue(orgId: String, installationId: String): List<PoWarehouseIntakeDto> =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/procurement/organizations/" + uuid(orgId) + "/warehouse-po-intake",
            options = HiltechRequestOptions(installationId = installationId),
            decode = {
                json.decodeFromString(ListSerializer(PoWarehouseIntakeDto.serializer()), it)
            },
        )

    suspend fun start(poId: String, operationId: String, installationId: String):
        ReceivingRecordResultDto {
        val command = StartPoWarehouseIntakeDto(uuid(operationId))
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/procurement/purchase-orders/" + uuid(poId) + "/warehouse-po-intake",
            options = HiltechRequestOptions(installationId = installationId,
                idempotencyKey = command.operationId),
            requestBody = json.encodeToString(StartPoWarehouseIntakeDto.serializer(), command),
            decode = { json.decodeFromString(ReceivingRecordResultDto.serializer(), it) },
        )
    }

    private fun uuid(v: String): String {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(v))
        return v
    }
}
