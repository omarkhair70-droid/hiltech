package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ServiceDeliveryIntent(
    val operationId: String, val organizationId: String, val projectId: String, val kind: String, val title: String,
    val versionId: String? = null, val draft: HandoverDraftRequestDto? = null, val internalReview: HandoverReviewRecordDto? = null,
    val proposal: ServiceDeliveryProposeRequest? = null, val proposalId: String? = null, val review: ServiceDeliveryReviewRequest? = null,
    val ticket: CreateInternalSupportTicketDto? = null,
)

class ServiceDeliveryApiClient(private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }) {
    /** Reuses the canonical support command; its project supplies company and client ownership. */
    suspend fun openIncident(project: String, actor: String, request: CreateInternalSupportTicketDto, installation: String): InternalSupportTicketDto {
        canonicalId(actor)
        return SupportDeskApiClient(api, json).open(project, request, installation).also { ticket ->
            canonicalId(ticket.ticketId); canonicalId(ticket.clientOrganizationId)
            check(same(ticket.projectId, project) && same(ticket.openedByUserId, actor))
            check(ticket.title == request.title.trim() && ticket.description == request.description.trim())
            check(ticket.severityCode == request.severityCode && ticket.priorityCode == request.priorityCode)
            check(ticket.clientVisibility == "INTERNAL_ONLY")
        }
    }

    suspend fun workspace(org: String, project: String, installation: String): ServiceDeliveryWorkspaceDto = api.request(
        HttpMethod.Get, projectPath(project), HiltechRequestOptions(installationId = installation), decode = {
            json.decodeFromString<ServiceDeliveryWorkspaceDto>(it).also { source ->
                check(same(source.organizationId, org) && same(source.projectId, project))
                check(same(source.readiness.projectId, project) && same(source.readiness.organizationId, org))
                check(source.packages.all { p -> same(p.projectId, project) } && source.proposals.all { p -> same(p.projectId, project) && same(p.organizationId, org) })
            }
        })

    suspend fun reviewQueue(org: String, installation: String): ServiceDeliveryReviewQueueDto = api.request(
        HttpMethod.Get, reviewPath(org), HiltechRequestOptions(installationId = installation), decode = {
            json.decodeFromString<ServiceDeliveryReviewQueueDto>(it).also { source ->
                check(same(source.organizationId, org) && source.items.all { p -> same(p.organizationId, org) })
            }
        })

    suspend fun proposal(org: String, id: String, installation: String): ServiceDeliveryProposalDto = api.request(
        HttpMethod.Get, reviewPath(org) + "/" + canonicalId(id), HiltechRequestOptions(installationId = installation), decode = {
            json.decodeFromString<ServiceDeliveryProposalDto>(it).also { p -> check(same(p.organizationId, org) && same(p.proposalId, id)) }
        })

    suspend fun createDocument(org: String, project: String, request: ServiceDeliveryDocumentRequest, installation: String): ServiceDeliveryDocumentSlotDto = api.request(
        HttpMethod.Post, projectPath(project) + "/documents", HiltechRequestOptions(installationId = installation, idempotencyKey = canonicalId(request.operationId)),
        requestBody = json.encodeToString(request), decode = {
            json.decodeFromString<ServiceDeliveryDocumentSlotDto>(it).also { slot ->
                check(same(slot.organizationId, org) && same(slot.projectId, project) && slot.purpose == request.purpose && slot.title == request.title.trim())
                check(slot.evidenceType == "SERVICE_DELIVERY_" + request.purpose); canonicalId(slot.slotId)
            }
        })

    suspend fun propose(org: String, project: String, request: ServiceDeliveryProposeRequest, installation: String): ServiceDeliveryReceipt = api.request(
        HttpMethod.Post, projectPath(project) + "/proposals", HiltechRequestOptions(installationId = installation, idempotencyKey = canonicalId(request.operationId)),
        requestBody = json.encodeToString(request), decode = { receipt(it, org, project, request.operationId, "SUBMITTED_FOR_REVIEW") })

    suspend fun review(org: String, project: String, proposal: String, request: ServiceDeliveryReviewRequest, installation: String): ServiceDeliveryReceipt {
        require(request.decision in setOf("APPROVE", "REJECT", "CLOSE_STALE"))
        val expected = when (request.decision) { "APPROVE" -> "RECORDED"; "REJECT" -> "REJECTED"; else -> "SUPERSEDED" }
        return api.request(HttpMethod.Post, reviewPath(org) + "/" + canonicalId(proposal) + "/decisions",
            HiltechRequestOptions(installationId = installation, idempotencyKey = canonicalId(request.operationId)), requestBody = json.encodeToString(request),
            decode = { receipt(it, org, project, request.operationId, expected).also { r -> check(same(r.recordId, proposal)) } })
    }

