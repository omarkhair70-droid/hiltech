package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.evidence.EvidenceApiClient
import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import com.hiltech.shared.core.evidence.FinalizeEvidenceRequestDto
import com.hiltech.shared.core.evidence.ReserveEvidenceUploadRequestDto
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class CreateServiceOrderCandidateDto(
    val operationId:String,val rfqId:String,val quoteId:String,
)
@Serializable
data class ServiceOrderCandidateDto(
    val id:String,val organizationId:String,val rfqId:String,val quoteId:String,
    val supplierOrganizationId:String,val currency:String,val totalAmount:String,
    val sourceCurrent:Boolean,val approvalRequestId:String?=null,
    val approvalState:String?=null,val serviceOrderId:String?=null,
)
@Serializable
data class ServiceOrderCandidateReceiptDto(
    val candidate:ServiceOrderCandidateDto,val replayed:Boolean,
)
@Serializable
data class ServiceOrderApprovalRequestDto(val operationId:String)
@Serializable
data class ServiceOrderApprovalReceiptDto(
    val approvalRequired:Boolean,val approvalRequestId:String?=null,
    val state:String?=null,val policyKey:String,val policyVersion:Int,
    val authorityKey:String?=null,val replayed:Boolean,
)
@Serializable
data class IssueServiceOrderDto(val operationId:String,val expectedCandidateVersion:Long)
@Serializable
data class IssuedServiceOrderDto(
    val orderId:String,val candidateId:String,val organizationId:String,
    val supplierOrganizationId:String,val approvalRequestId:String,val quoteId:String,
    val totalAmount:String,val currency:String,
    val supplierDispatchState:String="NOT_SENT",val serviceAccepted:Boolean=false,
    val invoiceMatched:Boolean=false,val paymentAuthorized:Boolean=false,
    val replayed:Boolean=false,
)
@Serializable
data class ServiceWorkOrderDto(
    val orderId:String,val supplierOrganizationId:String,
    val accepted:Boolean,val invoiceCaptured:Boolean,
)
@Serializable
data class AttestServiceSupplierCommunicationDto(
    val operationId:String,val eventType:String,val channel:String,
    val externalReference:String,val occurredAt:String,
)
@Serializable
data class ServiceSupplierCommunicationDto(
    val id:String,val serviceOrderId:String,val eventVersion:Long,
    val eventType:String,val channel:String,val externalReference:String,
    val occurredAt:String,val recordedBy:String,
    val replayed:Boolean=false,val sentByThisApi:Boolean=false,
)
@Serializable
data class CreateServicePurchaseDocumentSlotDto(
    val operationId:String,val documentKind:String,
)
@Serializable
data class ServicePurchaseDocumentSlotDto(
    val slotId:String,val orderId:String,val documentKind:String,val replayed:Boolean,
)
@Serializable
data class RecordServiceDeliveryDto(
    val operationId:String,val documentSlotId:String,val evidenceId:String,
    val evidenceSha256:String,val outcome:String,val reviewNotes:String,
)
@Serializable
data class ServiceDeliveryReviewDto(
    val id:String,val serviceOrderId:String,val outcome:String,val evidenceId:String,
    val evidenceSha256:String,val reviewNotes:String,val replayed:Boolean=false,
)
@Serializable
data class CaptureServiceInvoiceDto(
    val operationId:String,val documentSlotId:String,val evidenceId:String,
    val evidenceSha256:String,val invoiceNumber:String,val invoiceDate:String,
    val currency:String,val serviceAmount:String,val taxAmount:String,
)
@Serializable
data class ServiceInvoiceDto(
    val invoiceId:String,val serviceOrderId:String,val invoiceNumber:String,
    val supplierOrganizationId:String,val invoiceDate:String,val currency:String,
    val serviceAmount:String,val taxAmount:String,val totalAmount:String,
    val evidenceId:String,val replayed:Boolean=false,
)
@Serializable
data class ServiceInvoiceMatchDto(
    val invoiceId:String,val serviceOrderId:String,val resultCode:String,
    val sourceSha256:String,val expectedCurrency:String,val invoicedCurrency:String,
    val expectedTotal:String,val invoicedTotal:String,
    val acceptedEvidenceId:String?=null,val matchEventId:String?=null,
    val replayed:Boolean=false,val paymentAuthorized:Boolean=false,
)
@Serializable
data class RecordServiceInvoiceMatchDto(
    val operationId:String,val expectedSourceSha256:String,
)
@Serializable
data class HandOffServiceInvoiceDto(
    val operationId:String,val matchEventId:String,
)
@Serializable
data class ServiceFinanceHandoffDto(
    val handoffId:String,val invoiceId:String,val matchEventId:String,
    val reviewOnly:Boolean=true,val payableCreated:Boolean=false,
    val paymentAuthorized:Boolean=false,val replayed:Boolean=false,
)

