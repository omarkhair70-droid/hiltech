package com.hiltech.shared.core.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Mount only in the authenticated company Finance shell. No cash controls. */
@Composable
fun FinanceApLedgerEvidenceReportSection(
    organizationId:String,
    installationId:String,
    report:FinanceApLedgerEvidenceReportApiClient,
    fiscalPeriodId:String?=null,
) {
    val scope=rememberCoroutineScope()
    var loading by remember(organizationId,fiscalPeriodId) { mutableStateOf(false) }
    var result by remember(organizationId,fiscalPeriodId) {
        mutableStateOf<FinanceApLedgerEvidenceReportDto?>(null)
    }
    var error by remember(organizationId,fiscalPeriodId) {
        mutableStateOf<String?>(null)
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Supplier ledger · recorded history",
            style=MaterialTheme.typography.titleSmall)
        Text(
            "Only recognized supplier journal entries. Not a bank, overdue, "+
                "open balance or company-wide financial report.",
            style=MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(enabled=!loading,onClick={
            loading=true
            scope.launch {
                try {
                    val latest=report.get(
                        organizationId,installationId,fiscalPeriodId,
                    )
                    check(
                        !latest.isCompanyWideTrialBalance &&
                        latest.companyOutstandingPayables==null &&
                        latest.companySupplierPayments==null &&
                        latest.companyBankReconciledTotal==null &&
                        latest.groups.all {
                            it.outstandingBalance==null &&
                                it.settledAmount==null &&
                                it.overdueBalance==null &&
                                it.bankConfirmedAmount==null
                        }
                    ) { "Unverified bank/payable balances are not allowed in this report." }
                    result=latest
                    error=null
                } catch (failure:Exception) {
                    error=failure.message ?: "Company ledger report unavailable."
                } finally { loading=false }
            }
        }) { Text(if(loading) "Loading…" else "Refresh recorded supplier journals") }
        result?.let { data->
            if (!data.sourceAvailable) {
                Text(
                    "Posting ledger has not been integrated; actual company "+
                        "supplier liabilities are UNKNOWN, not zero.",
                    color=MaterialTheme.colorScheme.error,
                )
            } else {
                if(data.hasMoreGroups) {
                    Text(
                        "First ${data.queryLimit} supplier/currency groups only. "+
                            "This is not a complete statement.",
                    )
                }
                if(data.groups.isEmpty()) {
                    Text("No posted supplier records in this source/scope; "+
                        "this is not a zero company balance.")
                }
                data.groups.forEach { item->
                    Text(
                        "Supplier "+item.supplierOrganizationId.take(8)+
                            " · "+item.historicallyRecognizedGross+
                            " "+item.currency,
                        style=MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${item.postedJournalCount} posted entries · "+
                            "Remaining, paid and overdue: not verified",
                        style=MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
}