    suspend fun evidencePage(org: String, project: String, proposal: String?, evidence: ServiceDeliveryProofDto, page: Int, installation: String): ServiceDeliveryEvidencePageDto {
        require(page in 0..499)
        val path = if (proposal == null) projectPath(project) + "/documents/${canonicalId(evidence.evidenceId)}/pages/$page"
            else reviewPath(org) + "/${canonicalId(proposal)}/documents/${canonicalId(evidence.evidenceId)}/pages/$page"
        return api.request(HttpMethod.Post, path, HiltechRequestOptions(installationId = installation), decode = {
            json.decodeFromString<ServiceDeliveryEvidencePageDto>(it).also { source ->
                check(same(source.organizationId, org) && same(source.projectId, project) && source.proposalId == proposal && same(source.evidenceId, evidence.evidenceId))
                check(source.pageNumber == page && source.pageCount in 1..500 && page < source.pageCount)
                check(source.originalSha256 == evidence.sha256 && source.originalContentType == evidence.contentType && source.imageBase64.length in 1..22_369_624)
                if (proposal != null) canonicalId(requireNotNull(source.viewReceiptId))
            }
        })
    }

    suspend fun packageDownload(org: String, project: String, release: String, evidence: String?, installation: String): ServiceDeliveryPackageDto {
        canonicalId(org)
        val suffix = if (evidence == null) "/package" else "/documents/" + canonicalId(evidence)
        return api.request(HttpMethod.Post, projectPath(project) + "/releases/" + canonicalId(release) + suffix, HiltechRequestOptions(installationId = installation),
            decode = { json.decodeFromString<ServiceDeliveryPackageDto>(it).also { file ->
                check(same(file.projectId, project) && same(file.releaseEventId, release) && file.filename.isNotBlank() && file.filename.none { c -> c == '/' || c == '\\' || c.code < 32 })
                check(file.base64.length in 1..89_478_488 && Regex("^[a-f0-9]{64}$").matches(file.sha256))
                check(file.contentType in setOf("application/zip", "application/pdf", "image/png", "image/jpeg", "text/csv", "application/octet-stream"))
            } })
    }

    suspend fun confirmDisplayed(page: ServiceDeliveryEvidencePageDto, installation: String): ServiceDeliveryDisplayDto {
        val proposal = requireNotNull(page.proposalId)
        val receipt = requireNotNull(page.viewReceiptId)
        return api.request(HttpMethod.Post, reviewPath(page.organizationId) + "/${canonicalId(proposal)}/display-confirmations/${canonicalId(receipt)}",
            HiltechRequestOptions(installationId = installation), decode = {
                json.decodeFromString<ServiceDeliveryDisplayDto>(it).also { result ->
                    check(result.confirmed && same(result.organizationId, page.organizationId) && same(result.projectId, page.projectId) &&
                        same(result.proposalId, proposal) && same(result.evidenceId, page.evidenceId) && same(result.viewReceiptId, receipt))
                }
            })
    }

    private fun receipt(text: String, org: String, project: String, operation: String, result: String): ServiceDeliveryReceipt =
        json.decodeFromString<ServiceDeliveryReceipt>(text).also {
            check(same(it.organizationId, org) && same(it.projectId, project) && same(it.operationId, operation) && it.result == result); canonicalId(it.recordId)
        }
    private fun projectPath(project: String) = "/v1/projects/${canonicalId(project)}/service-delivery"
    private fun reviewPath(org: String) = "/v1/service/organizations/${canonicalId(org)}/delivery-reviews"
    private fun same(a: String, b: String) = canonicalId(a).equals(canonicalId(b), true)
    private fun canonicalId(value: String): String = value.also { require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(it)) }
}

data class ServiceDeliveryFailure(val unknown: Boolean, val rejected: Boolean)
fun serviceDeliveryFailure(previouslyUnknown: Boolean, error: Throwable): ServiceDeliveryFailure {
    val api = error as? HiltechApiException
    if (api?.code in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED")) return ServiceDeliveryFailure(previouslyUnknown, false)
    val rejected = api != null && api.httpStatus?.let { it in 400..499 } == true && api.envelope?.code == api.code && !api.retryable && api.code != "IDEMPOTENCY_KEY_REUSED"
    val stored = rejected && api?.code == "OPERATION_PREVIOUSLY_FAILED"
    return ServiceDeliveryFailure(!stored && (previouslyUnknown || !rejected), rejected && (!previouslyUnknown || stored))
}
