package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.administration.PurchaseOrderNumberingDto
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Canonical company-issued order is not supplier dispatch, receipt or payment. */
@Serializable
data class PurchaseOrderIssuerQueueItemDto(
    val candidateId: String,
    val supplierOrganizationId: String,
    val supplierName: String,
    val stockItemId: String,
    val quantity: String,
    val unitCode: String,
    val currency: String,
    val total: String,
    val approvalRequestId: String,
    val approvalPolicyVersion: Int,
)

@Serializable
data class PurchaseOrderDeliveryDestinationDto(
    val storageLocationId: String,
    val code: String,
    val name: String,
    val kind: String,
)

@Serializable
data class IssuePurchaseOrderDto(
    val operationId: String,
    val expectedCandidateVersion: Long,
    val deliveryStorageLocationId: String,
)

@Serializable
data class PurchaseOrderIssueIntent(
    val operationId: String, val organizationId: String, val candidateId: String,
    val deliveryStorageLocationId: String, val destinationName: String, val supplierName: String,
    val expectedCandidateVersion: Long = 1,
)

@Serializable
data class PurchaseOrderIssueReceiptDto(
    val purchaseOrderId: String,
    val code: String,
    val serial: Long,
    val state: String,
    val supplierDispatchState: String,
    val candidateId: String,
    val approvalRequestId: String,
    val total: String,
    val currency: String,
    val replayed: Boolean,
    val stockReceived: Boolean = false,
    val supplierNotified: Boolean = false,
    val paymentAuthorized: Boolean = false,
)

@Serializable
data class PurchaseOrderLifecycleSummaryDto(
    val id: String,
    val code: String,
    val candidateId: String,
    val effectiveState: String,
    val version: Long,
    val supplierDispatchState: String,
    val total: String,
    val currency: String,
)

@Serializable
data class CancelPurchaseOrderDto(
    val operationId: String,
    val expectedVersion: Long,
    val reason: String,
)

@Serializable
data class CancelPurchaseOrderReceiptDto(
    val purchaseOrderId: String,
    val code: String,
    val state: String,
    val eventId: String,
    val eventVersion: Long,
    val replayed: Boolean,
    val supplierNotified: Boolean = false,
    val inventoryChanged: Boolean = false,
    val paymentChanged: Boolean = false,
)

/** Online-only; never buffers commercial issuance in Room or an offline queue. */
class PurchaseOrderIssueApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun numbering(organizationId: String, installationId: String): PurchaseOrderNumberingDto = api.request(
        HttpMethod.Get, "/v1/procurement/organizations/" + uuid(organizationId) + "/po-numbering",
        HiltechRequestOptions(installationId), decode = { json.decodeFromString(PurchaseOrderNumberingDto.serializer(), it) },
    ).also { require(it.organizationId == organizationId && it.version >= 0 &&
        it.source in setOf("COMPANY", "DEPLOYMENT", "NOT_CONFIGURED") && (!it.ready || it.exampleCode != null)) }

    suspend fun issue(intent: PurchaseOrderIssueIntent, installationId: String,
        onCommandSending: () -> Unit = {}): PurchaseOrderIssueReceiptDto {
        uuid(intent.organizationId); uuid(intent.candidateId); uuid(intent.operationId); uuid(intent.deliveryStorageLocationId)
        require(intent.expectedCandidateVersion == 1L)
        onCommandSending()
        return issue(intent.candidateId, IssuePurchaseOrderDto(intent.operationId, intent.expectedCandidateVersion, intent.deliveryStorageLocationId), installationId)
            .also { require(it.candidateId == intent.candidateId && it.state == "ISSUED" && it.code.isNotBlank() && it.serial > 0 &&
                it.supplierDispatchState == "NOT_SENT" && !it.supplierNotified && !it.paymentAuthorized && !it.stockReceived) }
    }

    suspend fun queue(organizationId: String, installationId: String):
        List<PurchaseOrderIssuerQueueItemDto> = api.request(
        method = HttpMethod.Get,
        path = "/v1/procurement/organizations/" + uuid(organizationId) + "/po-issuer-queue",
        options = HiltechRequestOptions(installationId = installationId),
        decode = {
            json.decodeFromString(ListSerializer(PurchaseOrderIssuerQueueItemDto.serializer()), it)
        },
    )

    suspend fun destinations(candidateId: String, installationId: String):
        List<PurchaseOrderDeliveryDestinationDto> = api.request(
        method = HttpMethod.Get,
        path = "/v1/procurement/po-candidates/" + uuid(candidateId) + "/delivery-destinations",
        options = HiltechRequestOptions(installationId = installationId),
        decode = {
            json.decodeFromString(ListSerializer(PurchaseOrderDeliveryDestinationDto.serializer()), it)
        },
    )

    suspend fun issue(
        candidateId: String,
        command: IssuePurchaseOrderDto,
        installationId: String,
    ): PurchaseOrderIssueReceiptDto = api.request(
        method = HttpMethod.Post,
        path = "/v1/procurement/po-candidates/" + uuid(candidateId) + "/issue",
        options = HiltechRequestOptions(
            installationId = installationId, idempotencyKey = uuid(command.operationId),
        ),
        requestBody = json.encodeToString(IssuePurchaseOrderDto.serializer(), command),
        decode = { json.decodeFromString(PurchaseOrderIssueReceiptDto.serializer(), it) },
    )

    suspend fun issuedOrders(
        organizationId: String,
        installationId: String,
    ): List<PurchaseOrderLifecycleSummaryDto> = api.request(
        method = HttpMethod.Get,
        path = "/v1/procurement/organizations/" + uuid(organizationId) + "/issued-pos",
        options = HiltechRequestOptions(installationId = installationId),
        decode = {
            json.decodeFromString(
                ListSerializer(PurchaseOrderLifecycleSummaryDto.serializer()), it,
            )
        },
    )

    suspend fun cancel(
        purchaseOrderId: String,
        request: CancelPurchaseOrderDto,
        installationId: String,
    ): CancelPurchaseOrderReceiptDto = api.request(
        method = HttpMethod.Post,
        path = "/v1/procurement/purchase-orders/" + uuid(purchaseOrderId) + "/cancel",
        options = HiltechRequestOptions(
            installationId = installationId,
            idempotencyKey = uuid(request.operationId),
        ),
        requestBody = json.encodeToString(CancelPurchaseOrderDto.serializer(), request),
        decode = { json.decodeFromString(CancelPurchaseOrderReceiptDto.serializer(), it) },
    )

    private fun uuid(value: String): String {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(value)) { "A valid UUID is required." }
        return value
    }
}
