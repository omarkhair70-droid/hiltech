package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FinanceCashboxOpeningPrepareDto(
 val operationId:String,val cashboxCode:String,val currency:String,
 val custodianUserId:String,val companyCustodyReference:String,
)
@Serializable
data class FinanceCashboxOperationDto(val operationId:String)
@Serializable
data class FinanceCashboxReceiptDto(
 val proposalId:String,val organizationId:String,val cashboxCode:String,
 val currency:String,val custodianUserId:String,val state:String,
 val approvalRequestId:String?=null,val replayed:Boolean=false,
 val cashboxId:String?=null,val openingBalance:String="0.000000",
)
@Serializable
data class FinanceCashboxBalanceDto(
 val cashboxId:String,val organizationId:String,val code:String,
 val currency:String,val custodianUserId:String,val cashOnHand:String,
 val voucherCount:Long,val physicalCountReconciled:Boolean=false,
)
@Serializable
data class FinanceCashVoucherPrepareDto(
 val operationId:String,val cashboxId:String,val amount:String,
 val voucherReference:String,val reasonReference:String,
)
@Serializable
data class FinanceCashVoucherOperationDto(val operationId:String)
@Serializable
data class FinanceCashVoucherRecordDto(
 val operationId:String,val signedCustodyEvidenceId:String,
)
@Serializable
data class FinanceCashVoucherReceiptDto(
 val proposalId:String,val organizationId:String,val cashboxId:String,
 val sourceKind:String,val sourceJournalId:String,val direction:String,
 val amount:String,val currency:String,val state:String,
 val approvalRequestId:String?=null,val voucherRecordId:String?=null,
 val replayed:Boolean=false,val physicalCustodyConfirmed:Boolean=false,
 val bankTransactionInitiated:Boolean=false,
 val signedCashboxCountReconciled:Boolean=false,
)

/** Shared desktop/Android source-bound physical-cash transport, not bank transfer. */
class FinancePhysicalCashApiClient(
 private val api:HiltechApiClient,
 private val json:Json=Json{ignoreUnknownKeys=true;explicitNulls=false},
) {
 suspend fun prepareCashbox(
   org:String,req:FinanceCashboxOpeningPrepareDto,installation:String,
 ):FinanceCashboxReceiptDto {
   val op=uuid(req.operationId)
   uuid(req.custodianUserId)
   require(req.currency.matches(Regex("^[A-Z]{3}$")))
   require(req.cashboxCode.trim().length in 3..64)
   return api.request(
     method=HttpMethod.Post,
     path=base(org)+"/cashboxes/prepare-opening",
     options=options(installation,op),
     requestBody=json.encodeToString(
       FinanceCashboxOpeningPrepareDto.serializer(),req),
     decode={json.decodeFromString(
       FinanceCashboxReceiptDto.serializer(),it)},
   )
 }
 suspend fun reviewCashbox(
   org:String,box:String,operation:String,installation:String,
 ):FinanceCashboxReceiptDto {
   val op=uuid(operation)
   return api.request(
     method=HttpMethod.Post,
     path=base(org)+"/cashboxes/"+uuid(box)+"/request-opening-review",
     options=options(installation,op),
     requestBody=json.encodeToString(
       FinanceCashboxOperationDto.serializer(),
       FinanceCashboxOperationDto(op)),
     decode={json.decodeFromString(
       FinanceCashboxReceiptDto.serializer(),it)},
   )
 }
 suspend fun activateCashbox(
   org:String,box:String,operation:String,installation:String,
 ):FinanceCashboxReceiptDto {
   val op=uuid(operation)
   return api.request(
     method=HttpMethod.Post,
     path=base(org)+"/cashboxes/"+uuid(box)+"/activate",
     options=options(installation,op),
     requestBody=json.encodeToString(
       FinanceCashboxOperationDto.serializer(),
       FinanceCashboxOperationDto(op)),
     decode={json.decodeFromString(
       FinanceCashboxReceiptDto.serializer(),it)},
   )
 }
 suspend fun balance(
   org:String,box:String,installation:String,
 ):FinanceCashboxBalanceDto=api.request(
     method=HttpMethod.Get,
     path=base(org)+"/cashboxes/"+uuid(box)+"/balance",
     options=HiltechRequestOptions(installationId=installation),
     decode={json.decodeFromString(
       FinanceCashboxBalanceDto.serializer(),it)},
 )
 suspend fun prepareVoucher(
   org:String,kind:String,journal:String,req:FinanceCashVoucherPrepareDto,
   installation:String,
 ):FinanceCashVoucherReceiptDto {
   require(kind=="AP" || kind=="AR")
   val op=uuid(req.operationId)
   uuid(req.cashboxId)
   require(Regex("^[0-9]+(\\.[0-9]{1,6})?$").matches(req.amount) &&
     req.amount.any{it in '1'..'9'})
   return api.request(
     method=HttpMethod.Post,
     path=base(org)+"/cash-vouchers/"+kind+"/"+uuid(journal)+"/prepare",
     options=options(installation,op),
     requestBody=json.encodeToString(
       FinanceCashVoucherPrepareDto.serializer(),req),
     decode={json.decodeFromString(
       FinanceCashVoucherReceiptDto.serializer(),it)},
   )
 }
 suspend fun reviewVoucher(
   org:String,proposal:String,operation:String,installation:String,
 ):FinanceCashVoucherReceiptDto =
   voucherCommand(org,proposal,operation,installation,"request-review",
     null)
 suspend fun recordVoucher(
   org:String,proposal:String,operation:String,evidence:String,
   installation:String,
 ):FinanceCashVoucherReceiptDto =
   voucherCommand(org,proposal,operation,installation,"record",
     uuid(evidence))
 suspend fun voucherStatus(
   org:String,proposal:String,installation:String,
 ):FinanceCashVoucherReceiptDto=api.request(
     method=HttpMethod.Get,
     path=base(org)+"/cash-vouchers/"+uuid(proposal),
     options=HiltechRequestOptions(installationId=installation),
     decode={json.decodeFromString(
       FinanceCashVoucherReceiptDto.serializer(),it)},
 )
 private suspend fun voucherCommand(
   org:String,proposal:String,operation:String,installation:String,
   action:String,evidence:String?,
 ):FinanceCashVoucherReceiptDto {
   require(action in setOf("request-review","record"))
   val op=uuid(operation)
   val body=if(action=="record") {
     requireNotNull(evidence)
     json.encodeToString(FinanceCashVoucherRecordDto.serializer(),
       FinanceCashVoucherRecordDto(op,evidence))
   } else json.encodeToString(FinanceCashVoucherOperationDto.serializer(),
       FinanceCashVoucherOperationDto(op))
   return api.request(
     method=HttpMethod.Post,
     path=base(org)+"/cash-vouchers/"+uuid(proposal)+"/"+action,
     options=options(installation,op),
     requestBody=body,
     decode={json.decodeFromString(
       FinanceCashVoucherReceiptDto.serializer(),it)},
   )
 }
 private fun base(org:String)="/v1/finance/organizations/"+uuid(org)
 private fun options(installation:String,op:String)=HiltechRequestOptions(
   installationId=installation,idempotencyKey=op)
 private fun uuid(raw:String):String {
   require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
      .matches(raw)){"Valid UUID required"}
   return raw
 }
}
