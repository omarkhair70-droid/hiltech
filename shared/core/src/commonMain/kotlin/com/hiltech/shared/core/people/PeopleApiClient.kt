package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

class PeopleApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun directory(
        organizationId: String,
        installationId: String,
        limit: Int = 100,
    ): EmployeeDirectoryDto {
        requireId(organizationId)
        require(limit in 1..100)

        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/employees?organizationId=" +
                    organizationId +
                    "&limit=" +
                    limit,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    EmployeeDirectoryDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun detail(
        employeeId: String,
        installationId: String,
    ): EmployeeDetailDto {
        requireId(employeeId)

        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/employees/" +
                    employeeId,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    EmployeeDetailDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun own(
        organizationId: String,
        installationId: String,
    ): EmployeeDetailDto {
        requireId(organizationId)

        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/me/employee?organizationId=" +
                    organizationId,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    EmployeeDetailDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun create(
        request:
            CreateEmployeeRequestDto,
        installationId: String,
    ): EmployeeCommandResponseDto {
        requireId(request.operationId)
        requireId(request.organizationId)
        request.linkedUserIdentityId
            ?.let(::requireId)

        return api.request(
            method = HttpMethod.Post,
            path = "/v1/employees",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    CreateEmployeeRequestDto
                        .serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    EmployeeCommandResponseDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun linkIdentity(
        employeeId: String,
        request:
            LinkEmployeeIdentityRequestDto,
        installationId: String,
    ): EmployeeCommandResponseDto {
        requireId(employeeId)
        requireId(request.operationId)
        requireId(request.userIdentityId)
        require(request.baseVersion >= 1)

        return api.request(
            method = HttpMethod.Post,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/identity-link",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    LinkEmployeeIdentityRequestDto
                        .serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    EmployeeCommandResponseDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun updateProfile(
        employeeId: String,
        request:
            UpdateEmployeeProfileRequestDto,
        installationId: String,
    ): EmployeeCommandResponseDto {
        requireId(employeeId)
        requireId(request.operationId)
        require(request.baseVersion >= 1)

        return api.request(
            method = HttpMethod.Put,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/profile",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    UpdateEmployeeProfileRequestDto
                        .serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    EmployeeCommandResponseDto
                        .serializer(),
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
            Regex(
                "^[0-9a-fA-F-]{36}$",
            )
    }
}
