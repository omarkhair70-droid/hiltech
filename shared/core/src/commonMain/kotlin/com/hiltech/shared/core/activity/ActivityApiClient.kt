package com.hiltech.shared.core.activity

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

class ActivityApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun workOrderActivity(
        workOrderId: String,
        installationId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): WorkOrderActivityPageDto {
        require(workOrderId.isNotBlank())
        require(limit in 1..100)

        val path =
            buildString {
                append(
                    "/v1/work-orders/",
                )
                append(workOrderId)
                append(
                    "/activity?limit=",
                )
                append(limit)

                cursor
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        require(
                            CURSOR_PATTERN
                                .matches(it),
                        ) {
                            "Activity cursor contains unsupported characters."
                        }
                        append("&cursor=")
                        append(it)
                    }
            }

        return api.request(
            method = HttpMethod.Get,
            path = path,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    WorkOrderActivityPageDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    companion object {
        private val CURSOR_PATTERN =
            Regex(
                "^[A-Za-z0-9_.-]{1,4096}$",
            )
    }
}
