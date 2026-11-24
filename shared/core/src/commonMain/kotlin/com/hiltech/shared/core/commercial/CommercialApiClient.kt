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
data class CommercialWhatsappConnectionDto(val configured: Boolean,val setupAvailable: Boolean,val phoneId: String? = null,val displayPhone: String? = null,val template: String? = null,val language: String? = null,val apiVersion: String? = null,val version: Long = 0)
@Serializable
class ConnectCommercialWhatsappDto(val organizationId: String,val phoneId: String,val token: String,val template: String,val language: String,val apiVersion: String,val baseVersion: Long)
@Serializable
data class DisconnectCommercialWhatsappDto(val organizationId: String,val baseVersion: Long)

@Serializable
data class CommercialMailConnectionDto(val configured: Boolean, val setupAvailable: Boolean, val allowedHosts: List<String>, val host: String? = null, val username: String? = null, val version: Long = 0)
@Serializable
class ConnectCommercialMailDto(val organizationId: String, val host: String, val username: String, val appPassword: String, val baseVersion: Long)
@Serializable
data class DisconnectCommercialMailDto(val organizationId: String, val baseVersion: Long)

@Serializable
data class CommercialDocumentProfileDto(
    val legalName: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val registration: String = "",
    val taxNumber: String = "",
    val logoBase64: String = "",
    val quotePrefix: String = "Q",
    val paymentTerms: String = "",
    val deliveryTerms: String = "",
)
@Serializable
data class CommercialDocumentSettingsDto(val version: Long, val profile: CommercialDocumentProfileDto)
@Serializable
data class SaveCommercialDocumentSettingsDto(val operationId: String, val organizationId: String, val baseVersion: Long, val profile: CommercialDocumentProfileDto)

@Serializable
data class CommercialCapabilitiesDto(val canWrite: Boolean, val canCost: Boolean, val canRelease: Boolean, val canAward: Boolean)

@Serializable
data class CommercialOpportunityDto(
    val id: String,
    val title: String,
    val source: String,
    val state: String,
    @SerialName("client_organization_id") val clientOrganizationId: String,
    @SerialName("expected_decision_date") val expectedDecisionDate: String? = null,
    @SerialName("client_display_name") val clientDisplayName: String? = null,
)

@Serializable
data class CreateCommercialClientDto(val operationId: String, val organizationId: String, val legalName: String, val displayName: String)

@Serializable
data class CommercialClientRefDto(val id: String, @SerialName("display_name") val displayName: String, @SerialName("organization_code") val organizationCode: String)
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
    val boqVersions: List<CommercialBoqVersionRefDto> = emptyList(),
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
data class CommercialBoqVersionRefDto(val id: String, @SerialName("version_number") val versionNumber: Int, val currency: String)

@Serializable
data class CommercialQuoteSummaryDto(
    val id: String,
    @SerialName("quote_code") val quoteCode: String,
    @SerialName("version_number") val versionNumber: Int,
    val state: String,
    @SerialName("total_price") val totalPrice: JsonPrimitive,
    val currency: String,
)

/** Never include this in general-purpose customer or Sales Viewer responses. */
@Serializable
data class CommercialPrivateCostLineDto(
    @SerialName("boq_version_id") val boqVersionId: String,
    @SerialName("version_number") val versionNumber: Int,
    val currency: String,
    @SerialName("line_code") val lineCode: String,
    val description: String,
    val category: String,
    val quantity: JsonPrimitive,
    val unit: String,
    @SerialName("internal_unit_cost") val internalUnitCost: JsonPrimitive? = null,
    @SerialName("sell_unit_price") val sellUnitPrice: JsonPrimitive,
)

