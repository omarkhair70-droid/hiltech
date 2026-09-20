package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

class OnboardingApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun start(
        employeeId: String,
        request: StartOnboardingRequestDto,
        installationId: String,
    ): OnboardingCommandResponseDto {
        requireId(employeeId)
        requireId(request.operationId)
        requireId(
            request.onboardingPolicyRevisionId,
        )
        require(
            request.baseEmployeeVersion >= 1,
        )

        return command(
            method = HttpMethod.Post,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/onboarding",
            operationId =
                request.operationId,
            installationId =
                installationId,
            body =
                json.encodeToString(
                    StartOnboardingRequestDto
                        .serializer(),
                    request,
                ),
        )
    }

    suspend fun admin(
        employeeId: String,
        installationId: String,
    ): OnboardingCaseDto {
        requireId(employeeId)

        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/onboarding",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    OnboardingCaseDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun own(
        organizationId: String,
        installationId: String,
    ): OnboardingCaseDto {
        requireId(organizationId)

        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/me/onboarding?organizationId=" +
                    organizationId,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    OnboardingCaseDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun resolve(
        caseId: String,
        requirementKey: String,
        request:
            ResolveOnboardingRequirementRequestDto,
        installationId: String,
    ): OnboardingCommandResponseDto {
        requireId(caseId)
        requireId(request.operationId)
        require(
            request.baseVersion >= 1,
        )
        require(
            REQUIREMENT_KEY.matches(
                requirementKey,
            ),
        )

        return command(
            method = HttpMethod.Post,
            path =
                "/v1/onboarding/" +
                    caseId +
                    "/requirements/" +
                    requirementKey +
                    "/resolve",
            operationId =
                request.operationId,
            installationId =
                installationId,
            body =
                json.encodeToString(
                    ResolveOnboardingRequirementRequestDto
                        .serializer(),
                    request,
                ),
        )
    }

    suspend fun activate(
        caseId: String,
        request:
            ActivateEmployeeOnboardingRequestDto,
        installationId: String,
    ): OnboardingCommandResponseDto {
        requireId(caseId)
        requireId(request.operationId)
        require(
            request.caseBaseVersion >= 1,
        )
        require(
            request.employeeBaseVersion >= 1,
        )

        return command(
            method = HttpMethod.Post,
            path =
                "/v1/onboarding/" +
                    caseId +
                    "/activate",
            operationId =
                request.operationId,
            installationId =
                installationId,
            body =
                json.encodeToString(
                    ActivateEmployeeOnboardingRequestDto
                        .serializer(),
                    request,
                ),
        )
    }

    suspend fun provisionIdentity(
        employeeId: String,
        request:
            ProvisionEmployeeIdentityRequestDto,
        installationId: String,
    ): EmployeeIdentityInvitationDto {
        requireId(employeeId)
        requireId(request.operationId)
        require(
            request.baseEmployeeVersion >= 1,
        )

        return api.request(
            method = HttpMethod.Post,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/identity-invitations",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    ProvisionEmployeeIdentityRequestDto
                        .serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    EmployeeIdentityInvitationDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    private suspend fun command(
        method: HttpMethod,
        path: String,
        operationId: String,
        installationId: String,
        body: String,
    ): OnboardingCommandResponseDto =
        api.request(
            method = method,
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
                    OnboardingCommandResponseDto
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
        private val REQUIREMENT_KEY =
            Regex(
                "^[A-Z][A-Z0-9_-]{0,63}$",
            )
    }
}
