package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FinanceCashAllocationProposeDto(
    val operationId:String,
    val bankStatementLineId:String,
    val allocatedAmount:String,
    val reasonReference:String,
)
@Serializable
data class FinanceCashAllocationOperationDto(val operationId:String)
@Serializable
data class FinanceCashAllocationReceiptDto(
    val proposalId:String,
    val organizationId:String,
    val sourceKind:String,
    val sourceJournalId:String,
    val bankStatementLineId:String,
    val amount:String,
    val currency:String,
    val state:String,
    val approvalRequestId:String?=null,
    val recordedAllocationId:String?=null,
    val replayed:Boolean=false,
    val cashAllocatedToAccountingSource:Boolean=false,
    val moneyTransferredByHiltech:Boolean=false,
    val bankReconciled:Boolean=false,
)

@Serializable data class FinanceObligationChoiceDto(val id:String,val counterpartyName:String,val currency:String,val outstanding:String,
    val dueDate:String?=null,val openingDocumentReference:String?=null)
@Serializable data class FinanceObligationChoicesDto(val organizationId:String,val kind:String,val channel:String,val offset:Int,
    val records:List<FinanceObligationChoiceDto>,val hasMore:Boolean)

/**
 * KMP transport only. Company-verified bank intake and Finance approvals must
 * exist before its screens are mounted. No bank transfer endpoint is exposed.
 */
class FinanceCashAllocationApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json { ignoreUnknownKeys=true; explicitNulls=false },
) {
    suspend fun obligations(org:String,kind:String,channel:String,offset:Int,installation:String):FinanceObligationChoicesDto {
        require(kind in setOf("AP","AR")&&channel in setOf("BANK","CASH")&&offset in 0..1_000_000)
        return api.request(HttpMethod.Get,base(org)+"/obligation-choices?kind="+kind+"&channel="+channel+"&offset="+offset,
            HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString<FinanceObligationChoicesDto>(it)}).also{page->
                check(page.organizationId.equals(org,true)&&page.kind==kind&&page.channel==channel&&page.offset==offset&&
                    page.records.size<=50&&page.records.map{it.id.lowercase()}.distinct().size==page.records.size&&page.records.all{r->
                        financeLookupIdIsUuid(r.id)&&r.counterpartyName.isNotBlank()&&r.currency.matches(Regex("[A-Z]{3}"))&&
                        settlementCanonicalAmount(r.outstanding)!=null&&(r.dueDate==null||financeValidCalendarDate(r.dueDate))&&
                        (r.openingDocumentReference==null||r.openingDocumentReference.isNotBlank())
                    })
            }
    }
    suspend fun propose(
        org:String,kind:String,sourceJournal:String,bankLine:String,
        operation:String,amount:String,reason:String,installation:String,
    ):FinanceCashAllocationReceiptDto {
        require(kind in setOf("AP","AR"))
        require(reason.trim().length in 8..180)
        require(Regex("^[0-9]+(\\.[0-9]{1,6})?$").matches(amount) &&
            amount.any { it in '1'..'9' })
        val id=uuid(operation)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/posted-journals/"+kind+"/"+uuid(sourceJournal)+
                "/cash-allocation-proposals",
            options=HiltechRequestOptions(
                installationId=installation,idempotencyKey=id,
            ),
            requestBody=json.encodeToString(
                FinanceCashAllocationProposeDto.serializer(),
                FinanceCashAllocationProposeDto(
                    id,uuid(bankLine),amount,reason.trim(),
                ),
            ),
            decode={ json.decodeFromString(
                FinanceCashAllocationReceiptDto.serializer(),it,
            ) },
        )
    }
    suspend fun requestApproval(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceCashAllocationReceiptDto=command(
        org,proposal,operation,installation,"request-approval",
    )
    suspend fun record(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceCashAllocationReceiptDto=command(
        org,proposal,operation,installation,"record",
    )
    suspend fun operatorQueue(org:String,installation:String):List<FinanceCashAllocationReceiptDto> =
        api.request(
            method=HttpMethod.Get,
            path=base(org)+"/cash-allocation-proposals/operator-queue",
            options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(ListSerializer(FinanceCashAllocationReceiptDto.serializer()),it)},
        )
    suspend fun status(
        org:String,proposal:String,installation:String,
    ):FinanceCashAllocationReceiptDto=api.request(
        method=HttpMethod.Get,
        path=base(org)+"/cash-allocation-proposals/"+uuid(proposal),
        options=HiltechRequestOptions(installationId=installation),
        decode={json.decodeFromString(
            FinanceCashAllocationReceiptDto.serializer(),it,
        )},
    )
    private suspend fun command(
        org:String,proposal:String,operation:String,installation:String,
        action:String,
    ):FinanceCashAllocationReceiptDto {
        require(action=="request-approval" || action=="record")
        val id=uuid(operation)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/cash-allocation-proposals/"+uuid(proposal)+"/"+action,
            options=HiltechRequestOptions(
                installationId=installation,idempotencyKey=id,
            ),
            requestBody=json.encodeToString(
                FinanceCashAllocationOperationDto.serializer(),
                FinanceCashAllocationOperationDto(id),
            ),
            decode={json.decodeFromString(
                FinanceCashAllocationReceiptDto.serializer(),it,
            )},
        )
    }
    private fun base(org:String)="/v1/finance/organizations/"+uuid(org)
    private fun uuid(x:String):String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(x)) { "Valid UUID required" }
        return x
    }
}
