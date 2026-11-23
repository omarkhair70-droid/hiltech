package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class MaintenanceReviewReadinessDto(
    val visitId: String,
    val workOrderId: String,
    val currentWorkVersion: Long,
    val currentWorkState: String,
    val reviewState: String,
    val latestEventNumber: Int,
    val requiredChecklistTotal: Int,
    val requiredChecklistPending: Int,
    val readyVisitEvidenceCount: Int,
    val missingReasons: List<String>,
    val canConfirmInternalTechnicalCompletion: Boolean,
    val clientAcceptanceRecorded: Boolean = false,
    val warrantyEntitlementConfirmed: Boolean = false,
)

@Serializable
data class MaintenanceReviewEventDto(
    val eventId: String,
    val visitId: String,
    val eventNumber: Int,
    val eventType: String,
    val resultingState: String,
    val sourceWorkVersion: Long,
    val evidenceId: String? = null,
    val reason: String,
    val visibility: String,
    val recordedAt: String,
    val duplicateReplay: Boolean,
)

@Serializable
data class RecordMaintenanceReviewDto(
    val operationId: String,
    val expectedLatestEventNumber: Int,
    val sourceWorkVersion: Long,
    val eventType: String,
    val evidenceId: String? = null,
    val reason: String,
)

/**
 * Authenticated internal Project Manager technical visit evidence only.
 * These calls never claim client acceptance or activate a warranty.
 */
class MaintenanceReviewApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun readiness(
        projectId: String, visitId: String, installationId: String,
    ): MaintenanceReviewReadinessDto {
        requireId(projectId); requireId(visitId)
        return api.request(
            method = HttpMethod.Get,
            path = path(projectId, visitId) + "/readiness",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(MaintenanceReviewReadinessDto.serializer(), it) },
        )
    }

    suspend fun events(
        projectId: String, visitId: String, installationId: String,
    ): List<MaintenanceReviewEventDto> {
        requireId(projectId); requireId(visitId)
        return api.request(
            method = HttpMethod.Get,
            path = path(projectId, visitId) + "/events",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(MaintenanceReviewEventDto.serializer()), it) },
        )
    }

    suspend fun record(
        projectId: String, visitId: String,
        input: RecordMaintenanceReviewDto, installationId: String,
    ): MaintenanceReviewEventDto {
        requireId(projectId); requireId(visitId); requireId(input.operationId)
        require(input.sourceWorkVersion > 0 && input.expectedLatestEventNumber >= 0)
        require(input.reason.isNotBlank())
        if (input.eventType == "INTERNAL_TECHNICAL_COMPLETION") {
            require(input.evidenceId != null)
        } else {
            require(input.evidenceId == null)
        }
        input.evidenceId?.let { requireId(it) }
        return api.request(
            method = HttpMethod.Post,
            path = path(projectId, visitId) + "/events",
            options = HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = input.operationId,
            ),
            requestBody = json.encodeToString(RecordMaintenanceReviewDto.serializer(), input),
            decode = { json.decodeFromString(MaintenanceReviewEventDto.serializer(), it) },
        )
    }

    private fun path(projectId: String, visitId: String) =
        "/v1/projects/" + projectId + "/maintenance-visits/" + visitId + "/review"

    private fun requireId(raw: String) {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        ).matches(raw)) { "Canonical UUID required." }
    }
}
