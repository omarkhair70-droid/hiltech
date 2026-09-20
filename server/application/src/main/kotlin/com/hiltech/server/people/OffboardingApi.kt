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
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

data class StartEmployeeOffboardingRequest(
    val operationId: String,
    val baseEmployeeVersion: Long,
    val lastWorkingDate: String,
    val reasonCategoryCode: String,
    val note: String? = null,
)

data class RevokeEmployeeOffboardingAccessRequest(
    val operationId: String,
    val baseCaseVersion: Long,
)

data class ResolveOffboardingClearanceRequest(
    val operationId: String,
    val baseCaseVersion: Long,
    val resolution: String,
    val reason: String? = null,
)

data class CompleteEmployeeOffboardingRequest(
    val operationId: String,
    val baseCaseVersion: Long,
    val baseEmployeeVersion: Long,
    val baseEmploymentVersion: Long,
)

data class OffboardingClearanceResponse(
    val type: String,
    val state: String,
    val source: String,
    val reason: String?,
    val resolvedAt: String?,
    val version: Long,
)

data class OffboardingAccessFactsResponse(
    val linkedIdentityPresent: Boolean,
    val activeOrganizationMemberships: Int,
    val activeSessions: Int,
    val currentWorkforceAssignmentPresent: Boolean,
    val clear: Boolean,
)

data class OffboardingCaseResponse(
    val caseId: String,
    val organizationId: String,
    val employeeId: String,
    val employeeCode: String,
    val employeeDisplayName: String,
    val employeeState: String,
    val employeeVersion: Long,
    val activeEmploymentId: String?,
    val employmentStartDate: String?,
    val employmentVersion: Long?,
    val state: String,
    val lastWorkingDate: String,
    val reasonCategoryCode: String,
    val note: String?,
    val clearances:
        List<OffboardingClearanceResponse>,
    val accessFacts:
        OffboardingAccessFactsResponse,
    val canComplete: Boolean,
    val blockers: List<String>,
    val caseVersion: Long,
    val startedAt: String,
    val completedAt: String?,
)

