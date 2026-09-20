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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class CreateWorkforceAssignmentRequest(
    val operationId: String,
    val baseEmployeeVersion: Long,
    val teamId: String? = null,
    val roleCode: String,
    val roleLabel: String? = null,
    val reportsToEmployeeId: String? = null,
    val effectiveFrom: String,
)

data class WorkforceAssignmentResponse(
    val assignmentId: String,
    val organizationId: String,
    val employeeId: String,
    val employeeCode: String,
    val employeeDisplayName: String,
    val teamId: String?,
    val teamCode: String?,
    val teamName: String?,
    val roleCode: String,
    val roleLabel: String?,
    val reportsToEmployeeId: String?,
    val reportsToEmployeeCode: String?,
    val reportsToDisplayName: String?,
    val state: String,
    val effectiveFrom: String,
    val effectiveTo: String?,
    val version: Long,
)

data class WorkforceAssignmentCommandResponse(
    val assignment:
        WorkforceAssignmentResponse,
    val replayed: Boolean,
    val correlationId: String,
)

data class WorkforceStructureResponse(
    val items:
        List<WorkforceAssignmentResponse>,
    val correlationId: String,
)

@RestController
@RequestMapping("/v1/employees")
class WorkforceAssignmentEmployeeController(
    private val service:
        WorkforceAssignmentService,
) {
    @PostMapping(
        "/{employeeId}/workforce-assignment",
    )
    fun create(
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
            CreateWorkforceAssignmentRequest,
    ): WorkforceAssignmentCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId
                .toWorkforceUuid(
                    "INVALID_OPERATION_ID",
                )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        val result =
            service.create(
                CreateWorkforceAssignmentCommand(
                    operationId =
                        operationId,
                    employeeId =
                        employeeId
                            .toWorkforceUuid(
                                "INVALID_EMPLOYEE_ID",
                            ),
                    baseEmployeeVersion =
                        request.baseEmployeeVersion,
                    teamId =
                        request.teamId
                            ?.toWorkforceUuid(
                                "INVALID_TEAM_ID",
                            ),
                    roleCode =
                        request.roleCode,
                    roleLabel =
                        request.roleLabel,
                    reportsToEmployeeId =
                        request
                            .reportsToEmployeeId
                            ?.toWorkforceUuid(
                                "INVALID_REPORTING_MANAGER_ID",
                            ),
                    effectiveFrom =
                        request.effectiveFrom
                            .toWorkforceInstant(
                                "INVALID_EFFECTIVE_FROM",
                            ),
                    actorUserId =
                        context
                            .requireWorkforceIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return WorkforceAssignmentCommandResponse(
            assignment =
                result.assignment
                    .toResponse(),
            replayed =
                result.replayed,
            correlationId =
                context.correlationId,
        )
    }

    @GetMapping(
        "/{employeeId}/workforce-assignment",
    )
    fun current(
        servletRequest:
            HttpServletRequest,
        @PathVariable
        employeeId: String,
    ): WorkforceAssignmentResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )

        return service
            .currentForEmployee(
                actorUserId =
                    context
                        .requireWorkforceIdentityId(),
                employeeId =
                    employeeId
                        .toWorkforceUuid(
                            "INVALID_EMPLOYEE_ID",
                        ),
            )
            .toResponse()
    }
}

@RestController
@RequestMapping("/v1/me/workforce-assignment")
class OwnWorkforceAssignmentController(
    private val service:
        WorkforceAssignmentService,
) {
    @GetMapping
    fun own(
        servletRequest:
            HttpServletRequest,
        @RequestParam
        organizationId: String,
    ): WorkforceAssignmentResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )

        return service.own(
            actorUserId =
                context
                    .requireWorkforceIdentityId(),
            organizationId =
                organizationId
                    .toWorkforceUuid(
                        "INVALID_ORGANIZATION_ID",
                    ),
        ).toResponse()
    }
}

@RestController
@RequestMapping("/v1/workforce-assignments")
class WorkforceStructureController(
    private val service:
        WorkforceAssignmentService,
) {
    @GetMapping
    fun structure(
        servletRequest:
            HttpServletRequest,
        @RequestParam
        organizationId: String,
        @RequestParam(
            defaultValue = "200",
        )
        limit: Int,
    ): WorkforceStructureResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )

        return WorkforceStructureResponse(
            items =
                service.organizationStructure(
                    actorUserId =
                        context
                            .requireWorkforceIdentityId(),
                    organizationId =
                        organizationId
                            .toWorkforceUuid(
                                "INVALID_ORGANIZATION_ID",
                            ),
                    limit = limit,
                ).map {
                    it.toResponse()
                },
            correlationId =
                context.correlationId,
        )
    }
}

private fun WorkforceAssignmentSnapshot
    .toResponse():
    WorkforceAssignmentResponse =
    WorkforceAssignmentResponse(
        assignmentId =
            assignmentId.toString(),
        organizationId =
            organizationId.toString(),
        employeeId =
            employeeId.toString(),
        employeeCode =
            employeeCode,
        employeeDisplayName =
            employeeDisplayName,
        teamId =
            teamId?.toString(),
        teamCode =
            teamCode,
        teamName =
            teamName,
        roleCode =
            roleCode,
        roleLabel =
            roleLabel,
        reportsToEmployeeId =
            reportsToEmployeeId
                ?.toString(),
        reportsToEmployeeCode =
            reportsToEmployeeCode,
        reportsToDisplayName =
            reportsToDisplayName,
        state =
            state.name,
        effectiveFrom =
            effectiveFrom.toString(),
        effectiveTo =
            effectiveTo?.toString(),
        version =
            version,
    )

private fun String.toWorkforceUuid(
    code: String,
): UUID =
    runCatching {
        UUID.fromString(this)
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "A Workforce Assignment identifier is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun String.toWorkforceInstant(
    code: String,
): Instant =
    runCatching {
        Instant.parse(this)
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "A Workforce Assignment timestamp is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun com.hiltech.server.platform.HiltechRequestContextSnapshot
    .requireWorkforceIdentityId(): UUID =
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
