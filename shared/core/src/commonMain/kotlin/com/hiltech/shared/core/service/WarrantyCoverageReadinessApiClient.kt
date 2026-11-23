package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WarrantyCoverageReadinessDto(
    val projectId: String,
    val ticketId: String,
    val contractId: String?,
    val clientPoId: String?,
    val commercialSourceState: String,
    val writtenWarrantyTermsPresent: Boolean,
    val coverageDecisionState: String,
    val approvalState: String,
    val coveredAssetOrSiteState: String,
    val blockerCodes: List<String>,
    val warrantyApproved: Boolean,
    val slaAuthorized: Boolean,
    val clientMayBeNotified: Boolean,
    val billableServiceAuthorized: Boolean,
    val clientVisibility: String,
)

class WarrantyCoverageReadinessApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun read(
        projectId: String, ticketId: String, installationId: String,
    ): WarrantyCoverageReadinessDto {
        val canonicalId = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )
        require(canonicalId.matches(projectId)) { "Canonical project ID required" }
        require(canonicalId.matches(ticketId)) { "Canonical ticket ID required" }
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/projects/$projectId/support-tickets/$ticketId/warranty-readiness",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(WarrantyCoverageReadinessDto.serializer(), it) },
        )
    }
}
