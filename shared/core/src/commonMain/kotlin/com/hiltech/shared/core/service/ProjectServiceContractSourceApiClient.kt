package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProjectServiceContractSourceDto(
    val projectId: String,
    val sourceState: String,
    val contractId: String?,
    val clientPoId: String?,
    val sourceEffectiveDate: String?,
    val sourceEndDate: String?,
    val hasWrittenWarrantyTerms: Boolean,
    val hasWrittenMaintenanceTerms: Boolean,
    val warrantyEntitlementState: String,
    val slaPolicyState: String,
    val maintenanceScheduleState: String,
    val renewalEligibilityState: String,
    val clientVisibility: String,
)

class ProjectServiceContractSourceApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun read(
        projectId: String, installationId: String,
    ): ProjectServiceContractSourceDto {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(projectId)) { "Canonical project ID required" }
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/projects/" + projectId + "/service-contract-source",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ProjectServiceContractSourceDto.serializer(), it) },
        )
    }
}
