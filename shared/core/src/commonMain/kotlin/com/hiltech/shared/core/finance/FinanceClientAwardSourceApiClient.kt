package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Financial truth: commercial award is not an issued customer invoice. */
@Serializable
data class FinanceClientAwardSourceRowDto(
    val organizationId: String,
    val opportunityId: String,
    val projectId: String,
    val clientOrganizationId: String,
    val quoteVersionId: String,
    val quoteRevision: Int,
    val quoteRecordVersion: Long,
    val contractId: String,
    val clientPoId: String,
    val sellAmount: String,
    val currency: String,
    val paymentTerms: String,
    val sourceFingerprintSha256: String,
    val financeReviewRequired: Boolean = true,
    val customerInvoiceIssued: Boolean = false,
    val receivableRecognized: Boolean = false,
    val billingMilestonesVerified: Boolean = false,
    val taxTreatmentVerified: Boolean = false,
    val amountDueOrOverdueKnown: Boolean = false,
    val cashCollected: Boolean = false,
    val cashReconciled: Boolean = false,
)

@Serializable
data class FinanceClientAwardSourcePageDto(
    val organizationId: String,
    val observedAt: String,
    val sourceAvailable: Boolean,
    val sourceStatus: String,
    val rows: List<FinanceClientAwardSourceRowDto>,
    val queryLimit: Int = 100,
    val hasMoreSources: Boolean = false,
    val reportedCompanyReceivableTotal: String? = null,
    val reportedCompanyRevenueTotal: String? = null,
    val reportedCustomerCollectionsTotal: String? = null,
)

/**
 * Finance-only native bridge to Phase10's real released-award source.
 * This client has no mutation, invoice issue, tax, AR posting or cash command.
 */
class FinanceClientAwardSourceApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun list(
        organizationId: String, installationId: String,
    ): FinanceClientAwardSourcePageDto =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/finance/organizations/" + uuid(organizationId) +
                "/client-award-sources",
            options = HiltechRequestOptions(installationId = installationId),
            decode = {
                json.decodeFromString(FinanceClientAwardSourcePageDto.serializer(), it)
            },
        )

    suspend fun detail(
        organizationId: String,
        opportunityId: String,
        installationId: String,
    ): FinanceClientAwardSourceRowDto =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/finance/organizations/" + uuid(organizationId) +
                "/client-award-sources/" + uuid(opportunityId),
            options = HiltechRequestOptions(installationId = installationId),
            decode = {
                json.decodeFromString(FinanceClientAwardSourceRowDto.serializer(), it)
            },
        )

    private fun uuid(raw: String): String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(raw)) { "Valid UUID required." }
        return raw
    }
}