@Serializable
data class CommercialPrivateCostSummaryDto(
    @SerialName("boq_version_id") val boqVersionId: String,
    @SerialName("version_number") val versionNumber: Int,
    val currency: String,
    @SerialName("total_lines") val totalLines: Int,
    @SerialName("missing_cost_lines") val missingCostLines: Int,
    @SerialName("proposed_sell_total") val proposedSellTotal: JsonPrimitive,
    @SerialName("known_cost_subtotal") val knownCostSubtotal: JsonPrimitive,
    @SerialName("complete_internal_cost_total") val completeInternalCostTotal: JsonPrimitive? = null,
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
    val discountAmount: JsonPrimitive = JsonPrimitive(0),
    val taxAmount: JsonPrimitive = JsonPrimitive(0),
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
data class CommercialTenderRevisionDto(
    val operationId: String,
    val tenderType: String,
    val receivedAt: String,
    val submissionState: String = "RECEIVED",
    val referenceNumber: String? = null,
    val submissionDeadline: String? = null,
    val clarificationDeadline: String? = null,
    val sourceChannel: String? = null,
)

@Serializable
data class CompleteCommercialVisitDto(
    val operationId: String,
    val baseVersion: Long,
    val notes: String,
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
data class CommercialCloseOpportunityDto(
    val operationId: String,
    val baseVersion: Long,
    val state: String,
    val reason: String,
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
    val clientName: String? = null,
    val issuerName: String? = null,
    val releasedAt: String? = null,
    val subtotal: JsonPrimitive? = null,
    val discountAmount: JsonPrimitive = JsonPrimitive(0),
    val taxAmount: JsonPrimitive = JsonPrimitive(0),
)

@Serializable
data class CommercialQuoteDispatchDto(val id: String, val channel: String, val recipientReference: String, val recordedAt: String)
@Serializable
data class RecordCommercialQuoteDispatchDto(val operationId: String, val channel: String, val recipientReference: String)

@Serializable
data class CommercialWhatsappDto(val operationId: String,val recipient: String,val consentConfirmed: Boolean)
@Serializable
data class CommercialWhatsappStatusDto(val id: String,val recipient: String,val state: String,val createdAt: String)

@Serializable
data class CommercialQuoteEmailDto(val operationId: String, val recipient: String)
@Serializable
data class CommercialQuoteEmailStatusDto(val id: String, val recipient: String, val state: String, val createdAt: String)

@Serializable
data class ClientQuotePdfDto(val filename: String, val base64: String)

/** Uses existing identity, device/session headers and idempotency transport. No anonymous client endpoint. */
class CommercialApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun whatsappConnection(organizationId: String,installationId: String): CommercialWhatsappConnectionDto =
        api.request(method=HttpMethod.Get,path="/v1/commercial/whatsapp-connection?organizationId="+organizationId,options=HiltechRequestOptions(installationId=installationId),decode={json.decodeFromString(CommercialWhatsappConnectionDto.serializer(),it)})
    suspend fun connectWhatsapp(request: ConnectCommercialWhatsappDto,installationId: String): CommercialWhatsappConnectionDto =
        api.request(method=HttpMethod.Post,path="/v1/commercial/whatsapp-connection/connect",options=HiltechRequestOptions(installationId=installationId),requestBody=json.encodeToString(ConnectCommercialWhatsappDto.serializer(),request),decode={json.decodeFromString(CommercialWhatsappConnectionDto.serializer(),it)})
    suspend fun disconnectWhatsapp(request: DisconnectCommercialWhatsappDto,installationId: String): CommercialWhatsappConnectionDto =
        api.request(method=HttpMethod.Post,path="/v1/commercial/whatsapp-connection/disconnect",options=HiltechRequestOptions(installationId=installationId),requestBody=json.encodeToString(DisconnectCommercialWhatsappDto.serializer(),request),decode={json.decodeFromString(CommercialWhatsappConnectionDto.serializer(),it)})

    suspend fun mailConnection(organizationId: String, installationId: String): CommercialMailConnectionDto =
        api.request(method = HttpMethod.Get, path = "/v1/commercial/mail-connection?organizationId=" + organizationId,
            options = HiltechRequestOptions(installationId = installationId), decode = { json.decodeFromString(CommercialMailConnectionDto.serializer(), it) })
    suspend fun connectMail(request: ConnectCommercialMailDto, installationId: String): CommercialMailConnectionDto =
        api.request(method = HttpMethod.Post, path = "/v1/commercial/mail-connection/connect",
            options = HiltechRequestOptions(installationId = installationId), requestBody = json.encodeToString(ConnectCommercialMailDto.serializer(),request),
            decode = { json.decodeFromString(CommercialMailConnectionDto.serializer(),it) })
    suspend fun disconnectMail(request: DisconnectCommercialMailDto, installationId: String): CommercialMailConnectionDto =
        api.request(method = HttpMethod.Post, path = "/v1/commercial/mail-connection/disconnect",
            options = HiltechRequestOptions(installationId = installationId), requestBody = json.encodeToString(DisconnectCommercialMailDto.serializer(),request),
            decode = { json.decodeFromString(CommercialMailConnectionDto.serializer(),it) })

    suspend fun documentSettings(organizationId: String, installationId: String): CommercialDocumentSettingsDto =
        api.request(method = HttpMethod.Get, path = "/v1/commercial/document-settings?organizationId=" + organizationId,
            options = HiltechRequestOptions(installationId = installationId), decode = { json.decodeFromString(CommercialDocumentSettingsDto.serializer(), it) })

    suspend fun saveDocumentSettings(request: SaveCommercialDocumentSettingsDto, installationId: String) =
        command("/v1/commercial/document-settings", request.operationId, installationId, json.encodeToString(SaveCommercialDocumentSettingsDto.serializer(),request))

    suspend fun capabilities(organizationId: String, installationId: String): CommercialCapabilitiesDto =
        api.request(method = HttpMethod.Get, path = "/v1/commercial/capabilities?organizationId=" + organizationId,
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(CommercialCapabilitiesDto.serializer(), it) })

    suspend fun createClient(request: CreateCommercialClientDto, installationId: String) =
        command("/v1/commercial/clients", request.operationId, installationId, json.encodeToString(CreateCommercialClientDto.serializer(), request))

    suspend fun clients(organizationId: String, installationId: String): List<CommercialClientRefDto> =
        api.request(method = HttpMethod.Get, path = "/v1/commercial/clients?organizationId=" + organizationId,
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(CommercialClientRefDto.serializer()), it) })

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

    /** Restricted endpoint: only invoke after explicit SALES_COST_VIEW capability. */
    suspend fun privateCosting(opportunityId: String, installationId: String): List<CommercialPrivateCostLineDto> =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/commercial/opportunities/" + opportunityId + "/costing",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(CommercialPrivateCostLineDto.serializer()), it) },
        )

    /** Cost and completeness are never included in a customer-facing quote. */
    suspend fun privateCostSummary(opportunityId: String, installationId: String): List<CommercialPrivateCostSummaryDto> =
        api.request(
            method = HttpMethod.Get,
            path = "/v1/commercial/opportunities/" + opportunityId + "/cost-summary",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(CommercialPrivateCostSummaryDto.serializer()), it) },
        )

    suspend fun createOpportunity(request: CreateCommercialOpportunityDto, installationId: String) =
        command(
            "/v1/commercial/opportunities", request.operationId, installationId,
            json.encodeToString(CreateCommercialOpportunityDto.serializer(), request),
        )

    suspend fun closeOpportunity(
        opportunityId: String, request: CommercialCloseOpportunityDto, installationId: String,
    ) = command(
        "/v1/commercial/opportunities/" + opportunityId + "/close",
        request.operationId, installationId,
        json.encodeToString(CommercialCloseOpportunityDto.serializer(), request),
    )

    suspend fun visit(opportunityId: String, request: PlanCommercialVisitDto, installationId: String) =
        command(
            "/v1/commercial/opportunities/" + opportunityId + "/visits",
            request.operationId, installationId,
            json.encodeToString(PlanCommercialVisitDto.serializer(), request),
        )

    suspend fun tenderRevision(opportunityId: String, request: CommercialTenderRevisionDto, installationId: String) =
        command("/v1/commercial/opportunities/" + opportunityId + "/tenders",
            request.operationId, installationId,
            json.encodeToString(CommercialTenderRevisionDto.serializer(), request))

    suspend fun completeVisit(visitId: String, request: CompleteCommercialVisitDto, installationId: String) =
        command("/v1/commercial/visits/" + visitId + "/complete",
            request.operationId, installationId,
            json.encodeToString(CompleteCommercialVisitDto.serializer(), request))

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

    suspend fun customerPdf(quoteId: String, installationId: String): ClientQuotePdfDto =
        api.request(method = HttpMethod.Post, path = "/v1/commercial/quotes/" + quoteId + "/customer-pdf",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ClientQuotePdfDto.serializer(), it) })

    suspend fun dispatchHistory(quoteId: String, installationId: String): List<CommercialQuoteDispatchDto> =
        api.request(method = HttpMethod.Get, path = "/v1/commercial/quotes/" + quoteId + "/dispatch-history",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(CommercialQuoteDispatchDto.serializer()), it) })

    suspend fun recordDispatch(quoteId: String, request: RecordCommercialQuoteDispatchDto, installationId: String) =
        command("/v1/commercial/quotes/" + quoteId + "/dispatch-history", request.operationId, installationId,
            json.encodeToString(RecordCommercialQuoteDispatchDto.serializer(), request))

    suspend fun whatsappHistory(quoteId: String,installationId: String): List<CommercialWhatsappStatusDto> =
        api.request(method=HttpMethod.Get,path="/v1/commercial/quotes/$quoteId/whatsapp-history",options=HiltechRequestOptions(installationId=installationId),
            decode={json.decodeFromString(ListSerializer(CommercialWhatsappStatusDto.serializer()),it)})
    suspend fun sendWhatsapp(quoteId: String,request: CommercialWhatsappDto,installationId: String): CommercialWhatsappStatusDto =
        api.request(method=HttpMethod.Post,path="/v1/commercial/quotes/$quoteId/whatsapp",options=HiltechRequestOptions(installationId=installationId,idempotencyKey=request.operationId),
            requestBody=json.encodeToString(CommercialWhatsappDto.serializer(),request),decode={json.decodeFromString(CommercialWhatsappStatusDto.serializer(),it)})

    suspend fun emailHistory(quoteId: String, installationId: String): List<CommercialQuoteEmailStatusDto> =
        api.request(method = HttpMethod.Get, path = "/v1/commercial/quotes/" + quoteId + "/email-history",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(CommercialQuoteEmailStatusDto.serializer()), it) })

    suspend fun sendEmail(quoteId: String, request: CommercialQuoteEmailDto, installationId: String): CommercialQuoteEmailStatusDto =
        api.request(method = HttpMethod.Post, path = "/v1/commercial/quotes/" + quoteId + "/email",
            options = HiltechRequestOptions(installationId = installationId, idempotencyKey = request.operationId),
            requestBody = json.encodeToString(CommercialQuoteEmailDto.serializer(), request),
            decode = { json.decodeFromString(CommercialQuoteEmailStatusDto.serializer(), it) })

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
