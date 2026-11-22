package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Authenticated, **online-only** supplier terms transport.
 *
 * No local Room queue or offline optimistic purchasing mutations. The backend
 * fail-closed procurement authority, not this client, decides visibility.
 */
class SupplierSourcingApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun openRfq(
        requirementId: String,
        request: OpenSupplierRfqDto,
        installationId: String,
    ): SupplierRfqReceiptDto = post(
        "/v1/purchase-requirements/" + uuid(requirementId) + "/supplier-rfqs",
        request.operationId, json.encodeToString(OpenSupplierRfqDto.serializer(), request),
        installationId, SupplierRfqReceiptDto.serializer(),
    )

    suspend fun eligibleNeeds(
        organizationId: String,
        installationId: String,
    ): List<SourcingEligibleNeedDto> = api.request(
        method = HttpMethod.Get,
        path = "/v1/sourcing/organizations/" + uuid(organizationId) + "/eligible-needs",
        options = HiltechRequestOptions(installationId = installationId),
        decode = {
            json.decodeFromString(ListSerializer(SourcingEligibleNeedDto.serializer()), it)
        },
    )

    suspend fun roundsForRequirement(
        requirementId: String,
        installationId: String,
    ): List<SupplierRfqDto> = api.request(
        method = HttpMethod.Get,
        path = "/v1/purchase-requirements/" + uuid(requirementId) + "/supplier-rfqs",
        options = HiltechRequestOptions(installationId = installationId),
        decode = {
            json.decodeFromString(ListSerializer(SupplierRfqDto.serializer()), it)
        },
    )

    suspend fun recordQuote(
        rfqId: String,
        request: RecordSupplierQuoteDto,
        installationId: String,
    ): SupplierQuoteReceiptDto = post(
        "/v1/sourcing/rfqs/" + uuid(rfqId) + "/quote-evidence",
        request.operationId, json.encodeToString(RecordSupplierQuoteDto.serializer(), request),
        installationId, SupplierQuoteReceiptDto.serializer(),
    )

    suspend fun recordTechnicalAssessment(
        quoteEvidenceId: String,
        request: RecordSupplierQuoteTechnicalAssessmentDto,
        installationId: String,
    ): SupplierQuoteTechnicalAssessmentReceiptDto = post(
        "/v1/sourcing/quotes/" + uuid(quoteEvidenceId) + "/technical-assessments",
        request.operationId,
        json.encodeToString(RecordSupplierQuoteTechnicalAssessmentDto.serializer(), request),
        installationId, SupplierQuoteTechnicalAssessmentReceiptDto.serializer(),
    )

    suspend fun comparison(
        rfqId: String,
        installationId: String,
    ): SupplierRfqComparisonDto = api.request(
        method = HttpMethod.Get,
        path = "/v1/sourcing/rfqs/" + uuid(rfqId) + "/comparison",
        options = HiltechRequestOptions(installationId = installationId),
        decode = { json.decodeFromString(SupplierRfqComparisonDto.serializer(), it) },
    )

    private suspend fun <T> post(
        path: String,
        operationId: String,
        body: String,
        installationId: String,
        serializer: KSerializer<T>,
    ): T = api.request(
        method = HttpMethod.Post,
        path = path,
        options = HiltechRequestOptions(
            installationId = installationId, idempotencyKey = uuid(operationId),
        ),
        requestBody = body,
        decode = { json.decodeFromString(serializer, it) },
    )

    private fun uuid(id: String): String {
        require(
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                .matches(id),
        ) { "A valid UUID is required." }
        return id
    }
}
