package com.hiltech.server.people

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class StartOnboardingRequest(
    val operationId: String,
    val baseEmployeeVersion: Long,
    val onboardingPolicyRevisionId: String,
)

data class ResolveOnboardingRequirementRequest(
    val operationId: String,
    val baseVersion: Long,
    val resolution: String,
    val reason: String? = null,
)

data class ActivateEmployeeOnboardingRequest(
    val operationId: String,
    val caseBaseVersion: Long,
    val employeeBaseVersion: Long,
)

data class OnboardingRequirementResponse(
    val requirementKey: String,
    val label: String,
    val responsibility: String,
    val blocking: Boolean,
    val status: String,
    val actionCode: String?,
    val sourceStateCode: String?,
    val sortOrder: Int,
)

data class OnboardingCaseResponse(
    val caseId: String,
    val organizationId: String,
    val employeeId: String,
    val employeeCode: String,
    val employeeDisplayName: String,
    val employeeState: String,
    val employeeVersion: Long,
    val policyConfigRevisionId: String,
    val policyCode: String,
    val policyRevisionNumber: Int,
    val state: String,
    val requirements:
        List<OnboardingRequirementResponse>,
    val blockingSatisfied: Boolean,
    val caseVersion: Long,
    val startedAt: String,
    val activatedAt: String?,
)

