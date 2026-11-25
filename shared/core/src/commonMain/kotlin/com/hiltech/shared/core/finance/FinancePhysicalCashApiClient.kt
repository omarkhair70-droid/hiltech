package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FinanceCashboxOpeningPrepareDto(
 val operationId:String,val cashboxCode:String?=null,val currency:String,
 val custodianUserId:String,val companyCustodyReference:String,
 val expectedCustodianVersion:Long?=null,val expectedReviewVersion:Long?=null,
)
@Serializable
data class FinanceCashboxOperationDto(val operationId:String)
@Serializable
data class FinanceCashboxReceiptDto(
 val proposalId:String,val organizationId:String,val cashboxCode:String,
 val currency:String,val custodianUserId:String,val state:String,
 val approvalRequestId:String?=null,val replayed:Boolean=false,
 val cashboxId:String?=null,val openingBalance:String="0.000000",
 val custodianDisplayName:String="",val custodianEmployeeCode:String="",
 val companyCustodyReference:String="",val createdAt:String?=null,val sourceVersion:Long?=null,
 val codeSource:String="MANUAL",val canRequestReview:Boolean=false,val canActivate:Boolean=false,
 val sourceIssueCode:String?=null,val confirmedOperationId:String?=null,
)
@Serializable
data class FinanceCashboxCustodianChoiceDto(
 val identityId:String,val employeeId:String,val employeeCode:String,val displayName:String,val sourceVersion:Long,
)
@Serializable
data class FinanceCashboxCustodianDirectoryDto(
 val organizationId:String,val items:List<FinanceCashboxCustodianChoiceDto>,val hasMore:Boolean,
 val reviewSourceVersion:Long?,val issueCode:String?,
)
@Serializable
data class FinanceCashboxOpeningWorkbenchDto(
 val organizationId:String,val canPrepare:Boolean,val canReadCompanyHistory:Boolean,
 val reviewReady:Boolean,val reviewSourceVersion:Long?,val preparationIssueCode:String?,
 val items:List<FinanceCashboxReceiptDto>,val hasMore:Boolean,
)
@Serializable
enum class FinanceCashboxOpeningAction { PREPARE, REQUEST_REVIEW, ACTIVATE }
@Serializable
data class FinanceCashboxOpeningIntent(
 val operationId:String,val organizationId:String,val action:FinanceCashboxOpeningAction,
 val prepare:FinanceCashboxOpeningPrepareDto?=null,val source:FinanceCashboxReceiptDto?=null,
 val custodianDisplayName:String="",val custodianEmployeeCode:String="",
)

fun validOpeningReceipt(value:FinanceCashboxReceiptDto,organizationId:String):Boolean =
 value.organizationId==organizationId && financeLookupIdIsUuid(value.proposalId) && financeLookupIdIsUuid(value.custodianUserId) &&
 value.cashboxCode.length in 3..64 && value.currency.matches(Regex("^[A-Z]{3}$")) &&
 value.openingBalance.matches(Regex("^0(\\.0+)?$")) &&
 value.state in setOf("PROPOSED","PENDING","APPROVED","ACTIVE","REJECTED","CHANGE_REQUESTED","SUPERSEDED","CANCELLED","REVIEW_REQUIRED") &&
 (value.approvalRequestId==null || financeLookupIdIsUuid(value.approvalRequestId)) &&
 (value.cashboxId==null || value.cashboxId==value.proposalId)

