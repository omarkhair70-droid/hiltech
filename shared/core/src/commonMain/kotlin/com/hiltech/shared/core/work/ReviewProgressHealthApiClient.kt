package com.hiltech.shared.core.work

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class ReviewProgressHealthApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun reviewWorkItems(
        installationId: String,
        limit: Int = 200,
    ): List<ReviewWorkItemDto> =
        api.request(
            HttpMethod.Get,
            "/v1/review/work-items?limit=" +
                limit.coerceIn(1, 500),
            HiltechRequestOptions(
                installationId = installationId,
            ),
            decode = {
                json.decodeFromString(it)
            },
        )

    suspend fun accept(
        workOrderId: String,
        request: ReviewDecisionRequestDto,
        installationId: String,
    ): ReviewMutationResponseDto =
        command(
            "/v1/work-orders/" +
                id(workOrderId) +
                "/accept",
            request.operationId,
            request,
            ReviewDecisionRequestDto.serializer(),
            ReviewMutationResponseDto.serializer(),
            installationId,
        )

    suspend fun requestRework(
        workOrderId: String,
        request: ReviewDecisionRequestDto,
        installationId: String,
    ): ReviewMutationResponseDto =
        command(
            "/v1/work-orders/" +
                id(workOrderId) +
                "/request-rework",
            request.operationId,
            request,
            ReviewDecisionRequestDto.serializer(),
            ReviewMutationResponseDto.serializer(),
            installationId,
        )

    suspend fun progress(
        projectId: String,
        installationId: String,
    ): ProjectProgressDto =
        api.request(
            HttpMethod.Get,
            "/v1/projects/" +
                id(projectId) +
                "/progress",
            HiltechRequestOptions(
                installationId = installationId,
            ),
            decode = {
                json.decodeFromString(it)
            },
        )

    suspend fun health(
        projectId: String,
        installationId: String,
    ): ProjectHealthDto =
        api.request(
            HttpMethod.Get,
            "/v1/projects/" +
                id(projectId) +
                "/health",
            HiltechRequestOptions(
                installationId = installationId,
            ),
            decode = {
                json.decodeFromString(it)
            },
        )

    suspend fun commandCenter(
        projectId: String,
        installationId: String,
    ): ProjectCommandCenterDto =
        api.request(
            HttpMethod.Get,
            "/v1/projects/" +
                id(projectId) +
                "/command-center",
            HiltechRequestOptions(
                installationId = installationId,
            ),
            decode = {
                json.decodeFromString(it)
            },
        )

    suspend fun putOnHold(
        projectId: String,
        request: ProjectHoldRequestDto,
        installationId: String,
    ): ProjectHoldMutationResponseDto =
        command(
            "/v1/projects/" +
                id(projectId) +
                "/put-on-hold",
            request.operationId,
            request,
            ProjectHoldRequestDto.serializer(),
            ProjectHoldMutationResponseDto.serializer(),
            installationId,
        )

    suspend fun resume(
        projectId: String,
        request: ProjectResumeRequestDto,
        installationId: String,
    ): ProjectHoldMutationResponseDto =
        command(
            "/v1/projects/" +
                id(projectId) +
                "/resume",
            request.operationId,
            request,
            ProjectResumeRequestDto.serializer(),
            ProjectHoldMutationResponseDto.serializer(),
            installationId,
        )

    private suspend fun <T, R> command(
        path: String,
        operationId: String,
        request: T,
        requestSerializer: KSerializer<T>,
        resultSerializer: KSerializer<R>,
        installationId: String,
    ): R {
        id(operationId)
        return api.request(
            HttpMethod.Post,
            path,
            HiltechRequestOptions(
                installationId =
                    installationId,
                idempotencyKey =
                    operationId,
            ),
            json.encodeToString(
                requestSerializer,
                request,
            ),
            decode = {
                json.decodeFromString(
                    resultSerializer,
                    it,
                )
            },
        )
    }

    private fun id(
        value: String,
    ): String =
        value.trim().also {
            require(UUID.matches(it)) {
                "Identifiers must use canonical UUID format."
            }
        }

    private companion object {
        val UUID =
            Regex(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
            )
    }
}
