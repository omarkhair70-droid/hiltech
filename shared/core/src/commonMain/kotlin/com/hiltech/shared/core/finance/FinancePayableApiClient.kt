package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SupplierHandoffFinanceDto(
    val handoffId: String, val invoiceId: String, val purchaseOrderId: String,
    val supplierOrganizationId: String, val invoiceNumber: String,
    val currency: String, val invoiceAmount: String,
    val sourceSha256: String, val eligible: Boolean, val alreadyRecorded: Boolean,
)
@Serializable
data class SupplierPayableCandidateDto(
    val id: String, val handoffId: String, val invoiceId: String,
    val supplierOrganizationId: String, val invoiceNumber: String,
    val amount: String, val currency: String, val sourceSha256: String,
    val state: String, val sourceCurrent: Boolean, val replayed: Boolean = false,
)
@Serializable
data class RecordSupplierPayableCandidateDto(
    val operationId: String, val expectedSourceSha256: String,
)

/** Always online. Financial data must never enter the offline command queue. */
class FinancePayableApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun handoffs(orgId: String, installationId: String):
        List<SupplierHandoffFinanceDto> = api.request(
        method = HttpMethod.Get,
        path = "/v1/finance/organizations/" + uuid(orgId) + "/supplier-handoffs",
        options = HiltechRequestOptions(installationId = installationId),
        decode = {
            json.decodeFromString(ListSerializer(SupplierHandoffFinanceDto.serializer()), it)
        },
    )

    suspend fun candidates(orgId: String, installationId: String):
        List<SupplierPayableCandidateDto> = api.request(
        method = HttpMethod.Get,
        path = "/v1/finance/organizations/" + uuid(orgId) + "/payable-candidates",
        options = HiltechRequestOptions(installationId = installationId),
        decode = {
            json.decodeFromString(ListSerializer(SupplierPayableCandidateDto.serializer()), it)
        },
    )

    suspend fun record(handoffId: String, req: RecordSupplierPayableCandidateDto,
                       installationId: String): SupplierPayableCandidateDto = api.request(
        method = HttpMethod.Post,
        path = "/v1/finance/supplier-handoffs/" + uuid(handoffId) + "/payable-candidates",
        options = HiltechRequestOptions(
            installationId = installationId, idempotencyKey = uuid(req.operationId),
        ),
        requestBody = json.encodeToString(RecordSupplierPayableCandidateDto.serializer(), req),
        decode = { json.decodeFromString(SupplierPayableCandidateDto.serializer(), it) },
    )

    private fun uuid(value: String): String {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(value)) { "Valid UUID required" }
        return value
    }
}
