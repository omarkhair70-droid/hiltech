package com.hiltech.shared.core.projects

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

class ProjectsApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun list(
        organizationId: String,
        installationId: String,
        limit: Int = 100,
    ): ProjectListDto {
        requireId(organizationId)
        require(limit in 1..200)
        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/projects?organizationId=" +
                    organizationId +
                    "&limit=" +
                    limit,
            options =
                HiltechRequestOptions(
                    installationId = installationId,
                ),
            decode = {
                json.decodeFromString(
                    ProjectListDto.serializer(),
                    it,
                )
            },
        )
    }

    suspend fun detail(
        projectId: String,
        installationId: String,
    ): ProjectSummaryDto {
        requireId(projectId)
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/projects/" + projectId,
            options =
                HiltechRequestOptions(
                    installationId = installationId,
                ),
            decode = {
                json.decodeFromString(
                    ProjectSummaryDto.serializer(),
                    it,
                )
            },
        )
    }

    suspend fun create(
        request: CreateProjectRequestDto,
        installationId: String,
    ): ProjectCommandResponseDto =
        command(
            method = HttpMethod.Post,
            path = "/v1/projects",
            operationId = request.operationId,
            installationId = installationId,
            body =
                json.encodeToString(
                    CreateProjectRequestDto.serializer(),
                    request,
                ),
        )

    suspend fun updateDetails(
        projectId: String,
        request: UpdateProjectDetailsRequestDto,
        installationId: String,
    ): ProjectCommandResponseDto {
        requireId(projectId)
        return command(
            method = HttpMethod.Put,
            path = "/v1/projects/" + projectId + "/details",
            operationId = request.operationId,
            installationId = installationId,
            body =
                json.encodeToString(
                    UpdateProjectDetailsRequestDto.serializer(),
                    request,
                ),
        )
    }

    suspend fun changeManager(
        projectId: String,
        request: ChangeProjectManagerRequestDto,
        installationId: String,
    ): ProjectCommandResponseDto {
        requireId(projectId)
        return command(
            method = HttpMethod.Post,
            path =
                "/v1/projects/" +
                    projectId +
                    "/change-manager",
            operationId = request.operationId,
            installationId = installationId,
            body =
                json.encodeToString(
                    ChangeProjectManagerRequestDto.serializer(),
                    request,
                ),
        )
    }

    suspend fun startKickoff(
        projectId: String,
        request: ProjectTransitionRequestDto,
        installationId: String,
    ): ProjectCommandResponseDto =
        transition(
            projectId,
            "start-kickoff",
            request,
            installationId,
        )

    suspend fun completeKickoff(
        projectId: String,
        request: ProjectTransitionRequestDto,
        installationId: String,
    ): ProjectCommandResponseDto =
        transition(
            projectId,
            "complete-kickoff",
            request,
            installationId,
        )

    suspend fun sites(
        projectId: String,
        installationId: String,
    ): ProjectSiteListDto {
        requireId(projectId)
        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/projects/" +
                    projectId +
                    "/sites",
            options =
                HiltechRequestOptions(
                    installationId = installationId,
                ),
            decode = {
                json.decodeFromString(
                    ProjectSiteListDto.serializer(),
                    it,
                )
            },
        )
    }

    suspend fun attachSite(
        projectId: String,
        request: AttachProjectSiteRequestDto,
        installationId: String,
    ): ProjectSiteCommandResponseDto {
        requireId(projectId)
        requireId(request.siteId)
        requireId(request.operationId)
        return api.request(
            method = HttpMethod.Post,
            path =
                "/v1/projects/" +
                    projectId +
                    "/sites",
            options =
                HiltechRequestOptions(
                    installationId = installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    AttachProjectSiteRequestDto.serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    ProjectSiteCommandResponseDto.serializer(),
                    it,
                )
            },
        )
    }

    suspend fun createSite(
        request: CreateSiteRequestDto,
        installationId: String,
    ): SiteCommandResponseDto {
        requireId(request.operationId)
        requireId(request.organizationId)
        requireId(request.clientOrganizationId)
        return api.request(
            method = HttpMethod.Post,
            path = "/v1/sites",
            options =
                HiltechRequestOptions(
                    installationId = installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    CreateSiteRequestDto.serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    SiteCommandResponseDto.serializer(),
                    it,
                )
            },
        )
    }

    suspend fun site(
        siteId: String,
        installationId: String,
    ): SiteDto {
        requireId(siteId)
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/sites/" + siteId,
            options =
                HiltechRequestOptions(
                    installationId = installationId,
                ),
            decode = {
                json.decodeFromString(
                    SiteDto.serializer(),
                    it,
                )
            },
        )
    }

    suspend fun updateSite(
        siteId: String,
        request: UpdateSiteRequestDto,
        installationId: String,
    ): SiteCommandResponseDto {
        requireId(siteId)
        requireId(request.operationId)
        return api.request(
            method = HttpMethod.Put,
            path = "/v1/sites/" + siteId + "/details",
            options =
                HiltechRequestOptions(
                    installationId = installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    UpdateSiteRequestDto.serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    SiteCommandResponseDto.serializer(),
                    it,
                )
            },
        )
    }

    private suspend fun transition(
        projectId: String,
        action: String,
        request: ProjectTransitionRequestDto,
        installationId: String,
    ): ProjectCommandResponseDto {
        requireId(projectId)
        return command(
            method = HttpMethod.Post,
            path =
                "/v1/projects/" +
                    projectId +
                    "/" +
                    action,
            operationId = request.operationId,
            installationId = installationId,
            body =
                json.encodeToString(
                    ProjectTransitionRequestDto.serializer(),
                    request,
                ),
        )
    }

    private suspend fun command(
        method: HttpMethod,
        path: String,
        operationId: String,
        installationId: String,
        body: String,
    ): ProjectCommandResponseDto {
        requireId(operationId)
        return api.request(
            method = method,
            path = path,
            options =
                HiltechRequestOptions(
                    installationId = installationId,
                    idempotencyKey = operationId,
                ),
            requestBody = body,
            decode = {
                json.decodeFromString(
                    ProjectCommandResponseDto.serializer(),
                    it,
                )
            },
        )
    }

    private fun requireId(
        value: String,
    ) {
        require(
            ID_PATTERN.matches(value),
        )
    }

    companion object {
        private val ID_PATTERN =
            Regex("^[0-9a-fA-F-]{36}$")
    }
}
