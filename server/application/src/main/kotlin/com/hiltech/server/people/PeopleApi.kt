package com.hiltech.server.people

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

data class CreateEmployeeRequest(
    val operationId: String,
    val organizationId: String,
    val displayName: String,
    val employeeCode: String,
    val startDate: String,
    val employmentTypeCode: String? = null,
    val linkedUserIdentityId: String? = null,
    val hireDate: String? = null,
    val legalName: String? = null,
    val mobile: String? = null,
    val email: String? = null,
)

data class LinkEmployeeIdentityRequest(
    val operationId: String,
    val baseVersion: Long,
    val userIdentityId: String,
)

data class UpdateEmployeeProfileRequest(
    val operationId: String,
    val baseVersion: Long,
    val displayName: String,
    val legalName: String? = null,
    val mobile: String? = null,
    val email: String? = null,
)

data class EmployeeDirectoryItemResponse(
    val employeeId: String,
    val employeeCode: String,
    val displayName: String,
    val employeeState: String,
    val linkedIdentity: Boolean,
    val currentEmploymentTypeCode: String?,
    val hireDate: String?,
    val version: Long,
)

data class EmployeeDirectoryResponse(
    val items: List<EmployeeDirectoryItemResponse>,
    val canManagePeople: Boolean,
    val correlationId: String,
)

data class EmployeeDetailResponse(
    val employeeId: String,
    val organizationId: String,
    val employeeCode: String,
    val employeeState: String,
    val displayName: String,
    val legalName: String?,
    val mobile: String?,
    val email: String?,
    val hireDate: String?,
    val endDate: String?,
    val currentEmploymentId: String?,
    val employmentTypeCode: String?,
    val employmentStartDate: String?,
    val employmentEndDate: String?,
    val linkedIdentityId: String?,
    val version: Long,
)

data class EmployeeCommandResponse(
    val employee: EmployeeDetailResponse,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
@RequestMapping("/v1/employees")
class PeopleController(
    private val service: PeopleService,
) {
    @PostMapping
    fun create(
        servletRequest:
            HttpServletRequest,
        @RequestHeader(
            name = "Idempotency-Key",
        )
        idempotencyKey: String,
        @RequestBody
        request: CreateEmployeeRequest,
    ): EmployeeCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId.toUuid(
                "INVALID_OPERATION_ID",
            )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        val result =
            service.createEmployee(
                CreateEmployeeCommand(
                    operationId =
                        operationId,
                    organizationId =
                        request.organizationId
                            .toUuid(
                                "INVALID_ORGANIZATION_ID",
                            ),
                    displayName =
                        request.displayName,
                    employeeCode =
                        request.employeeCode,
                    startDate =
                        request.startDate
                            .toLocalDate(
                                "INVALID_START_DATE",
                            ),
                    employmentTypeCode =
                        request
                            .employmentTypeCode,
                    linkedUserIdentityId =
                        request
                            .linkedUserIdentityId
                            ?.toUuid(
                                "INVALID_IDENTITY_ID",
                            ),
                    hireDate =
                        request.hireDate
                            ?.toLocalDate(
                                "INVALID_HIRE_DATE",
                            ),
                    legalName =
                        request.legalName,
                    mobile =
                        request.mobile,
                    email =
                        request.email,
                    actorUserId =
                        context
                            .requireIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return EmployeeCommandResponse(
            employee =
                result.employee
                    .toDetailResponse(),
            replayed =
                result.replayed,
            correlationId =
                context.correlationId,
        )
    }

    @GetMapping
    fun directory(
        servletRequest:
            HttpServletRequest,
        @RequestParam
        organizationId: String,
        @RequestParam(
            defaultValue = "100",
        )
        limit: Int,
    ): EmployeeDirectoryResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val items =
            service.directory(
                actorUserId =
                    context
                        .requireIdentityId(),
                organizationId =
                    organizationId.toUuid(
                        "INVALID_ORGANIZATION_ID",
                    ),
                limit = limit,
            )

        return EmployeeDirectoryResponse(
            items =
                items.map {
                    EmployeeDirectoryItemResponse(
                        employeeId =
                            it.employeeId
                                .toString(),
                        employeeCode =
                            it.employeeCode,
                        displayName =
                            it.displayName,
                        employeeState =
                            it.employeeState.name,
                        linkedIdentity =
                            it.linkedIdentity,
                        currentEmploymentTypeCode =
                            it.currentEmploymentTypeCode,
                        hireDate =
                            it.hireDate
                                ?.toString(),
                        version =
                            it.version,
                    )
                },
            canManagePeople =
                service.canManagePeople(
                    actorUserId =
                        context.requireIdentityId(),
                    organizationId =
                        organizationId.toUuid(
                            "INVALID_ORGANIZATION_ID",
                        ),
                ),
            correlationId =
                context.correlationId,
        )
    }

    @GetMapping("/{employeeId}")
    fun detail(
        servletRequest:
            HttpServletRequest,
        @PathVariable
        employeeId: String,
    ): EmployeeDetailResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        return service.detail(
            actorUserId =
                context.requireIdentityId(),
            employeeId =
                employeeId.toUuid(
                    "INVALID_EMPLOYEE_ID",
                ),
        ).toDetailResponse()
    }

    @PostMapping("/{employeeId}/identity-link")
    fun linkIdentity(
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
            LinkEmployeeIdentityRequest,
    ): EmployeeCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId.toUuid(
                "INVALID_OPERATION_ID",
            )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )
        val result =
            service.linkIdentity(
                LinkEmployeeIdentityCommand(
                    operationId =
                        operationId,
                    employeeId =
                        employeeId.toUuid(
                            "INVALID_EMPLOYEE_ID",
                        ),
                    baseVersion =
                        request.baseVersion,
                    userIdentityId =
                        request.userIdentityId
                            .toUuid(
                                "INVALID_IDENTITY_ID",
                            ),
                    actorUserId =
                        context
                            .requireIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return EmployeeCommandResponse(
            employee =
                result.employee
                    .toDetailResponse(),
            replayed =
                result.replayed,
            correlationId =
                context.correlationId,
        )
    }

    @PutMapping("/{employeeId}/profile")
    fun updateProfile(
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
            UpdateEmployeeProfileRequest,
    ): EmployeeCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId.toUuid(
                "INVALID_OPERATION_ID",
            )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )
        val result =
            service.updateProfile(
                UpdateEmployeeProfileCommand(
                    operationId =
                        operationId,
                    employeeId =
                        employeeId.toUuid(
                            "INVALID_EMPLOYEE_ID",
                        ),
                    baseVersion =
                        request.baseVersion,
                    displayName =
                        request.displayName,
                    legalName =
                        request.legalName,
                    mobile =
                        request.mobile,
                    email =
                        request.email,
                    actorUserId =
                        context
                            .requireIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return EmployeeCommandResponse(
            employee =
                result.employee
                    .toDetailResponse(),
            replayed =
                result.replayed,
            correlationId =
                context.correlationId,
        )
    }
}

