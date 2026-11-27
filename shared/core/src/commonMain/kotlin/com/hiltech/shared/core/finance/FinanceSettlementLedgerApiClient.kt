package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val financeSettlementTypes=setOf("BANK_ALLOCATION","CASH_VOUCHER","BANK_VOID")
internal val financeEmployeeSettlementTypes=setOf("EMPLOYEE_FUNDING","EMPLOYEE_RETURN","IMPREST_EXPENSE","EXPENSE_REIMBURSEMENT")
private fun validSettlementPair(type:String,kind:String)=when(type) {
    in financeSettlementTypes -> kind in setOf("AP","AR")
    "EMPLOYEE_FUNDING","EMPLOYEE_RETURN" -> kind in setOf("EA","EI")
    "IMPREST_EXPENSE" -> kind=="EI"
    "EXPENSE_REIMBURSEMENT" -> kind=="ER"
    else -> false
}
private fun validDebitKind(required:String,kind:String)=if(required=="COST")
    kind in setOf("EXPENSE","INVENTORY_ASSET","FIXED_ASSET","OTHER_ASSET") else required==kind
private fun requiredDebitKind(type:String,kind:String):String?=when(type) {
    "EMPLOYEE_FUNDING" -> if(kind=="EA") "EMPLOYEE_ADVANCE_ASSET" else "EMPLOYEE_IMPREST_ASSET"
    "IMPREST_EXPENSE","EXPENSE_REIMBURSEMENT" -> "COST"
    else -> null
}
private fun validTreatment(type:String,ref:String?)=
    if(type in setOf("IMPREST_EXPENSE","EXPENSE_REIMBURSEMENT"))
        ref!=null && ref==ref.trim() && ref.length in 8..240 && ref.none {it.code<32 || it.code==127}
    else ref==null
@Serializable data class FinanceSettlementAccountChoiceDto(val id:String,val code:String,val name:String,val accountKind:String)
@Serializable data class FinanceSettlementSourceDto(
    val sourceType:String,val sourceId:String,val sourceKind:String,val sourceJournalId:String?=null,
    val sourceApprovalId:String,val amount:String,val currency:String,val sourceRecordedAt:String,
    val instrumentName:String,val counterpartyName:String?=null,val originalSettlementId:String?=null,
    val debitAccountId:String?=null,val debitAccountName:String?=null,val creditAccountId:String?=null,val creditAccountName:String?=null,
    val employeeFundingRecordId:String?=null,val expenseClaimId:String?=null,val requiredDebitKind:String?=null,
    val debitChoices:List<FinanceSettlementAccountChoiceDto> = emptyList(),val hasMoreDebitChoices:Boolean=false,
)
@Serializable data class FinanceSettlementQueueDto(val organizationId:String,val period:FinanceDeskPeriodDto?=null,
    val offset:Int,val hasMore:Boolean,val records:List<FinanceSettlementSourceDto>)
@Serializable data class FinanceSettlementPostIntent(val operationId:String,val sourceType:String,val sourceId:String,val sourceKind:String,
    val periodId:String,val debitAccountId:String,val creditAccountId:String,val amount:String,val currency:String,
    val accountingTreatmentRef:String?=null)
@Serializable data class FinanceSettlementReceiptDto(
    val organizationId:String,val operationId:String,val journalId:String,val sourceType:String,val sourceId:String,
    val sourceKind:String,val periodId:String,val debitAccountId:String,val creditAccountId:String,
    val amount:String,val currency:String,val replayed:Boolean,val moneyTransferredByHiltech:Boolean,
    val accountingTreatmentRef:String?=null,
)

private fun settlementCurrency(value:String)=value.matches(Regex("[A-Z]{3}"))
internal fun settlementCanonicalAmount(value:String):String? {
    if(!value.matches(Regex("[0-9]{1,18}([.][0-9]{1,6})?")))return null
    val parts=value.split('.')
    val whole=parts[0].trimStart('0').ifEmpty{"0"}
    val fraction=parts.getOrNull(1).orEmpty().trimEnd('0')
    if(whole=="0" && fraction.isEmpty())return null
    return whole+if(fraction.isEmpty())"" else ".$fraction"
}

internal fun financeSettlementIntent(source:FinanceSettlementSourceDto,period:FinanceDeskPeriodDto?,operationId:String,
    selectedDebitId:String?=null,accountingTreatmentRef:String?=null):FinanceSettlementPostIntent? {
    if(source.requiredDebitKind!=requiredDebitKind(source.sourceType,source.sourceKind))return null
    val choice=source.requiredDebitKind?.let {required->source.debitChoices.singleOrNull {
        it.id==selectedDebitId && validDebitKind(required,it.accountKind) && it.name.isNotBlank()
    } ?:return null}
    val debit=choice?.id ?: source.debitAccountId ?:return null
    val credit=source.creditAccountId?:return null
    if(period==null || period.state!="OPEN" || debit.equals(credit,true) ||
        listOf(debit,credit,period.id,operationId,source.sourceId).any {!financeLookupIdIsUuid(it)} ||
        !validSettlementPair(source.sourceType,source.sourceKind) || !validTreatment(source.sourceType,accountingTreatmentRef) ||
        settlementCanonicalAmount(source.amount)==null || !settlementCurrency(source.currency) ||
        (choice?.name ?: source.debitAccountName).isNullOrBlank() || source.creditAccountName.isNullOrBlank())return null
    return FinanceSettlementPostIntent(operationId,source.sourceType,source.sourceId,source.sourceKind,period.id,
        debit,credit,source.amount,source.currency,accountingTreatmentRef)
}

