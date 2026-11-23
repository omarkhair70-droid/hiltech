package com.hiltech.shared.core.procurement

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Intentionally distinct from the buyer's supplier sourcing client.
 * The server requires real supplier master-data authority plus a separate
 * default-OFF feature gate for *every* search and mutation.
 */
@Serializable
data class SupplierRegistryCandidateDto(
    val supplierOrganizationId: String,
    val displayName: String,
    val supplierStatus: String,
    val relationshipRevision: Int,
    val relationshipState: String? = null,
)

@Serializable
data class ChangeSupplierLinkDto(
    val operationId: String,
    val supplierOrganizationId: String,
    val expectedRevisionNumber: Int,
    val state: String,
    val sourceReference: String,
)

@Serializable
data class SupplierLinkEventReceiptDto(
    val eventId: String,
    val organizationId: String,
    val supplierOrganizationId: String,
    val revisionNumber: Int,
    val state: String,
    val replayed: Boolean,
)

class SupplierMasterDataApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun search(
        purchasingOrganizationId: String,
        nameContains: String,
        installationId: String,
    ): List<SupplierRegistryCandidateDto> {
        require(nameContains.length <= 80 && nameContains.none { it.code < 32 })
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/sourcing/organizations/" + uuid(purchasingOrganizationId) +
                "/supplier-registry?query=" + nameContains.encodeURLParameter(),
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(
                ListSerializer(SupplierRegistryCandidateDto.serializer()), it,
            ) },
        )
    }

    suspend fun changeSupplierLink(
        purchasingOrganizationId: String,
        request: ChangeSupplierLinkDto,
        installationId: String,
    ): SupplierLinkEventReceiptDto = api.request(
        method = HttpMethod.Post,
        path = "/v1/sourcing/organizations/" + uuid(purchasingOrganizationId) + "/supplier-links",
        options = HiltechRequestOptions(
            installationId = installationId,
            idempotencyKey = uuid(request.operationId),
        ),
        requestBody = json.encodeToString(ChangeSupplierLinkDto.serializer(), request),
        decode = {
            json.decodeFromString(SupplierLinkEventReceiptDto.serializer(), it)
        },
    )

    private fun uuid(value: String): String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(value)) { "A valid UUID is required." }
        return value
    }
}