data class OnboardingCommandResponse(
    val onboarding: OnboardingCaseResponse,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
class OnboardingController(
    private val service: OnboardingService,
) {
    @PostMapping(
        "/v1/employees/{employeeId}/onboarding",
    )
    fun start(
        servletRequest: HttpServletRequest,
        @PathVariable employeeId: String,
        @RequestHeader(
            name = "Idempotency-Key",
        )
        idempotencyKey: String,
        @RequestBody
        request: StartOnboardingRequest,
    ): OnboardingCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId
                .toOnboardingUuid(
                    "INVALID_OPERATION_ID",
                )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        val result =
            service.start(
                StartOnboardingCommand(
                    operationId =
                        operationId,
                    employeeId =
                        employeeId
                            .toOnboardingUuid(
                                "INVALID_EMPLOYEE_ID",
                            ),
                    baseEmployeeVersion =
                        request
                            .baseEmployeeVersion,
                    onboardingPolicyRevisionId =
                        request
                            .onboardingPolicyRevisionId
                            .toOnboardingUuid(
                                "INVALID_ONBOARDING_POLICY_ID",
                            ),
                    actorUserId =
                        context
                            .requireOnboardingIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return OnboardingCommandResponse(
            onboarding =
                result.onboarding.toResponse(),
            replayed = result.replayed,
            correlationId =
                context.correlationId,
        )
    }

    @GetMapping(
        "/v1/employees/{employeeId}/onboarding",
    )
    fun admin(
        servletRequest: HttpServletRequest,
        @PathVariable employeeId: String,
    ): OnboardingCaseResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        return service.adminCase(
            actorUserId =
                context
                    .requireOnboardingIdentityId(),
            employeeId =
                employeeId
                    .toOnboardingUuid(
                        "INVALID_EMPLOYEE_ID",
                    ),
        ).toResponse()
    }

    @PostMapping(
        "/v1/onboarding/{caseId}/requirements/{requirementKey}/resolve",
    )
    fun resolve(
        servletRequest: HttpServletRequest,
        @PathVariable caseId: String,
        @PathVariable requirementKey: String,
        @RequestHeader(
            name = "Idempotency-Key",
        )
        idempotencyKey: String,
        @RequestBody
        request:
            ResolveOnboardingRequirementRequest,
    ): OnboardingCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId
                .toOnboardingUuid(
                    "INVALID_OPERATION_ID",
                )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        val result =
            service.resolveRequirement(
                ResolveOnboardingRequirementCommand(
                    operationId =
                        operationId,
                    onboardingCaseId =
                        caseId.toOnboardingUuid(
                            "INVALID_ONBOARDING_CASE_ID",
                        ),
                    baseVersion =
                        request.baseVersion,
                    requirementKey =
                        requirementKey,
                    resolution =
                        request.resolution
                            .toOnboardingResolution(),
                    reason = request.reason,
                    actorUserId =
                        context
                            .requireOnboardingIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return OnboardingCommandResponse(
            onboarding =
                result.onboarding.toResponse(),
            replayed = result.replayed,
            correlationId =
                context.correlationId,
        )
    }

    @PostMapping(
        "/v1/onboarding/{caseId}/activate",
    )
    fun activate(
        servletRequest: HttpServletRequest,
        @PathVariable caseId: String,
        @RequestHeader(
            name = "Idempotency-Key",
        )
        idempotencyKey: String,
        @RequestBody
        request:
            ActivateEmployeeOnboardingRequest,
    ): OnboardingCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId
                .toOnboardingUuid(
                    "INVALID_OPERATION_ID",
                )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        val result =
            service.activate(
                ActivateEmployeeOnboardingCommand(
                    operationId =
                        operationId,
                    onboardingCaseId =
                        caseId.toOnboardingUuid(
                            "INVALID_ONBOARDING_CASE_ID",
                        ),
                    caseBaseVersion =
                        request.caseBaseVersion,
                    employeeBaseVersion =
                        request.employeeBaseVersion,
                    actorUserId =
                        context
                            .requireOnboardingIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return OnboardingCommandResponse(
            onboarding =
                result.onboarding.toResponse(),
            replayed = result.replayed,
            correlationId =
                context.correlationId,
        )
    }
}

@RestController
class OwnOnboardingController(
    private val service: OnboardingService,
) {
    @GetMapping("/v1/me/onboarding")
    fun own(
        servletRequest: HttpServletRequest,
        @RequestParam organizationId: String,
    ): OnboardingCaseResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        return service.own(
            actorUserId =
                context
                    .requireOnboardingIdentityId(),
            organizationId =
                organizationId
                    .toOnboardingUuid(
                        "INVALID_ORGANIZATION_ID",
                    ),
        ).toResponse()
    }
}

private fun OnboardingCaseView
    .toResponse():
    OnboardingCaseResponse =
    OnboardingCaseResponse(
        caseId = caseId.toString(),
        organizationId =
            organizationId.toString(),
        employeeId = employeeId.toString(),
        employeeCode = employeeCode,
        employeeDisplayName =
            employeeDisplayName,
        employeeState = employeeState.name,
        employeeVersion = employeeVersion,
        policyConfigRevisionId =
            policyConfigRevisionId.toString(),
        policyCode = policyCode,
        policyRevisionNumber =
            policyRevisionNumber,
        state = state.name,
        requirements =
            requirements.map {
                OnboardingRequirementResponse(
                    requirementKey =
                        it.requirementKey,
                    label = it.label,
                    responsibility =
                        it.responsibility.name,
                    blocking = it.blocking,
                    status = it.status.name,
                    actionCode =
                        it.actionCode,
                    sourceStateCode =
                        it.sourceStateCode,
                    sortOrder = it.sortOrder,
                )
            },
        blockingSatisfied =
            blockingSatisfied,
        caseVersion = caseVersion,
        startedAt = startedAt.toString(),
        activatedAt =
            activatedAt?.toString(),
    )

private fun String.toOnboardingResolution():
    OnboardingManualResolutionType =
    runCatching {
        OnboardingManualResolutionType
            .valueOf(
                trim().uppercase(),
            )
    }.getOrElse {
        throw ProductApiException(
            code =
                "INVALID_ONBOARDING_RESOLUTION",
            message =
                "The onboarding resolution is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun String.toOnboardingUuid(
    code: String,
): UUID =
    runCatching {
        UUID.fromString(this)
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "An onboarding identifier is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun com.hiltech.server.platform
    .HiltechRequestContextSnapshot
    .requireOnboardingIdentityId(): UUID =
    identityId
        ?.let {
            runCatching {
                UUID.fromString(it)
            }.getOrNull()
        }
        ?: throw ProductApiException(
            code = "UNAUTHENTICATED",
            message =
                "Authentication is required.",
            status =
                HttpStatus.UNAUTHORIZED,
        )
