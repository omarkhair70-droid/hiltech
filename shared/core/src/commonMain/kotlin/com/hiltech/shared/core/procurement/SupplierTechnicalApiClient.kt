package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SupplierTechnicalAssessmentCommand(
    val operationId: String, val expectedReviewRevision: Int, val decision: String, val justification: String,
    val technicalPacketId: String? = null, val packetReviewed: Boolean = false,
)
@Serializable
data class SupplierTechnicalAssessmentReceipt(val id: String, val quoteEvidenceId: String, val reviewRevision: Int, val decision: String, val replayed: Boolean)

class SupplierTechnicalApiClient(private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }) {
    internal val commands = SupplierTechnicalCommandSession()

    suspend fun queue(org: String, installation: String, search: String = "", offset: Int = 0): SupplierTechnicalQueueDto {
        require(search.length <= 160 && offset in 0..100000)
        return api.request(HttpMethod.Get, "/v1/sourcing/organizations/${technicalId(org)}/technical-review?search=${search.encodeURLParameter()}&offset=$offset&limit=40",
            HiltechRequestOptions(installationId = installation), decode = {
                json.decodeFromString<SupplierTechnicalQueueDto>(it).also { data ->
                    check(same(data.organizationId, org) && data.items.size <= 40)
                    check(data.items.all { row -> validQuote(row.quote, org) && row.packetRevision >= 0 })
                    check(data.nextOffset == null || data.nextOffset == offset + 40)
                }
            })
    }

    suspend fun detail(org: String, quote: String, installation: String): SupplierTechnicalDetailDto = api.request(
        HttpMethod.Get, quotePath(quote) + "/technical-review", HiltechRequestOptions(installationId = installation), decode = {
            json.decodeFromString<SupplierTechnicalDetailDto>(it).also { data ->
                check(validQuote(data.quote, org) && same(data.quote.quoteId, quote) && data.latestAssessmentRevision >= 0)
                check(data.packets.all { packet -> same(packet.organizationId, org) && same(packet.quoteId, quote) && validQuote(packet.needSnapshot, org) &&
                    same(packet.needSnapshot.quoteId, quote) && packet.packetRevision > 0 && technicalSha(packet.sourceHash) &&
                    packet.documents.all(::validDocument) && packet.documents.all { doc -> doc.evidenceId in packet.details.evidenceIds } &&
                    packet.displayedEvidenceIds.all { id -> id in packet.details.evidenceIds } })
                check(data.availableDocuments.all(::validDocument) && data.history.all { h -> h.reviewRevision > 0 })
            }
        })

    suspend fun publish(org: String, quote: String, request: SupplierTechnicalPublishRequest, installation: String): SupplierTechnicalPacketReceiptDto = api.request(
        HttpMethod.Post, quotePath(quote) + "/technical-packets", options(installation, request.operationId), json.encodeToString(request), decode = {
            json.decodeFromString<SupplierTechnicalPacketReceiptDto>(it).also { result ->
                check(same(result.organizationId, org) && same(result.quoteId, quote) && same(result.operationId, request.operationId))
                technicalId(result.packetId); check(result.packetRevision == request.expectedLatestRevision + 1 && result.result == "PUBLISHED_FOR_TECHNICAL_REVIEW")
            }
        })

    suspend fun assess(org: String, quote: String, request: SupplierTechnicalAssessmentCommand, installation: String): SupplierTechnicalAssessmentReceipt {
        technicalId(org); request.technicalPacketId?.let(::technicalId)
        require(request.decision in setOf("COMPLIANT", "NON_COMPLIANT", "NEEDS_CLARIFICATION") && request.expectedReviewRevision > 0)
        return api.request(HttpMethod.Post, quotePath(quote) + "/technical-assessments", options(installation, request.operationId), json.encodeToString(request), decode = {
            json.decodeFromString<SupplierTechnicalAssessmentReceipt>(it).also { result ->
                technicalId(result.id); check(same(result.quoteEvidenceId, quote) && result.reviewRevision == request.expectedReviewRevision && result.decision == request.decision)
            }
        })
    }

