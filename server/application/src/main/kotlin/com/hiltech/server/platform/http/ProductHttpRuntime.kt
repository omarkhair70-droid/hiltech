package com.hiltech.server.platform.http

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Component
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.util.UUID

object HiltechRequestHeaders {
    const val CORRELATION_ID = "X-Correlation-Id"
    const val DEVICE_INSTALLATION_ID =
        "X-Device-Installation-Id"
    const val IDEMPOTENCY_KEY = "Idempotency-Key"
    const val TRACE_PARENT = "traceparent"
}

data class HiltechRequestContextSnapshot(
    val correlationId: String,
    val traceParent: String?,
    val idempotencyKey: String?,
    val deviceInstallationId: String?,
    val identityId: String?,
    val sessionId: String?,
)

object HiltechRequestContext {
    private const val BASE_ATTRIBUTE =
        "hiltech.request.context.base"
    private const val IDENTITY_ATTRIBUTE =
        "hiltech.request.context.identity"
    private const val SESSION_ATTRIBUTE =
        "hiltech.request.context.session"

    fun current(
        request: HttpServletRequest,
    ): HiltechRequestContextSnapshot {
        val base =
            request.getAttribute(
                BASE_ATTRIBUTE,
            ) as? HiltechRequestContextSnapshot

        return (
            base ?: HiltechRequestContextSnapshot(
                correlationId =
                    normalizedCorrelationId(
                        request.getHeader(
                            HiltechRequestHeaders
                                .CORRELATION_ID,
                        ),
                    ),
                traceParent =
                    normalizedTraceParent(
                        request.getHeader(
                            HiltechRequestHeaders
                                .TRACE_PARENT,
                        ),
                    ),
                idempotencyKey =
                    boundedHeader(
                        request.getHeader(
                            HiltechRequestHeaders
                                .IDEMPOTENCY_KEY,
                        ),
                    ),
                deviceInstallationId =
                    boundedHeader(
                        request.getHeader(
                            HiltechRequestHeaders
                                .DEVICE_INSTALLATION_ID,
                        ),
                    ),
                identityId = null,
                sessionId = null,
            )
            ).copy(
                identityId =
                    request.getAttribute(
                        IDENTITY_ATTRIBUTE,
                    ) as? String,
                sessionId =
                    request.getAttribute(
                        SESSION_ATTRIBUTE,
                    ) as? String,
            )
    }

    fun installBase(
        request: HttpServletRequest,
        context: HiltechRequestContextSnapshot,
    ) {
        request.setAttribute(
            BASE_ATTRIBUTE,
            context,
        )
    }

    fun bindAuthenticatedIdentity(
        request: HttpServletRequest,
        identityId: UUID,
        sessionId: UUID,
    ) {
        request.setAttribute(
            IDENTITY_ATTRIBUTE,
            identityId.toString(),
        )
        request.setAttribute(
            SESSION_ATTRIBUTE,
            sessionId.toString(),
        )
    }

    fun normalizedCorrelationId(
        raw: String?,
    ): String {
        val candidate =
            raw
                ?.trim()
                ?.takeIf {
                    CORRELATION_ID_PATTERN.matches(it)
                }

        return candidate
            ?: UUID.randomUUID().toString()
    }

    fun normalizedTraceParent(
        raw: String?,
    ): String? =
        raw
            ?.trim()
            ?.lowercase()
            ?.takeIf {
                TRACE_PARENT_PATTERN.matches(it)
            }

    fun boundedHeader(
        raw: String?,
    ): String? =
        raw
            ?.trim()
            ?.takeIf {
                it.isNotEmpty() &&
                    it.length <= 128 &&
                    SAFE_HEADER_PATTERN.matches(it)
            }

    private val CORRELATION_ID_PATTERN =
        Regex("^[A-Za-z0-9._:-]{1,128}$")

    private val SAFE_HEADER_PATTERN =
        Regex("^[A-Za-z0-9._:/-]{1,128}$")

    private val TRACE_PARENT_PATTERN =
        Regex(
            "^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$",
        )
}

@Component
class HiltechRequestContextFilter :
    OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val context =
            HiltechRequestContextSnapshot(
                correlationId =
                    HiltechRequestContext
                        .normalizedCorrelationId(
                            request.getHeader(
                                HiltechRequestHeaders
                                    .CORRELATION_ID,
                            ),
                        ),
                traceParent =
                    HiltechRequestContext
                        .normalizedTraceParent(
                            request.getHeader(
                                HiltechRequestHeaders
                                    .TRACE_PARENT,
                            ),
                        ),
                idempotencyKey =
                    HiltechRequestContext
                        .boundedHeader(
                            request.getHeader(
                                HiltechRequestHeaders
                                    .IDEMPOTENCY_KEY,
                            ),
                        ),
                deviceInstallationId =
                    HiltechRequestContext
                        .boundedHeader(
                            request.getHeader(
                                HiltechRequestHeaders
                                    .DEVICE_INSTALLATION_ID,
                            ),
                        ),
                identityId = null,
                sessionId = null,
            )

        HiltechRequestContext.installBase(
            request,
            context,
        )
        response.setHeader(
            HiltechRequestHeaders.CORRELATION_ID,
            context.correlationId,
        )

        filterChain.doFilter(
            request,
            response,
        )
    }
}

