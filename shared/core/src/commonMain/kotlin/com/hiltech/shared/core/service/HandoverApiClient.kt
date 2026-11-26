package com.hiltech.shared.core.service

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class HandoverDocumentDto(
    val workOrderId: String,
    val documentId: String,
    val revisionId: String,
    val evidenceId: String,
    val sha256: String,
    val workOrderCode: String = "",
    val workOrderTitle: String = "",
    val documentCode: String = "",
    val documentTitle: String = "",
    val revisionLabel: String = "",
)

@Serializable
data class HandoverSourceFindingDto(
    val workOrderCode: String, val workOrderTitle: String, val requirementKey: String,
    val satisfactionState: String, val waiverReason: String?,
)

@Serializable
data class HandoverReadinessDto(
    val projectId: String,
    val organizationId: String,
    val documentCount: Int,
    val workOrderCount: Int,
    val missingReasons: List<String>,
    val canSubmitForInternalReview: Boolean,
    val canShareWithClient: Boolean,
    val canAcceptForClient: Boolean,
    val sourceDigest: String,
    val projectVersion: Long = 1,
    val sourceFindings: List<HandoverSourceFindingDto> = emptyList(),
)

@Serializable
data class HandoverDraftRequestDto(
    val operationId: String,
    val baseProjectVersion: Long,
    val expectedLatestVersion: Int,
)

@Serializable
data class HandoverPackageVersionDto(
    val packageId: String,
    val packageVersionId: String,
    val projectId: String,
    val versionNumber: Int,
    val state: String,
    val projectVersionAtSnapshot: Long,
    val sourceDigest: String,
    val missingReasonsAtSnapshot: List<String>,
    val documents: List<HandoverDocumentDto>,
    val createdAt: String,
    val duplicateReplay: Boolean,
)

/**
 * Internal-only Phase 11A client. Works on Android and Windows.
 * The server fails closed until the integration owner enables the API.
 */
class HandoverApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    suspend fun readiness(projectId: String, installationId: String): HandoverReadinessDto {
        requireUuid(projectId)
        return api.request(
            HttpMethod.Get, "/v1/projects/$projectId/handover/readiness",
            HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(HandoverReadinessDto.serializer(), it).also { dto -> check(dto.projectId == projectId) } },
        )
    }

    suspend fun latest(projectId: String, installationId: String): HandoverPackageVersionDto {
        requireUuid(projectId)
        return api.request(
            HttpMethod.Get, "/v1/projects/$projectId/handover/packages/latest",
            HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(HandoverPackageVersionDto.serializer(), it).also { dto -> check(dto.projectId == projectId) } },
        )
    }

    suspend fun createDraft(
        projectId: String,
        input: HandoverDraftRequestDto,
        installationId: String,
    ): HandoverPackageVersionDto {
        requireUuid(projectId)
        requireUuid(input.operationId)
        require(input.baseProjectVersion > 0)
        require(input.expectedLatestVersion >= 0)
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/projects/$projectId/handover/packages",
            options = HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = input.operationId,
            ),
            requestBody = json.encodeToString(HandoverDraftRequestDto.serializer(), input),
            decode = { json.decodeFromString(HandoverPackageVersionDto.serializer(), it).also { dto ->
                check(dto.projectId == projectId && dto.projectVersionAtSnapshot == input.baseProjectVersion && dto.versionNumber == input.expectedLatestVersion + 1)
            } },
        )
    }

    suspend fun history(projectId: String, installationId: String): List<HandoverPackageVersionDto> {
        requireUuid(projectId)
        return api.request(HttpMethod.Get, "/v1/projects/$projectId/handover/packages", HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ListSerializer(HandoverPackageVersionDto.serializer()), it).also { records -> check(records.all { dto -> dto.projectId == projectId }) } })
    }

    private fun requireUuid(value: String) {
        require(
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                .matches(value)
        ) { "Expected a canonical UUID." }
    }
}
