package com.hiltech.shared.core.work

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class ReadinessAssignmentApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun readiness(
        workOrderId: String,
        installationId: String,
    ): WorkReadinessDto =
        api.request(
            HttpMethod.Get,
            "/v1/work-orders/${id(workOrderId)}/readiness",
            HiltechRequestOptions(
                installationId = installationId,
            ),
            decode = {
                json.decodeFromString(it)
            },
        )

    suspend fun eligibleTargets(
        workOrderId: String,
        installationId: String,
    ): List<EligibleTargetDto> =
        api.request(
            HttpMethod.Get,
            "/v1/work-orders/${id(workOrderId)}/eligible-targets",
            HiltechRequestOptions(
                installationId = installationId,
            ),
            decode = {
                json.decodeFromString(it)
            },
        )

    suspend fun workQueueContext(
        projectId: String,
        installationId: String,
    ): List<WorkQueueContextDto> =
        api.request(
            HttpMethod.Get,
            "/v1/projects/${id(projectId)}/work-queue-context",
            HiltechRequestOptions(
                installationId = installationId,
            ),
            decode = {
                json.decodeFromString(it)
            },
        )

    suspend fun evaluate(
        workOrderId: String,
        request: EvaluateReadinessRequestDto,
        installationId: String,
    ): WorkReadinessMutationResponseDto =
        command(
            HttpMethod.Post,
            "/v1/work-orders/${id(workOrderId)}/evaluate-readiness",
            request.operationId,
            request,
            EvaluateReadinessRequestDto.serializer(),
            WorkReadinessMutationResponseDto.serializer(),
            installationId,
        )

    suspend fun assign(
        workOrderId: String,
        request: AssignWorkRequestDto,
        installationId: String,
    ): WorkAssignmentMutationResponseDto =
        command(
            HttpMethod.Post,
            "/v1/work-orders/${id(workOrderId)}/assign",
            request.operationId,
            request,
            AssignWorkRequestDto.serializer(),
            WorkAssignmentMutationResponseDto.serializer(),
            installationId,
        )

    suspend fun reassign(
        workOrderId: String,
        request: ReassignWorkRequestDto,
        installationId: String,
    ): WorkAssignmentMutationResponseDto =
        command(
            HttpMethod.Post,
            "/v1/work-orders/${id(workOrderId)}/reassign",
            request.operationId,
            request,
            ReassignWorkRequestDto.serializer(),
            WorkAssignmentMutationResponseDto.serializer(),
            installationId,
        )

    suspend fun waive(
        workOrderId: String,
        requirementId: String,
        request: WaiveReadinessRequirementRequestDto,
        installationId: String,
    ): WorkReadinessMutationResponseDto =
        command(
            HttpMethod.Post,
            "/v1/work-orders/${id(workOrderId)}/readiness-requirements/${id(requirementId)}/waive",
            request.operationId,
            request,
            WaiveReadinessRequirementRequestDto.serializer(),
            WorkReadinessMutationResponseDto.serializer(),
            installationId,
        )

    private suspend fun <T, R> command(
        method: HttpMethod,
        path: String,
        operationId: String,
        request: T,
        requestSerializer: KSerializer<T>,
        resultSerializer: KSerializer<R>,
        installationId: String,
    ): R {
        id(operationId)
        return api.request(
            method,
            path,
            HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = operationId,
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

    private fun id(value: String): String =
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
