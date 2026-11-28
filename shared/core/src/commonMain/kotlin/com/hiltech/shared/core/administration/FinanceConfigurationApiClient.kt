package com.hiltech.shared.core.administration

import com.hiltech.shared.core.finance.financeLookupIdIsUuid
import com.hiltech.shared.core.finance.financeValidCalendarDate
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val financeConfigurationKinds = listOf("ACCOUNT","PERIOD","BANK")
internal val financeConfigurationAccountKinds = listOf("SUPPLIER_PAYABLE","EXPENSE","INVENTORY_ASSET","FIXED_ASSET",
    "TAX_RECOVERABLE","OTHER_ASSET","CUSTOMER_RECEIVABLE","SALES_REVENUE","TAX_PAYABLE","BANK_ASSET","CASH_ASSET","EMPLOYEE_ADVANCE_ASSET","EMPLOYEE_IMPREST_ASSET","PAYROLL_EXPENSE","PAYROLL_PAYABLE","PAYROLL_DEDUCTION_PAYABLE","EQUITY","OTHER_LIABILITY")
internal val financeTreasuryAccountKinds=setOf("BANK_ASSET","CASH_ASSET")
private val currencyPattern = Regex("[A-Z]{3}")
private val bankMaskPattern = Regex("(?:[A-Za-z][A-Za-z0-9_-]{0,23}:)?[*]{2,12}[0-9]{2,4}")

@Serializable data class FinanceConfigurationIntent(
    val operationId: String, val kind: String, val authorizationReference: String,
    val code: String?=null,val name: String?=null,val accountKind: String?=null,
    val currency: String?=null,val startDate: String?=null,val endDate: String?=null,
    val treasuryInstrumentId: String?=null,
)
@Serializable data class FinanceConfigurationRecordDto(
    val id: String,val kind: String,val state: String,val authorizationReference: String,
    val code: String?=null,val name: String?=null,val accountKind: String?=null,
    val currency: String?=null,val startDate: String?=null,val endDate: String?=null,
    val treasuryInstrumentId: String?=null,
)
@Serializable data class FinanceTreasuryInstrumentDto(val id:String,val name:String,val currency:String)
@Serializable data class FinanceTreasuryInstrumentPageDto(val organizationId:String,val accountKind:String,
    val records:List<FinanceTreasuryInstrumentDto>,val hasMore:Boolean)
@Serializable data class FinanceConfigurationPageDto(val organizationId: String,val kind: String,
    val offset: Int,val hasMore: Boolean,val records: List<FinanceConfigurationRecordDto>)
@Serializable data class FinanceConfigurationReceiptDto(val operationId: String,val organizationId: String,
    val record: FinanceConfigurationRecordDto,val replayed: Boolean)

private fun plain(value: String?, min: Int, max: Int) =
    value != null && value.length in min..max && value == value.trim() && value.none { it.code < 32 || it.code == 127 }

internal fun financeConfigurationIntentValid(input: FinanceConfigurationIntent): Boolean {
    if (!plain(input.authorizationReference,8,if(input.kind=="BANK")180 else 160) ||
        (input.currency!=null && !currencyPattern.matches(input.currency))) return false
    return when(input.kind) {
        "ACCOUNT" -> plain(input.code,1,64) && plain(input.name,1,180) && input.accountKind in financeConfigurationAccountKinds &&
            input.startDate==null && input.endDate==null &&
            (if(input.accountKind in financeTreasuryAccountKinds) input.treasuryInstrumentId?.let(::financeLookupIdIsUuid)==true && input.currency!=null
             else input.treasuryInstrumentId==null)
        "PERIOD" -> input.code==null && input.name==null && input.accountKind==null && input.currency==null &&
            input.treasuryInstrumentId==null && input.startDate!=null && input.endDate!=null && financeValidCalendarDate(input.startDate) &&
            financeValidCalendarDate(input.endDate) && !input.startDate.startsWith("0000") && input.endDate>=input.startDate
        "BANK" -> input.code?.matches(bankMaskPattern)==true && plain(input.name,3,160) && input.currency!=null &&
            input.accountKind==null && input.startDate==null && input.endDate==null && input.treasuryInstrumentId==null
        else -> false
    }
}

