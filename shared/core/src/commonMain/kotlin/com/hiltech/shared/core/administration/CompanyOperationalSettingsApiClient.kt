package com.hiltech.shared.core.administration

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PurchaseOrderNumberingHistoryDto(
    val version: Long, val prefix: String, val changedAt: String, val changedByDisplayName: String, val reason: String,
)
@Serializable
data class PurchaseOrderNumberingDto(
    val organizationId: String, val source: String, val prefix: String?, val version: Long, val ready: Boolean,
    val exampleCode: String?, val changedAt: String?, val history: List<PurchaseOrderNumberingHistoryDto> = emptyList(),
)
@Serializable
data class CompanyPurchaseOrderNumberingIntent(
    val operationId: String, val organizationId: String, val expectedVersion: Long,
    val expectedSource: String, val expectedPrefix: String?, val prefix: String, val reason: String,
)
@Serializable
private data class ConfigureCompanyPurchaseOrderNumberingRequest(
    val operationId: String, val expectedVersion: Long, val expectedSource: String,
    val expectedPrefix: String?, val prefix: String, val reason: String,
)
@Serializable
data class CompanyPurchaseOrderNumberingReceiptDto(
    val operationId: String, val organizationId: String, val version: Long, val prefix: String,
    val current: Boolean, val replayed: Boolean, val resultCode: String,
)

fun validCompanyPurchaseOrderPrefix(value: String): Boolean = value.length in 2..24 && Regex("^[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*$").matches(value)

class CompanyOperationalSettingsApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun purchaseOrderNumbering(organizationId: String, installationId: String): PurchaseOrderNumberingDto =
        api.request(HttpMethod.Get, path(organizationId), HiltechRequestOptions(installationId),
            decode = { json.decodeFromString(PurchaseOrderNumberingDto.serializer(), it) }).also { validate(it, organizationId) }

    suspend fun configurePurchaseOrderNumbering(intent: CompanyPurchaseOrderNumberingIntent, installationId: String,
        onCommandSending: () -> Unit = {}): CompanyPurchaseOrderNumberingReceiptDto {
        requireUuid(intent.operationId)
        require(intent.expectedVersion >= 0 && intent.expectedSource in setOf("COMPANY", "DEPLOYMENT", "NOT_CONFIGURED"))
        require((intent.expectedSource == "COMPANY") == (intent.expectedVersion > 0))
        require((intent.expectedSource == "NOT_CONFIGURED") == (intent.expectedPrefix == null))
        require(validCompanyPurchaseOrderPrefix(intent.prefix) && intent.reason.trim().length in 3..1000)
        val endpoint = path(intent.organizationId)
        val body = json.encodeToString(ConfigureCompanyPurchaseOrderNumberingRequest.serializer(), ConfigureCompanyPurchaseOrderNumberingRequest(
            intent.operationId, intent.expectedVersion, intent.expectedSource, intent.expectedPrefix, intent.prefix, intent.reason))
        onCommandSending()
        return api.request(HttpMethod.Post, endpoint, HiltechRequestOptions(installationId, intent.operationId), body,
            decode = { json.decodeFromString(CompanyPurchaseOrderNumberingReceiptDto.serializer(), it) }).also {
            require(it.operationId == intent.operationId && it.organizationId == intent.organizationId &&
                it.prefix == intent.prefix && it.version == intent.expectedVersion + 1 && it.resultCode == "PURCHASE_ORDER_NUMBERING_CONFIGURED")
        }
    }
    private fun validate(value: PurchaseOrderNumberingDto, organizationId: String) {
        require(value.organizationId == organizationId && value.version >= 0 && value.source in setOf("COMPANY", "DEPLOYMENT", "NOT_CONFIGURED"))
        require((value.source == "COMPANY") == (value.version > 0))
        require((value.source == "NOT_CONFIGURED") == (value.prefix == null))
        require(!value.ready || (value.prefix != null && value.exampleCode != null))
    }
    private fun path(organizationId: String): String {
        requireUuid(organizationId)
        return "/v1/organizations/$organizationId/administration/purchase-order-numbering"
    }
    private fun requireUuid(value: String) = require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(value))
}
