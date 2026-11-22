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
data class CommercialDealDetailDto(
    val opportunity: CommercialOpportunityDetailDto,
    val qualification: List<CommercialQualificationDto> = emptyList(),
    val tenders: List<CommercialTenderSummaryDto> = emptyList(),
    val visits: List<CommercialVisitSummaryDto> = emptyList(),
    val scopeVersions: List<CommercialScopeVersionDto> = emptyList(),
    val quotes: List<CommercialQuoteSummaryDto> = emptyList(),
    val contracts: List<CommercialContractSummaryDto> = emptyList(),
    val clientPurchaseOrders: List<CommercialClientPoSummaryDto> = emptyList(),
    val handoff: List<CommercialHandoffSummaryDto> = emptyList(),
)

@Serializable
data class CommercialOpportunityDetailDto(
    val id: String,
    val title: String,
    val state: String,
    val version: Long,
    val source: String,
    @SerialName("client_organization_id") val clientOrganizationId: String,
)

@Serializable
data class CommercialQualificationDto(
    val id: String,
    @SerialName("discovery_summary") val discoverySummary: String,
    @SerialName("open_questions") val openQuestions: String,
)

@Serializable
data class CommercialTenderSummaryDto(
    val id: String,
    @SerialName("tender_type") val tenderType: String,
    @SerialName("revision_number") val revisionNumber: Int,
    @SerialName("submission_state") val submissionState: String,
    @SerialName("reference_number") val referenceNumber: String? = null,
)

@Serializable
data class CommercialVisitSummaryDto(
    val id: String,
    val state: String,
    val purpose: String,
    val version: Long,
)

@Serializable
data class CommercialScopeVersionDto(
    val id: String,
    @SerialName("version_number") val versionNumber: Int,
    val description: String,
    val inclusions: String,
    val exclusions: String,
    val assumptions: String,
)

@Serializable
data class CommercialQuoteSummaryDto(
    val id: String,
    @SerialName("quote_code") val quoteCode: String,
    @SerialName("version_number") val versionNumber: Int,
    val state: String,
    @SerialName("total_price") val totalPrice: JsonPrimitive,
    val currency: String,
)

@Serializable
data class CommercialContractSummaryDto(
    val id: String,
    @SerialName("quote_version_id") val quoteVersionId: String,
    @SerialName("contract_code") val contractCode: String,
    val state: String,
)

@Serializable
data class CommercialClientPoSummaryDto(
    val id: String,
    @SerialName("contract_id") val contractId: String,
    @SerialName("client_po_number") val clientPoNumber: String,
    val verified: Boolean,
)

@Serializable
data class CommercialHandoffSummaryDto(
    @SerialName("project_id") val projectId: String,
    @SerialName("quote_version_id") val quoteVersionId: String,
    @SerialName("contract_id") val contractId: String,
    @SerialName("client_po_id") val clientPoId: String,
)

@Serializable
data class QualifyCommercialOpportunityDto(
    val operationId: String,
    val baseVersion: Long,
    val discoverySummary: String,
    val openQuestions: String = "",
)

@Serializable
data class CommercialEvidenceConfirmationDto(
    val operationId: String,
    val evidenceId: String,
)

@Serializable
data class CommercialScopeDto(
    val operationId: String,
    val description: String,
    val inclusions: String,
    val exclusions: String,
    val assumptions: String,
    val technicalConstraints: String,
    val clientResponsibilities: String,
)

@Serializable
data class CommercialBoqLineDto(
    val lineCode: String,
    val description: String,
    val category: String,
    val quantity: JsonPrimitive,
    val unit: String,
    val internalUnitCost: JsonPrimitive? = null,
    val sellUnitPrice: JsonPrimitive,
)

@Serializable
data class CommercialBoqDto(
    val operationId: String,
    val scopeVersionId: String,
    val currency: String,
    val lines: List<CommercialBoqLineDto>,
)

@Serializable
data class CommercialQuoteDraftDto(
    val operationId: String,
    val boqVersionId: String,
    val paymentTerms: String,
    val deliveryTerms: String,
    val exclusions: String,
    val validityDate: String,
)

@Serializable
data class CommercialContractDraftDto(
    val operationId: String,
    val quoteVersionId: String,
    val contractCode: String,
    val effectiveDate: String? = null,
    val endDate: String? = null,
    val warrantyTerms: String? = null,
    val maintenanceTerms: String? = null,
)

@Serializable
data class CommercialClientPoDraftDto(
    val operationId: String,
    val contractId: String,
    val clientPoNumber: String,
    val issuedOn: String,
    val amount: JsonPrimitive,
    val currency: String,
)

@Serializable
data class CommercialConversionDto(
    val operationId: String,
    val contractId: String,
    val clientPoId: String,
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

    suspend fun detail(opportunityId: String, installationId: String): CommercialDealDetailDto =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/commercial/opportunities/" + opportunityId,
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(CommercialDealDetailDto.serializer(), it) },
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

    suspend fun qualify(opportunityId: String, request: QualifyCommercialOpportunityDto, installationId: String) =
        command("/v1/commercial/opportunities/" + opportunityId + "/qualify",
            request.operationId, installationId,
            json.encodeToString(QualifyCommercialOpportunityDto.serializer(), request))

    suspend fun scope(opportunityId: String, request: CommercialScopeDto, installationId: String) =
        command("/v1/commercial/opportunities/" + opportunityId + "/scopes",
            request.operationId, installationId,
            json.encodeToString(CommercialScopeDto.serializer(), request))

    suspend fun boq(opportunityId: String, request: CommercialBoqDto, installationId: String) =
        command("/v1/commercial/opportunities/" + opportunityId + "/boqs",
            request.operationId, installationId,
            json.encodeToString(CommercialBoqDto.serializer(), request))

    suspend fun draftQuote(opportunityId: String, request: CommercialQuoteDraftDto, installationId: String) =
        command("/v1/commercial/opportunities/" + opportunityId + "/quotes",
            request.operationId, installationId,
            json.encodeToString(CommercialQuoteDraftDto.serializer(), request))

    suspend fun draftContract(opportunityId: String, request: CommercialContractDraftDto, installationId: String) =
        command("/v1/commercial/opportunities/" + opportunityId + "/contracts",
            request.operationId, installationId,
            json.encodeToString(CommercialContractDraftDto.serializer(), request))

    suspend fun draftClientPo(opportunityId: String, request: CommercialClientPoDraftDto, installationId: String) =
        command("/v1/commercial/opportunities/" + opportunityId + "/client-pos",
            request.operationId, installationId,
            json.encodeToString(CommercialClientPoDraftDto.serializer(), request))

    suspend fun confirmSigned(contractId: String, evidenceId: String,
                              operationId: String, installationId: String) =
        command("/v1/commercial/contracts/" + contractId + "/confirm-signed",
            operationId, installationId,
            json.encodeToString(CommercialEvidenceConfirmationDto.serializer(),
                CommercialEvidenceConfirmationDto(operationId, evidenceId)))

    suspend fun verifyClientPo(clientPoId: String, evidenceId: String,
                              operationId: String, installationId: String) =
        command("/v1/commercial/client-pos/" + clientPoId + "/verify",
            operationId, installationId,
            json.encodeToString(CommercialEvidenceConfirmationDto.serializer(),
                CommercialEvidenceConfirmationDto(operationId, evidenceId)))

    suspend fun convertToProject(opportunityId: String, request: CommercialConversionDto, installationId: String) =
        command("/v1/commercial/opportunities/" + opportunityId + "/convert",
            request.operationId, installationId,
            json.encodeToString(CommercialConversionDto.serializer(), request))

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