data class OffboardingCommandResponse(
    val offboarding: OffboardingCaseResponse,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
class OffboardingController(
    private val service: OffboardingService,
) {
    @PostMapping(
        "/v1/employees/{employeeId}/offboarding",
    )
    fun start(
        servletRequest:
            HttpServletRequest,
        @PathVariable
        employeeId: String,
        @RequestHeader(
            name = "Idempotency-Key",
        )
        idempotencyKey: String,
        @RequestBody
        request:
            StartEmployeeOffboardingRequest,
    ): OffboardingCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId
                .toOffboardingUuid(
                    "INVALID_OPERATION_ID",
                )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        val result =
            service.start(
                StartEmployeeOffboardingCommand(
                    operationId =
                        operationId,
                    employeeId =
                        employeeId
                            .toOffboardingUuid(
                                "INVALID_EMPLOYEE_ID",
                            ),
                    baseEmployeeVersion =
                        request.baseEmployeeVersion,
                    lastWorkingDate =
                        request.lastWorkingDate
                            .toOffboardingDate(
                                "INVALID_LAST_WORKING_DATE",
                            ),
                    reasonCategoryCode =
                        request.reasonCategoryCode,
                    note = request.note,
                    actorUserId =
                        context
                            .requireOffboardingIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return result.toResponse(
            context.correlationId,
        )
    }

    @GetMapping(
        "/v1/employees/{employeeId}/offboarding",
    )
    fun read(
        servletRequest:
            HttpServletRequest,
        @PathVariable
        employeeId: String,
    ): OffboardingCaseResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )

        return service.adminCase(
            actorUserId =
                context
                    .requireOffboardingIdentityId(),
            employeeId =
                employeeId
                    .toOffboardingUuid(
                        "INVALID_EMPLOYEE_ID",
                    ),
        ).toResponse()
    }

    @PostMapping(
        "/v1/offboarding/{caseId}/access/revoke",
    )
    fun revokeAccess(
        servletRequest:
            HttpServletRequest,
        @PathVariable
        caseId: String,
        @RequestHeader(
            name = "Idempotency-Key",
        )
        idempotencyKey: String,
        @RequestBody
        request:
            RevokeEmployeeOffboardingAccessRequest,
    ): OffboardingCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId
                .toOffboardingUuid(
                    "INVALID_OPERATION_ID",
                )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        return service.revokeAccess(
            RevokeEmployeeOffboardingAccessCommand(
                operationId =
                    operationId,
                caseId =
                    caseId.toOffboardingUuid(
                        "INVALID_OFFBOARDING_CASE_ID",
                    ),
                baseCaseVersion =
                    request.baseCaseVersion,
                actorUserId =
                    context
                        .requireOffboardingIdentityId(),
                correlationId =
                    context.correlationId,
            ),
        ).toResponse(
            context.correlationId,
        )
    }

    @PostMapping(
        "/v1/offboarding/{caseId}/hr-clearance/resolve",
    )
    fun resolveHr(
        servletRequest:
            HttpServletRequest,
        @PathVariable
        caseId: String,
        @RequestHeader(
            name = "Idempotency-Key",
        )
        idempotencyKey: String,
        @RequestBody
        request:
            ResolveOffboardingClearanceRequest,
    ): OffboardingCommandResponse =
        resolveClearance(
            servletRequest =
                servletRequest,
            caseId = caseId,
            clearanceType =
                OffboardingClearanceType.HR,
            idempotencyKey =
                idempotencyKey,
            request = request,
            hr = true,
        )

    @PostMapping(
        "/v1/offboarding/{caseId}/clearances/{clearanceType}/resolve",
    )
    fun resolveExternal(
        servletRequest:
            HttpServletRequest,
        @PathVariable
        caseId: String,
        @PathVariable
        clearanceType: String,
        @RequestHeader(
            name = "Idempotency-Key",
        )
        idempotencyKey: String,
        @RequestBody
        request:
            ResolveOffboardingClearanceRequest,
    ): OffboardingCommandResponse =
        resolveClearance(
            servletRequest =
                servletRequest,
            caseId = caseId,
            clearanceType =
                clearanceType
                    .toOffboardingClearanceType(),
            idempotencyKey =
                idempotencyKey,
            request = request,
            hr = false,
        )

    @PostMapping(
        "/v1/offboarding/{caseId}/complete",
    )
    fun complete(
        servletRequest:
            HttpServletRequest,
        @PathVariable
        caseId: String,
        @RequestHeader(
            name = "Idempotency-Key",
        )
        idempotencyKey: String,
        @RequestBody
        request:
            CompleteEmployeeOffboardingRequest,
    ): OffboardingCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId
                .toOffboardingUuid(
                    "INVALID_OPERATION_ID",
                )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        return service.complete(
            CompleteEmployeeOffboardingCommand(
                operationId =
                    operationId,
                caseId =
                    caseId.toOffboardingUuid(
                        "INVALID_OFFBOARDING_CASE_ID",
                    ),
                baseCaseVersion =
                    request.baseCaseVersion,
                baseEmployeeVersion =
                    request.baseEmployeeVersion,
                baseEmploymentVersion =
                    request.baseEmploymentVersion,
                actorUserId =
                    context
                        .requireOffboardingIdentityId(),
                correlationId =
                    context.correlationId,
            ),
        ).toResponse(
            context.correlationId,
        )
    }

    private fun resolveClearance(
        servletRequest:
            HttpServletRequest,
        caseId: String,
        clearanceType:
            OffboardingClearanceType,
        idempotencyKey: String,
        request:
            ResolveOffboardingClearanceRequest,
        hr: Boolean,
    ): OffboardingCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId
                .toOffboardingUuid(
                    "INVALID_OPERATION_ID",
                )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        val command =
            ResolveOffboardingClearanceCommand(
                operationId =
                    operationId,
                caseId =
                    caseId.toOffboardingUuid(
                        "INVALID_OFFBOARDING_CASE_ID",
                    ),
                baseCaseVersion =
                    request.baseCaseVersion,
                clearanceType =
                    clearanceType,
                resolution =
                    request.resolution
                        .toOffboardingClearanceState(),
                reason = request.reason,
                actorUserId =
                    context
                        .requireOffboardingIdentityId(),
                correlationId =
                    context.correlationId,
            )

        val result =
            if (hr) {
                service.resolveHr(command)
            } else {
                service.resolveExternal(
                    command,
                )
            }

        return result.toResponse(
            context.correlationId,
        )
    }
}

