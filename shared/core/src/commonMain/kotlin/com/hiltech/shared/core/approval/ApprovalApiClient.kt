package com.hiltech.shared.core.approval

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

class ApprovalApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun assigned(
        installationId: String,
        limit: Int = 50,
        cursor: String? = null,
    ): AssignedApprovalPageDto {
        require(limit in 1..100)

        val path =
            buildString {
                append(
                    "/v1/approvals/assigned?limit=",
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
                            "Approval cursor contains unsupported characters."
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
                    AssignedApprovalPageDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun one(
        approvalRequestId: String,
        installationId: String,
    ): ApprovalRequestDto {
        require(
            APPROVAL_ID_PATTERN.matches(
                approvalRequestId,
            ),
        )

        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/approvals/" +
                    approvalRequestId,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    ApprovalRequestDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun decide(
        approvalRequestId: String,
        request:
            ApprovalDecisionRequestDto,
        installationId: String,
    ): ApprovalDecisionResponseDto {
        require(
            APPROVAL_ID_PATTERN.matches(
                approvalRequestId,
            ),
        )
        require(
            APPROVAL_ID_PATTERN.matches(
                request.operationId,
            ),
        )
        require(
            request.decision in
                setOf(
                    "APPROVE",
                    "REJECT",
                    "REQUEST_CHANGE",
                ),
        )

        return api.request(
            method = HttpMethod.Post,
            path =
                "/v1/approvals/" +
                    approvalRequestId +
                    "/decisions",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    ApprovalDecisionRequestDto
                        .serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    ApprovalDecisionResponseDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    companion object {
        private val APPROVAL_ID_PATTERN =
            Regex(
                "^[0-9a-fA-F-]{36}$",
            )
        private val CURSOR_PATTERN =
            Regex(
                "^[A-Za-z0-9_.-]{1,4096}$",
            )
    }
}
