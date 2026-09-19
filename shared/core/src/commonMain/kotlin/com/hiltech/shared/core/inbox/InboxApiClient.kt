package com.hiltech.shared.core.inbox

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

class InboxApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun workQueue(
        installationId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): InboxPageDto =
        api.request(
            method = HttpMethod.Get,
            path =
                buildListPath(
                    base =
                        "/v1/work-queue",
                    limit = limit,
                    cursor = cursor,
                    state = null,
                    read = null,
                ),
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    InboxPageDto.serializer(),
                    it,
                )
            },
        )

    suspend fun inbox(
        installationId: String,
        limit: Int = 50,
        cursor: String? = null,
        state: String? = null,
        read: String? = null,
    ): InboxPageDto {
        state?.let {
            require(
                it in
                    setOf(
                        "OPEN",
                        "RESOLVED",
                    ),
            )
        }
        read?.let {
            require(
                it in
                    setOf(
                        "READ",
                        "UNREAD",
                    ),
            )
        }

        return api.request(
            method = HttpMethod.Get,
            path =
                buildListPath(
                    base = "/v1/inbox",
                    limit = limit,
                    cursor = cursor,
                    state = state,
                    read = read,
                ),
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    InboxPageDto.serializer(),
                    it,
                )
            },
        )
    }

    suspend fun one(
        inboxItemId: String,
        installationId: String,
    ): InboxItemDto {
        requireId(inboxItemId)

        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/inbox/" +
                    inboxItemId,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    InboxItemDto.serializer(),
                    it,
                )
            },
        )
    }

    suspend fun markRead(
        inboxItemId: String,
        request:
            InboxReadStateRequestDto,
        installationId: String,
    ): InboxReadStateResponseDto =
        setReadState(
            inboxItemId =
                inboxItemId,
            request = request,
            installationId =
                installationId,
            pathSuffix = "read",
        )

    suspend fun markUnread(
        inboxItemId: String,
        request:
            InboxReadStateRequestDto,
        installationId: String,
    ): InboxReadStateResponseDto =
        setReadState(
            inboxItemId =
                inboxItemId,
            request = request,
            installationId =
                installationId,
            pathSuffix = "unread",
        )

    private suspend fun setReadState(
        inboxItemId: String,
        request:
            InboxReadStateRequestDto,
        installationId: String,
        pathSuffix: String,
    ): InboxReadStateResponseDto {
        requireId(inboxItemId)
        requireId(request.operationId)

        return api.request(
            method = HttpMethod.Post,
            path =
                "/v1/inbox/" +
                    inboxItemId +
                    "/" +
                    pathSuffix,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    InboxReadStateRequestDto
                        .serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    InboxReadStateResponseDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    private fun buildListPath(
        base: String,
        limit: Int,
        cursor: String?,
        state: String?,
        read: String?,
    ): String {
        require(limit in 1..100)

        return buildString {
            append(base)
            append("?limit=")
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
                        "Inbox cursor contains unsupported characters."
                    }
                    append("&cursor=")
                    append(it)
                }

            state?.let {
                append("&state=")
                append(it)
            }

            read?.let {
                append("&read=")
                append(it)
            }
        }
    }

    private fun requireId(
        value: String,
    ) {
        require(
            ID_PATTERN.matches(value),
        )
    }

    companion object {
        private val ID_PATTERN =
            Regex(
                "^[0-9a-fA-F-]{36}$",
            )
        private val CURSOR_PATTERN =
            Regex(
                "^[A-Za-z0-9_.-]{1,4096}$",
            )
    }
}
