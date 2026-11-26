package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FinanceCashCountPrepareDto(
 val operationId:String,val cashboxId:String,val countedAmount:String,
 val countReference:String,
)
@Serializable
data class FinanceCashCountOperationDto(val operationId:String)
@Serializable
data class FinanceCashCountRecordDto(
 val operationId:String,val signedCountEvidenceId:String,
)
@Serializable
data class FinanceCashCountReceiptDto(
 val proposalId:String,val organizationId:String,val cashboxId:String,
 val currency:String,val countedAmount:String,val ledgerAmount:String,
 val varianceAmount:String,val voucherCount:Long,val state:String,
 val approvalRequestId:String?=null,val countRecordId:String?=null,
 val replayed:Boolean=false,val ledgerAdjusted:Boolean=false,
 val bankTransferInitiated:Boolean=false,
)

/** Typed transport for witnessed cash counts; never a command to alter money. */
class FinanceCashCountApiClient(
 private val api:HiltechApiClient,
 private val json:Json=Json{ignoreUnknownKeys=true;explicitNulls=false},
) {
 suspend fun prepare(
   org:String,req:FinanceCashCountPrepareDto,installation:String,
 ):FinanceCashCountReceiptDto {
   val op=uuid(req.operationId)
   uuid(req.cashboxId)
   require(req.countReference.trim().length in 8..180)
   require(req.countedAmount.matches(
     Regex("^[0-9]+(\\.[0-9]{1,6})?$")))
   return api.request(
     method=HttpMethod.Post,path=base(org)+"/cash-counts/prepare",
     options=command(installation,op),
     requestBody=json.encodeToString(FinanceCashCountPrepareDto.serializer(),req),
     decode={json.decodeFromString(FinanceCashCountReceiptDto.serializer(),it)},
   )
 }
 suspend fun requestReview(
   org:String,proposal:String,operation:String,installation:String,
 ):FinanceCashCountReceiptDto=write(
   org,proposal,operation,null,installation,"request-review")
 suspend fun record(
   org:String,proposal:String,operation:String,evidence:String,
   installation:String,
 ):FinanceCashCountReceiptDto=write(
   org,proposal,operation,uuid(evidence),installation,"record")
 suspend fun status(
   org:String,proposal:String,installation:String,
 ):FinanceCashCountReceiptDto=api.request(
   method=HttpMethod.Get,
   path=base(org)+"/cash-counts/"+uuid(proposal),
   options=HiltechRequestOptions(installationId=installation),
   decode={json.decodeFromString(FinanceCashCountReceiptDto.serializer(),it)},
 )

 suspend fun operatorQueue(org:String,installation:String):List<FinanceCashCountReceiptDto> =
    api.request(method=HttpMethod.Get,path=base(org)+"/cash-counts/operator-queue",
      options=HiltechRequestOptions(installationId=installation),
      decode={json.decodeFromString(ListSerializer(FinanceCashCountReceiptDto.serializer()),it)})
 suspend fun operatorStatus(org:String,id:String,installation:String):FinanceCashCountReceiptDto =
    api.request(method=HttpMethod.Get,path=base(org)+"/cash-counts/"+uuid(id)+"/operator-status",
      options=HiltechRequestOptions(installationId=installation),
      decode={json.decodeFromString(FinanceCashCountReceiptDto.serializer(),it)})
 private suspend fun write(
   org:String,proposal:String,operation:String,evidence:String?,
   installation:String,action:String,
 ):FinanceCashCountReceiptDto {
   require(action=="record" || action=="request-review")
   val op=uuid(operation)
   val body=if(action=="record") {
     json.encodeToString(FinanceCashCountRecordDto.serializer(),
       FinanceCashCountRecordDto(op,requireNotNull(evidence)))
   } else {
     json.encodeToString(FinanceCashCountOperationDto.serializer(),
       FinanceCashCountOperationDto(op))
   }
   return api.request(
     method=HttpMethod.Post,
     path=base(org)+"/cash-counts/"+uuid(proposal)+"/"+action,
     options=command(installation,op),
     requestBody=body,
     decode={json.decodeFromString(FinanceCashCountReceiptDto.serializer(),it)},
   )
 }
 private fun base(org:String)="/v1/finance/organizations/"+uuid(org)
 private fun command(installation:String,op:String)=HiltechRequestOptions(
   installationId=installation,idempotencyKey=op)
 private fun uuid(raw:String):String {
   require(Regex(
     "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
   ).matches(raw)){"Valid UUID required"}
   return raw
 }
}
