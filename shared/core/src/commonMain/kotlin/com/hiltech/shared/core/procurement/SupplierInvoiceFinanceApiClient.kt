package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class InvoicablePurchaseOrderDto(
    val purchaseOrderId: String,
    val poCode: String,
    val supplierOrganizationId: String,
    val stockItemId: String,
    val quantity: String,
    val currency: String,
    val unitPrice: String,
    val taxAmount: String,
    val version: Long,
)
@Serializable
data class SupplierInvoiceSlotDto(
    val slotId: String,
    val purchaseOrderId: String,
    val supplierOrganizationId: String,
    val replayed: Boolean,
)
@Serializable
data class CaptureSupplierInvoiceDto(
    val operationId: String, val documentSlotId: String,
    val evidenceId: String, val invoiceNumber: String, val invoiceDate: String,
    val quantity: String, val currency: String,
    val unitPrice: String, val taxAmount: String,
)
@Serializable
data class SupplierInvoiceDto(
    val id: String, val purchaseOrderId: String, val supplierOrganizationId: String,
    val invoiceNumber: String, val quantity: String, val currency: String,
    val unitPrice: String, val taxAmount: String, val totalAmount: String,
    val submittedAt: String, val replayed: Boolean = false,
)
@Serializable
data class SupplierInvoiceMatchDto(
    val invoiceId: String, val purchaseOrderId: String, val sourceSha256: String,
    val resultCode: String, val poQuantity: String, val receivedQuantity: String,
    val invoicedQuantity: String, val quantityVariance: String,
    val priceVariance: String, val taxVariance: String,
    val documentVerified: Boolean = true, val payableCreated: Boolean = false,
    val paymentCreated: Boolean = false, val matchEventId: String? = null, val revision: Long? = null,
    val replayed: Boolean = false,
)
@Serializable
data class SupplierInvoiceHandoffDto(
    val handoffId: String, val invoiceId: String, val matchEventId: String,
    val state: String, val replayed: Boolean, val payableCreated: Boolean = false,
    val paymentCreated: Boolean = false,
)
@Serializable
data class RecordSupplierInvoiceMatchDto(val operationId: String, val expectedSourceSha256: String)
@Serializable
data class SupplierInvoiceHandoffRequestDto(val operationId: String, val matchEventId: String)
@Serializable
data class CreateSupplierInvoiceSlotDto(val operationId: String)

/** Fully online Finance-only commands; no offline invoice writes or payments. */
class SupplierInvoiceFinanceApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun eligibleOrders(
        orgId: String, installationId: String,
    ): List<InvoicablePurchaseOrderDto> = api.request(
        method = HttpMethod.Get,
        path = "/v1/procurement/organizations/" + uuid(orgId) + "/invoicable-pos",
        options = HiltechRequestOptions(installationId = installationId),
        decode = {
            json.decodeFromString(ListSerializer(InvoicablePurchaseOrderDto.serializer()), it)
        },
    )

    suspend fun list(orgId: String, installationId: String): List<SupplierInvoiceDto> = api.request(
        method = HttpMethod.Get,
        path = "/v1/procurement/organizations/" + uuid(orgId) + "/supplier-invoices",
        options = HiltechRequestOptions(installationId = installationId),
        decode = { json.decodeFromString(ListSerializer(SupplierInvoiceDto.serializer()), it) },
    )

    suspend fun createSlot(poId: String, op: String, installationId: String): SupplierInvoiceSlotDto {
        val command = CreateSupplierInvoiceSlotDto(uuid(op))
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/procurement/purchase-orders/" + uuid(poId) + "/invoice-document-slots",
            options = HiltechRequestOptions(installationId = installationId,
                idempotencyKey = command.operationId),
            requestBody = json.encodeToString(CreateSupplierInvoiceSlotDto.serializer(), command),
            decode = { json.decodeFromString(SupplierInvoiceSlotDto.serializer(), it) },
        )
    }

    suspend fun capture(poId: String, req: CaptureSupplierInvoiceDto, installationId: String):
        SupplierInvoiceDto = api.request(
        method = HttpMethod.Post,
        path = "/v1/procurement/purchase-orders/" + uuid(poId) + "/supplier-invoices",
        options = HiltechRequestOptions(installationId = installationId,
            idempotencyKey = uuid(req.operationId)),
        requestBody = json.encodeToString(CaptureSupplierInvoiceDto.serializer(), req),
        decode = { json.decodeFromString(SupplierInvoiceDto.serializer(), it) },
    )

    suspend fun preview(invoiceId: String, installationId: String): SupplierInvoiceMatchDto =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/procurement/supplier-invoices/" + uuid(invoiceId) + "/match-preview",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(SupplierInvoiceMatchDto.serializer(), it) },
        )

    suspend fun recordMatch(invoiceId: String, req: RecordSupplierInvoiceMatchDto,
        installationId: String): SupplierInvoiceMatchDto = api.request(
        method = HttpMethod.Post,
        path = "/v1/procurement/supplier-invoices/" + uuid(invoiceId) + "/matches",
        options = HiltechRequestOptions(installationId = installationId,
            idempotencyKey = uuid(req.operationId)),
        requestBody = json.encodeToString(RecordSupplierInvoiceMatchDto.serializer(), req),
        decode = { json.decodeFromString(SupplierInvoiceMatchDto.serializer(), it) },
    )

    suspend fun handoff(invoiceId: String, req: SupplierInvoiceHandoffRequestDto,
        installationId: String): SupplierInvoiceHandoffDto = api.request(
        method = HttpMethod.Post,
        path = "/v1/procurement/supplier-invoices/" + uuid(invoiceId) + "/finance-handoff",
        options = HiltechRequestOptions(installationId = installationId,
            idempotencyKey = uuid(req.operationId)),
        requestBody = json.encodeToString(SupplierInvoiceHandoffRequestDto.serializer(), req),
        decode = { json.decodeFromString(SupplierInvoiceHandoffDto.serializer(), it) },
    )

    private fun uuid(value: String): String {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(value)) { "A valid UUID is required." }
        return value
    }
}
