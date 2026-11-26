package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class InternalSupportTicketDto(
    val ticketId: String,
    val projectId: String,
    val clientOrganizationId: String,
    val title: String,
    val description: String,
    val severityCode: String,
    val priorityCode: String,
    val lifecycleState: String,
    val internalCaseState: String = "OPEN",
    val coverageState: String,
    val slaState: String,
    val clientVisibility: String,
    val openedByUserId: String,
    val createdAt: String,
    val duplicateReplay: Boolean,
)

@Serializable
data class InternalSupportNoteDto(
    val noteId: String,
    val ticketId: String,
    val body: String,
    val clientVisibility: String,
    val createdByUserId: String,
    val createdAt: String,
    val duplicateReplay: Boolean,
)

@Serializable
data class CreateInternalSupportTicketDto(
    val operationId: String,
    val baseProjectVersion: Long,
    val title: String,
    val description: String,
    val severityCode: String,
    val priorityCode: String,
)

@Serializable
data class CreateInternalSupportNoteDto(
    val operationId: String,
    val body: String,
)

/**
 * Internal PM/Project Admin only. The source project supplies client context.
 * Coverage, warranty and SLA are not guessed by the client.
 */
class SupportDeskApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    internal var noteSession = SupportNoteSession()
        private set

    internal constructor(api: HiltechApiClient, noteSession: SupportNoteSession) : this(api) {
        this.noteSession = noteSession
    }

    suspend fun list(projectId: String, installationId: String): List<InternalSupportTicketDto> {
        requireCanonicalId(projectId)
        return api.request(
            HttpMethod.Get,
            "/v1/projects/" + projectId + "/service-tickets",
            HiltechRequestOptions(installationId = installationId),
            decode = {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(InternalSupportTicketDto.serializer()), it)
            },
        )
    }

    suspend fun open(
        projectId: String,
        request: CreateInternalSupportTicketDto,
        installationId: String,
    ): InternalSupportTicketDto {
        requireCanonicalId(projectId)
        requireCanonicalId(request.operationId)
        require(request.baseProjectVersion > 0)
        require(request.title.isNotBlank() && request.description.isNotBlank())
        require(request.severityCode.isNotBlank() && request.priorityCode.isNotBlank())
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/projects/" + projectId + "/service-tickets",
            options = HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = request.operationId,
            ),
            requestBody = json.encodeToString(CreateInternalSupportTicketDto.serializer(), request),
            decode = { json.decodeFromString(InternalSupportTicketDto.serializer(), it) },
        )
    }

    suspend fun notes(
        projectId: String,
        ticketId: String,
        installationId: String,
    ): List<InternalSupportNoteDto> {
        requireCanonicalId(projectId)
        requireCanonicalId(ticketId)
        return api.request(
            HttpMethod.Get,
            "/v1/projects/" + projectId + "/service-tickets/" + ticketId + "/notes",
            HiltechRequestOptions(installationId = installationId),
            decode = {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(InternalSupportNoteDto.serializer()), it)
            },
        )
    }

    suspend fun addInternalNote(
        projectId: String, ticketId: String,
        request: CreateInternalSupportNoteDto,
        installationId: String,
    ): InternalSupportNoteDto {
        requireCanonicalId(projectId)
        requireCanonicalId(ticketId)
        requireCanonicalId(request.operationId)
        require(request.body.isNotBlank())
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/projects/" + projectId + "/service-tickets/" + ticketId + "/notes",
            options = HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = request.operationId,
            ),
            requestBody = json.encodeToString(CreateInternalSupportNoteDto.serializer(), request),
            decode = { json.decodeFromString(InternalSupportNoteDto.serializer(), it) },
        )
    }

    private fun requireCanonicalId(input: String) {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(input)) { "Expected a canonical UUID." }
    }
}
