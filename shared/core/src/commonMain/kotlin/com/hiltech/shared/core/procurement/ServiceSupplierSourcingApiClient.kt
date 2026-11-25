package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class ServiceRfqDto(
    val id:String,val needId:String,val needVersion:Long,val organizationId:String,
    val specification:String,val acceptanceCriteria:String,val sourceCurrent:Boolean,
    val supplierCount:Int,val purchaseAuthorized:Boolean=false,
)
@Serializable
data class ServiceEligibleNeedDto(
    val needId:String,val needVersion:Long,val organizationId:String,
    val specification:String,val acceptanceCriteria:String,
    val rfqId:String?=null,val sourceCurrent:Boolean,
)
@Serializable
data class ServiceSupplierDirectoryDto(
    val organizationId: String,
    val query: String,
    val suppliers: List<SourcingSupplierCandidateDto>,
)
@Serializable
data class ServiceRfqReceiptDto(val rfq:ServiceRfqDto,val replayed:Boolean)
@Serializable
data class OpenServiceRfqDto(val operationId:String,val expectedNeedVersion:Long)
@Serializable
data class CreateServiceQuoteSlotDto(val operationId:String,val supplierOrganizationId:String)
@Serializable
data class ServiceQuoteSlotReceiptDto(
    val slotId:String,val rfqId:String,val supplierOrganizationId:String,val replayed:Boolean,
)
@Serializable
data class RecordServiceQuoteDto(
    val operationId:String,val slotId:String,val expectedRevisionNumber:Int,
    val supplierQuoteReference:String,val evidenceId:String,val expectedSha256:String,
    val currency:String,val serviceAmount:String,val taxAmount:String,
    val serviceTerms:String,val validUntil:String?=null,
)
@Serializable
data class ServiceSupplierQuoteDto(
    val id:String,val rfqId:String,val supplierOrganizationId:String,
    val quoteReference:String,val revision:Int,val currency:String,
    val serviceAmount:String,val taxAmount:String,val totalAmount:String,
    val serviceTerms:String,val validUntil:String?=null,val evidenceId:String,
    val sourceCurrent:Boolean,val supplierAwarded:Boolean=false,
    val paymentAuthorized:Boolean=false,
)
@Serializable
data class ServiceSupplierQuoteReceiptDto(val quote:ServiceSupplierQuoteDto,val replayed:Boolean)

/**
 * Online-only restricted service supplier API. No outbox/offline posting of
 * price or supplier documents, no stock receipt and no authorization in UI.
 */
class ServiceSupplierSourcingApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json {ignoreUnknownKeys=true;explicitNulls=false},
) {
    suspend fun searchSuppliers(
        org: String,
        rawQuery: String,
        install: String,
    ): List<SourcingSupplierCandidateDto> {
        val organizationId = uuid(org)
        val query = rawQuery.trim()
        require(query.length in 2..80 && query.none { it.code < 32 || it.code == 127 })
        val result = get(
            "/v1/service-procurement/organizations/$organizationId/sourcing-suppliers" +
                "?query=" + query.encodeURLParameter(),
            install, ServiceSupplierDirectoryDto.serializer(),
        )
        require(result.organizationId.equals(organizationId, ignoreCase = true) && result.query == query) {
            "Supplier search response does not match the requested organization and query."
        }
        require(result.suppliers.size <= 100 &&
            result.suppliers.map { it.supplierOrganizationId.lowercase() }.distinct().size == result.suppliers.size) {
            "Supplier search response is not a bounded distinct directory."
        }
        result.suppliers.forEach {
            uuid(it.supplierOrganizationId)
            require(it.displayName.isNotBlank() && it.relationshipRevision >= 1)
        }
        return result.suppliers
    }

    suspend fun eligibleNeeds(org:String,install:String):List<ServiceEligibleNeedDto> =
        get("/v1/service-procurement/organizations/"+uuid(org)+"/sourcing-eligible-needs",
            install,ListSerializer(ServiceEligibleNeedDto.serializer()))

    suspend fun rounds(need:String,install:String):List<ServiceRfqDto> =
        get("/v1/service-procurement/needs/"+uuid(need)+"/rfqs",install,
            ListSerializer(ServiceRfqDto.serializer()))

    suspend fun open(need:String,input:OpenServiceRfqDto,install:String):ServiceRfqReceiptDto =
        post("/v1/service-procurement/needs/"+uuid(need)+"/rfqs",
            input.operationId,json.encodeToString(OpenServiceRfqDto.serializer(),input),
            install,ServiceRfqReceiptDto.serializer())

    suspend fun documentSlot(rfq:String,input:CreateServiceQuoteSlotDto,
                             install:String):ServiceQuoteSlotReceiptDto =
        post("/v1/service-procurement/rfqs/"+uuid(rfq)+"/document-slots",
            input.operationId,json.encodeToString(CreateServiceQuoteSlotDto.serializer(),input),
            install,ServiceQuoteSlotReceiptDto.serializer())

    suspend fun verifiedQuote(rfq:String,input:RecordServiceQuoteDto,
                              install:String):ServiceSupplierQuoteReceiptDto =
        post("/v1/service-procurement/rfqs/"+uuid(rfq)+"/verified-quotes",
            input.operationId,json.encodeToString(RecordServiceQuoteDto.serializer(),input),
            install,ServiceSupplierQuoteReceiptDto.serializer())

    suspend fun comparison(rfq:String,install:String):List<ServiceSupplierQuoteDto> =
        get("/v1/service-procurement/rfqs/"+uuid(rfq)+"/comparison",
            install,ListSerializer(ServiceSupplierQuoteDto.serializer()))

    private suspend fun <T> get(path:String,install:String,serializer:KSerializer<T>):T =
        api.request(method=HttpMethod.Get,path=path,
            options=HiltechRequestOptions(installationId=install),
            decode={json.decodeFromString(serializer,it)})

    private suspend fun <T> post(path:String,op:String,body:String,
                                 install:String,serializer:KSerializer<T>):T =
        api.request(method=HttpMethod.Post,path=path,
            options=HiltechRequestOptions(installationId=install,idempotencyKey=uuid(op)),
            requestBody=body,decode={json.decodeFromString(serializer,it)})

    private fun uuid(raw:String):String {
        require(Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$").matches(raw))
        return raw
    }
}
