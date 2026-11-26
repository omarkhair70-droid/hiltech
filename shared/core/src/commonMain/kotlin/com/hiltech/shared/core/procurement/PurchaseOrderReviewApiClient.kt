package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

class PurchaseOrderReviewApiClient(private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }) {
    internal val commands = PurchaseReviewCommandSession()
    suspend fun completed(approvalRequestId: String, installationId: String): PurchaseReviewCompletedDto =
        api.request(HttpMethod.Get, "/v1/sourcing/purchase-review-receipts/${uuid(approvalRequestId)}", HiltechRequestOptions(installationId),
            decode = { json.decodeFromString(PurchaseReviewCompletedDto.serializer(), it) }).also {
                uuid(it.candidateId)
                require(it.approvalRequestId == approvalRequestId && it.approvalState in setOf("APPROVED", "REJECTED", "CHANGE_REQUESTED", "SUPERSEDED") &&
                    it.completedAt.isNotBlank() && (it.decidedByCurrentUser || it.approvalState == "SUPERSEDED"))
            }
    suspend fun read(candidateId: String, approvalRequestId: String, installationId: String): PurchaseOrderReviewDto =
        api.request(HttpMethod.Get, path(candidateId, approvalRequestId), HiltechRequestOptions(installationId),
            decode = { json.decodeFromString(PurchaseOrderReviewDto.serializer(), it) }).also {
                require(it.candidateId == candidateId && it.approvalRequestId == approvalRequestId && it.approvalRequestVersion > 0)
            }

    suspend fun quotationPage(detail: PurchaseOrderReviewDto, pageIndex: Int, installationId: String): PurchaseQuotePageDto {
        val proof = requireNotNull(detail.quotation)
        require(proof.available && proof.sizeBytes in 1..16L * 1024 * 1024 && pageIndex in 0..499)
        val source = detail.exactSource()
        return api.request(HttpMethod.Post, path(detail.candidateId, detail.approvalRequestId) + "/quote-pages",
            HiltechRequestOptions(installationId), json.encodeToString(PurchaseQuotePageRequestDto.serializer(), PurchaseQuotePageRequestDto(source, pageIndex)),
            decode = { json.decodeFromString(PurchaseQuotePageDto.serializer(), it) }).also {
                uuid(it.deliveryId)
                require(it.candidateId == detail.candidateId && it.approvalRequestId == detail.approvalRequestId && it.source == source &&
                    it.pageIndex == pageIndex && it.pageCount in 1..500 && pageIndex < it.pageCount && it.imagePngBase64.length in 1..24 * 1024 * 1024)
            }
    }

    suspend fun recordOpened(candidateId: String, approvalRequestId: String, request: OpenPurchaseReviewQuoteDto, installationId: String): PurchaseQuoteOpeningReceiptDto =
        api.request(HttpMethod.Post, path(candidateId, approvalRequestId) + "/quote-openings",
            HiltechRequestOptions(installationId, uuid(request.operationId)), json.encodeToString(OpenPurchaseReviewQuoteDto.serializer(), request),
            decode = { json.decodeFromString(PurchaseQuoteOpeningReceiptDto.serializer(), it) }).also {
                require(it.operationId == request.operationId && it.candidateId == candidateId && it.approvalRequestId == approvalRequestId && it.opened)
            }

    suspend fun decide(candidateId: String, approvalRequestId: String, request: DecidePurchaseReviewDto, installationId: String): PurchaseReviewDecisionReceiptDto {
        require(request.decision in setOf("APPROVE", "REQUEST_CHANGE", "REJECT", "CLOSE_STALE"))
        require(request.comment.orEmpty().length <= 1000)
        require(request.decision == "APPROVE" || !request.comment.isNullOrBlank())
        return api.request(HttpMethod.Post, path(candidateId, approvalRequestId) + "/decisions",
            HiltechRequestOptions(installationId, uuid(request.operationId)), json.encodeToString(DecidePurchaseReviewDto.serializer(), request),
            decode = { json.decodeFromString(PurchaseReviewDecisionReceiptDto.serializer(), it) }).also {
                val expected = when (request.decision) { "APPROVE" -> "APPROVED"; "REQUEST_CHANGE" -> "CHANGE_REQUESTED"; "REJECT" -> "REJECTED"; else -> "SUPERSEDED" }
                require(it.operationId == request.operationId && it.candidateId == candidateId && it.approvalRequestId == approvalRequestId &&
                    it.approvalState in setOf(expected, "SUPERSEDED"))
            }
    }

    private fun path(candidateId: String, approvalRequestId: String) =
        "/v1/sourcing/po-candidates/${uuid(candidateId)}/reviews/${uuid(approvalRequestId)}"
    private fun uuid(value: String): String { require(UUID_PATTERN.matches(value)); return value }
    private companion object { val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$") }
}
