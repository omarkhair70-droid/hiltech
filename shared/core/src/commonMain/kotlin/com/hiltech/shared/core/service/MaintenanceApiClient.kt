package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class CreateMaintenanceVisitDraftDto(
    val operationId: String,
    val workOrderId: String,
    val baseWorkOrderVersion: Long,
    val plannedFor: String,
)

@Serializable
data class RecordMaintenanceFindingDto(
    val operationId: String,
    val evidenceId: String,
    val findingTypeCode: String,
    val severityCode: String,
    val description: String,
)

@Serializable
data class MaintenanceChecklistItemDto(
    val itemKey: String,
    val label: String,
    val required: Boolean,
    val completionState: String,
    val version: Long,
)

@Serializable
data class MaintenanceVisitDto(
    val visitId: String,
    val projectId: String,
    val workOrderId: String,
    val siteId: String,
    val plannedFor: String,
    val state: String,
    val coverageState: String,
    val workOrderVersionAtPlan: Long,
    val sourceChecklist: List<MaintenanceChecklistItemDto>,
    val createdAt: String,
    val duplicateReplay: Boolean,
)

@Serializable
data class MaintenanceFindingDto(
    val findingId: String,
    val visitId: String,
    val evidenceId: String,
    val findingTypeCode: String,
    val severityCode: String,
    val description: String,
    val visibility: String,
    val createdAt: String,
    val duplicateReplay: Boolean,
)

/**
 * KMP request boundary; actual session/auth/token and native navigation are
 * supplied by the existing HILTECH shell. No coverage, fees or due dates are inferred.
 */
class MaintenanceApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    suspend fun visits(projectId: String, installationId: String): List<MaintenanceVisitDto> {
        requireId(projectId)
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/projects/" + projectId + "/maintenance-visits",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(MaintenanceVisitDto.serializer()), it) },
        )
    }

    suspend fun createDraft(
        projectId: String,
        input: CreateMaintenanceVisitDraftDto,
        installationId: String,
    ): MaintenanceVisitDto {
        requireId(projectId)
        requireId(input.operationId)
        requireId(input.workOrderId)
        require(input.baseWorkOrderVersion > 0)
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/projects/" + projectId + "/maintenance-visits",
            options = HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = input.operationId,
            ),
            requestBody = json.encodeToString(CreateMaintenanceVisitDraftDto.serializer(), input),
            decode = { json.decodeFromString(MaintenanceVisitDto.serializer(), it) },
        )
    }

    suspend fun findings(
        projectId: String,
        visitId: String,
        installationId: String,
    ): List<MaintenanceFindingDto> {
        requireId(projectId)
        requireId(visitId)
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/projects/" + projectId + "/maintenance-visits/" + visitId + "/findings",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(MaintenanceFindingDto.serializer()), it) },
        )
    }

    suspend fun recordFinding(
        projectId: String,
        visitId: String,
        input: RecordMaintenanceFindingDto,
        installationId: String,
    ): MaintenanceFindingDto {
        requireId(projectId)
        requireId(visitId)
        requireId(input.operationId)
        requireId(input.evidenceId)
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/projects/" + projectId + "/maintenance-visits/" + visitId + "/findings",
            options = HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = input.operationId,
            ),
            requestBody = json.encodeToString(RecordMaintenanceFindingDto.serializer(), input),
            decode = { json.decodeFromString(MaintenanceFindingDto.serializer(), it) },
        )
    }

    private fun requireId(id: String) {
        require(
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                .matches(id)
        ) { "A canonical UUID is required." }
    }
}
