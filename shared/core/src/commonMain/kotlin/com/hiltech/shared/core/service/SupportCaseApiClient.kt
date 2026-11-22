package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SupportCaseRecordDto(
    val operationId: String,
    val expectedLatestEventNumber: Int,
    val eventType: String,
    val reason: String,
    val workOrderId: String? = null,
    val evidenceId: String? = null,
)

@Serializable
data class SupportCaseEventDto(
    val eventId: String,
    val ticketId: String,
    val eventNumber: Int,
    val eventType: String,
    val resultingState: String,
    val reason: String,
    val workOrderId: String?,
    val evidenceId: String?,
    val recordedAt: String,
    val visibility: String,
    val duplicateReplay: Boolean,
)

@Serializable
data class SupportCaseSummaryDto(
    val ticketId: String,
    val internalState: String,
    val latestEventNumber: Int,
    val linkedWorkOrderId: String?,
    val coverageState: String,
    val slaState: String,
)

class SupportCaseApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun summary(projectId: String, ticketId: String, installationId: String): SupportCaseSummaryDto {
        canonical(projectId); canonical(ticketId)
        return api.request(
            HttpMethod.Get,
            "/v1/projects/" + projectId + "/service-tickets/" + ticketId + "/case",
            HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(SupportCaseSummaryDto.serializer(), it) },
        )
    }

    suspend fun workChoices(
        projectId: String, ticketId: String, installationId: String,
    ): List<SupportCaseWorkChoice> {
        canonical(projectId); canonical(ticketId)
        return api.request(
            HttpMethod.Get,
            "/v1/projects/" + projectId + "/service-tickets/" + ticketId + "/case/work-choices",
            HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(SupportCaseWorkChoice.serializer()), it) },
        )
    }

    suspend fun readyEvidenceChoices(
        projectId: String, ticketId: String, installationId: String,
    ): List<SupportCaseEvidenceChoice> {
        canonical(projectId); canonical(ticketId)
        return api.request(
            HttpMethod.Get,
            "/v1/projects/" + projectId + "/service-tickets/" + ticketId + "/case/ready-evidence",
            HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(SupportCaseEvidenceChoice.serializer()), it) },
        )
    }

    suspend fun history(
        projectId: String, ticketId: String, installationId: String,
    ): List<SupportCaseEventDto> {
        canonical(projectId); canonical(ticketId)
        return api.request(
            HttpMethod.Get,
            "/v1/projects/" + projectId + "/service-tickets/" + ticketId + "/case/events",
            HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(SupportCaseEventDto.serializer()), it) },
        )
    }

    suspend fun record(
        projectId: String, ticketId: String,
        request: SupportCaseRecordDto, installationId: String,
    ): SupportCaseEventDto {
        canonical(projectId); canonical(ticketId); canonical(request.operationId)
        request.workOrderId?.let(::canonical)
        request.evidenceId?.let(::canonical)
        require(request.expectedLatestEventNumber >= 0)
        require(request.reason.isNotBlank())
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/projects/" + projectId + "/service-tickets/" + ticketId + "/case/events",
            options = HiltechRequestOptions(installationId = installationId, idempotencyKey = request.operationId),
            requestBody = json.encodeToString(SupportCaseRecordDto.serializer(), request),
            decode = { json.decodeFromString(SupportCaseEventDto.serializer(), it) },
        )
    }

    private fun canonical(s: String) {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(s)) { "Canonical UUID required" }
    }
}