    suspend fun createDocument(org: String, quote: String, request: SupplierTechnicalDocumentRequest, installation: String): SupplierTechnicalDocumentSlotDto = api.request(
        HttpMethod.Post, quotePath(quote) + "/technical-documents", options(installation, request.operationId), json.encodeToString(request), decode = {
            json.decodeFromString<SupplierTechnicalDocumentSlotDto>(it).also { slot ->
                check(same(slot.operationId, request.operationId) && same(slot.organizationId, org) && same(slot.quoteId, quote) && slot.title == request.title.trim())
                technicalId(slot.slotId)
            }
        })

    suspend fun page(org: String, quote: String, packet: String?, evidence: SupplierTechnicalEvidenceDto, index: Int, installation: String): SupplierTechnicalEvidencePageDto {
        require(index in 0..499)
        val path = if (packet == null) quotePath(quote) + "/technical-documents/${technicalId(evidence.evidenceId)}/pages/$index"
            else quotePath(quote) + "/technical-packets/${technicalId(packet)}/documents/${technicalId(evidence.evidenceId)}/pages/$index"
        return api.request(HttpMethod.Post, path, HiltechRequestOptions(installationId = installation), decode = {
            json.decodeFromString<SupplierTechnicalEvidencePageDto>(it).also { page ->
                check(same(page.organizationId, org) && same(page.quoteId, quote) && page.packetId == packet && same(page.evidenceId, evidence.evidenceId))
                check(page.originalSha256 == evidence.sha256 && page.originalContentType == evidence.contentType && page.title == evidence.title)
                check(page.pageNumber == index && page.pageCount in 1..500 && index < page.pageCount && page.imageBase64.length in 1..22_369_624)
                if (packet != null) technicalId(requireNotNull(page.viewReceiptId)) else check(page.viewReceiptId == null)
            }
        })
    }

    suspend fun confirmDisplayed(page: SupplierTechnicalEvidencePageDto, installation: String): SupplierTechnicalDisplayDto {
        val packet = technicalId(requireNotNull(page.packetId)); val receipt = technicalId(requireNotNull(page.viewReceiptId))
        return api.request(HttpMethod.Post, quotePath(page.quoteId) + "/technical-packets/$packet/display-confirmations/$receipt",
            HiltechRequestOptions(installationId = installation), decode = {
                json.decodeFromString<SupplierTechnicalDisplayDto>(it).also { result ->
                    check(result.confirmed && same(result.organizationId, page.organizationId) && same(result.quoteId, page.quoteId) &&
                        same(result.packetId, packet) && same(result.evidenceId, page.evidenceId) && same(result.viewReceiptId, receipt))
                }
            })
    }

    private fun validQuote(q: SupplierTechnicalQuoteDto, org: String): Boolean {
        listOf(q.quoteId, q.rfqId, q.supplierId, q.itemId, q.requirementId).forEach(::technicalId)
        return same(q.organizationId, org) && q.quoteRevision > 0 && q.needVersion >= 2
    }
    private fun validDocument(d: SupplierTechnicalEvidenceDto): Boolean {
        technicalId(d.evidenceId)
        return d.title.isNotBlank() && technicalSha(d.sha256) && d.sizeBytes in 1..16_777_216 && d.contentType in setOf("application/pdf", "image/png", "image/jpeg")
    }
    private fun quotePath(id: String) = "/v1/sourcing/quotes/${technicalId(id)}"
    private fun options(installation: String, operation: String) = HiltechRequestOptions(installationId = installation, idempotencyKey = technicalId(operation))
    private fun same(a: String, b: String) = technicalId(a).equals(technicalId(b), ignoreCase = true)
}

internal fun technicalId(value: String): String = value.also { require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(it)) }
internal fun technicalSha(value: String) = Regex("^[a-f0-9]{64}$").matches(value)