internal fun financeSettlementReceiptMatches(org:String,input:FinanceSettlementPostIntent,receipt:FinanceSettlementReceiptDto):Boolean =
    receipt.organizationId.equals(org,true) && receipt.operationId.equals(input.operationId,true) &&
        financeLookupIdIsUuid(receipt.journalId) && receipt.sourceType==input.sourceType && receipt.sourceId.equals(input.sourceId,true) &&
        receipt.sourceKind==input.sourceKind && receipt.periodId.equals(input.periodId,true) &&
        receipt.debitAccountId.equals(input.debitAccountId,true) && receipt.creditAccountId.equals(input.creditAccountId,true) &&
        settlementCanonicalAmount(input.amount)!=null && settlementCanonicalAmount(receipt.amount)==settlementCanonicalAmount(input.amount) &&
        receipt.currency==input.currency && !receipt.moneyTransferredByHiltech && receipt.accountingTreatmentRef==input.accountingTreatmentRef

class FinanceSettlementLedgerApiClient(private val api:HiltechApiClient,
    private val json:Json=Json {ignoreUnknownKeys=true;explicitNulls=false}) {
    private fun path(org:String):String {
        require(financeLookupIdIsUuid(org))
        return "/v1/finance/organizations/$org/settlement-ledger"
    }
    suspend fun queue(org:String,offset:Int,installation:String):FinanceSettlementQueueDto {
        require(offset in 0..1_000_000)
        return api.request(HttpMethod.Get,path(org)+"?offset=$offset&includeEmployee=true",HiltechRequestOptions(installation),
            decode={json.decodeFromString(FinanceSettlementQueueDto.serializer(),it)}).also { q ->
            check(q.organizationId.equals(org,true) && q.offset==offset && q.records.size<=50 &&
                (q.period==null || (financeLookupIdIsUuid(q.period.id) && q.period.state=="OPEN" &&
                    financeValidCalendarDate(q.period.dateStart) && financeValidCalendarDate(q.period.dateEnd) &&
                    q.period.dateStart<=q.period.dateEnd)) &&
                q.records.map { it.sourceType to it.sourceId.lowercase() }.distinct().size==q.records.size &&
                q.records.all { s -> validSettlementPair(s.sourceType,s.sourceKind) &&
                    listOf(s.sourceId,s.sourceApprovalId).all(::financeLookupIdIsUuid) &&
                    listOfNotNull(s.sourceJournalId,s.originalSettlementId,s.debitAccountId,s.creditAccountId,s.employeeFundingRecordId,s.expenseClaimId).all(::financeLookupIdIsUuid) &&
                    (s.sourceKind in setOf("AP","AR"))==(s.sourceJournalId!=null) &&
                    (s.sourceKind in setOf("EA","EI"))==(s.employeeFundingRecordId!=null) &&
                    (s.sourceKind=="ER")==(s.expenseClaimId!=null) &&
                    s.requiredDebitKind==requiredDebitKind(s.sourceType,s.sourceKind) &&
                    (s.requiredDebitKind==null || s.debitAccountId==null) &&
                    (s.requiredDebitKind!=null || (s.debitChoices.isEmpty() && !s.hasMoreDebitChoices)) &&
                    s.debitChoices.size<=100 && s.debitChoices.map {it.id.lowercase()}.distinct().size==s.debitChoices.size &&
                    s.debitChoices.all { choice -> financeLookupIdIsUuid(choice.id) && choice.code.isNotBlank() &&
                        choice.name.isNotBlank() && s.requiredDebitKind?.let {validDebitKind(it,choice.accountKind)}==true } &&
                    (s.sourceType=="BANK_VOID")== (s.originalSettlementId!=null) &&
                    (s.debitAccountId==null)==(s.debitAccountName==null) &&
                    (s.creditAccountId==null)==(s.creditAccountName==null) &&
                    settlementCanonicalAmount(s.amount)!=null && settlementCurrency(s.currency) &&
                    s.instrumentName.isNotBlank() && s.sourceRecordedAt.isNotBlank() })
        }
    }
    suspend fun post(org:String,input:FinanceSettlementPostIntent,installation:String):FinanceSettlementReceiptDto {
        require(listOf(input.operationId,input.sourceId,input.periodId,input.debitAccountId,input.creditAccountId).all(::financeLookupIdIsUuid) &&
            validSettlementPair(input.sourceType,input.sourceKind) && validTreatment(input.sourceType,input.accountingTreatmentRef) &&
            !input.debitAccountId.equals(input.creditAccountId,true) && settlementCanonicalAmount(input.amount)!=null && settlementCurrency(input.currency))
        return api.request(HttpMethod.Post,path(org),HiltechRequestOptions(installation,input.operationId),
            json.encodeToString(FinanceSettlementPostIntent.serializer(),input),
            decode={json.decodeFromString(FinanceSettlementReceiptDto.serializer(),it)}).also {
            check(financeSettlementReceiptMatches(org,input,it))
        }
    }
}
