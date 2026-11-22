package com.hiltech.shared.core.commercial

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class CommercialOpportunityDto(
    val id: String,
    val title: String,
    val source: String,
    val state: String,
    @SerialName("client_organization_id") val clientOrganizationId: String,
    @SerialName("expected_decision_date") val expectedDecisionDate: String? = null,
)
@Serializable
data class CreateCommercialOpportunityDto(
    val operationId: String,
    val organizationId: String,
    val clientOrganizationId: String,
    val title: String,
    val source: String,
    val description: String? = null,
    val expectedDecisionDate: String? = null,
)
@Serializable
data class PlanCommercialVisitDto(
    val operationId: String,
    val scheduledAt: String,
    val purpose: String,
    val siteId: String? = null,
    val notes: String? = null,
)
@Serializable
data class CommercialApprovalCommandDto(val operationId: String)
@Serializable
data class CommercialCommandResultDto(
    val id: String,
    val replayed: Boolean,
    val state: String,
    val correlationId: String,
)
@Serializable
data class ClientQuoteLineDto(
    val lineCode: String,
    val description: String,
    val quantity: JsonPrimitive,
    val unit: String,
    val unitPrice: JsonPrimitive,
)
@Serializable
data class ClientQuoteDto(
    val quoteId: String,
    val quoteCode: String,
    val versionNumber: Int,
    val clientOrganizationId: String,
    val currency: String,
    val totalPrice: JsonPrimitive,
    val paymentTerms: String,
    val deliveryTerms: String,
    val exclusions: String,
    val validityDate: String,
    val lines: List<ClientQuoteLineDto>,
)

/** Uses existing identity, device/session headers and idempotency transport. No anonymous client endpoint. */
class CommercialApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun opportunities(organizationId: String, installationId: String): List<CommercialOpportunityDto> =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/commercial/opportunities?organizationId=" + organizationId,
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(CommercialOpportunityDto.serializer()), it) },
        )

    suspend fun createOpportunity(request: CreateCommercialOpportunityDto, installationId: String) =
        command(
            "/v1/commercial/opportunities", request.operationId, installationId,
            json.encodeToString(CreateCommercialOpportunityDto.serializer(), request),
        )

    suspend fun visit(opportunityId: String, request: PlanCommercialVisitDto, installationId: String) =
        command(
            "/v1/commercial/opportunities/" + opportunityId + "/visits",
            request.operationId, installationId,
            json.encodeToString(PlanCommercialVisitDto.serializer(), request),
        )

    suspend fun requestApproval(quoteId: String, operationId: String, installationId: String) =
        command(
            "/v1/commercial/quotes/" + quoteId + "/request-approval",
            operationId, installationId,
            json.encodeToString(CommercialApprovalCommandDto.serializer(),
                CommercialApprovalCommandDto(operationId)),
        )

    suspend fun releaseApprovedQuote(quoteId: String, operationId: String, installationId: String) =
        command(
            "/v1/commercial/quotes/" + quoteId + "/release",
            operationId, installationId,
            json.encodeToString(CommercialApprovalCommandDto.serializer(),
                CommercialApprovalCommandDto(operationId)),
        )

    suspend fun customerQuote(quoteId: String, installationId: String): ClientQuoteDto =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/commercial/quotes/" + quoteId + "/customer-preview",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ClientQuoteDto.serializer(), it) },
        )

    private suspend fun command(path: String, operationId: String, installationId: String, body: String) =
        api.request(
            method = HttpMethod.Post,
            path = path,
            options = HiltechRequestOptions(
                installationId = installationId, idempotencyKey = operationId,
            ),
            requestBody = body,
            decode = { json.decodeFromString(CommercialCommandResultDto.serializer(), it) },
        )
}
