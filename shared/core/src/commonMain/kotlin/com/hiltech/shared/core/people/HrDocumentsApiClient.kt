package com.hiltech.shared.core.people

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

class HrDocumentsApiClient(
    private val api: HiltechApiClient,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
) {
    suspend fun createDocument(
        employeeId: String,
        request: CreateEmployeeDocumentRequestDto,
        installationId: String,
    ): EmployeeDocumentCommandResponseDto {
        requireId(employeeId)
        requireId(request.operationId)
        require(request.baseEmployeeVersion >= 1)

        return api.request(
            method = HttpMethod.Post,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/documents",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    CreateEmployeeDocumentRequestDto
                        .serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    EmployeeDocumentCommandResponseDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun documents(
        employeeId: String,
        installationId: String,
    ): EmployeeDocumentListDto {
        requireId(employeeId)
        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/documents",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    EmployeeDocumentListDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun document(
        documentId: String,
        installationId: String,
    ): EmployeeDocumentDto {
        requireId(documentId)
        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/employee-documents/" +
                    documentId,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    EmployeeDocumentDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun verifyDocument(
        documentId: String,
        request: VerifyHrRecordRequestDto,
        installationId: String,
    ): EmployeeDocumentCommandResponseDto {
        requireId(documentId)
        requireId(request.operationId)
        require(request.baseVersion >= 1)

        return api.request(
            method = HttpMethod.Post,
            path =
                "/v1/employee-documents/" +
                    documentId +
                    "/verification",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    VerifyHrRecordRequestDto
                        .serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    EmployeeDocumentCommandResponseDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun createCertification(
        employeeId: String,
        request: CreateCertificationRequestDto,
        installationId: String,
    ): CertificationCommandResponseDto {
        requireId(employeeId)
        requireId(request.operationId)
        require(request.baseEmployeeVersion >= 1)
        request.employeeDocumentId
            ?.let(::requireId)

        return api.request(
            method = HttpMethod.Post,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/certifications",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    CreateCertificationRequestDto
                        .serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    CertificationCommandResponseDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun certifications(
        employeeId: String,
        installationId: String,
    ): CertificationListDto {
        requireId(employeeId)
        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/employees/" +
                    employeeId +
                    "/certifications",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    CertificationListDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun certification(
        certificationId: String,
        installationId: String,
    ): CertificationDto {
        requireId(certificationId)
        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/certifications/" +
                    certificationId,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    CertificationDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun verifyCertification(
        certificationId: String,
        request: VerifyHrRecordRequestDto,
        installationId: String,
    ): CertificationCommandResponseDto {
        requireId(certificationId)
        requireId(request.operationId)
        require(request.baseVersion >= 1)

        return api.request(
            method = HttpMethod.Post,
            path =
                "/v1/certifications/" +
                    certificationId +
                    "/verification",
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                    idempotencyKey =
                        request.operationId,
                ),
            requestBody =
                json.encodeToString(
                    VerifyHrRecordRequestDto
                        .serializer(),
                    request,
                ),
            decode = {
                json.decodeFromString(
                    CertificationCommandResponseDto
                        .serializer(),
                    it,
                )
            },
        )
    }

    suspend fun eligibility(
        organizationId: String,
        installationId: String,
        limit: Int = 500,
    ): CertificationEligibilityListDto {
        requireId(organizationId)
        require(limit in 1..500)

        return api.request(
            method = HttpMethod.Get,
            path =
                "/v1/certification-eligibility?organizationId=" +
                    organizationId +
                    "&limit=" +
                    limit,
            options =
                HiltechRequestOptions(
                    installationId =
                        installationId,
                ),
            decode = {
                json.decodeFromString(
                    CertificationEligibilityListDto
                        .serializer(),
                    it,
                )
            },
        )
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
    }
}
