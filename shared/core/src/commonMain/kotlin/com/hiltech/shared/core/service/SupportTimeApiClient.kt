package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class RecordSupportTimeDto(
    val operationId: String,
    val expectedLatestEventNumber: Int,
    val eventType: String,
    val reason: String,
)

@Serializable
data class SupportTimeEventDto(
    val eventId: String,
    val ticketId: String,
    val eventNumber: Int,
    val eventType: String,
    val reason: String,
    val recordedAt: String,
    val visibility: String,
    val duplicateReplay: Boolean,
)

@Serializable
data class SupportTimeObservationDto(
    val ticketId: String,
    val openedAt: String,
    val observedAt: String,
    val caseState: String,
    val latestEventNumber: Int,
    val firstInternalAttentionAt: String? = null,
    val firstInternalAttentionElapsedSeconds: Long? = null,
    val elapsedWallSeconds: Long,
    val notedInternalHoldSeconds: Long,
    val internalHoldActive: Boolean,
    val internalHoldStartedAt: String? = null,
    val contractualResponseDeadline: String? = null,
    val contractualResolutionDeadline: String? = null,
    val contractualSlaState: String = "NOT_CONFIGURED",
    val holdSlaPauseApprovalState: String = "NOT_APPROVED",
    val customerResponseConfirmed: Boolean = false,
    val clientVisibility: String = "INTERNAL_ONLY",
)

/**
 * Authentic internal observation. There are NO contractual SLA pause, breach,
 * deadline, warranty, client-response, or calendar claims.
 */
class SupportTimeApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun observation(projectId: String, ticketId: String, installationId: String):
        SupportTimeObservationDto {
        requireId(projectId); requireId(ticketId)
        return api.request(
            method = HttpMethod.Get,
            path = path(projectId, ticketId),
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(SupportTimeObservationDto.serializer(), it) },
        )
    }

    suspend fun events(projectId: String, ticketId: String, installationId: String):
        List<SupportTimeEventDto> {
        requireId(projectId); requireId(ticketId)
        return api.request(
            method = HttpMethod.Get,
            path = path(projectId, ticketId) + "/events",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(SupportTimeEventDto.serializer()), it) },
        )
    }

    suspend fun record(
        projectId: String, ticketId: String,
        command: RecordSupportTimeDto, installationId: String,
    ): SupportTimeEventDto {
        requireId(projectId); requireId(ticketId); requireId(command.operationId)
        require(command.expectedLatestEventNumber >= 0)
        require(command.reason.isNotBlank() && command.reason.length <= 4000)
        require(command.eventType in setOf(
            "INTERNAL_ATTENTION_RECORDED","INTERNAL_HOLD_STARTED","INTERNAL_HOLD_ENDED",
        ))
        return api.request(
            method = HttpMethod.Post,
            path = path(projectId, ticketId) + "/events",
            options = HiltechRequestOptions(
                installationId = installationId, idempotencyKey = command.operationId,
            ),
            requestBody = json.encodeToString(RecordSupportTimeDto.serializer(), command),
            decode = { json.decodeFromString(SupportTimeEventDto.serializer(), it) },
        )
    }

    private fun path(projectId: String, ticketId: String) =
        "/v1/projects/" + projectId + "/support-tickets/" + ticketId + "/internal-time"

    private fun requireId(input: String) {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(input)) { "A canonical UUID is required." }
    }
}