internal fun financeConfigurationReceiptMatches(org: String,input: FinanceConfigurationIntent,receipt: FinanceConfigurationReceiptDto): Boolean {
    val row=receipt.record
    return receipt.organizationId.equals(org,true) && receipt.operationId.equals(input.operationId,true) &&
        financeLookupIdIsUuid(row.id) && row.kind==input.kind &&
        row.state in (if(row.kind=="PERIOD") setOf("OPEN","CLOSED") else setOf("APPROVED","RETIRED")) &&
        row.authorizationReference==input.authorizationReference && row.code==input.code && row.name==input.name &&
        row.accountKind==input.accountKind && row.currency==input.currency && row.startDate==input.startDate && row.endDate==input.endDate &&
        row.treasuryInstrumentId==input.treasuryInstrumentId
}

@Serializable data class FinanceOpeningLineDto(val accountId:String,val side:String,val amount:String,val reference:String,val bankStatementId:String?=null)
@Serializable data class FinanceOpeningInputDto(val operationId:String,val periodId:String,val currency:String,
    val companyReference:String,val sourceReference:String,val lines:List<FinanceOpeningLineDto>)
@Serializable data class FinanceOpeningAccountDto(val id:String,val code:String,val name:String,val accountKind:String,
    val bankStatementId:String?=null,val bankSide:String?=null,val bankAmount:String?=null)
@Serializable data class FinanceOpeningPeriodDto(val id:String,val dateStart:String,val dateEnd:String)
@Serializable data class FinanceOpeningViewDto(val organizationId:String,val currency:String,val period:FinanceOpeningPeriodDto?=null,
    val existingJournalId:String?=null,val canPost:Boolean,val accounts:List<FinanceOpeningAccountDto>,val hasMore:Boolean)
@Serializable data class FinanceOpeningReceiptDto(val organizationId:String,val operationId:String,val journalId:String,val periodId:String,
    val currency:String,val openingDate:String,val replayed:Boolean)
internal val openingKinds=setOf("FIXED_ASSET","OTHER_ASSET","OTHER_LIABILITY","EQUITY","BANK_ASSET")
internal fun openingLineValid(line:FinanceOpeningLineDto)=financeLookupIdIsUuid(line.accountId) &&
    line.side in setOf("DEBIT","CREDIT") && com.hiltech.shared.core.finance.settlementCanonicalAmount(line.amount)!=null &&
    plain(line.reference,8,240) && (line.bankStatementId==null||financeLookupIdIsUuid(line.bankStatementId))
// Exact decimal addition for common Kotlin, without Double or currency rounding.
internal fun openingTotal(lines:List<FinanceOpeningLineDto>,side:String):String? {
    val digits=IntArray(26)
    if(lines.size>32)return null
    for(line in lines.filter{it.side==side}){
        val value=com.hiltech.shared.core.finance.settlementCanonicalAmount(line.amount)?:return null
        val parts=value.split('.')
        val scaled=parts[0]+parts.getOrNull(1).orEmpty().padEnd(6,'0')
        scaled.reversed().forEachIndexed{i,c->digits[i]+=c-'0'}
    }
    for(i in 0 until digits.lastIndex){digits[i+1]+=digits[i]/10;digits[i]%=10}
    val scaled=digits.reversed().joinToString("").trimStart('0').padStart(7,'0')
    return scaled.dropLast(6)+"."+scaled.takeLast(6)
}
internal fun openingInputValid(input:FinanceOpeningInputDto)=financeLookupIdIsUuid(input.periodId) &&
    currencyPattern.matches(input.currency) && plain(input.companyReference,8,160) && plain(input.sourceReference,8,240) &&
    input.lines.size in 2..32 && input.lines.map{it.accountId.lowercase()}.distinct().size==input.lines.size &&
    input.lines.all(::openingLineValid) && input.lines.any{it.side=="DEBIT"} && input.lines.any{it.side=="CREDIT"} &&
    openingTotal(input.lines,"DEBIT")?.let{it==openingTotal(input.lines,"CREDIT")&&it!="0.000000"}==true

