package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class OnboardingApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun workspace(employeeId: String, installationId: String): OnboardingWorkspaceDto {
        requireId(employeeId)
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/employees/$employeeId/onboarding/workspace",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(OnboardingWorkspaceDto.serializer(), it) },
        )
    }

    suspend fun applyWorkspaceIntent(
        intent: OnboardingWorkspaceIntent,
        installationId: String,
        priorOutcomeUnknown: Boolean,
        sessionCurrent: () -> Boolean,
        onCommandSending: () -> Unit,
    ): OnboardingCommandResponseDto {
        requireId(intent.operationId)
        requireId(intent.employeeId)
        requireId(intent.organizationId)
        requireId(intent.policyConfigRevisionId)
        require(intent.employeeVersion >= 1)
        if (intent.action != OnboardingWorkspaceAction.START) {
            requireId(requireNotNull(intent.caseId))
            require((intent.caseVersion ?: 0) >= 1)
        }
        if (intent.action == OnboardingWorkspaceAction.RESOLVE) {
            require(REQUIREMENT_KEY.matches(requireNotNull(intent.requirementKey)))
            require(intent.resolution in setOf("SATISFIED", "WAIVED"))
            require((intent.reason?.length ?: 0) <= 500)
            require(intent.resolution != "WAIVED" || !intent.reason.isNullOrBlank())
        }
        currentCoroutineContext().ensureActive()
        if (!sessionCurrent()) throw OnboardingPreparationException("SESSION_CHANGED")
        if (!priorOutcomeUnknown) {
            val current = try {
                workspace(intent.employeeId, installationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw OnboardingPreparationException("SOURCE_CHECK_FAILED")
            }
            if (!onboardingIntentIsCurrent(intent, current)) throw OnboardingPreparationException("SOURCE_CHANGED")
        }
        currentCoroutineContext().ensureActive()
        if (!sessionCurrent()) throw OnboardingPreparationException("SESSION_CHANGED")
        onCommandSending()
        val response = when (intent.action) {
            OnboardingWorkspaceAction.START -> start(intent.employeeId,
                StartOnboardingRequestDto(intent.operationId, intent.employeeVersion, intent.policyConfigRevisionId), installationId)
            OnboardingWorkspaceAction.RESOLVE -> resolve(requireNotNull(intent.caseId), requireNotNull(intent.requirementKey),
                ResolveOnboardingRequirementRequestDto(intent.operationId, requireNotNull(intent.caseVersion),
                    requireNotNull(intent.resolution), intent.reason), installationId)
            OnboardingWorkspaceAction.ACTIVATE -> activate(requireNotNull(intent.caseId),
                ActivateEmployeeOnboardingRequestDto(intent.operationId, requireNotNull(intent.caseVersion), intent.employeeVersion), installationId)
        }
        currentCoroutineContext().ensureActive()
        if (!sessionCurrent()) throw CancellationException("Onboarding session changed")
        check(onboardingReceiptMatches(intent, response)) { "ONBOARDING_RECEIPT_MISMATCH" }
        return response
    }

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

    suspend fun identityInvitationAvailability(
        employeeId: String,
        installationId: String,
    ): EmployeeIdentityInvitationAvailabilityDto {
        requireId(employeeId)
        return api.request(
            method = HttpMethod.Get,
            path = "/v1/employees/$employeeId/identity-invitations/availability",
            options = HiltechRequestOptions(installationId = installationId),
            decode = { json.decodeFromString(EmployeeIdentityInvitationAvailabilityDto.serializer(), it) },
        ).also { require(it.employeeId == employeeId) }
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
        require(request.deliveryMode in setOf("EMAIL_ACTION_LINK", "TEMPORARY_PASSWORD_HANDOFF"))
        require(request.requestedLogin.trim().length in 3..320 && request.requestedLogin.none(Char::isISOControl))

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
        ).also { receipt ->
            require(receipt.employeeId == employeeId && receipt.operationId == request.operationId)
            require(receipt.deliveryMode == request.deliveryMode && receipt.state == "READY" && receipt.version >= 1)
            require(!receipt.userIdentityId.isNullOrBlank())
            when (request.deliveryMode) {
                "EMAIL_ACTION_LINK" -> require(receipt.deliveryState == "SENT" && receipt.temporaryCredential == null)
                "TEMPORARY_PASSWORD_HANDOFF" -> require(receipt.deliveryState == "READY" && !receipt.temporaryCredential.isNullOrBlank())
            }
        }
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
