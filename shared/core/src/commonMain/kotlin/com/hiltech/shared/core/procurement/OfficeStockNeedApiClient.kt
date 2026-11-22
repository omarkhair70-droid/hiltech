package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class OfficeTeamOptionDto(
    val teamId:String,val organizationId:String,val code:String,val name:String,
    val version:Long,val canRequest:Boolean,val canReview:Boolean,
)
@Serializable
data class OfficeDestinationOptionDto(
    val locationId:String,val code:String,val name:String,val version:Long,
)
@Serializable
data class OfficeStockItemOptionDto(
    val id:String,val code:String,val name:String,val unitCode:String,val version:Long,
)
@Serializable
data class OfficeStockNeedDto(
    val id:String,val organizationId:String,val teamId:String,val teamName:String,
    val teamVersion:Long,val locationId:String,val locationName:String,
    val stockItemId:String,val stockItemVersion:Long,val requestedQuantity:String,
    val unitCode:String,val justification:String,val state:String,val version:Long,
    val requestedBy:String,val assessmentCode:String,val sourceCurrent:Boolean,
    val canSubmit:Boolean,val canRevise:Boolean,val canWithdraw:Boolean,
    val canTechnicalReview:Boolean,val lastReviewReason:String?=null,
)
@Serializable
data class OfficeStockNeedReceiptDto(val need:OfficeStockNeedDto,val replayed:Boolean)
@Serializable
data class CreateOfficeStockNeedDto(
    val operationId:String,val teamVersion:Long,
    val deliveryLocationId:String,val deliveryLocationVersion:Long,
    val stockItemId:String,val stockItemVersion:Long,
    val quantity:String,val unitCode:String,val justification:String,
)
@Serializable
data class ReviseOfficeStockNeedDto(
    val operationId:String,val expectedVersion:Long,val teamVersion:Long,
    val stockItemVersion:Long,val quantity:String,val unitCode:String,
    val justification:String,
)

/** The company/team/stock source and current authority are server-derived.
 * No client-supplied manager role, duplicate warehouse ledger or fake WorkOrder. */
class OfficeStockNeedApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json { ignoreUnknownKeys=true; explicitNulls=false },
) {
    suspend fun teams(org:String,installationId:String):List<OfficeTeamOptionDto> =
        get("/v1/office-stock/teams?organizationId="+uuid(org),installationId,
            ListSerializer(OfficeTeamOptionDto.serializer()))
    suspend fun destinations(team:String,installationId:String):
        List<OfficeDestinationOptionDto> =
        get("/v1/office-stock/teams/"+uuid(team)+"/destinations",installationId,
            ListSerializer(OfficeDestinationOptionDto.serializer()))
    suspend fun items(team:String,term:String,installationId:String):
        List<OfficeStockItemOptionDto> =
        get("/v1/office-stock/teams/"+uuid(team)+"/items?search="+encode(term),installationId,
            ListSerializer(OfficeStockItemOptionDto.serializer()))
    suspend fun list(team:String,installationId:String):List<OfficeStockNeedDto> =
        get("/v1/office-stock/teams/"+uuid(team)+"/needs",installationId,
            ListSerializer(OfficeStockNeedDto.serializer()))
    suspend fun detail(id:String,installationId:String):OfficeStockNeedDto =
        get("/v1/office-stock/needs/"+uuid(id),installationId,
            OfficeStockNeedDto.serializer())
    suspend fun create(team:String,req:CreateOfficeStockNeedDto,installationId:String):
        OfficeStockNeedReceiptDto = post(
        "/v1/office-stock/teams/"+uuid(team)+"/needs",req.operationId,
        json.encodeToString(CreateOfficeStockNeedDto.serializer(),req),
        installationId,OfficeStockNeedReceiptDto.serializer(),
    )
    suspend fun submit(id:String,req:SubmitPurchaseRequirementDraftDto,installationId:String):
        OfficeStockNeedReceiptDto = post(
        "/v1/office-stock/needs/"+uuid(id)+"/submit",req.operationId,
        json.encodeToString(SubmitPurchaseRequirementDraftDto.serializer(),req),
        installationId,OfficeStockNeedReceiptDto.serializer(),
    )
    suspend fun revise(id:String,req:ReviseOfficeStockNeedDto,installationId:String):
        OfficeStockNeedReceiptDto = post(
        "/v1/office-stock/needs/"+uuid(id)+"/revise",req.operationId,
        json.encodeToString(ReviseOfficeStockNeedDto.serializer(),req),
        installationId,OfficeStockNeedReceiptDto.serializer(),
    )
    suspend fun withdraw(id:String,req:WithdrawPurchaseRequirementDraftDto,installationId:String):
        OfficeStockNeedReceiptDto = post(
        "/v1/office-stock/needs/"+uuid(id)+"/withdraw",req.operationId,
        json.encodeToString(WithdrawPurchaseRequirementDraftDto.serializer(),req),
        installationId,OfficeStockNeedReceiptDto.serializer(),
    )
    suspend fun review(id:String,req:ReviewPurchaseRequirementDto,installationId:String):
        OfficeStockNeedReceiptDto = post(
        "/v1/office-stock/needs/"+uuid(id)+"/technical-review",req.operationId,
        json.encodeToString(ReviewPurchaseRequirementDto.serializer(),req),
        installationId,OfficeStockNeedReceiptDto.serializer(),
    )
    private suspend fun <T> get(path:String,install:String,serializer:KSerializer<T>):T =
        api.request(method=HttpMethod.Get,path=path,
            options=HiltechRequestOptions(installationId=install),
            decode={ json.decodeFromString(serializer,it) })
    private suspend fun <T> post(path:String,op:String,body:String,
                                 install:String,serializer:KSerializer<T>):T =
        api.request(method=HttpMethod.Post,path=path,
            options=HiltechRequestOptions(installationId=install,idempotencyKey=uuid(op)),
            requestBody=body,decode={ json.decodeFromString(serializer,it) })
    private fun uuid(raw:String):String {
        require(Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$").matches(raw))
        return raw
    }
    private fun encode(raw:String):String=raw.take(120).encodeToByteArray()
        .joinToString("") { b ->
            val n=b.toInt() and 0xff
            if((n in 65..90)||(n in 97..122)||(n in 48..57)||n==45||n==95)
                n.toChar().toString()
            else "%"+n.toString(16).uppercase().padStart(2,'0')
        }
}
