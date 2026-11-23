package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FinanceApLedgerEvidenceGroupDto(
    val supplierOrganizationId:String,
    val currency:String,
    val postedJournalCount:Long,
    val historicallyRecognizedGross:String,
    val mostRecentPostingAt:String,
    val sourceOnly:Boolean=true,
    val settledAmount:String?=null,
    val outstandingBalance:String?=null,
    val overdueBalance:String?=null,
    val bankConfirmedAmount:String?=null,
)

@Serializable
data class FinanceApLedgerEvidenceReportDto(
    val organizationId:String,
    val fiscalPeriodId:String?=null,
    val observedAt:String,
    val sourceAvailable:Boolean,
    val sourceStatus:String,
    val groups:List<FinanceApLedgerEvidenceGroupDto>,
    val queryLimit:Int=100,
    val hasMoreGroups:Boolean=false,
    val isCompanyWideTrialBalance:Boolean=false,
    val companyOutstandingPayables:String?=null,
    val companySupplierPayments:String?=null,
    val companyBankReconciledTotal:String?=null,
)

/**
 * Finance-only historical AP evidence report.
 * No native bank actions, supplier balance or overdue figure without a source.
 */
class FinanceApLedgerEvidenceReportApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json { ignoreUnknownKeys=true; explicitNulls=false },
) {
    suspend fun get(
        organizationId:String,
        installationId:String,
        fiscalPeriodId:String?=null,
    ):FinanceApLedgerEvidenceReportDto {
        val period=fiscalPeriodId?.let { "?periodId="+uuid(it) } ?: ""
        return api.request(
            method=HttpMethod.Get,
            path="/v1/finance/organizations/"+uuid(organizationId)+
                "/supplier-ap-evidence-report"+period,
            options=HiltechRequestOptions(installationId=installationId),
            decode={
                json.decodeFromString(
                    FinanceApLedgerEvidenceReportDto.serializer(),it,
                )
            },
        )
    }
    private fun uuid(value:String):String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(value)) { "Valid UUID required." }
        return value
    }
}
