package com.hiltech.shared.core.identity

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

typealias IdentityApiException = HiltechApiException

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
    private val api =
        HiltechApiClient(
            client = client,
            baseUrl = baseUrl,
            accessTokenProvider =
                accessTokenProvider,
            correlationIdProvider =
                correlationIdProvider,
            traceParentProvider =
                traceParentProvider,
            json = json,
        )

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
    ): T =
        api.request(
            method = method,
            path = path,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            requestBody = requestBody,
            decode = decode,
        )

}
