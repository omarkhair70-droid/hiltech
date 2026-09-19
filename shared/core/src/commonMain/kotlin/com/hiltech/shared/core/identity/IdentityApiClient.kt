package com.hiltech.shared.core.identity

import com.hiltech.shared.core.network.ErrorEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class IdentityApiException(
    val code: String,
    val correlationId: String?,
    val httpStatus: Int?,
    message: String,
) : RuntimeException(message)

class IdentityApiClient(
    private val client: HttpClient,
    baseUrl: String,
    private val accessTokenProvider: suspend () -> String?,
    private val correlationIdProvider: () -> String,
    private val traceParentProvider: () -> String? = { null },
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val baseUrl = baseUrl.trimEnd('/')

    suspend fun bootstrap(
        installationId: String?,
    ): IdentityBootstrapDto =
        request(
            method = HttpMethod.Get,
            path = "/v1/me/bootstrap",
            installationId = installationId,
            requestBody = null,
            decode = {
                json.decodeFromString<IdentityBootstrapDto>(it)
            },
        )

    suspend fun registerDevice(
        request: DeviceRegistrationDto,
    ): IdentityDeviceDto =
        request(
            method = HttpMethod.Put,
            path = "/v1/me/device",
            installationId = request.installationId,
            requestBody = json.encodeToString(
                DeviceRegistrationDto.serializer(),
                request,
            ),
            decode = {
                json.decodeFromString<IdentityDeviceDto>(it)
            },
        )

    suspend fun completeReauthentication(
        idToken: String,
        installationId: String,
    ): IdentitySessionDto =
        request(
            method = HttpMethod.Post,
            path = "/v1/me/reauth/complete",
            installationId = installationId,
            requestBody = json.encodeToString(
                ReauthenticationCompletionDto.serializer(),
                ReauthenticationCompletionDto(
                    idToken = idToken,
                ),
            ),
            decode = {
                json.decodeFromString<IdentitySessionDto>(it)
            },
        )

    suspend fun sessions(
        installationId: String,
    ): List<IdentitySessionDto> =
        request(
            method = HttpMethod.Get,
            path = "/v1/me/sessions",
            installationId = installationId,
            requestBody = null,
            decode = {
                json.decodeFromString(
                    ListSerializer(
                        IdentitySessionDto.serializer(),
                    ),
                    it,
                )
            },
        )

    suspend fun devices(
        installationId: String,
    ): List<IdentityDeviceSecurityDto> =
        request(
            method = HttpMethod.Get,
            path = "/v1/me/devices",
            installationId = installationId,
            requestBody = null,
            decode = {
                json.decodeFromString(
                    ListSerializer(
                        IdentityDeviceSecurityDto.serializer(),
                    ),
                    it,
                )
            },
        )

    suspend fun revokeSession(
        sessionId: String,
        installationId: String,
    ): IdentitySessionDto =
        request(
            method = HttpMethod.Post,
            path = "/v1/me/sessions/$sessionId/revoke",
            installationId = installationId,
            requestBody = null,
            decode = {
                json.decodeFromString<IdentitySessionDto>(it)
            },
        )

    suspend fun revokeDevice(
        deviceId: String,
        installationId: String,
    ): IdentityDeviceSecurityDto =
        request(
            method = HttpMethod.Post,
            path = "/v1/me/devices/$deviceId/revoke",
            installationId = installationId,
            requestBody = null,
            decode = {
                json.decodeFromString<IdentityDeviceSecurityDto>(it)
            },
        )

    private suspend fun <T> request(
        method: HttpMethod,
        path: String,
        installationId: String?,
        requestBody: String?,
        decode: (String) -> T,
    ): T {
        val correlationId = correlationIdProvider()
        val token = accessTokenProvider()
            ?.takeIf { it.isNotBlank() }
            ?: throw IdentityApiException(
                code = "REAUTH_REQUIRED",
                correlationId = correlationId,
                httpStatus = null,
                message = "No access token is available.",
            )

        val response = client.request(baseUrl + path) {
            this.method = method
            contentType(ContentType.Application.Json)
            header(
                HttpHeaders.Authorization,
                "Bearer $token",
            )
            header(
                "X-Correlation-Id",
                correlationId,
            )
            installationId
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    header(
                        "X-Device-Installation-Id",
                        it,
                    )
                }
            traceParentProvider()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    header("traceparent", it)
                }
            if (requestBody != null) {
                setBody(requestBody)
            }
        }

        val body = response.bodyAsText()
        if (response.status.value in 200..299) {
            return decode(body)
        }

        val error = runCatching {
            json.decodeFromString<ErrorEnvelope>(body)
        }.getOrNull()

        throw IdentityApiException(
            code = error?.code ?: "HTTP_ERROR",
            correlationId =
                error?.correlationId ?: correlationId,
            httpStatus = response.status.value,
            message =
                error?.message ?: "Identity API request failed.",
        )
    }
}
