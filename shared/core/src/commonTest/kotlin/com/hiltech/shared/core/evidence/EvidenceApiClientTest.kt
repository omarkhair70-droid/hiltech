package com.hiltech.shared.core.evidence

import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvidenceApiClientTest {
    @Test
    fun reserveAndFinalizeUseCanonicalAuthenticatedCommandHeaders() =
        runBlocking {
            val seenPaths =
                mutableListOf<String>()
            val apiHttp = HttpClient(
                MockEngine { request ->
                    seenPaths +=
                        request.url.encodedPath

                    assertEquals(
                        "Bearer evidence-token",
                        request.headers[
                            HttpHeaders.Authorization
                        ],
                    )
                    assertEquals(
                        "11111111-1111-1111-1111-111111111111",
                        request.headers[
                            "X-Device-Installation-Id"
                        ],
                    )
                    assertTrue(
                        !request.headers[
                            "Idempotency-Key"
                        ].isNullOrBlank(),
                    )

                    val body =
                        if (
                            request.url.encodedPath ==
                            "/v1/evidence/reservations"
                        ) {
                            """
                            {
                              "evidence":{
                                "evidenceId":"evidence-1",
                                "uploadSessionId":"upload-1",
                                "targetType":"WORK_ORDER",
                                "targetId":"work-1",
                                "workOrderId":"work-1",
                                "evidenceRequirementKey":"after-photo",
                                "evidencePolicyId":"policy-1",
                                "evidencePolicyRevision":3,
                                "evidenceTypeCode":"PHOTO",
                                "contentType":"image/jpeg",
                                "sizeBytes":3,
                                "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                "storageState":"RESERVED",
                                "classificationCode":"INTERNAL",
                                "clientVisibilityMode":"INTERNAL_ONLY",
                                "evidenceVersion":1
                              },
                              "upload":{
                                "uploadUrl":"https://storage.test/object",
                                "requiredHeaders":{"x-amz-checksum-sha256":["abc"]},
                                "expiresAt":"2026-09-19T09:10:00Z",
                                "expectedSizeBytes":3
                              },
                              "correlationId":"corr-reserve",
                              "replayed":false
                            }
                            """.trimIndent()
                        } else {
                            """
                            {
                              "evidence":{
                                "evidenceId":"evidence-1",
                                "uploadSessionId":"upload-1",
                                "targetType":"WORK_ORDER",
                                "targetId":"work-1",
                                "workOrderId":"work-1",
                                "evidenceRequirementKey":"after-photo",
                                "evidencePolicyId":"policy-1",
                                "evidencePolicyRevision":3,
                                "evidenceTypeCode":"PHOTO",
                                "contentType":"image/jpeg",
                                "sizeBytes":3,
                                "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                "storageState":"READY",
                                "classificationCode":"INTERNAL",
                                "clientVisibilityMode":"INTERNAL_ONLY",
                                "evidenceVersion":2
                              },
                              "correlationId":"corr-finalize",
                              "replayed":false
                            }
                            """.trimIndent()
                        }

                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            "application/json",
                        ),
                    )
                },
            )

            val api =
                HiltechApiClient(
                    client = apiHttp,
                    baseUrl =
                        "https://api.hiltech.test",
                    accessTokenProvider = {
                        "evidence-token"
                    },
                    correlationIdProvider = {
                        "corr-client"
                    },
                )
            val client =
                EvidenceApiClient(
                    api = api,
                    binaryClient =
                        HttpClient(
                            MockEngine {
                                respond(
                                    "",
                                    HttpStatusCode.OK,
                                )
                            },
                        ),
                )
            val installationId =
                "11111111-1111-1111-1111-111111111111"

            val reserve =
                client.reserve(
                    request =
                        ReserveEvidenceUploadRequestDto(
                            operationId =
                                "22222222-2222-2222-2222-222222222222",
                            targetType =
                                "WORK_ORDER",
                            targetId =
                                "33333333-3333-3333-3333-333333333333",
                            workOrderId =
                                "33333333-3333-3333-3333-333333333333",
                            evidenceRequirementKey =
                                "after-photo",
                            evidenceTypeCode =
                                "PHOTO",
                            contentType =
                                "image/jpeg",
                            sizeBytes = 3,
                            expectedSha256 =
                                "a".repeat(64),
                            capturedAt =
                                "2026-09-19T09:00:00Z",
                        ),
                    installationId =
                        installationId,
                )

            assertEquals(
                "RESERVED",
                reserve.evidence.storageState,
            )

            val finalized =
                client.finalize(
                    evidenceId =
                        reserve.evidence.evidenceId,
                    request =
                        FinalizeEvidenceRequestDto(
                            operationId =
                                "44444444-4444-4444-4444-444444444444",
                            uploadSessionId =
                                reserve.evidence
                                    .uploadSessionId,
                            expectedSha256 =
                                reserve.evidence.sha256,
                            expectedSizeBytes =
                                reserve.evidence.sizeBytes,
                        ),
                    installationId =
                        installationId,
                )

            assertEquals(
                "READY",
                finalized.evidence.storageState,
            )
            assertEquals(
                listOf(
                    "/v1/evidence/reservations",
                    "/v1/evidence/evidence-1/finalize",
                ),
                seenPaths,
            )
        }

    @Test
    fun signedBinaryUploadUsesOnlyStorageHeadersAndNoBearerToken() =
        runBlocking {
            var called = false
            val binaryHttp =
                HttpClient(
                    MockEngine { request ->
                        called = true

                        assertNull(
                            request.headers[
                                HttpHeaders.Authorization
                            ],
                        )
                        assertEquals(
                            "signed-checksum",
                            request.headers[
                                "x-amz-checksum-sha256"
                            ],
                        )
                        assertEquals(
                            "image/jpeg",
                            request.headers[
                                HttpHeaders.ContentType
                            ],
                        )

                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                        )
                    },
                )

            val client =
                EvidenceApiClient(
                    api =
                        HiltechApiClient(
                            client =
                                HttpClient(
                                    MockEngine {
                                        error(
                                            "API should not be called",
                                        )
                                    },
                                ),
                            baseUrl =
                                "https://api.hiltech.test",
                            accessTokenProvider = {
                                "token"
                            },
                            correlationIdProvider = {
                                "corr"
                            },
                        ),
                    binaryClient =
                        binaryHttp,
                )

            client.uploadBytes(
                target =
                    EvidenceUploadTargetDto(
                        uploadUrl =
                            "https://storage.test/object",
                        requiredHeaders =
                            mapOf(
                                "x-amz-checksum-sha256" to
                                    listOf(
                                        "signed-checksum",
                                    ),
                            ),
                        expiresAt =
                            "2026-09-19T09:10:00Z",
                        expectedSizeBytes = 3,
                    ),
                contentType = "image/jpeg",
                bytes =
                    byteArrayOf(
                        1,
                        2,
                        3,
                    ),
            )

            assertTrue(called)
        }

    @Test
    fun binarySizeMismatchFailsBeforeStorageNetwork() =
        runBlocking {
            var called = false
            val client =
                EvidenceApiClient(
                    api =
                        HiltechApiClient(
                            client =
                                HttpClient(
                                    MockEngine {
                                        error("unused")
                                    },
                                ),
                            baseUrl =
                                "https://api.hiltech.test",
                            accessTokenProvider = {
                                "token"
                            },
                            correlationIdProvider = {
                                "corr"
                            },
                        ),
                    binaryClient =
                        HttpClient(
                            MockEngine {
                                called = true
                                respond(
                                    "",
                                    HttpStatusCode.OK,
                                )
                            },
                        ),
                )

            val failure =
                assertFailsWith<
                    EvidenceBinaryUploadException
                > {
                    client.uploadBytes(
                        target =
                            EvidenceUploadTargetDto(
                                uploadUrl =
                                    "https://storage.test/object",
                                requiredHeaders =
                                    emptyMap(),
                                expiresAt =
                                    "2026-09-19T09:10:00Z",
                                expectedSizeBytes =
                                    4,
                            ),
                        contentType =
                            "image/jpeg",
                        bytes =
                            byteArrayOf(
                                1,
                                2,
                                3,
                            ),
                    )
                }

            assertFalse(
                failure.retryable,
            )
            assertFalse(called)
        }
}
