package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

class OffboardingApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun start(
        employeeId: String,
        request:
            StartEmployeeOffboardingRequestDto,
        installationId: String,
    ): OffboardingCommandResponseDto {
        requireId(employeeId)
        requireId(request.operationId)
        require(
            request.baseEmployeeVersion >= 1,
        )

        return command(
            path =
                "/v1/employees/" +
                    employeeId +
                    "/offboarding",
            operationId =
                request.operationId,
            installationId =
                installationId,
            body =
                json.encodeToString(
                    StartEmployeeOffboardingRequestDto
                        .serializer(),
                    request,
                ),
        )
    }

    suspend fun read(
        employeeId: String,
        installationId: String,
    ): OffboardingCaseDto {
        requireId(employeeId)

        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/offboarding",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    OffboardingCaseDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun revokeAccess(
        caseId: String,
        request:
            RevokeEmployeeOffboardingAccessRequestDto,
        installationId: String,
    ): OffboardingCommandResponseDto {
        requireId(caseId)
        requireId(request.operationId)
        require(request.baseCaseVersion >= 1)

        return command(
            path =
                "/v1/offboarding/" +
                    caseId +
                    "/access/revoke",
            operationId =
                request.operationId,
            installationId =
                installationId,
            body =
                json.encodeToString(
                    RevokeEmployeeOffboardingAccessRequestDto
                        .serializer(),
                    request,
                ),
        )
    }

    suspend fun resolveHr(
        caseId: String,
        request:
            ResolveOffboardingClearanceRequestDto,
        installationId: String,
    ): OffboardingCommandResponseDto {
        requireId(caseId)
        requireId(request.operationId)
        require(request.baseCaseVersion >= 1)

        return command(
            path =
                "/v1/offboarding/" +
                    caseId +
                    "/hr-clearance/resolve",
            operationId =
                request.operationId,
            installationId =
                installationId,
            body =
                json.encodeToString(
                    ResolveOffboardingClearanceRequestDto
                        .serializer(),
                    request,
                ),
        )
    }

    suspend fun resolveExternal(
        caseId: String,
        clearanceType: String,
        request:
            ResolveOffboardingClearanceRequestDto,
        installationId: String,
    ): OffboardingCommandResponseDto {
        requireId(caseId)
        requireId(request.operationId)
        require(request.baseCaseVersion >= 1)
        require(
            clearanceType in
                EXTERNAL_CLEARANCES,
        )

        return command(
            path =
                "/v1/offboarding/" +
                    caseId +
                    "/clearances/" +
                    clearanceType +
                    "/resolve",
            operationId =
                request.operationId,
            installationId =
                installationId,
            body =
                json.encodeToString(
                    ResolveOffboardingClearanceRequestDto
                        .serializer(),
                    request,
                ),
        )
    }

    suspend fun complete(
        caseId: String,
        request:
            CompleteEmployeeOffboardingRequestDto,
        installationId: String,
    ): OffboardingCommandResponseDto {
        requireId(caseId)
        requireId(request.operationId)
        require(request.baseCaseVersion >= 1)
        require(request.baseEmployeeVersion >= 1)
        require(request.baseEmploymentVersion >= 1)

        return command(
            path =
                "/v1/offboarding/" +
                    caseId +
                    "/complete",
            operationId =
                request.operationId,
            installationId =
                installationId,
            body =
                json.encodeToString(
                    CompleteEmployeeOffboardingRequestDto
                        .serializer(),
                    request,
                ),
        )
    }

    private suspend fun command(
        path: String,
        operationId: String,
        installationId: String,
        body: String,
    ): OffboardingCommandResponseDto =
        api.request(
            method = HttpMethod.Post,
            path = path,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                    idempotencyKey =
                        operationId,
                ),
            requestBody = body,
            decode = {
                json.decodeFromString(
                    OffboardingCommandResponseDto
                        .serializer(),
                    it,
                )
            },
        )

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

        private val EXTERNAL_CLEARANCES =
            setOf(
                "PROJECT",
                "ASSET",
                "FINANCE",
                "PAYROLL",
            )
    }
}