data class ProductTargetRef(
    val type: String,
    val id: String,
)

data class ProductConflictPayload(
    val conflictType: String,
    val targetType: String? = null,
    val targetId: String? = null,
    val attemptedBaseVersion: Long? = null,
    val currentVersion: Long? = null,
    val safeCurrentState: Map<String, Any?>? = null,
    val localWorkSafe: Boolean? = null,
    val allowedRecoveryActions: List<String> =
        emptyList(),
)

data class ProductErrorEnvelope(
    val code: String,
    val message: String,
    val correlationId: String,
    val retryable: Boolean = false,
    val details: Map<String, Any?>? = null,
    val currentVersion: Long? = null,
    val conflict: ProductConflictPayload? = null,
    val messageKey: String? = null,
    val target: ProductTargetRef? = null,
)

open class ProductApiException(
    val code: String,
    override val message: String,
    val status: HttpStatus,
    val retryable: Boolean = false,
    val details: Map<String, Any?>? = null,
    val currentVersion: Long? = null,
    val conflict: ProductConflictPayload? = null,
    val messageKey: String? = null,
    val target: ProductTargetRef? = null,
) : RuntimeException(message)

@Component
class ProductApiErrorWriter(
    private val objectMapper: ObjectMapper,
) {
    fun envelope(
        request: HttpServletRequest,
        code: String,
        message: String,
        retryable: Boolean = false,
        details: Map<String, Any?>? = null,
        currentVersion: Long? = null,
        conflict: ProductConflictPayload? = null,
        messageKey: String? = null,
        target: ProductTargetRef? = null,
    ): ProductErrorEnvelope =
        ProductErrorEnvelope(
            code = code,
            message = message,
            correlationId =
                HiltechRequestContext
                    .current(request)
                    .correlationId,
            retryable = retryable,
            details = details,
            currentVersion = currentVersion,
            conflict = conflict,
            messageKey = messageKey,
            target = target,
        )

    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        code: String,
        message: String,
        retryable: Boolean = false,
        details: Map<String, Any?>? = null,
        currentVersion: Long? = null,
        conflict: ProductConflictPayload? = null,
        messageKey: String? = null,
        target: ProductTargetRef? = null,
    ) {
        val envelope =
            envelope(
                request = request,
                code = code,
                message = message,
                retryable = retryable,
                details = details,
                currentVersion = currentVersion,
                conflict = conflict,
                messageKey = messageKey,
                target = target,
            )

        response.status = status.value()
        response.contentType =
            MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.setHeader(
            HiltechRequestHeaders.CORRELATION_ID,
            envelope.correlationId,
        )
        objectMapper.writeValue(
            response.writer,
            envelope,
        )
    }
}

@RestControllerAdvice
class ProductApiExceptionHandler(
    private val errorWriter: ProductApiErrorWriter,
) {
    @ExceptionHandler(ProductApiException::class)
    fun handleProductFailure(
        exception: ProductApiException,
        request: HttpServletRequest,
    ): ResponseEntity<ProductErrorEnvelope> {
        val envelope =
            errorWriter.envelope(
                request = request,
                code = exception.code,
                message = exception.message,
                retryable =
                    exception.retryable,
                details = exception.details,
                currentVersion =
                    exception.currentVersion,
                conflict = exception.conflict,
                messageKey =
                    exception.messageKey,
                target = exception.target,
            )

        return ResponseEntity
            .status(exception.status)
            .header(
                HiltechRequestHeaders.CORRELATION_ID,
                envelope.correlationId,
            )
            .body(envelope)
    }

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingRequestHeaderException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun handleMalformedRequest(
        request: HttpServletRequest,
    ): ResponseEntity<ProductErrorEnvelope> {
        val envelope =
            errorWriter.envelope(
                request = request,
                code = "MALFORMED_REQUEST",
                message =
                    "The request could not be read.",
            )

        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .header(
                HiltechRequestHeaders.CORRELATION_ID,
                envelope.correlationId,
            )
            .body(envelope)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedFailure(
        request: HttpServletRequest,
    ): ResponseEntity<ProductErrorEnvelope> {
        val envelope =
            errorWriter.envelope(
                request = request,
                code = "INTERNAL_ERROR",
                message =
                    "The request could not be completed.",
            )

        return ResponseEntity
            .status(
                HttpStatus.INTERNAL_SERVER_ERROR,
            )
            .header(
                HiltechRequestHeaders.CORRELATION_ID,
                envelope.correlationId,
            )
            .body(envelope)
    }
}
