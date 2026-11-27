package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class FinanceDeskPeriodDto(val id:String,val dateStart:String,val dateEnd:String,val state:String)
@Serializable data class FinanceDeskPeriodsDto(val organizationId:String,val records:List<FinanceDeskPeriodDto>,val hasMore:Boolean)
@Serializable data class FinanceDeskMovementDto(val accountId:String,val accountCode:String,val accountName:String,
    val accountKind:String,val currency:String,val debit:String,val credit:String)
@Serializable data class FinanceDeskCurrencyTotalDto(val currency:String,val debit:String,val credit:String,val balanced:Boolean)
@Serializable data class FinanceDeskJournalLineDto(val number:Int,val accountCode:String,val accountName:String,
    val side:String,val amount:String)
@Serializable data class FinanceDeskJournalDto(val id:String,val kind:String,val postedAt:String,val currency:String,
    val lines:List<FinanceDeskJournalLineDto>)
@Serializable data class FinanceDeskLedgerDto(val organizationId:String,val periodId:String,val offset:Int,val hasMore:Boolean,
    val movements:List<FinanceDeskMovementDto>,val journals:List<FinanceDeskJournalDto>,
    val includedSources:List<String>,val isCompanyWideTrialBalance:Boolean,
    val currencyTotals:List<FinanceDeskCurrencyTotalDto>)
@Serializable data class FinanceDeskExposureTotalDto(val currency:String,val knownOutstanding:String,val knownOverdue:String,
    val openWithoutDueDate:Long,val exceptionCount:Long,val recordCount:Long)
@Serializable data class FinanceDeskExposureRowDto(val journalId:String,val counterpartyName:String,val currency:String,
    val gross:String,val allocated:String,val outstanding:String?=null,val dueDate:String?=null,val state:String,val reversed:Boolean)
@Serializable data class FinanceDeskExposureDto(val organizationId:String,val kind:String,val asOfDate:String,val offset:Int,
    val hasMore:Boolean,val totals:List<FinanceDeskExposureTotalDto>,val records:List<FinanceDeskExposureRowDto>,
    val isBankBalance:Boolean)

private val deskSources=setOf("AP","AR","AP_REVERSAL")
private val expandedDeskSources=deskSources+financeSettlementTypes
private fun currency(value:String)=value.matches(Regex("[A-Z]{3}"))
private fun amount(value:String)=value.matches(Regex("[0-9]+([.][0-9]+)?"))

internal fun financeDeskLedgerMatches(report:FinanceDeskLedgerDto,org:String,period:String,offset:Int):Boolean =
    report.organizationId.equals(org,true) && report.periodId.equals(period,true) && report.offset==offset &&
        !report.isCompanyWideTrialBalance && report.includedSources.toSet() in setOf(deskSources,expandedDeskSources) &&
        report.includedSources.distinct().size==report.includedSources.size &&
        report.movements.all { financeLookupIdIsUuid(it.accountId) && currency(it.currency) && amount(it.debit) && amount(it.credit) } &&
        report.movements.map { it.accountId.lowercase() to it.currency }.distinct().size==report.movements.size &&
        report.currencyTotals.map { it.currency }.toSet()==report.movements.map { it.currency }.toSet() &&
        report.currencyTotals.map { it.currency }.distinct().size==report.currencyTotals.size &&
        report.currencyTotals.all { currency(it.currency) && amount(it.debit) && amount(it.credit) } &&
        report.journals.size<=50 && report.journals.map { it.kind to it.id.lowercase() }.distinct().size==report.journals.size &&
        report.journals.all { j -> financeLookupIdIsUuid(j.id) && j.kind in report.includedSources && currency(j.currency) &&
            j.lines.isNotEmpty() && j.lines.size<=32 && j.lines.map { it.number }.distinct().size==j.lines.size &&
            j.lines.all { it.number in 1..32 && it.side in setOf("DEBIT","CREDIT") && amount(it.amount) } }

class FinanceAccountingDeskApiClient(private val api:HiltechApiClient,
    private val json:Json=Json { ignoreUnknownKeys=true; explicitNulls=false }) {
    private fun path(org:String):String {
        require(financeLookupIdIsUuid(org))
        return "/v1/finance/organizations/$org/accounting-desk"
    }
    suspend fun periods(org:String,installation:String):FinanceDeskPeriodsDto {
        val result=api.request(HttpMethod.Get,path(org)+"/periods",options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(FinanceDeskPeriodsDto.serializer(),it)})
        check(result.organizationId.equals(org,true) && result.records.size<=100 &&
            result.records.map { it.id.lowercase() }.distinct().size==result.records.size &&
            result.records.all { financeLookupIdIsUuid(it.id) && financeValidCalendarDate(it.dateStart) &&
                financeValidCalendarDate(it.dateEnd) && it.dateEnd>=it.dateStart && it.state in setOf("OPEN","CLOSED") })
        return result
    }

    suspend fun ledger(org:String,period:String,offset:Int,installation:String):FinanceDeskLedgerDto {
        require(financeLookupIdIsUuid(period) && offset in 0..1_000_000)
        val result=api.request(HttpMethod.Get,path(org)+"/ledger?periodId=$period&offset=$offset&includeSettlements=true",
            options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(FinanceDeskLedgerDto.serializer(),it)})
        check(financeDeskLedgerMatches(result,org,period,offset)) { "FINANCE_DESK_LEDGER_SCOPE_MISMATCH" }
        return result
    }

    suspend fun exposure(org:String,kind:String,offset:Int,installation:String):FinanceDeskExposureDto {
        require(kind in setOf("AP","AR") && offset in 0..1_000_000)
        val result=api.request(HttpMethod.Get,path(org)+"/exposure/$kind?offset=$offset",
            options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(FinanceDeskExposureDto.serializer(),it)})
        check(result.organizationId.equals(org,true) && result.kind==kind && result.offset==offset &&
            !result.isBankBalance && financeValidCalendarDate(result.asOfDate) &&
            result.totals.map { it.currency }.distinct().size==result.totals.size &&
            result.totals.all { currency(it.currency) && amount(it.knownOutstanding) && amount(it.knownOverdue) &&
                it.openWithoutDueDate>=0 && it.exceptionCount>=0 && it.recordCount>=0 } &&
            result.records.size<=50 && result.records.map { it.journalId.lowercase() }.distinct().size==result.records.size &&
            result.records.all { financeLookupIdIsUuid(it.journalId) && currency(it.currency) &&
                amount(it.gross) && amount(it.allocated) &&
                (it.outstanding==null || amount(it.outstanding.removePrefix("-"))) &&
                (it.dueDate==null || financeValidCalendarDate(it.dueDate)) })
        return result
    }
}
