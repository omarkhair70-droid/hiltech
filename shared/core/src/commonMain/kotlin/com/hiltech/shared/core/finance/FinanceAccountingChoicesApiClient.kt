package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FinanceGlAccountChoiceDto(val id:String,val accountKind:String,val currency:String?=null,
    val accountCode:String?=null,val accountName:String?=null)
@Serializable
data class FinanceGlPeriodChoiceDto(val id:String,val dateStart:String,val dateEnd:String)
@Serializable
data class FinanceAccountingChoicesDto(
    val organizationId:String,val kind:String,
    val approvedAccounts:List<FinanceGlAccountChoiceDto>,
    val openPeriods:List<FinanceGlPeriodChoiceDto>,
    val hasMoreAccounts:Boolean=false,val hasMorePeriods:Boolean=false,
)

/** Existing AP/AR preparer permission only; no accounting POST or approval. */
class FinanceAccountingChoicesApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json {ignoreUnknownKeys=true;explicitNulls=false},
) {
    suspend fun get(org:String,kind:String,installation:String):FinanceAccountingChoicesDto {
        require(financeLookupIdIsUuid(org) && kind in setOf("AP","AR"))
        return api.request(
            method=HttpMethod.Get,
            path="/v1/finance/organizations/"+org+"/accounting-choices?kind="+kind,
            options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(FinanceAccountingChoicesDto.serializer(),it)},
        )
    }
}

suspend fun financeReadAccountingChoices(apis:FinanceEvidenceReadApis,
    org:String,kind:String,install:String):FinanceAccountingChoicesDto {
    val result=apis.accountingChoices.get(org,kind,install)
    check(result.organizationId.equals(org,true) && result.kind==kind &&
        result.approvedAccounts.all {financeLookupIdIsUuid(it.id) &&
            it.accountKind.isNotBlank() &&
            (it.accountCode==null || it.accountCode.isNotBlank()) &&
            (it.accountName==null || it.accountName.isNotBlank()) &&
            (it.currency==null || it.currency.matches(Regex("[A-Z]{3}")))} &&
        result.openPeriods.all {financeLookupIdIsUuid(it.id) &&
            financeValidCalendarDate(it.dateStart) &&
            financeValidCalendarDate(it.dateEnd) &&
            it.dateEnd>=it.dateStart}) {
        "FINANCE_ACCOUNTING_CHOICES_SOURCE_MISMATCH"
    }
    return result
}
