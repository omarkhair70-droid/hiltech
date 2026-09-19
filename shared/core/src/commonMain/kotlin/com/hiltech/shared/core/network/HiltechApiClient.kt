package com.hiltech.shared.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

data class HiltechRequestOptions(
    val installationId: String? = null,
    val idempotencyKey: String? = null,
)

class HiltechApiException(
    val code: String,
    val correlationId: String?,
    val httpStatus: Int?,
    val retryable: Boolean,
    val envelope: ErrorEnvelope?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(
    message,
    cause,
)

class HiltechApiClient(
    private val client: HttpClient,
    baseUrl: String,
    private val accessTokenProvider:
        suspend () -> String?,
    private val correlationIdProvider:
        () -> String,
    private val traceParentProvider:
        () -> String? = { null },
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val baseUrl =
        baseUrl.trimEnd('/')


    suspend fun <T> requestWithReauthentication(
        method: HttpMethod,
        path: String,
        options: HiltechRequestOptions =
            HiltechRequestOptions(),
        requestBody: String? = null,
        reauthenticate: suspend () -> Unit,
        decode: (String) -> T,
    ): T {
        val replaySafe =
            method == HttpMethod.Get ||
                method == HttpMethod.Head ||
                options.idempotencyKey
                    ?.takeIf {
                        it.isNotBlank()
                    } != null

        try {
            return request(
                method = method,
                path = path,
                options = options,
                requestBody = requestBody,
                decode = decode,
            )
        } catch (
            failure: HiltechApiException,
        ) {
            if (
                failure.code !=
                    "REAUTH_REQUIRED" ||
                !replaySafe
            ) {
                throw failure
            }

            reauthenticate()

            return request(
                method = method,
                path = path,
                options = options,
                requestBody = requestBody,
                decode = decode,
            )
        }
    }

    suspend fun <T> request(
        method: HttpMethod,
        path: String,
        options: HiltechRequestOptions =
            HiltechRequestOptions(),
        requestBody: String? = null,
        decode: (String) -> T,
    ): T {
        val correlationId =
            correlationIdProvider()
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: "client-correlation-missing"

        val token =
            accessTokenProvider()
                ?.takeIf { it.isNotBlank() }
                ?: throw HiltechApiException(
                    code = "REAUTH_REQUIRED",
                    correlationId =
                        correlationId,
                    httpStatus = null,
                    retryable = false,
                    envelope = null,
                    message =
                        "No access token is available.",
                )

        val response =
            try {
                client.request(
                    baseUrl + path,
                ) {
                    this.method = method
                    contentType(
                        ContentType.Application.Json,
                    )
                    header(
                        HttpHeaders.Authorization,
                        "Bearer $token",
                    )
                    header(
                        "X-Correlation-Id",
                        correlationId,
                    )
                    options.installationId
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            header(
                                "X-Device-Installation-Id",
                                it,
                            )
                        }
                    options.idempotencyKey
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            header(
                                "Idempotency-Key",
                                it,
                            )
                        }
                    traceParentProvider()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            header(
                                "traceparent",
                                it,
                            )
                        }
                    if (requestBody != null) {
                        setBody(
                            requestBody,
                        )
                    }
                }
            } catch (
                cancellation: CancellationException,
            ) {
                throw cancellation
            } catch (failure: Throwable) {
                throw HiltechApiException(
                    code = "NETWORK_UNAVAILABLE",
                    correlationId =
                        correlationId,
                    httpStatus = null,
                    retryable = true,
                    envelope = null,
                    message =
                        "The HILTECH service is temporarily unreachable.",
                    cause = failure,
                )
            }

        val body =
            response.bodyAsText()

        if (
            response.status.value in 200..299
        ) {
            return decode(body)
        }

        val envelope =
            runCatching {
                json.decodeFromString<
                    ErrorEnvelope
                >(body)
            }.getOrNull()

        val serverCorrelationId =
            envelope?.correlationId
                ?: response.headers[
                    "X-Correlation-Id"
                ]
                ?: correlationId

        val retryable =
            envelope?.retryable
                ?: defaultRetryable(
                    response.status.value,
                )

        throw HiltechApiException(
            code =
                envelope?.code
                    ?: fallbackCode(
                        response.status.value,
                    ),
            correlationId =
                serverCorrelationId,
            httpStatus =
                response.status.value,
            retryable = retryable,
            envelope = envelope,
            message =
                envelope?.message
                    ?: "HILTECH request failed.",
        )
    }

    private fun defaultRetryable(
        status: Int,
    ): Boolean =
        status in setOf(
            429,
            502,
            503,
            504,
        )

    private fun fallbackCode(
        status: Int,
    ): String =
        when (status) {
            400 ->
                "MALFORMED_REQUEST"
            401 ->
                "UNAUTHENTICATED"
            403 ->
                "PERMISSION_DENIED"
            404 ->
                "OBJECT_NOT_VISIBLE"
            409 ->
                "STATE_CONFLICT"
            422 ->
                "REJECTED_VALIDATION"
            428 ->
                "REAUTH_REQUIRED"
            429 ->
                "RATE_LIMITED"
            503 ->
                "TEMPORARY_UNAVAILABLE"
            else ->
                "HTTP_ERROR"
        }
}