class FinanceConfigurationApiClient(private val api: HiltechApiClient,
    private val json: Json=Json { ignoreUnknownKeys=true; explicitNulls=false }) {
    private fun path(org: String): String {
        require(financeLookupIdIsUuid(org))
        return "/v1/organizations/$org/administration/financial-configuration"
    }
    suspend fun page(org: String,kind: String,offset: Int,installation: String): FinanceConfigurationPageDto {
        require(kind in financeConfigurationKinds && offset in 0..1_000_000)
        return api.request(HttpMethod.Get,path(org)+"?kind=$kind&offset=$offset",
            HiltechRequestOptions(installation),decode={json.decodeFromString(FinanceConfigurationPageDto.serializer(),it)}).also { result ->
            require(result.organizationId.equals(org,true) && result.kind==kind && result.offset==offset &&
                result.records.size<=50 && result.records.map { it.id.lowercase() }.distinct().size==result.records.size &&
                result.records.all { row -> financeLookupIdIsUuid(row.id) && row.kind==kind &&
                    row.state in (if(kind=="PERIOD") setOf("OPEN","CLOSED") else setOf("APPROVED","RETIRED")) })
        }
    }
    suspend fun treasuryInstruments(org:String,accountKind:String,installation:String):FinanceTreasuryInstrumentPageDto {
        require(accountKind in financeTreasuryAccountKinds)
        return api.request(HttpMethod.Get,path(org)+"/treasury-instruments?accountKind=$accountKind",
            HiltechRequestOptions(installation),decode={json.decodeFromString(FinanceTreasuryInstrumentPageDto.serializer(),it)}).also {
            require(it.organizationId.equals(org,true) && it.accountKind==accountKind && it.records.size<=100 &&
                it.records.map { row -> row.id.lowercase() }.distinct().size==it.records.size &&
                it.records.all { row -> financeLookupIdIsUuid(row.id) && row.name.isNotBlank() && currencyPattern.matches(row.currency) })
        }
    }
    suspend fun opening(org:String,currency:String,installation:String):FinanceOpeningViewDto {
        require(currencyPattern.matches(currency))
        return api.request(HttpMethod.Get,path(org)+"/opening?currency="+currency,HiltechRequestOptions(installation),
            decode={json.decodeFromString(FinanceOpeningViewDto.serializer(),it)}).also {v->
                check(v.organizationId.equals(org,true)&&v.currency==currency&&
                    (v.period==null||(financeLookupIdIsUuid(v.period.id)&&financeValidCalendarDate(v.period.dateStart)&&financeValidCalendarDate(v.period.dateEnd)&&v.period.dateStart<=v.period.dateEnd))&&
                    (v.existingJournalId==null||financeLookupIdIsUuid(v.existingJournalId))&&
                    (!v.canPost||(v.period!=null&&v.existingJournalId==null))&&v.accounts.size<=100&&
                    v.accounts.map{it.id.lowercase()}.distinct().size==v.accounts.size&&v.accounts.all{a->
                        financeLookupIdIsUuid(a.id)&&a.name.isNotBlank()&&a.code.isNotBlank()&&a.accountKind in openingKinds&&
                        (if(a.accountKind=="BANK_ASSET")a.bankStatementId?.let(::financeLookupIdIsUuid)==true&&
                            a.bankSide in setOf("DEBIT","CREDIT")&&a.bankAmount?.let{com.hiltech.shared.core.finance.settlementCanonicalAmount(it)!=null}==true
                         else a.bankStatementId==null&&a.bankSide==null&&a.bankAmount==null)})
            }
    }
    suspend fun postOpening(org:String,input:FinanceOpeningInputDto,installation:String):FinanceOpeningReceiptDto {
        require(financeLookupIdIsUuid(input.operationId)&&openingInputValid(input))
        return api.request(HttpMethod.Post,path(org)+"/opening",HiltechRequestOptions(installation,input.operationId),
            json.encodeToString(FinanceOpeningInputDto.serializer(),input),
            decode={json.decodeFromString(FinanceOpeningReceiptDto.serializer(),it)}).also{
                check(it.organizationId.equals(org,true)&&it.operationId.equals(input.operationId,true)&&
                    financeLookupIdIsUuid(it.journalId)&&it.periodId.equals(input.periodId,true)&&
                    it.currency==input.currency&&financeValidCalendarDate(it.openingDate))
            }
    }
    suspend fun create(org: String,input: FinanceConfigurationIntent,installation: String): FinanceConfigurationReceiptDto {
        require(financeLookupIdIsUuid(input.operationId) && financeConfigurationIntentValid(input))
        return api.request(HttpMethod.Post,path(org),HiltechRequestOptions(installation,input.operationId),
            json.encodeToString(FinanceConfigurationIntent.serializer(),input),
            decode={json.decodeFromString(FinanceConfigurationReceiptDto.serializer(),it)}).also {
            require(financeConfigurationReceiptMatches(org,input,it))
        }
    }
}
