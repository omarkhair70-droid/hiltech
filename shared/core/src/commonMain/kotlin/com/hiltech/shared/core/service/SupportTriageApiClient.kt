package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class RecordSupportTriageDto(
    val operationId: String,
    val expectedLatestEventNumber: Int,
    val eventType: String,
    val ownerEmployeeId: String? = null,
    val proposedSeverityCode: String? = null,
    val proposedPriorityCode: String? = null,
    val reason: String,
)

@Serializable
data class InternalSupportTriageEventDto(
    val eventId: String,
    val ticketId: String,
    val eventNumber: Int,
    val eventType: String,
    val ownerEmployeeId: String?,
    val proposedSeverityCode: String?,
    val proposedPriorityCode: String?,
    val reason: String,
    val clientVisibility: String,
    val createdAt: String,
    val duplicateReplay: Boolean,
)

/** Authenticated HILTECH-shell caller, internal PM/Project Admin only. */
class SupportTriageApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun events(
        projectId: String, ticketId: String, installationId: String,
    ): List<InternalSupportTriageEventDto> {
        checkId(projectId)
        checkId(ticketId)
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/projects/" + projectId + "/service-tickets/" + ticketId + "/triage",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(
                ListSerializer(InternalSupportTriageEventDto.serializer()), it
            ) },
        )
    }

    suspend fun record(
        projectId: String, ticketId: String,
        request: RecordSupportTriageDto, installationId: String,
    ): InternalSupportTriageEventDto {
        checkId(projectId)
        checkId(ticketId)
        checkId(request.operationId)
        require(request.expectedLatestEventNumber >= 0)
        require(request.reason.isNotBlank())
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/projects/" + projectId + "/service-tickets/" + ticketId + "/triage",
            options = HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = request.operationId,
            ),
            requestBody = json.encodeToString(RecordSupportTriageDto.serializer(), request),
            decode = { json.decodeFromString(InternalSupportTriageEventDto.serializer(), it) },
        )
    }

    private fun checkId(raw: String) {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(raw)) { "A canonical UUID is required." }
    }
}