@RestController
@RequestMapping("/v1/me/employee")
class OwnEmployeeController(
    private val service: PeopleService,
) {
    @GetMapping
    fun own(
        servletRequest:
            HttpServletRequest,
        @RequestParam
        organizationId: String,
    ): EmployeeDetailResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val employee =
            service.ownProfile(
                actorUserId =
                    context.requireIdentityId(),
                organizationId =
                    organizationId.toUuid(
                        "INVALID_ORGANIZATION_ID",
                    ),
            )

        return employee
            .toDetailResponse(
                includeLinkedIdentityId =
                    false,
            )
    }
}

private fun EmployeeAggregateSnapshot
    .toDetailResponse(
        includeLinkedIdentityId:
            Boolean = true,
    ): EmployeeDetailResponse =
    EmployeeDetailResponse(
        employeeId =
            employeeId.toString(),
        organizationId =
            organizationId.toString(),
        employeeCode =
            employeeCode,
        employeeState =
            employeeState.name,
        displayName =
            displayName,
        legalName =
            legalName,
        mobile = mobile,
        email = email,
        hireDate =
            hireDate?.toString(),
        endDate =
            endDate?.toString(),
        currentEmploymentId =
            activeEmploymentId
                ?.toString(),
        employmentTypeCode =
            employmentTypeCode,
        employmentStartDate =
            employmentStartDate
                ?.toString(),
        employmentEndDate =
            employmentEndDate
                ?.toString(),
        linkedIdentityId =
            if (
                includeLinkedIdentityId
            ) {
                linkedIdentityId
                    ?.toString()
            } else {
                null
            },
        version =
            employeeVersion,
    )

private fun String.toUuid(
    code: String,
): UUID =
    runCatching {
        UUID.fromString(this)
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "A People identifier is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun String.toLocalDate(
    code: String,
): LocalDate =
    runCatching {
        LocalDate.parse(this)
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "A People date is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun com.hiltech.server.platform.HiltechRequestContextSnapshot
    .requireIdentityId(): UUID =
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
