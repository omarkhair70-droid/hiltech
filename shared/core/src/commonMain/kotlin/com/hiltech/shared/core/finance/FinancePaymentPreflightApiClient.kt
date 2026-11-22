package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FinancePaymentPreflightRowDto(
    val sourcePayableCandidateId: String,
    val supplierOrganizationId: String,
    val supplierInvoiceId: String,
    val invoiceNumber: String,
    val amount: String,
    val currency: String,
    val sourceSha256: String,
    val accountingSourceState: String,
    val sourceCurrent: Boolean,
    val blockingReasons: List<String>,
    val actionablePaymentInstruction: Boolean = false,
    val paymentExecutionAvailable: Boolean = false,
)

@Serializable
data class FinancePaymentPreflightReportDto(
    val organizationId: String,
    val observedAt: String,
    val sourceType: String,
    val rows: List<FinancePaymentPreflightRowDto>,
    val paymentInstructionEnabled: Boolean = false,
    val approvalEnabled: Boolean = false,
    val bankExecutionEnabled: Boolean = false,
    val matchedBankStatements: Int? = null,
    val paymentResultsObserved: Int? = null,
    val paymentResultEvidenceSource: String,
)

@Serializable
data class FinanceCandidatePulseGroupDto(
    val currency: String,
    val reviewCandidateCount: Int,
    val reviewCandidateTotal: String,
    val sourceChangedCount: Int,
    val postedPayables: Int? = null,
    val approvedPayments: Int? = null,
    val executedPayments: Int? = null,
)

@Serializable
data class FinanceCandidatePulseDto(
    val organizationId: String,
    val observedAt: String,
    val dataSemantics: String,
    val sourceItems: Int,
    val groupsByCurrency: List<FinanceCandidatePulseGroupDto>,
    val actualBankBalanceKnown: Boolean = false,
    val reconciliationAvailable: Boolean = false,
)

/**
 * Online-only Finance diagnostic views. No POST, beneficiary or execute
 * route is implemented here.
 */
class FinancePaymentPreflightApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun readiness(orgId: String, installationId: String):
        FinancePaymentPreflightReportDto = api.request(
        method = HttpMethod.Get,
        path = "/v1/finance/organizations/" + uuid(orgId) + "/payment-preflight",
        options = HiltechRequestOptions(installationId = installationId),
        decode = { json.decodeFromString(FinancePaymentPreflightReportDto.serializer(), it) },
    )

    suspend fun candidatePulse(orgId: String, installationId: String):
        FinanceCandidatePulseDto = api.request(
        method = HttpMethod.Get,
        path = "/v1/finance/organizations/" + uuid(orgId) + "/review-candidate-pulse",
        options = HiltechRequestOptions(installationId = installationId),
        decode = { json.decodeFromString(FinanceCandidatePulseDto.serializer(), it) },
    )

    private fun uuid(value: String): String {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(value)) { "Valid UUID required" }
        return value
    }
}
