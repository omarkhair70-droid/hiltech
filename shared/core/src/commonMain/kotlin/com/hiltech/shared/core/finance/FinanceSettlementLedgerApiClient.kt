package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val financeSettlementTypes=setOf("BANK_ALLOCATION","CASH_VOUCHER","BANK_VOID")
@Serializable data class FinanceSettlementSourceDto(
    val sourceType:String,val sourceId:String,val sourceKind:String,val sourceJournalId:String,
    val sourceApprovalId:String,val amount:String,val currency:String,val sourceRecordedAt:String,
    val instrumentName:String,val counterpartyName:String?=null,val originalSettlementId:String?=null,
    val debitAccountId:String?=null,val debitAccountName:String?=null,val creditAccountId:String?=null,val creditAccountName:String?=null,
)
@Serializable data class FinanceSettlementQueueDto(val organizationId:String,val period:FinanceDeskPeriodDto?=null,
    val offset:Int,val hasMore:Boolean,val records:List<FinanceSettlementSourceDto>)
@Serializable data class FinanceSettlementPostIntent(val operationId:String,val sourceType:String,val sourceId:String,val sourceKind:String,
    val periodId:String,val debitAccountId:String,val creditAccountId:String,val amount:String,val currency:String)
@Serializable data class FinanceSettlementReceiptDto(
    val organizationId:String,val operationId:String,val journalId:String,val sourceType:String,val sourceId:String,
    val sourceKind:String,val periodId:String,val debitAccountId:String,val creditAccountId:String,
    val amount:String,val currency:String,val replayed:Boolean,val moneyTransferredByHiltech:Boolean,
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

internal fun financeSettlementIntent(source:FinanceSettlementSourceDto,period:FinanceDeskPeriodDto?,operationId:String):FinanceSettlementPostIntent? {
    val debit=source.debitAccountId?:return null
    val credit=source.creditAccountId?:return null
    if(period==null || period.state!="OPEN" || debit.equals(credit,true) ||
        listOf(debit,credit,period.id,operationId,source.sourceId).any {!financeLookupIdIsUuid(it)} ||
        source.sourceType !in financeSettlementTypes || source.sourceKind !in setOf("AP","AR") ||
        settlementCanonicalAmount(source.amount)==null || !settlementCurrency(source.currency) ||
        source.debitAccountName.isNullOrBlank() || source.creditAccountName.isNullOrBlank())return null
    return FinanceSettlementPostIntent(operationId,source.sourceType,source.sourceId,source.sourceKind,period.id,
        debit,credit,source.amount,source.currency)
}

internal fun financeSettlementReceiptMatches(org:String,input:FinanceSettlementPostIntent,receipt:FinanceSettlementReceiptDto):Boolean =
    receipt.organizationId.equals(org,true) && receipt.operationId.equals(input.operationId,true) &&
        financeLookupIdIsUuid(receipt.journalId) && receipt.sourceType==input.sourceType && receipt.sourceId.equals(input.sourceId,true) &&
        receipt.sourceKind==input.sourceKind && receipt.periodId.equals(input.periodId,true) &&
        receipt.debitAccountId.equals(input.debitAccountId,true) && receipt.creditAccountId.equals(input.creditAccountId,true) &&
        settlementCanonicalAmount(input.amount)!=null && settlementCanonicalAmount(receipt.amount)==settlementCanonicalAmount(input.amount) &&
        receipt.currency==input.currency && !receipt.moneyTransferredByHiltech

class FinanceSettlementLedgerApiClient(private val api:HiltechApiClient,
    private val json:Json=Json {ignoreUnknownKeys=true;explicitNulls=false}) {
    private fun path(org:String):String {
        require(financeLookupIdIsUuid(org))
        return "/v1/finance/organizations/$org/settlement-ledger"
    }
    suspend fun queue(org:String,offset:Int,installation:String):FinanceSettlementQueueDto {
        require(offset in 0..1_000_000)
        return api.request(HttpMethod.Get,path(org)+"?offset=$offset",HiltechRequestOptions(installation),
            decode={json.decodeFromString(FinanceSettlementQueueDto.serializer(),it)}).also { q ->
            check(q.organizationId.equals(org,true) && q.offset==offset && q.records.size<=50 &&
                (q.period==null || (financeLookupIdIsUuid(q.period.id) && q.period.state=="OPEN" &&
                    financeValidCalendarDate(q.period.dateStart) && financeValidCalendarDate(q.period.dateEnd) &&
                    q.period.dateStart<=q.period.dateEnd)) &&
                q.records.map { it.sourceType to it.sourceId.lowercase() }.distinct().size==q.records.size &&
                q.records.all { s -> s.sourceType in financeSettlementTypes && s.sourceKind in setOf("AP","AR") &&
                    listOf(s.sourceId,s.sourceJournalId,s.sourceApprovalId).all(::financeLookupIdIsUuid) &&
                    listOfNotNull(s.originalSettlementId,s.debitAccountId,s.creditAccountId).all(::financeLookupIdIsUuid) &&
                    (s.sourceType=="BANK_VOID")== (s.originalSettlementId!=null) &&
                    (s.debitAccountId==null)==(s.debitAccountName==null) &&
                    (s.creditAccountId==null)==(s.creditAccountName==null) &&
                    settlementCanonicalAmount(s.amount)!=null && settlementCurrency(s.currency) &&
                    s.instrumentName.isNotBlank() && s.sourceRecordedAt.isNotBlank() })
        }
    }
    suspend fun post(org:String,input:FinanceSettlementPostIntent,installation:String):FinanceSettlementReceiptDto {
        require(listOf(input.operationId,input.sourceId,input.periodId,input.debitAccountId,input.creditAccountId).all(::financeLookupIdIsUuid) &&
            input.sourceType in financeSettlementTypes && input.sourceKind in setOf("AP","AR") &&
            !input.debitAccountId.equals(input.creditAccountId,true) && settlementCanonicalAmount(input.amount)!=null && settlementCurrency(input.currency))
        return api.request(HttpMethod.Post,path(org),HiltechRequestOptions(installation,input.operationId),
            json.encodeToString(FinanceSettlementPostIntent.serializer(),input),
            decode={json.decodeFromString(FinanceSettlementReceiptDto.serializer(),it)}).also {
            check(financeSettlementReceiptMatches(org,input,it))
        }
    }
}
