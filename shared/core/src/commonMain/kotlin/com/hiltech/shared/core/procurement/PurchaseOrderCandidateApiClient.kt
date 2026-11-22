package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Explicit buyer-only S03 preflight: no approval or PO issuance endpoint. */
@Serializable
data class PurchaseOrderCandidatePreviewDto(
    val rfqId: String,
    val quoteId: String,
    val supplierOrganizationId: String? = null,
    val sourceCurrent: Boolean,
    val quoteLatest: Boolean,
    val quotedTotal: String? = null,
    val currency: String? = null,
    val blockers: List<String>,
    val canPropose: Boolean,
    val purchaseAuthorized: Boolean = false,
    val purchaseOrderIssued: Boolean = false,
)

@Serializable
data class ProposePurchaseOrderCandidateDto(
    val operationId: String,
    val quoteEvidenceId: String,
    val expectedQuoteRevision: Int,
    val rationale: String,
)

@Serializable
data class PurchaseOrderCandidateReceiptDto(
    val candidateId: String,
    val rfqId: String,
    val quoteId: String,
    val requirementVersion: Long,
    val candidateVersion: Long,
    val state: String,
    val replayed: Boolean,
    val purchaseAuthorized: Boolean = false,
    val purchaseOrderIssued: Boolean = false,
)

@Serializable
data class RequestPurchaseCandidateApprovalDto(val operationId: String)

@Serializable
data class PurchaseCandidateApprovalReceiptDto(
    val candidateId: String,
    val candidateVersion: Long,
    val approvalRequestId: String? = null,
    val approvalRequired: Boolean,
    val approvalState: String? = null,
    val policyKey: String,
    val policyVersion: Int,
    val replayed: Boolean,
    val purchaseAuthorized: Boolean = false,
    val purchaseOrderIssued: Boolean = false,
)

class PurchaseOrderCandidateApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun preview(
        rfqId: String,
        quoteId: String,
        installationId: String,
    ): PurchaseOrderCandidatePreviewDto = api.request(
        method = HttpMethod.Get,
        path = "/v1/sourcing/rfqs/" + uuid(rfqId) +
            "/po-candidate-preflight?quoteId=" + uuid(quoteId),
        options = HiltechRequestOptions(installationId = installationId),
        decode = { json.decodeFromString(PurchaseOrderCandidatePreviewDto.serializer(), it) },
    )

    suspend fun propose(
        rfqId: String,
        request: ProposePurchaseOrderCandidateDto,
        installationId: String,
    ): PurchaseOrderCandidateReceiptDto = api.request(
        method = HttpMethod.Post,
        path = "/v1/sourcing/rfqs/" + uuid(rfqId) + "/po-candidates",
        options = HiltechRequestOptions(
            installationId = installationId,
            idempotencyKey = uuid(request.operationId),
        ),
        requestBody = json.encodeToString(ProposePurchaseOrderCandidateDto.serializer(), request),
        decode = { json.decodeFromString(PurchaseOrderCandidateReceiptDto.serializer(), it) },
    )

    suspend fun requestApproval(
        candidateId: String,
        request: RequestPurchaseCandidateApprovalDto,
        installationId: String,
    ): PurchaseCandidateApprovalReceiptDto = api.request(
        method = HttpMethod.Post,
        path = "/v1/sourcing/po-candidates/" + uuid(candidateId) + "/request-approval",
        options = HiltechRequestOptions(
            installationId = installationId,
            idempotencyKey = uuid(request.operationId),
        ),
        requestBody = json.encodeToString(RequestPurchaseCandidateApprovalDto.serializer(), request),
        decode = { json.decodeFromString(PurchaseCandidateApprovalReceiptDto.serializer(), it) },
    )

    private fun uuid(id: String): String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(id)) { "A valid UUID is required." }
        return id
    }
}