/** Display names and current action availability may change without changing the pinned financial source. */
fun sameCashboxOpeningSource(value:FinanceCashboxReceiptDto,expected:FinanceCashboxReceiptDto):Boolean =
 validOpeningReceipt(value,expected.organizationId) && value.proposalId==expected.proposalId && value.cashboxCode==expected.cashboxCode &&
 value.currency==expected.currency && value.custodianUserId==expected.custodianUserId &&
 (expected.sourceVersion==null || value.sourceVersion==expected.sourceVersion) &&
 (expected.companyCustodyReference.isBlank() || value.companyCustodyReference==expected.companyCustodyReference)
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
   onCommandSending:()->Unit={},
 ):FinanceCashboxReceiptDto {
   val op=uuid(req.operationId)
   uuid(req.custodianUserId)
   require(req.currency.matches(Regex("^[A-Z]{3}$")))
   require(req.cashboxCode==null || req.cashboxCode.trim().length in 3..64)
   require(req.companyCustodyReference.trim().length in 8..180)
   require(req.cashboxCode!=null || ((req.expectedCustodianVersion ?: 0)>0 && (req.expectedReviewVersion ?: 0)>0))
   val path=base(org)+"/cashboxes/prepare-opening"
   val body=json.encodeToString(FinanceCashboxOpeningPrepareDto.serializer(),req)
   onCommandSending()
   return api.request(
     method=HttpMethod.Post,
     path=path,
     options=options(installation,op),
     requestBody=body,
     decode={json.decodeFromString(
       FinanceCashboxReceiptDto.serializer(),it)},
   )
 }
 suspend fun reviewCashbox(
   org:String,box:String,operation:String,installation:String,
   onCommandSending:()->Unit={},
 ):FinanceCashboxReceiptDto {
   val op=uuid(operation)
   val path=base(org)+"/cashboxes/"+uuid(box)+"/request-opening-review"
   onCommandSending()
   return api.request(
     method=HttpMethod.Post,
     path=path,
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
   onCommandSending:()->Unit={},
 ):FinanceCashboxReceiptDto {
   val op=uuid(operation)
   val path=base(org)+"/cashboxes/"+uuid(box)+"/activate"
   onCommandSending()
   return api.request(
     method=HttpMethod.Post,
     path=path,
     options=options(installation,op),
     requestBody=json.encodeToString(
       FinanceCashboxOperationDto.serializer(),
       FinanceCashboxOperationDto(op)),
     decode={json.decodeFromString(
       FinanceCashboxReceiptDto.serializer(),it)},
   )
 }
 suspend fun openingQueue(org:String,installation:String):List<FinanceCashboxReceiptDto> =
   api.request(
     method=HttpMethod.Get,path=base(org)+"/cashboxes/opening-queue",
     options=HiltechRequestOptions(installationId=installation),
     decode={json.decodeFromString(ListSerializer(FinanceCashboxReceiptDto.serializer()),it)},
   )
 suspend fun openingWorkbench(org:String,installation:String,query:String=""):FinanceCashboxOpeningWorkbenchDto {
   require(query.length<=120)
   return api.request(HttpMethod.Get,base(org)+"/cashboxes/opening-workbench?query="+query.encodeURLParameter(),
     HiltechRequestOptions(installationId=installation),
     decode={json.decodeFromString(FinanceCashboxOpeningWorkbenchDto.serializer(),it)}).also { value ->
       check(value.organizationId==org && (value.canPrepare || value.canReadCompanyHistory) &&
         value.items.all { validOpeningReceipt(it,org) }) { "FINANCE_CASHBOX_SOURCE_INVALID" }
     }
 }
 suspend fun custodianChoices(org:String,installation:String,query:String=""):FinanceCashboxCustodianDirectoryDto {
   require(query.length<=120)
   return api.request(HttpMethod.Get,base(org)+"/cashboxes/custodian-choices?query="+query.encodeURLParameter(),
     HiltechRequestOptions(installationId=installation),
     decode={json.decodeFromString(FinanceCashboxCustodianDirectoryDto.serializer(),it)}).also { value ->
       check(value.organizationId==org && value.items.all { financeLookupIdIsUuid(it.identityId) &&
         financeLookupIdIsUuid(it.employeeId) && it.displayName.isNotBlank() && it.employeeCode.isNotBlank() && it.sourceVersion>0 }) {
         "FINANCE_CASHBOX_CUSTODIAN_SOURCE_INVALID"
       }
     }
 }
 suspend fun submitOpeningIntent(intent:FinanceCashboxOpeningIntent,installation:String,onCommandSending:()->Unit={}):FinanceCashboxReceiptDto {
   uuid(intent.operationId);uuid(intent.organizationId)
   val result=when(intent.action) {
     FinanceCashboxOpeningAction.PREPARE -> {
       val request=requireNotNull(intent.prepare)
       require(intent.source==null && request.operationId==intent.operationId)
       prepareCashbox(intent.organizationId,request,installation,onCommandSending).also {
         check(validOpeningReceipt(it,intent.organizationId) && it.currency==request.currency && it.custodianUserId==request.custodianUserId &&
           it.companyCustodyReference==request.companyCustodyReference && (if(request.cashboxCode==null)
             it.codeSource=="AUTOMATIC" && it.cashboxCode.matches(Regex("^CBX-[0-9]{6,19}$")) else it.cashboxCode==request.cashboxCode)) {
           "FINANCE_CASHBOX_PREPARATION_UNCONFIRMED"
         }
       }
     }
     FinanceCashboxOpeningAction.REQUEST_REVIEW,FinanceCashboxOpeningAction.ACTIVATE -> {
       val expected=requireNotNull(intent.source)
       require(intent.prepare==null && validOpeningReceipt(expected,intent.organizationId))
       val before=cashboxStatus(intent.organizationId,expected.proposalId,installation)
       check(sameCashboxOpeningSource(before,expected)) { "FINANCE_CASHBOX_SOURCE_CHANGED" }
       if(intent.action==FinanceCashboxOpeningAction.REQUEST_REVIEW) {
         require(expected.state=="PROPOSED" && before.state in setOf("PROPOSED","PENDING"))
         reviewCashbox(intent.organizationId,expected.proposalId,intent.operationId,installation,onCommandSending).also {
           check(sameCashboxOpeningSource(it,expected) && it.state=="PENDING" && financeLookupIdIsUuid(it.approvalRequestId.orEmpty())) {
             "FINANCE_CASHBOX_REVIEW_UNCONFIRMED"
           }
         }
       } else {
         require(expected.state=="APPROVED" && before.state in setOf("APPROVED","ACTIVE") && before.approvalRequestId==expected.approvalRequestId)
         activateCashbox(intent.organizationId,expected.proposalId,intent.operationId,installation,onCommandSending).also {
           check(sameCashboxOpeningSource(it,expected) && it.state=="ACTIVE" && it.cashboxId==expected.proposalId &&
             it.approvalRequestId==expected.approvalRequestId) { "FINANCE_CASHBOX_ACTIVATION_UNCONFIRMED" }
         }
       }
     }
   }
   check(result.confirmedOperationId==intent.operationId) { "FINANCE_CASHBOX_OPERATION_UNCONFIRMED" }
   return result
 }
 suspend fun cashboxStatus(org:String,proposal:String,installation:String):FinanceCashboxReceiptDto =
   api.request(
     method=HttpMethod.Get,path=base(org)+"/cashboxes/"+uuid(proposal),
     options=HiltechRequestOptions(installationId=installation),
     decode={json.decodeFromString(FinanceCashboxReceiptDto.serializer(),it)},
   )
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

 suspend fun operatorQueue(org:String,installation:String):List<FinanceCashVoucherReceiptDto> =
    api.request(method=HttpMethod.Get,path=base(org)+"/cash-vouchers/operator-queue",
      options=HiltechRequestOptions(installationId=installation),
      decode={json.decodeFromString(ListSerializer(FinanceCashVoucherReceiptDto.serializer()),it)})
 suspend fun operatorStatus(org:String,id:String,installation:String):FinanceCashVoucherReceiptDto =
    api.request(method=HttpMethod.Get,path=base(org)+"/cash-vouchers/"+uuid(id)+"/operator-status",
      options=HiltechRequestOptions(installationId=installation),
      decode={json.decodeFromString(FinanceCashVoucherReceiptDto.serializer(),it)})
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
