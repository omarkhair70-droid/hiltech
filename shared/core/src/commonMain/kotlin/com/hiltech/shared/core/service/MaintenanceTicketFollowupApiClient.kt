package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class LinkMaintenanceFindingTicketDto(
    val operationId: String,
    val ticketId: String,
    val reason: String,
)

@Serializable
data class MaintenanceTicketFollowupDto(
    val followupId: String,
    val findingId: String,
    val visitId: String,
    val ticketId: String,
    val reason: String,
    val visibility: String,
    val recordedAt: String,
    val duplicateReplay: Boolean,
)

class MaintenanceTicketFollowupApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun forVisit(
        projectId: String, visitId: String, installationId: String,
    ): List<MaintenanceTicketFollowupDto> {
        requireUuid(projectId); requireUuid(visitId)
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/projects/" + projectId +
                "/maintenance-visits/" + visitId + "/ticket-followups",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(MaintenanceTicketFollowupDto.serializer()), it) },
        )
    }

    suspend fun link(
        projectId: String, visitId: String, findingId: String,
        request: LinkMaintenanceFindingTicketDto, installationId: String,
    ): MaintenanceTicketFollowupDto {
        requireUuid(projectId); requireUuid(visitId); requireUuid(findingId)
        requireUuid(request.operationId); requireUuid(request.ticketId)
        require(request.reason.isNotBlank())
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/projects/" + projectId +
                "/maintenance-visits/" + visitId + "/ticket-followups/" + findingId,
            options = HiltechRequestOptions(
                installationId = installationId, idempotencyKey = request.operationId,
            ),
            requestBody = json.encodeToString(LinkMaintenanceFindingTicketDto.serializer(), request),
            decode = { json.decodeFromString(MaintenanceTicketFollowupDto.serializer(), it) },
        )
    }

    private fun requireUuid(value: String) {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(value)) { "Canonical UUID required" }
    }
}
