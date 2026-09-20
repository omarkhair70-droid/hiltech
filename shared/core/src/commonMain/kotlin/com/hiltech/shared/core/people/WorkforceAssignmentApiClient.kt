package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

class WorkforceAssignmentApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun currentForEmployee(
        employeeId: String,
        installationId: String,
    ): WorkforceAssignmentDto {
        requireId(employeeId)

        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/workforce-assignment",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    WorkforceAssignmentDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun own(
        organizationId: String,
        installationId: String,
    ): WorkforceAssignmentDto {
        requireId(organizationId)

        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/me/workforce-assignment?organizationId=" +
                    organizationId,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    WorkforceAssignmentDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun structure(
        organizationId: String,
        installationId: String,
        limit: Int = 200,
    ): WorkforceStructureDto {
        requireId(organizationId)
        require(limit in 1..200)

        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/workforce-assignments?organizationId=" +
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
                    WorkforceStructureDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun history(
        employeeId: String,
        installationId: String,
        limit: Int = 100,
    ): WorkforceStructureDto {
        requireId(employeeId)
        require(limit in 1..200)

        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/workforce-assignments?limit=" +
                    limit,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    WorkforceStructureDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun change(
        employeeId: String,
        request:
            ChangeWorkforceAssignmentRequestDto,
        installationId: String,
    ): WorkforceAssignmentCommandResponseDto {
        requireId(employeeId)
        requireId(request.operationId)
        requireId(
            request.currentAssignmentId,
        )
        require(
            request.baseAssignmentVersion >= 1,
        )
        request.teamId?.let(::requireId)
        request.reportsToEmployeeId
            ?.let(::requireId)

        return api.request(
            method = HttpMethod.Post,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/workforce-assignment/change",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    ChangeWorkforceAssignmentRequestDto
                        .serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    WorkforceAssignmentCommandResponseDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun create(
        employeeId: String,
        request:
            CreateWorkforceAssignmentRequestDto,
        installationId: String,
    ): WorkforceAssignmentCommandResponseDto {
        requireId(employeeId)
        requireId(request.operationId)
        require(request.baseEmployeeVersion >= 1)
        request.teamId?.let(::requireId)
        request.reportsToEmployeeId
            ?.let(::requireId)

        return api.request(
            method = HttpMethod.Post,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/workforce-assignment",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    CreateWorkforceAssignmentRequestDto
                        .serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    WorkforceAssignmentCommandResponseDto
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
