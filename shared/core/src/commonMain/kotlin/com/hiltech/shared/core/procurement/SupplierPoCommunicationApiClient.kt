package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class AttestSupplierPoCommunicationDto(
    val operationId: String,
    val expectedOrderVersion: Long,
    val eventType: String,
    val channel: String,
    val externalReference: String,
    val occurredAt: String,
)

@Serializable
data class SupplierPoCommunicationDto(
    val eventId: String,
    val purchaseOrderId: String,
    val eventType: String,
    val eventVersion: Long,
    val channel: String,
    val externalReference: String,
    val occurredAt: String,
    val recordedBy: String,
    val replayed: Boolean = false,
    val sentByThisApi: Boolean = false,
)

/** Online-only audit of externally verified supplier communication. Never sends email. */
class SupplierPoCommunicationApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun history(poId: String, installationId: String): List<SupplierPoCommunicationDto> =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/procurement/purchase-orders/" + uuid(poId) + "/supplier-communications",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(SupplierPoCommunicationDto.serializer()), it) },
        )

    suspend fun attest(
        poId: String,
        req: AttestSupplierPoCommunicationDto,
        installationId: String,
    ): SupplierPoCommunicationDto = api.request(
        method = HttpMethod.Post,
        path = "/v1/procurement/purchase-orders/" + uuid(poId) + "/supplier-communications",
        options = HiltechRequestOptions(
            installationId = installationId,
            idempotencyKey = uuid(req.operationId),
        ),
        requestBody = json.encodeToString(AttestSupplierPoCommunicationDto.serializer(), req),
        decode = { json.decodeFromString(SupplierPoCommunicationDto.serializer(), it) },
    )

    private fun uuid(v: String): String {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(v))
        return v
    }
}