/**
 * Actual shared Android/Windows client. File storage uses the EXISTING secure
 * Evidence lifecycle; an app may provide its platform file-picker bytes and
 * SHA256, never pass an arbitrary URL or a fake "file present" checkbox.
 */
class ServiceOrderAndInvoiceApiClient(
    private val api:HiltechApiClient,
    private val evidence:EvidenceApiClient,
    private val json:Json=Json {ignoreUnknownKeys=true;explicitNulls=false},
) {
    suspend fun selectQuote(req:CreateServiceOrderCandidateDto,installationId:String):
        ServiceOrderCandidateReceiptDto = post(
        "/v1/service-procurement/order-candidates",req.operationId,
        json.encodeToString(CreateServiceOrderCandidateDto.serializer(),req),
        installationId,ServiceOrderCandidateReceiptDto.serializer())

    suspend fun candidate(id:String,install:String):ServiceOrderCandidateDto =
        get("/v1/service-procurement/order-candidates/"+uuid(id),install,
            ServiceOrderCandidateDto.serializer())

    suspend fun queue(org:String,install:String):List<ServiceOrderCandidateDto> =
        get("/v1/service-procurement/organizations/"+uuid(org)+"/order-queue",install,
            ListSerializer(ServiceOrderCandidateDto.serializer()))

    suspend fun requestApproval(id:String,req:ServiceOrderApprovalRequestDto,
                                install:String):ServiceOrderApprovalReceiptDto=post(
        "/v1/service-procurement/order-candidates/"+uuid(id)+"/request-approval",
        req.operationId,json.encodeToString(ServiceOrderApprovalRequestDto.serializer(),req),
        install,ServiceOrderApprovalReceiptDto.serializer())

    suspend fun issue(id:String,req:IssueServiceOrderDto,install:String):IssuedServiceOrderDto=post(
        "/v1/service-procurement/order-candidates/"+uuid(id)+"/issue",
        req.operationId,json.encodeToString(IssueServiceOrderDto.serializer(),req),
        install,IssuedServiceOrderDto.serializer())

    suspend fun order(id:String,install:String):IssuedServiceOrderDto =
        get("/v1/service-procurement/orders/"+uuid(id),install,
            IssuedServiceOrderDto.serializer())

    suspend fun serviceSupplierCommunications(orderId:String,install:String):
        List<ServiceSupplierCommunicationDto> =
        get("/v1/service-procurement/orders/"+uuid(orderId)+"/supplier-communications",
            install,ListSerializer(ServiceSupplierCommunicationDto.serializer()))

    suspend fun attestServiceSupplierCommunication(
        orderId:String,input:AttestServiceSupplierCommunicationDto,install:String
    ):ServiceSupplierCommunicationDto=post(
        "/v1/service-procurement/orders/"+uuid(orderId)+"/supplier-communications",
        input.operationId,
        json.encodeToString(AttestServiceSupplierCommunicationDto.serializer(),input),
        install,ServiceSupplierCommunicationDto.serializer())

    suspend fun deliveryOrders(org:String,install:String):List<ServiceWorkOrderDto> =
        get("/v1/service-procurement/organizations/"+uuid(org)+"/delivery-orders",
            install,ListSerializer(ServiceWorkOrderDto.serializer()))

    suspend fun invoiceOrders(org:String,install:String):List<ServiceWorkOrderDto> =
        get("/v1/service-procurement/organizations/"+uuid(org)+"/invoice-orders",
            install,ListSerializer(ServiceWorkOrderDto.serializer()))

    suspend fun documentSlot(orderId:String,req:CreateServicePurchaseDocumentSlotDto,
                             install:String):ServicePurchaseDocumentSlotDto=post(
        "/v1/service-procurement/orders/"+uuid(orderId)+"/document-slots",
        req.operationId,
        json.encodeToString(CreateServicePurchaseDocumentSlotDto.serializer(),req),
        install,ServicePurchaseDocumentSlotDto.serializer())

    /** Returns verified finalized canonical Evidence metadata, not a raw URL. */
    suspend fun uploadDocument(
        slot:ServicePurchaseDocumentSlotDto,
        install:String,contentType:String,originalFileName:String,
        bytes:ByteArray,expectedSha256:String,capturedAt:String,
        reserveOperationId:String,finalizeOperationId:String,
    ):EvidenceMetadataDto {
        val kind=when(slot.documentKind) {
            "DELIVERY" -> "SERVICE_DELIVERY"
            "INVOICE" -> "SERVICE_INVOICE"
            else -> throw IllegalArgumentException("Unsupported service document kind")
        }
        require(Regex("^[a-f0-9]{64}$").matches(expectedSha256))
        require(bytes.isNotEmpty())
        val reservation=evidence.reserve(
            ReserveEvidenceUploadRequestDto(
                operationId=uuid(reserveOperationId),
                targetType="SERVICE_PURCHASE_DOCUMENT",targetId=uuid(slot.slotId),
                evidenceRequirementKey=kind+"_BINARY",evidenceTypeCode=kind,
                contentType=contentType,originalFileName=originalFileName,
                sizeBytes=bytes.size.toLong(),expectedSha256=expectedSha256,
                capturedAt=capturedAt,
            ),install,
        )
        evidence.uploadBytes(reservation.upload,contentType,bytes)
        return evidence.finalize(
            reservation.evidence.evidenceId,
            FinalizeEvidenceRequestDto(
                operationId=uuid(finalizeOperationId),
                uploadSessionId=reservation.evidence.uploadSessionId,
                expectedSha256=expectedSha256,expectedSizeBytes=bytes.size.toLong(),
            ),install,
        ).evidence
    }

    suspend fun reviewDelivery(order:String,req:RecordServiceDeliveryDto,
                               install:String):ServiceDeliveryReviewDto=post(
        "/v1/service-procurement/orders/"+uuid(order)+"/delivery-reviews",req.operationId,
        json.encodeToString(RecordServiceDeliveryDto.serializer(),req),
        install,ServiceDeliveryReviewDto.serializer())

    suspend fun captureInvoice(order:String,req:CaptureServiceInvoiceDto,
                               install:String):ServiceInvoiceDto=post(
        "/v1/service-procurement/orders/"+uuid(order)+"/invoices",req.operationId,
        json.encodeToString(CaptureServiceInvoiceDto.serializer(),req),
        install,ServiceInvoiceDto.serializer())

    suspend fun invoices(org:String,install:String):List<ServiceInvoiceDto> =
        get("/v1/service-procurement/organizations/"+uuid(org)+"/invoices",
            install,ListSerializer(ServiceInvoiceDto.serializer()))

    suspend fun invoice(id:String,install:String):ServiceInvoiceDto =
        get("/v1/service-procurement/invoices/"+uuid(id),install,
            ServiceInvoiceDto.serializer())

    suspend fun preview(invoiceId:String,install:String):ServiceInvoiceMatchDto =
        get("/v1/service-procurement/invoices/"+uuid(invoiceId)+"/match-preview",
            install,ServiceInvoiceMatchDto.serializer())

    suspend fun recordMatch(invoiceId:String,req:RecordServiceInvoiceMatchDto,
                            install:String):ServiceInvoiceMatchDto=post(
        "/v1/service-procurement/invoices/"+uuid(invoiceId)+"/match",
        req.operationId,
        json.encodeToString(RecordServiceInvoiceMatchDto.serializer(),req),
        install,ServiceInvoiceMatchDto.serializer())

    suspend fun financeReviewHandoff(invoiceId:String,req:HandOffServiceInvoiceDto,
                                     install:String):ServiceFinanceHandoffDto=post(
        "/v1/service-procurement/invoices/"+uuid(invoiceId)+"/finance-review-handoff",
        req.operationId,json.encodeToString(HandOffServiceInvoiceDto.serializer(),req),
        install,ServiceFinanceHandoffDto.serializer())

    private suspend fun <T> get(path:String,install:String,s:KSerializer<T>):T =
        api.request(method=HttpMethod.Get,path=path,
            options=HiltechRequestOptions(installationId=install),
            decode={json.decodeFromString(s,it)})

    private suspend fun <T> post(path:String,op:String,body:String,
                                 install:String,s:KSerializer<T>):T =
        api.request(method=HttpMethod.Post,path=path,
            options=HiltechRequestOptions(installationId=install,idempotencyKey=uuid(op)),
            requestBody=body,decode={json.decodeFromString(s,it)})

    private fun uuid(value:String):String {
        require(Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$").matches(value))
        return value
    }
}