private fun OffboardingCommandResult
    .toResponse(
        correlationId: String,
    ): OffboardingCommandResponse =
    OffboardingCommandResponse(
        offboarding =
            offboarding.toResponse(),
        replayed = replayed,
        correlationId =
            correlationId,
    )

private fun OffboardingCaseView
    .toResponse():
    OffboardingCaseResponse =
    OffboardingCaseResponse(
        caseId = caseId.toString(),
        organizationId =
            organizationId.toString(),
        employeeId =
            employeeId.toString(),
        employeeCode =
            employeeCode,
        employeeDisplayName =
            employeeDisplayName,
        employeeState =
            employeeState.name,
        employeeVersion =
            employeeVersion,
        activeEmploymentId =
            activeEmploymentId?.toString(),
        employmentStartDate =
            employmentStartDate?.toString(),
        employmentVersion =
            employmentVersion,
        state = state.name,
        lastWorkingDate =
            lastWorkingDate.toString(),
        reasonCategoryCode =
            reasonCategoryCode,
        note = note,
        clearances =
            clearances.map {
                clearance ->
                OffboardingClearanceResponse(
                    type =
                        clearance.type.name,
                    state =
                        clearance.state.name,
                    source =
                        clearance.source.name,
                    reason =
                        clearance.reason,
                    resolvedAt =
                        clearance.resolvedAt
                            ?.toString(),
                    version =
                        clearance.version,
                )
            },
        accessFacts =
            OffboardingAccessFactsResponse(
                linkedIdentityPresent =
                    accessFacts
                        .linkedIdentityPresent,
                activeOrganizationMemberships =
                    accessFacts
                        .activeOrganizationMemberships,
                activeSessions =
                    accessFacts.activeSessions,
                currentWorkforceAssignmentPresent =
                    accessFacts
                        .currentWorkforceAssignmentPresent,
                clear = accessFacts.clear,
            ),
        canComplete = canComplete,
        blockers = blockers,
        caseVersion = caseVersion,
        startedAt = startedAt.toString(),
        completedAt =
            completedAt?.toString(),
    )

private fun String
    .toOffboardingClearanceType():
    OffboardingClearanceType =
    runCatching {
        OffboardingClearanceType
            .valueOf(
                trim().uppercase(),
            )
    }.getOrElse {
        throw ProductApiException(
            code =
                "INVALID_OFFBOARDING_CLEARANCE_TYPE",
            message =
                "The offboarding clearance type is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun String
    .toOffboardingClearanceState():
    OffboardingClearanceState =
    runCatching {
        OffboardingClearanceState
            .valueOf(
                trim().uppercase(),
            )
    }.getOrElse {
        throw ProductApiException(
            code =
                "INVALID_OFFBOARDING_CLEARANCE_RESOLUTION",
            message =
                "The offboarding clearance resolution is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun String.toOffboardingDate(
    code: String,
): LocalDate =
    try {
        LocalDate.parse(this)
    } catch (
        failure: DateTimeParseException,
    ) {
        throw ProductApiException(
            code = code,
            message =
                "The offboarding date is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun String.toOffboardingUuid(
    code: String,
): UUID =
    runCatching {
        UUID.fromString(this)
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "An offboarding identifier is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun com.hiltech.server.platform
    .HiltechRequestContextSnapshot
    .requireOffboardingIdentityId():
    UUID =
    identityId
        ?.let {
            runCatching {
                UUID.fromString(it)
            }.getOrNull()
        }
        ?: throw ProductApiException(
            code =
                "UNAUTHENTICATED",
            message =
                "Authentication is required.",
            status =
                HttpStatus.UNAUTHORIZED,
        )
