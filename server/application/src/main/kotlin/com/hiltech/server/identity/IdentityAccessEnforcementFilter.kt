package com.hiltech.server.identity

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiErrorWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class IdentityAccessEnforcementFilter(
    private val sessionService: IdentitySessionService,
    private val oidcProperties: HiltechOidcProperties,
    private val errorWriter: ProductApiErrorWriter,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(
        request: HttpServletRequest,
    ): Boolean {
        if (!oidcProperties.enabled) return true

        val path = request.requestURI
        return path == "/actuator/health" ||
            path.startsWith("/actuator/health/") ||
            path == "/v1/me/device"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val jwt =
            SecurityContextHolder.getContext()
                .authentication
                ?.principal as? Jwt

        if (jwt == null) {
            filterChain.doFilter(request, response)
            return
        }

        try {
            val installationId =
                request.getHeader(
                    "X-Device-Installation-Id",
                )
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        runCatching {
                            UUID.fromString(it)
                        }.getOrElse {
                            throw IdentityAccessException(
                                code =
                                    "INVALID_DEVICE_INSTALLATION_ID",
                                message =
                                    "X-Device-Installation-Id must be a UUID.",
                                status =
                                    org.springframework.http.HttpStatus.BAD_REQUEST,
                            )
                        }
                    }
                    ?: throw IdentityAccessException(
                        code = "DEVICE_CONTEXT_REQUIRED",
                        message =
                            "A registered device context is required.",
                    )

            val access =
                sessionService.requireCurrentAccess(
                    jwt = jwt,
                    installationId = installationId,
                )

            HiltechRequestContext
                .bindAuthenticatedIdentity(
                    request = request,
                    identityId =
                        access.identity.id,
                    sessionId =
                        access.session.id,
                )

            filterChain.doFilter(
                request,
                response,
            )
        } catch (failure: IdentityAccessException) {
            writeFailure(
                request = request,
                response = response,
                failure = failure,
            )
        }
    }

    private fun writeFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        failure: IdentityAccessException,
    ) {
        errorWriter.write(
            request = request,
            response = response,
            status = failure.status,
            code = failure.code,
            message =
                safeMessage(failure.code),
            retryable =
                failure.retryable,
        )
    }

    private fun safeMessage(
        code: String,
    ): String =
        when (code) {
            "DEVICE_CONTEXT_REQUIRED" ->
                "A registered device context is required."
            "INVALID_DEVICE_INSTALLATION_ID" ->
                "The device context is invalid."
            "DEVICE_NOT_REGISTERED" ->
                "This device is not registered."
            "DEVICE_REVOKED" ->
                "This device has been revoked."
            "SESSION_NOT_ACTIVE",
            "SESSION_REVOKED" ->
                "This HILTECH session is not active."
            "IDENTITY_NOT_ACTIVE" ->
                "This HILTECH identity is not active."
            else ->
                "Access is unavailable."
        }
}
