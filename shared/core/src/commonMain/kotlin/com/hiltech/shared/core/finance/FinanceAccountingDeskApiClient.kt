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
@Serializable data class FinanceDeskAccountBalanceDto(
    val accountId:String,val accountCode:String,val accountName:String,val accountKind:String,val currency:String,
    val openingDebit:String,val openingCredit:String,val periodDebit:String,val periodCredit:String,
    val closingDebit:String,val closingCredit:String,
)
@Serializable data class FinanceDeskBalanceTotalDto(
    val currency:String,val openingDebit:String,val openingCredit:String,val periodDebit:String,val periodCredit:String,
    val closingDebit:String,val closingCredit:String,val balanced:Boolean,
    val recognizedRevenue:String,val recognizedExpense:String,val recognizedResult:String,
)
@Serializable data class FinanceDeskPositionDto(
    val currency:String,val assets:String,val liabilities:String,val equity:String,
    val cumulativeRecognizedResult:String,val unclassifiedDebitNet:String,val equationBalances:Boolean,
)
@Serializable data class FinanceDeskTreasuryDto(
    val currency:String,val accountKind:String,val carriedNet:String,val debits:String,val credits:String,
    val netMovement:String,val closingNet:String,val movementBalances:Boolean,
)
@Serializable data class FinanceDeskTreasurySourceDto(
    val currency:String,val accountKind:String,val sourceKind:String,val debits:String,val credits:String,
    val category:String="UNCLASSIFIED",
)
@Serializable data class FinanceDeskCashFlowCategoryDto(val category:String,val debit:String,val credit:String,val net:String)
@Serializable data class FinanceDeskCashFlowDto(
    val currency:String,val carriedNet:String,val closingNet:String,val netClassifiedMovement:String,
    val accountingAdjustmentNet:String,val unclassifiedNet:String,val totalMovement:String,
    val reconcilesToLedger:Boolean,val categories:List<FinanceDeskCashFlowCategoryDto>,
)
@Serializable data class FinanceDeskBalancesDto(
    val organizationId:String,val periodId:String,val dateStart:String,val dateEnd:String,
    val includedSources:List<String>,val priorPeriodCount:Int,val firstIncludedDate:String,
    val priorCoverageHasGaps:Boolean,val openingBasis:String,val isCompanyWideTrialBalance:Boolean,
    val isCompleteIncomeStatement:Boolean,val rows:List<FinanceDeskAccountBalanceDto>,val totals:List<FinanceDeskBalanceTotalDto>,
    val recordedOpeningCurrencies:List<String> = emptyList(),
    val positions:List<FinanceDeskPositionDto> = emptyList(),
    val treasury:List<FinanceDeskTreasuryDto> = emptyList(),
    val treasurySources:List<FinanceDeskTreasurySourceDto> = emptyList(),
    val isCompleteBalanceSheet:Boolean=false,val isCompleteCashFlowStatement:Boolean=false,
    val cashFlow:List<FinanceDeskCashFlowDto> = emptyList(),
)
@Serializable data class FinanceDeskExposureTotalDto(val currency:String,val knownOutstanding:String,val knownOverdue:String,
    val openWithoutDueDate:Long,val exceptionCount:Long,val recordCount:Long)
@Serializable data class FinanceDeskExposureRowDto(val journalId:String,val counterpartyName:String,val currency:String,
    val gross:String,val allocated:String,val outstanding:String?=null,val dueDate:String?=null,val state:String,val reversed:Boolean,
    val openingDocumentReference:String?=null,val openingDocumentDate:String?=null)
@Serializable data class FinanceDeskExposureDto(val organizationId:String,val kind:String,val asOfDate:String,val offset:Int,
    val hasMore:Boolean,val totals:List<FinanceDeskExposureTotalDto>,val records:List<FinanceDeskExposureRowDto>,
    val isBankBalance:Boolean)

private val deskSources=setOf("AP","AR","AP_REVERSAL")
private val expandedDeskSources=deskSources+financeSettlementTypes
private val employeeDeskSources=expandedDeskSources+financeEmployeeSettlementTypes
internal val payrollDeskSources=setOf("PAYROLL_ACCRUAL","PAYROLL_PAYMENT","PAYROLL_RETURN","PAYROLL_VOID")
private fun currency(value:String)=value.matches(Regex("[A-Z]{3}"))
private fun amount(value:String)=value.matches(Regex("[0-9]+([.][0-9]+)?"))
private fun signedAmount(value:String)=amount(value.removePrefix("-"))
private fun nonzero(value:String)=value.any{it in '1'..'9'}
private val treasuryAccountKinds=setOf("BANK_ASSET","CASH_ASSET")
private val cashFlowCategories=setOf("OPERATING","INVESTING","UNCLASSIFIED","ACCOUNTING_ADJUSTMENT")

internal fun financeDeskLedgerMatches(report:FinanceDeskLedgerDto,org:String,period:String,offset:Int):Boolean =
    report.organizationId.equals(org,true) && report.periodId.equals(period,true) && report.offset==offset &&
        !report.isCompanyWideTrialBalance && (report.includedSources.toSet()-"OPENING_BALANCE") in setOf(deskSources,expandedDeskSources,employeeDeskSources,deskSources+payrollDeskSources,expandedDeskSources+payrollDeskSources,employeeDeskSources+payrollDeskSources) &&
        report.includedSources.distinct().size==report.includedSources.size &&
        report.movements.all { financeLookupIdIsUuid(it.accountId) && currency(it.currency) && amount(it.debit) && amount(it.credit) } &&
        report.movements.map { it.accountId.lowercase() to it.currency }.distinct().size==report.movements.size &&
        report.currencyTotals.map { it.currency }.toSet()==report.movements.map { it.currency }.toSet() &&
        report.currencyTotals.map { it.currency }.distinct().size==report.currencyTotals.size &&
        report.currencyTotals.all { currency(it.currency) && amount(it.debit) && amount(it.credit) } &&
        report.journals.size<=50 && report.journals.map { it.kind to it.id.lowercase() }.distinct().size==report.journals.size &&
        report.journals.all { j -> financeLookupIdIsUuid(j.id) && j.kind in report.includedSources && currency(j.currency) &&
            j.lines.isNotEmpty() && j.lines.size<=(if(j.kind=="PAYROLL_ACCRUAL")64 else 32) && j.lines.map { it.number }.distinct().size==j.lines.size &&
            j.lines.all { it.number in 1..(if(j.kind=="PAYROLL_ACCRUAL")64 else 32) && it.side in setOf("DEBIT","CREDIT") && amount(it.amount) } }

