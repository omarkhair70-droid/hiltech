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
 * Every request rechecks the current supplier registrar responsibility.
 */
@Serializable
data class SupplierRegistryCandidateDto(
    val supplierOrganizationId: String,
    val displayName: String,
    val supplierStatus: String,
    val relationshipRevision: Int,
    val relationshipState: String? = null,
    val organizationCode: String = "",
    val profileVersion: Long = 0,
)

@Serializable
data class SupplierRegistryCapabilitiesDto(val organizationId: String, val available: Boolean, val canMaintain: Boolean)

@Serializable
data class SupplierProfileDto(
    val legalName: String = "",
    val displayName: String = "",
    val contactName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val taxReference: String? = null,
    val address: String? = null,
)

@Serializable
data class SupplierRegistryDetailDto(
    val organizationId: String,
    val supplierOrganizationId: String,
    val organizationCode: String,
    val supplierStatus: String,
    val relationshipRevision: Int,
    val relationshipState: String? = null,
    val version: Long,
    val profile: SupplierProfileDto,
)

@Serializable
data class SupplierRegistryHistoryEntryDto(
    val eventId: String,
    val action: String,
    val revision: Long,
    val recordedAt: String,
    val reason: String? = null,
)

@Serializable
data class CreateSupplierDto(val operationId: String, val profile: SupplierProfileDto)

@Serializable
data class UpdateSupplierProfileDto(val operationId: String, val baseVersion: Long, val profile: SupplierProfileDto)

@Serializable
data class SupplierProfileReceiptDto(
    val operationId: String,
    val organizationId: String,
    val supplierOrganizationId: String,
    val version: Long,
    val action: String,
    val replayed: Boolean,
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
    suspend fun capabilities(organizationId: String, installationId: String): SupplierRegistryCapabilitiesDto = api.request(
        method = HttpMethod.Get, path = registryPath(organizationId) + "/capabilities",
        options = HiltechRequestOptions(installationId = installationId),
        decode = { json.decodeFromString<SupplierRegistryCapabilitiesDto>(it).also { result ->
            check(sameUuid(result.organizationId, organizationId))
            check(!result.canMaintain || result.available)
        } },
    )

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

    suspend fun detail(organizationId: String, supplierId: String, installationId: String): SupplierRegistryDetailDto = api.request(
        method = HttpMethod.Get, path = registryPath(organizationId) + "/" + uuid(supplierId),
        options = HiltechRequestOptions(installationId = installationId),
        decode = { json.decodeFromString<SupplierRegistryDetailDto>(it).also { result ->
            check(sameUuid(result.organizationId, organizationId) && sameUuid(result.supplierOrganizationId, supplierId))
            check(result.version >= 0 && result.relationshipRevision >= 0)
        } },
    )

    suspend fun history(organizationId: String, supplierId: String, installationId: String): List<SupplierRegistryHistoryEntryDto> = api.request(
        method = HttpMethod.Get, path = registryPath(organizationId) + "/" + uuid(supplierId) + "/history",
        options = HiltechRequestOptions(installationId = installationId),
        decode = { json.decodeFromString(ListSerializer(SupplierRegistryHistoryEntryDto.serializer()), it) },
    )

    suspend fun createSupplier(organizationId: String, request: CreateSupplierDto, installationId: String): SupplierProfileReceiptDto = api.request(
        method = HttpMethod.Post, path = registryPath(organizationId),
        options = HiltechRequestOptions(installationId = installationId, idempotencyKey = uuid(request.operationId)),
        requestBody = json.encodeToString(CreateSupplierDto.serializer(), request),
        decode = { json.decodeFromString<SupplierProfileReceiptDto>(it).also { receipt ->
            check(sameUuid(receipt.operationId, request.operationId) && sameUuid(receipt.organizationId, organizationId))
            uuid(receipt.supplierOrganizationId)
            check(receipt.action == "CREATED" && receipt.version == 1L)
        } },
    )

    suspend fun updateProfile(
        organizationId: String, supplierId: String, request: UpdateSupplierProfileDto, installationId: String,
    ): SupplierProfileReceiptDto = api.request(
        method = HttpMethod.Post, path = registryPath(organizationId) + "/" + uuid(supplierId) + "/profile",
        options = HiltechRequestOptions(installationId = installationId, idempotencyKey = uuid(request.operationId)),
        requestBody = json.encodeToString(UpdateSupplierProfileDto.serializer(), request),
        decode = { json.decodeFromString<SupplierProfileReceiptDto>(it).also { receipt ->
            check(sameUuid(receipt.operationId, request.operationId) && sameUuid(receipt.organizationId, organizationId) &&
                sameUuid(receipt.supplierOrganizationId, supplierId))
            check(receipt.action == "UPDATED" && receipt.version == request.baseVersion + 1)
        } },
    )

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
            json.decodeFromString(SupplierLinkEventReceiptDto.serializer(), it).also { receipt ->
                check(sameUuid(receipt.organizationId, purchasingOrganizationId) &&
                    sameUuid(receipt.supplierOrganizationId, request.supplierOrganizationId))
                check(receipt.state == request.state && receipt.revisionNumber == request.expectedRevisionNumber)
                uuid(receipt.eventId)
            }
        },
    )

    private fun registryPath(organizationId: String): String =
        "/v1/sourcing/organizations/" + uuid(organizationId) + "/supplier-registry"

    private fun sameUuid(a: String, b: String): Boolean = uuid(a).equals(uuid(b), ignoreCase = true)

    private fun uuid(value: String): String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(value)) { "A valid UUID is required." }
        return value
    }
}
