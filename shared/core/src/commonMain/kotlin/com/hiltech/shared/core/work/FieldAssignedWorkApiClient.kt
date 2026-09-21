package com.hiltech.shared.core.work

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class FieldAssignedWorkApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun today(
        installationId: String,
    ): List<FieldTodayItemDto> =
        api.request(
            HttpMethod.Get,
            "/v1/field/today",
            HiltechRequestOptions(
                installationId =
                    installationId,
            ),
            decode = {
                json.decodeFromString(
                    ListSerializer(
                        FieldTodayItemDto.serializer(),
                    ),
                    it,
                )
            },
        )

    suspend fun jobBundle(
        workOrderId: String,
        installationId: String,
    ): TechnicianJobBundleDto {
        require(
            UUID.matches(
                workOrderId.trim(),
            ),
        ) {
            "WorkOrder id must use canonical UUID format."
        }
        return api.request(
            HttpMethod.Get,
            "/v1/work-orders/" +
                workOrderId.trim() +
                "/job-bundle",
            HiltechRequestOptions(
                installationId =
                    installationId,
            ),
            decode = {
                json.decodeFromString(
                    TechnicianJobBundleDto.serializer(),
                    it,
                )
            },
        )
    }

    private companion object {
        val UUID =
            Regex(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
            )
    }
}
