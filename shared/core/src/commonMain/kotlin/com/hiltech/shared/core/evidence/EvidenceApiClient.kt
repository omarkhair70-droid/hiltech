package com.hiltech.shared.core.evidence

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class EvidenceApiClient(
    private val api: HiltechApiClient,
    private val binaryClient: HttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    suspend fun reserve(
        request: ReserveEvidenceUploadRequestDto,
        installationId: String,
    ): ReserveEvidenceUploadResponseDto =
        api.request(
            method = HttpMethod.Post,
            path = "/v1/evidence/reservations",
            options =
                HiltechRequestOptions(
                    installationId = installationId,
                    idempotencyKey = request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    ReserveEvidenceUploadRequestDto.serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    ReserveEvidenceUploadResponseDto.serializer(),
                    it,
                )
            },
        )

    suspend fun finalize(
        evidenceId: String,
        request: FinalizeEvidenceRequestDto,
        installationId: String,
    ): FinalizeEvidenceResponseDto =
        api.request(
            method = HttpMethod.Post,
            path = "/v1/evidence/$evidenceId/finalize",
            options =
                HiltechRequestOptions(
                    installationId = installationId,
                    idempotencyKey = request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    FinalizeEvidenceRequestDto.serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    FinalizeEvidenceResponseDto.serializer(),
                    it,
                )
            },
        )

    suspend fun uploadBytes(
        target: EvidenceUploadTargetDto,
        contentType: String,
        bytes: ByteArray,
    ) {
        if (bytes.size.toLong() != target.expectedSizeBytes) {
            throw EvidenceBinaryUploadException(
                httpStatus = null,
                retryable = false,
                message =
                    "Evidence bytes do not match the reserved size.",
            )
        }

        val response =
            try {
                binaryClient.request(
                    target.uploadUrl,
                ) {
                    method = HttpMethod.Put
                    target.requiredHeaders
                        .forEach { (name, values) ->
                            values.forEach { value ->
                                header(name, value)
                            }
                        }
                    contentType(
                        ContentType.parse(
                            contentType,
                        ),
                    )
                    setBody(bytes)
                }
            } catch (
                cancellation: CancellationException,
            ) {
                throw cancellation
            } catch (failure: Throwable) {
                throw EvidenceBinaryUploadException(
                    httpStatus = null,
                    retryable = true,
                    message =
                        "Evidence upload transport failed.",
                    cause = failure,
                )
            }

        if (response.status.value !in 200..299) {
            response.bodyAsText()
            throw EvidenceBinaryUploadException(
                httpStatus =
                    response.status.value,
                retryable =
                    response.status.value in
                        setOf(
                            408,
                            425,
                            429,
                            500,
                            502,
                            503,
                            504,
                        ),
                message =
                    "Evidence upload was rejected by storage.",
            )
        }
    }
}
