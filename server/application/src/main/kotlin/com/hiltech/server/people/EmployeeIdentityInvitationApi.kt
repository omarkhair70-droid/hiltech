package com.hiltech.server.people

import com.hiltech.server.identity.IdentityProvisioningDeliveryMode
import com.hiltech.server.identity.IdentitySessionService
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ProvisionEmployeeIdentityRequest(
    val operationId: String,
    val baseEmployeeVersion: Long,
    val requestedLogin: String,
    val deliveryMode: String,
)

data class EmployeeIdentityInvitationResponse(
    val invitationId: String,
    val organizationId: String,
    val employeeId: String,
    val state: String,
    val deliveryMode: String,
    val deliveryState: String,
    val userIdentityId: String?,
    val temporaryCredential: String?,
    val version: Long,
    val replayed: Boolean,
    val correlationId: String,
)

@RestController
class EmployeeIdentityInvitationController(
    private val service:
        EmployeeIdentityInvitationService,
    private val sessionService:
        IdentitySessionService,
) {
    @PostMapping(
        "/v1/employees/{employeeId}/identity-invitations",
    )
    fun provision(
        servletRequest:
            HttpServletRequest,
        @AuthenticationPrincipal
        jwt: Jwt,
        @PathVariable
        employeeId: String,
        @RequestHeader(
            name = "Idempotency-Key",
        )
        idempotencyKey: String,
        @RequestHeader(
            name =
                "X-Device-Installation-Id",
        )
        installationId: String,
        @RequestBody
        request:
            ProvisionEmployeeIdentityRequest,
    ): ResponseEntity<
        EmployeeIdentityInvitationResponse
    > {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val actor =
            context.identityId
                ?.toIdentityInvitationUuid(
                    "UNAUTHENTICATED",
                )
                ?: throw ProductApiException(
                    code =
                        "UNAUTHENTICATED",
                    message =
                        "Authentication is required.",
                    status =
                        HttpStatus.UNAUTHORIZED,
                )
        val operationId =
            request.operationId
                .toIdentityInvitationUuid(
                    "INVALID_OPERATION_ID",
                )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        val deliveryMode =
            request.deliveryMode
                .toDeliveryMode()

        if (
            deliveryMode ==
            IdentityProvisioningDeliveryMode
                .TEMPORARY_PASSWORD_HANDOFF
        ) {
            val access =
                sessionService
                    .requireCurrentAccess(
                        jwt = jwt,
                        installationId =
                            installationId
                                .toIdentityInvitationUuid(
                                    "INVALID_DEVICE_INSTALLATION_ID",
                                ),
                    )
            sessionService
                .requireRecentReauthentication(
                    access,
                )
        }

        val result =
            service.provision(
                ProvisionEmployeeIdentityCommand(
                    operationId =
                        operationId,
                    employeeId =
                        employeeId
                            .toIdentityInvitationUuid(
                                "INVALID_EMPLOYEE_ID",
                            ),
                    baseEmployeeVersion =
                        request
                            .baseEmployeeVersion,
                    requestedLogin =
                        request.requestedLogin,
                    deliveryMode =
                        deliveryMode,
                    actorUserId =
                        actor,
                    correlationId =
                        context.correlationId,
                ),
            )

        val invitation =
            result.invitation
        val response =
            EmployeeIdentityInvitationResponse(
                invitationId =
                    invitation.invitationId
                        .toString(),
                organizationId =
                    invitation.organizationId
                        .toString(),
                employeeId =
                    invitation.employeeId
                        .toString(),
                state =
                    invitation.state.name,
                deliveryMode =
                    invitation.deliveryMode.name,
                deliveryState =
                    invitation.deliveryState
                        .name,
                userIdentityId =
                    invitation.userIdentityId
                        ?.toString(),
                temporaryCredential =
                    result
                        .temporaryCredential,
                version =
                    invitation.version,
                replayed =
                    result.replayed,
                correlationId =
                    context.correlationId,
            )

        return ResponseEntity.ok()
            .cacheControl(
                CacheControl.noStore(),
            )
            .header(
                HttpHeaders.PRAGMA,
                "no-cache",
            )
            .body(response)
    }
}

private fun String.toDeliveryMode():
    IdentityProvisioningDeliveryMode =
    runCatching {
        IdentityProvisioningDeliveryMode
            .valueOf(
                trim().uppercase(),
            )
    }.getOrElse {
        throw ProductApiException(
            code =
                "INVALID_IDENTITY_DELIVERY_MODE",
            message =
                "The identity delivery mode is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun String.toIdentityInvitationUuid(
    code: String,
): UUID =
    runCatching {
        UUID.fromString(this)
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "An identity invitation identifier is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }
