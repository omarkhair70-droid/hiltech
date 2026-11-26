package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class PurchaseOrderDeliveryAmendmentStatusDto(
    val proposalId: String,
    val purchaseOrderId: String,
    val baseVersion: Long,
    val proposedStorageLocationId: String,
    val reason: String,
    val approvalState: String,
    val approvalRequestId: String? = null,
)

@Serializable
data class PurchaseOrderDeliveryAmendmentStatusEnvelopeDto(
    val proposal: PurchaseOrderDeliveryAmendmentStatusDto? = null,
)

@Serializable
data class ProposePurchaseOrderDeliveryAmendmentDto(
    val operationId: String,
    val expectedOrderVersion: Long,
    val proposedStorageLocationId: String,
    val reason: String,
)

@Serializable
data class PurchaseOrderDeliveryAmendmentProposalReceiptDto(
    val proposalId: String,
    val purchaseOrderId: String,
    val expectedOrderVersion: Long,
    val proposedStorageLocationId: String,
    val state: String,
    val replayed: Boolean,
)

@Serializable
data class RequestPurchaseOrderDeliveryAmendmentApprovalDto(val operationId: String)

@Serializable
data class PurchaseOrderDeliveryAmendmentApprovalReceiptDto(
    val proposalId: String,
    val approvalRequestId: String? = null,
    val policyKey: String,
    val policyVersion: Int,
    val approvalState: String? = null,
    val approvalRequired: Boolean,
    val replayed: Boolean,
    val orderChanged: Boolean = false,
)

@Serializable
data class ApplyPurchaseOrderDeliveryAmendmentDto(
    val operationId: String,
    val expectedOrderVersion: Long,
)

@Serializable
data class PurchaseOrderDeliveryAmendmentAppliedReceiptDto(
    val purchaseOrderId: String,
    val code: String,
    val eventId: String,
    val orderVersion: Long,
    val effectiveDestinationId: String,
    val approvalRequestId: String,
    val state: String,
    val supplierNotified: Boolean = false,
    val financeChanged: Boolean = false,
    val inventoryChanged: Boolean = false,
    val replayed: Boolean,
)

/** Online-only API; the server owns every permission and version transition. */
class PurchaseOrderDeliveryAmendmentApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun destinations(
        orderId: String,
        installationId: String,
    ): List<PurchaseOrderDeliveryDestinationDto> = api.request(
        method = HttpMethod.Get,
        path = "/v1/procurement/purchase-orders/" + uuid(orderId) + "/amendment-destinations",
        options = HiltechRequestOptions(installationId = installationId),
        decode = {
            json.decodeFromString(ListSerializer(PurchaseOrderDeliveryDestinationDto.serializer()), it)
        },
    )

    suspend fun current(
        orderId: String,
        installationId: String,
    ): PurchaseOrderDeliveryAmendmentStatusDto? = api.request(
        method = HttpMethod.Get,
        path = "/v1/procurement/purchase-orders/" + uuid(orderId) + "/current-delivery-amendment",
        options = HiltechRequestOptions(installationId = installationId),
        decode = {
            json.decodeFromString(PurchaseOrderDeliveryAmendmentStatusEnvelopeDto.serializer(), it)
                .proposal
        },
    )

    suspend fun propose(
        orderId: String,
        request: ProposePurchaseOrderDeliveryAmendmentDto,
        installationId: String,
    ): PurchaseOrderDeliveryAmendmentProposalReceiptDto = api.request(
        method = HttpMethod.Post,
        path = "/v1/procurement/purchase-orders/" + uuid(orderId) + "/delivery-amendments",
        options = HiltechRequestOptions(
            installationId = installationId, idempotencyKey = uuid(request.operationId),
        ),
        requestBody = json.encodeToString(
            ProposePurchaseOrderDeliveryAmendmentDto.serializer(), request,
        ),
        decode = { json.decodeFromString(
            PurchaseOrderDeliveryAmendmentProposalReceiptDto.serializer(), it,
        ) },
    )

    suspend fun requestApproval(
        proposalId: String,
        request: RequestPurchaseOrderDeliveryAmendmentApprovalDto,
        installationId: String,
    ): PurchaseOrderDeliveryAmendmentApprovalReceiptDto = api.request(
        method = HttpMethod.Post,
        path = "/v1/procurement/po-delivery-amendments/" + uuid(proposalId) +
            "/request-approval",
        options = HiltechRequestOptions(
            installationId = installationId, idempotencyKey = uuid(request.operationId),
        ),
        requestBody = json.encodeToString(
            RequestPurchaseOrderDeliveryAmendmentApprovalDto.serializer(), request,
        ),
        decode = { json.decodeFromString(
            PurchaseOrderDeliveryAmendmentApprovalReceiptDto.serializer(), it,
        ) },
    )

    suspend fun apply(
        proposalId: String,
        request: ApplyPurchaseOrderDeliveryAmendmentDto,
        installationId: String,
    ): PurchaseOrderDeliveryAmendmentAppliedReceiptDto = api.request(
        method = HttpMethod.Post,
        path = "/v1/procurement/po-delivery-amendments/" + uuid(proposalId) + "/apply",
        options = HiltechRequestOptions(
            installationId = installationId, idempotencyKey = uuid(request.operationId),
        ),
        requestBody = json.encodeToString(
            ApplyPurchaseOrderDeliveryAmendmentDto.serializer(), request,
        ),
        decode = { json.decodeFromString(
            PurchaseOrderDeliveryAmendmentAppliedReceiptDto.serializer(), it,
        ) },
    )

    private fun uuid(id: String): String {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(id)) { "A valid UUID is required." }
        return id
    }
}
