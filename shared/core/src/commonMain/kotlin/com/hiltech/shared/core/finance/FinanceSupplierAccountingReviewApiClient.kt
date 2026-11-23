package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RequestSupplierAccountingReviewDto(val operationId: String)

@Serializable
data class FinanceSupplierAccountingReviewStatusDto(
    val candidateId: String,
    val supplierInvoiceId: String,
    val supplierOrganizationId: String,
    val invoiceNumber: String,
    val amount: String,
    val currency: String,
    val sourceSha256: String,
    val sourceVersion: Long,
    val policyKey: String,
    val policyVersion: Int,
    val approvalRequestId: String? = null,
    val reviewState: String,
    val existingRequest: Boolean = false,
    val replayed: Boolean = false,
    val bookedPayable: Boolean = false,
    val taxCredited: Boolean = false,
    val paymentApproved: Boolean = false,
    val bankPaymentSent: Boolean = false,
    val paymentReconciled: Boolean = false,
)

/** Read a real P7 matched supplier invoice source and request review only. */
class FinanceSupplierAccountingReviewApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun request(
        candidateId: String, operationId: String, installationId: String,
    ): FinanceSupplierAccountingReviewStatusDto {
        val req = RequestSupplierAccountingReviewDto(uuid(operationId))
        return api.request(
            method = HttpMethod.Post,
            path = route(candidateId) + "/request-accounting-review",
            options = HiltechRequestOptions(
                installationId = installationId, idempotencyKey = req.operationId,
            ),
            requestBody = json.encodeToString(
                RequestSupplierAccountingReviewDto.serializer(), req,
            ),
            decode = {
                json.decodeFromString(
                    FinanceSupplierAccountingReviewStatusDto.serializer(), it,
                )
            },
        )
    }

    suspend fun status(
        candidateId: String, installationId: String,
    ): FinanceSupplierAccountingReviewStatusDto =
        api.request(
            method = HttpMethod.Get,
            path = route(candidateId) + "/accounting-review",
            options = HiltechRequestOptions(installationId = installationId),
            decode = {
                json.decodeFromString(
                    FinanceSupplierAccountingReviewStatusDto.serializer(), it,
                )
            },
        )

    private fun route(candidateId: String) =
        "/v1/finance/payable-candidates/" + uuid(candidateId)

    private fun uuid(raw: String): String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(raw)) { "Valid UUID required" }
        return raw
    }
}
