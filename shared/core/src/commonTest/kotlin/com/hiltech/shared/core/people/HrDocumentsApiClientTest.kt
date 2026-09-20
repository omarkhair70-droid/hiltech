package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class HrDocumentsApiClientTest {
    @Test
    fun privateHrCommandsAndSafeEligibilityReuseSharedClientContract() =
        runBlocking {
            val seen =
                mutableListOf<
                    Pair<String, String?>
                >()
            val organizationId =
                "11111111-1111-1111-1111-111111111111"
            val employeeId =
                "22222222-2222-2222-2222-222222222222"
            val documentId =
                "33333333-3333-3333-3333-333333333333"
            val certificationId =
                "44444444-4444-4444-4444-444444444444"
            val createDocumentOperation =
                "55555555-5555-5555-5555-555555555555"
            val verifyDocumentOperation =
                "66666666-6666-6666-6666-666666666666"
            val createCertificationOperation =
                "77777777-7777-7777-7777-777777777777"
            val verifyCertificationOperation =
                "88888888-8888-8888-8888-888888888888"
            val installationId =
                "99999999-9999-9999-9999-999999999999"

            val document =
                """
                {
                  "documentId":"$documentId",
                  "organizationId":"$organizationId",
                  "employeeId":"$employeeId",
                  "employeeCode":"EMP-001",
                  "employeeDisplayName":"Worker",
                  "documentTypeCode":"ID_COPY",
                  "documentLabel":"ID Copy",
                  "issueDate":"2026-01-01",
                  "expiryDate":null,
                  "verificationState":"VERIFIED",
                  "verifiedAt":"2026-09-20T00:00:00Z",
                  "verifiedByUserId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "evidenceId":null,
                  "evidenceStorageState":null,
                  "retentionPolicyCode":null,
                  "version":2
                }
                """.trimIndent()

            val certification =
                """
                {
                  "certificationId":"$certificationId",
                  "organizationId":"$organizationId",
                  "employeeId":"$employeeId",
                  "employeeCode":"EMP-001",
                  "employeeDisplayName":"Worker",
                  "certificationTypeCode":"SAFETY",
                  "certificationLabel":"Safety",
                  "issuer":"Fixture",
                  "issuedAt":"2026-01-01T00:00:00Z",
                  "validUntil":"2027-01-01T00:00:00Z",
                  "verificationState":"VERIFIED",
                  "verifiedAt":"2026-09-20T00:00:00Z",
                  "verifiedByUserId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "employeeDocumentId":"$documentId",
                  "documentVerificationState":"VERIFIED",
                  "documentEvidenceStorageState":null,
                  "version":2
                }
                """.trimIndent()

            val http =
                HttpClient(
                    MockEngine { request ->
                        seen +=
                            pathOf(request) to
                                request.headers[
                                    "Idempotency-Key"
                                ]

                        assertEquals(
                            "Bearer hr-token",
                            request.headers[
                                HttpHeaders.Authorization
                            ],
                        )
                        assertEquals(
                            installationId,
                            request.headers[
                                "X-Device-Installation-Id"
                            ],
                        )

                        val path =
                            request.url.encodedPath
                        val body =
                            when {
                                path ==
                                    "/v1/certification-eligibility" ->
                                    """
                                    {
                                      "items":[
                                        {
                                          "employeeId":"$employeeId",
                                          "employeeCode":"EMP-001",
                                          "employeeDisplayName":"Worker",
                                          "certificationId":"$certificationId",
                                          "certificationTypeCode":"SAFETY",
                                          "certificationLabel":"Safety",
                                          "verificationState":"VERIFIED",
                                          "validNow":true,
                                          "validUntil":"2027-01-01T00:00:00Z"
                                        }
                                      ],
                                      "correlationId":"corr-eligibility"
                                    }
                                    """.trimIndent()

                                path.endsWith(
                                    "/documents",
                                ) &&
                                    request.method.value ==
                                    "GET" ->
                                    """
                                    {
                                      "items":[$document],
                                      "correlationId":"corr-docs"
                                    }
                                    """.trimIndent()

                                path.endsWith(
                                    "/certifications",
                                ) &&
                                    request.method.value ==
                                    "GET" ->
                                    """
                                    {
                                      "items":[$certification],
                                      "correlationId":"corr-certs"
                                    }
                                    """.trimIndent()

                                path.contains(
                                    "employee-documents",
                                ) &&
                                    request.method.value ==
                                    "GET" ->
                                    document

                                path.contains(
                                    "certifications",
                                ) &&
                                    request.method.value ==
                                    "GET" ->
                                    certification

                                path.contains(
                                    "employee-documents",
                                ) ||
                                    (
                                        path.endsWith(
                                            "/documents",
                                        ) &&
                                            request.method.value ==
                                            "POST"
                                    ) ->
                                    """
                                    {
                                      "document":$document,
                                      "replayed":false,
                                      "correlationId":"corr-doc-command"
                                    }
                                    """.trimIndent()

                                else ->
                                    """
                                    {
                                      "certification":$certification,
                                      "replayed":false,
                                      "correlationId":"corr-cert-command"
                                    }
                                    """.trimIndent()
                            }

                        respond(
                            content = body,
                            status =
                                HttpStatusCode.OK,
                            headers =
                                headersOf(
                                    HttpHeaders.ContentType,
                                    "application/json",
                                ),
                        )
                    },
                )

            val client =
                HrDocumentsApiClient(
                    HiltechApiClient(
                        client = http,
                        baseUrl =
                            "https://api.hiltech.test",
                        accessTokenProvider = {
                            "hr-token"
                        },
                        correlationIdProvider = {
                            "corr-client"
                        },
                    ),
                )

            val createdDocument =
                client.createDocument(
                    employeeId =
                        employeeId,
                    request =
                        CreateEmployeeDocumentRequestDto(
                            operationId =
                                createDocumentOperation,
                            baseEmployeeVersion = 1,
                            documentTypeCode =
                                "ID_COPY",
                        ),
                    installationId =
                        installationId,
                )
            val docs =
                client.documents(
                    employeeId =
                        employeeId,
                    installationId =
                        installationId,
                )
            val detail =
                client.document(
                    documentId =
                        documentId,
                    installationId =
                        installationId,
                )
            val verifiedDocument =
                client.verifyDocument(
                    documentId =
                        documentId,
                    request =
                        VerifyHrRecordRequestDto(
                            operationId =
                                verifyDocumentOperation,
                            baseVersion = 1,
                            result = "VERIFIED",
                        ),
                    installationId =
                        installationId,
                )
            val createdCertification =
                client.createCertification(
                    employeeId =
                        employeeId,
                    request =
                        CreateCertificationRequestDto(
                            operationId =
                                createCertificationOperation,
                            baseEmployeeVersion = 1,
                            certificationTypeCode =
                                "SAFETY",
                            employeeDocumentId =
                                documentId,
                        ),
                    installationId =
                        installationId,
                )
            val certs =
                client.certifications(
                    employeeId =
                        employeeId,
                    installationId =
                        installationId,
                )
            val certDetail =
                client.certification(
                    certificationId =
                        certificationId,
                    installationId =
                        installationId,
                )
            val verifiedCertification =
                client.verifyCertification(
                    certificationId =
                        certificationId,
                    request =
                        VerifyHrRecordRequestDto(
                            operationId =
                                verifyCertificationOperation,
                            baseVersion = 1,
                            result = "VERIFIED",
                        ),
                    installationId =
                        installationId,
                )
            val eligibility =
                client.eligibility(
                    organizationId =
                        organizationId,
                    installationId =
                        installationId,
                    limit = 50,
                )

            assertEquals(
                documentId,
                createdDocument.document
                    .documentId,
            )
            assertEquals(
                documentId,
                docs.items.single().documentId,
            )
            assertEquals(
                "VERIFIED",
                detail.verificationState,
            )
            assertEquals(
                "VERIFIED",
                verifiedDocument.document
                    .verificationState,
            )
            assertEquals(
                certificationId,
                createdCertification.certification
                    .certificationId,
            )
            assertEquals(
                certificationId,
                certs.items.single()
                    .certificationId,
            )
            assertEquals(
                "SAFETY",
                certDetail
                    .certificationTypeCode,
            )
            assertEquals(
                "VERIFIED",
                verifiedCertification
                    .certification
                    .verificationState,
            )
            assertEquals(
                true,
                eligibility.items
                    .single()
                    .validNow,
            )

            assertEquals(
                listOf(
                    "/v1/employees/$employeeId/documents" to createDocumentOperation,
                    "/v1/employees/$employeeId/documents" to null,
                    "/v1/employee-documents/$documentId" to null,
                    "/v1/employee-documents/$documentId/verification" to verifyDocumentOperation,
                    "/v1/employees/$employeeId/certifications" to createCertificationOperation,
                    "/v1/employees/$employeeId/certifications" to null,
                    "/v1/certifications/$certificationId" to null,
                    "/v1/certifications/$certificationId/verification" to verifyCertificationOperation,
                    "/v1/certification-eligibility?organizationId=$organizationId&limit=50" to null,
                ),
                seen,
            )
        }

    private fun pathOf(
        request: HttpRequestData,
    ): String =
        buildString {
            append(
                request.url.encodedPath,
            )
            if (
                request.url.encodedQuery
                    .isNotBlank()
            ) {
                append("?")
                append(
                    request.url
                        .encodedQuery,
                )
            }
        }
}
