package com.hiltech.shared.core.projects

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class PlanningApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    suspend fun plan(projectId: String, installationId: String): ProjectPlanDto {
        requireId(projectId)
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/projects/$projectId/plan",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(ProjectPlanDto.serializer(), it) },
        )
    }

    suspend fun createArea(
        projectId: String,
        request: CreateAreaRequestDto,
        installationId: String,
    ): PlanningMutationResponseDto =
        command(
            HttpMethod.Post, "/v1/projects/${requireId(projectId)}/areas",
            request.operationId, request, CreateAreaRequestDto.serializer(), installationId,
        )

    suspend fun updateArea(
        areaId: String,
        request: UpdateAreaRequestDto,
        installationId: String,
    ): PlanningMutationResponseDto =
        command(
            HttpMethod.Put, "/v1/areas/${requireId(areaId)}",
            request.operationId, request, UpdateAreaRequestDto.serializer(), installationId,
        )

    suspend fun createMilestone(
        projectId: String,
        request: CreateMilestoneRequestDto,
        installationId: String,
    ): PlanningMutationResponseDto =
        command(
            HttpMethod.Post, "/v1/projects/${requireId(projectId)}/milestones",
            request.operationId, request, CreateMilestoneRequestDto.serializer(), installationId,
        )

    suspend fun updateMilestone(
        milestoneId: String,
        request: UpdateMilestoneRequestDto,
        installationId: String,
    ): PlanningMutationResponseDto =
        command(
            HttpMethod.Put, "/v1/milestones/${requireId(milestoneId)}",
            request.operationId, request, UpdateMilestoneRequestDto.serializer(), installationId,
        )

    suspend fun createWorkPackage(
        projectId: String,
        request: CreateWorkPackageRequestDto,
        installationId: String,
    ): PlanningMutationResponseDto =
        command(
            HttpMethod.Post, "/v1/projects/${requireId(projectId)}/work-packages",
            request.operationId, request, CreateWorkPackageRequestDto.serializer(), installationId,
        )

    suspend fun updateWorkPackage(
        workPackageId: String,
        request: UpdateWorkPackageRequestDto,
        installationId: String,
    ): PlanningMutationResponseDto =
        command(
            HttpMethod.Put, "/v1/work-packages/${requireId(workPackageId)}",
            request.operationId, request, UpdateWorkPackageRequestDto.serializer(), installationId,
        )

    suspend fun addDependency(
        projectId: String,
        request: AddPlanDependencyRequestDto,
        installationId: String,
    ): PlanningMutationResponseDto =
        command(
            HttpMethod.Post, "/v1/projects/${requireId(projectId)}/plan-dependencies",
            request.operationId, request, AddPlanDependencyRequestDto.serializer(), installationId,
        )

    suspend fun removeDependency(
        projectId: String,
        dependencyId: String,
        request: RemovePlanDependencyRequestDto,
        installationId: String,
    ): PlanningMutationResponseDto =
        command(
            HttpMethod.Delete,
            "/v1/projects/${requireId(projectId)}/plan-dependencies/${requireId(dependencyId)}",
            request.operationId, request, RemovePlanDependencyRequestDto.serializer(), installationId,
        )

    suspend fun markReady(
        projectId: String,
        request: MarkProjectReadyRequestDto,
        installationId: String,
    ): PlanningMutationResponseDto =
        command(
            HttpMethod.Post, "/v1/projects/${requireId(projectId)}/mark-ready",
            request.operationId, request, MarkProjectReadyRequestDto.serializer(), installationId,
        )

    private suspend fun <T> command(
        method: HttpMethod,
        path: String,
        operationId: String,
        request: T,
        serializer: KSerializer<T>,
        installationId: String,
    ): PlanningMutationResponseDto {
        requireId(operationId)
        return api.request(
            method = method,
            path = path,
            options = HiltechRequestOptions(
                installationId = installationId,
                idempotencyKey = operationId,
            ),
            requestBody = json.encodeToString(serializer, request),
            decode = { json.decodeFromString(PlanningMutationResponseDto.serializer(), it) },
        )
    }

    private fun requireId(value: String): String =
        value.trim().also {
            require(it.matches(UUID_PATTERN)) { "Identifiers must use canonical UUID format." }
        }

    private companion object {
        val UUID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
        )
    }
}