internal fun financeDeskBalancesMatches(data:FinanceDeskBalancesDto,org:String,period:String):Boolean =
    data.organizationId.equals(org,true) && data.periodId.equals(period,true) &&
        financeValidCalendarDate(data.dateStart) && financeValidCalendarDate(data.dateEnd) &&
        financeValidCalendarDate(data.firstIncludedDate) && data.firstIncludedDate<=data.dateStart && data.dateStart<=data.dateEnd &&
        data.priorPeriodCount>=0 && data.openingBasis in setOf("PRIOR_POSTED_MOVEMENTS_ONLY","RECORDED_OPENING_PLUS_PRIOR_MOVEMENTS") &&
        (data.openingBasis=="PRIOR_POSTED_MOVEMENTS_ONLY")==data.recordedOpeningCurrencies.isEmpty() &&
        data.recordedOpeningCurrencies.distinct().size==data.recordedOpeningCurrencies.size && data.recordedOpeningCurrencies.all(::currency) &&
        !data.isCompanyWideTrialBalance && !data.isCompleteIncomeStatement && !data.isCompleteBalanceSheet && !data.isCompleteCashFlowStatement &&
        (data.includedSources.toSet()-"OPENING_BALANCE") in setOf(employeeDeskSources,employeeDeskSources+payrollDeskSources) &&
        (data.recordedOpeningCurrencies.isEmpty() || "OPENING_BALANCE" in data.includedSources) &&
        data.includedSources.distinct().size==data.includedSources.size &&
        data.rows.map{it.accountId.lowercase() to it.currency}.distinct().size==data.rows.size &&
        data.rows.all{r->financeLookupIdIsUuid(r.accountId)&&r.accountCode.isNotBlank()&&r.accountName.isNotBlank()&&currency(r.currency)&&
            listOf(r.openingDebit,r.openingCredit,r.periodDebit,r.periodCredit,r.closingDebit,r.closingCredit).all(::amount)&&
            (!nonzero(r.openingDebit)||!nonzero(r.openingCredit))&&
            (!nonzero(r.closingDebit)||!nonzero(r.closingCredit))} &&
        data.totals.map{it.currency}.distinct().size==data.totals.size &&
        data.totals.map{it.currency}.toSet()==data.rows.map{it.currency}.toSet() &&
        data.totals.all{r->currency(r.currency)&&listOf(r.openingDebit,r.openingCredit,r.periodDebit,r.periodCredit,r.closingDebit,r.closingCredit).all(::amount)&&
            listOf(r.recognizedRevenue,r.recognizedExpense,r.recognizedResult).all(::signedAmount)} &&
        data.positions.map{it.currency}.distinct().size==data.positions.size &&
        data.positions.map{it.currency}.toSet()==data.totals.map{it.currency}.toSet() &&
        data.positions.all{currency(it.currency)&&listOf(it.assets,it.liabilities,it.equity,it.cumulativeRecognizedResult,it.unclassifiedDebitNet).all(::signedAmount)} &&
        data.treasury.map{it.currency to it.accountKind}.distinct().size==data.treasury.size &&
        data.treasury.map{it.currency to it.accountKind}.toSet()==data.rows.filter{it.accountKind in treasuryAccountKinds}.map{it.currency to it.accountKind}.toSet() &&
        data.treasury.all{currency(it.currency)&&it.accountKind in treasuryAccountKinds&&amount(it.debits)&&amount(it.credits)&&
            listOf(it.carriedNet,it.netMovement,it.closingNet).all(::signedAmount)} &&
        data.treasurySources.map{listOf(it.currency,it.accountKind,it.sourceKind,it.category)}.distinct().size==data.treasurySources.size &&
        data.treasurySources.all{s->s.sourceKind in data.includedSources&&s.sourceKind!="OPENING_BALANCE"&&
            s.category in cashFlowCategories &&
            ((s.sourceKind in setOf("BANK_VOID","PAYROLL_VOID"))==(s.category=="ACCOUNTING_ADJUSTMENT")) &&
            amount(s.debits)&&amount(s.credits)&&data.treasury.any{it.currency==s.currency&&it.accountKind==s.accountKind}} &&
        data.cashFlow.map{it.currency}.distinct().size==data.cashFlow.size &&
        data.cashFlow.map{it.currency}.toSet()==data.treasury.map{it.currency}.toSet() &&
        data.cashFlow.all{f->currency(f.currency)&&
            listOf(f.carriedNet,f.closingNet,f.netClassifiedMovement,f.accountingAdjustmentNet,f.unclassifiedNet,f.totalMovement).all(::signedAmount) &&
            f.categories.map{it.category}.distinct().size==f.categories.size &&
            f.categories.map{it.category}.toSet()==data.treasurySources.filter{it.currency==f.currency}.map{it.category}.toSet() &&
            f.categories.all{it.category in cashFlowCategories && amount(it.debit)&&amount(it.credit)&&signedAmount(it.net)}}

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
        val result=api.request(HttpMethod.Get,path(org)+"/ledger?periodId=$period&offset=$offset&includeSettlements=true&includeEmployee=true&includePayroll=true&includeOpening=true",
            options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(FinanceDeskLedgerDto.serializer(),it)})
        check(financeDeskLedgerMatches(result,org,period,offset)) { "FINANCE_DESK_LEDGER_SCOPE_MISMATCH" }
        return result
    }

    suspend fun balances(org:String,period:String,installation:String):FinanceDeskBalancesDto {
        require(financeLookupIdIsUuid(period))
        return api.request(HttpMethod.Get,path(org)+"/balances?periodId=$period&includePayroll=true",
            options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(FinanceDeskBalancesDto.serializer(),it)}).also {
                check(financeDeskBalancesMatches(it,org,period)){"FINANCE_DESK_BALANCE_SCOPE_MISMATCH"}
            }
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
