package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class HandoverReviewRecordDto(
    val operationId: String,
    val expectedLatestEventNumber: Int,
    val eventType: String,
    val reason: String,
)

@Serializable
data class HandoverReviewEventDto(
    val eventId: String,
    val packageVersionId: String,
    val eventNumber: Int,
    val eventType: String,
    val reviewState: String,
    val sourceDigest: String,
    val reason: String,
    val createdAt: String,
    val clientVisibility: String,
    val duplicateReplay: Boolean,
)

@Serializable
data class HandoverReviewSummaryDto(
    val packageVersionId: String,
    val packageVersionNumber: Int,
    val frozenSourceDigest: String,
    val currentSourceDigest: String,
    val sourceStillMatches: Boolean,
    val isLatestPackageVersion: Boolean,
    val reviewState: String,
    val latestEventNumber: Int,
    val canIssueToClient: Boolean,
    val canAcceptOnBehalfOfClient: Boolean,
)

class HandoverReviewApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun summary(
        projectId: String, versionId: String, installationId: String,
    ): HandoverReviewSummaryDto {
        checkId(projectId); checkId(versionId)
        return api.request(
            HttpMethod.Get,
            "/v1/projects/" + projectId + "/handover/packages/" + versionId + "/internal-review",
            HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(HandoverReviewSummaryDto.serializer(), it) },
        )
    }

    suspend fun events(
        projectId: String, versionId: String, installationId: String,
    ): List<HandoverReviewEventDto> {
        checkId(projectId); checkId(versionId)
        return api.request(
            HttpMethod.Get,
            "/v1/projects/" + projectId + "/handover/packages/" + versionId + "/internal-review/events",
            HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(HandoverReviewEventDto.serializer()), it) },
        )
    }

    suspend fun record(
        projectId: String, versionId: String,
        request: HandoverReviewRecordDto, installationId: String,
    ): HandoverReviewEventDto {
        checkId(projectId); checkId(versionId); checkId(request.operationId)
        require(request.expectedLatestEventNumber >= 0)
        require(request.reason.isNotBlank())
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/projects/" + projectId + "/handover/packages/" + versionId + "/internal-review/events",
            options = HiltechRequestOptions(
                installationId = installationId, idempotencyKey = request.operationId,
            ),
            requestBody = json.encodeToString(HandoverReviewRecordDto.serializer(), request),
            decode = { json.decodeFromString(HandoverReviewEventDto.serializer(), it) },
        )
    }

    private fun checkId(raw: String) {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(raw)) { "Canonical UUID required" }
    }
}
