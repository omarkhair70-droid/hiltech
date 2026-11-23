package com.hiltech.shared.core.executive

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

/**
 * Read-only, online-only executive projection. Never cache across actors,
 * organizations, sign-out or revocation; never enqueue executive mutations.
 */
class ExecutiveApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    suspend fun home(
        organizationId: String,
        installationId: String,
        expectedIdentityId: String,
    ): ExecutiveHomeDto {
        require(organizationId.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))) {
            "A valid organization ID is required."
        }
        require(expectedIdentityId.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))) {
            "A current authenticated identity ID is required."
        }
        val response: ExecutiveHomeDto = api.request(
            method = HttpMethod.Get,
            path = "/v1/executive/home?organizationId=" + organizationId,
            options = HiltechRequestOptions(installationId = installationId),
            decode = {
                json.decodeFromString(ExecutiveHomeDto.serializer(), it)
            },
        )
        if (!executiveSnapshotMatchesSession(response, expectedIdentityId, organizationId)) {
            throw ExecutiveSessionScopeMismatchException()
        }
        return response
    }
}

/**
 * A response from a different actor/organization may be in flight after
 * sign-out or switching company. Never cache or render it as fresh.
 */
class ExecutiveSessionScopeMismatchException :
    IllegalStateException("The executive response does not match the active session.")
