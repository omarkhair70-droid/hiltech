package com.hiltech.server.people

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateEmployeeDocumentRequest(
    val operationId: String,
    val baseEmployeeVersion: Long,
    val documentTypeCode: String,
    val documentLabel: String? = null,
    val issueDate: String? = null,
    val expiryDate: String? = null,
    val retentionPolicyCode: String? = null,
)

data class VerifyHrRecordRequest(
    val operationId: String,
    val baseVersion: Long,
    val result: String,
)

data class CreateCertificationRequest(
    val operationId: String,
    val baseEmployeeVersion: Long,
    val certificationTypeCode: String,
    val certificationLabel: String? = null,
    val issuer: String? = null,
    val issuedAt: String? = null,
    val validUntil: String? = null,
    val employeeDocumentId: String? = null,
)

data class EmployeeDocumentResponse(
    val documentId: String,
    val organizationId: String,
    val employeeId: String,
    val employeeCode: String,
    val employeeDisplayName: String,
    val documentTypeCode: String,
    val documentLabel: String?,
    val issueDate: String?,
    val expiryDate: String?,
    val verificationState: String,
    val verifiedAt: String?,
    val verifiedByUserId: String?,
    val evidenceId: String?,
    val evidenceStorageState: String?,
    val retentionPolicyCode: String?,
    val version: Long,
)

data class EmployeeDocumentListResponse(
    val items: List<EmployeeDocumentResponse>,
    val correlationId: String,
)

data class EmployeeDocumentCommandResponse(
    val document: EmployeeDocumentResponse,
    val replayed: Boolean,
    val correlationId: String,
)

data class CertificationResponse(
    val certificationId: String,
    val organizationId: String,
    val employeeId: String,
    val employeeCode: String,
    val employeeDisplayName: String,
    val certificationTypeCode: String,
    val certificationLabel: String?,
    val issuer: String?,
    val issuedAt: String?,
    val validUntil: String?,
    val verificationState: String,
    val verifiedAt: String?,
    val verifiedByUserId: String?,
    val employeeDocumentId: String?,
    val documentVerificationState: String?,
    val documentEvidenceStorageState: String?,
    val version: Long,
)

data class CertificationListResponse(
    val items: List<CertificationResponse>,
    val correlationId: String,
)

data class CertificationCommandResponse(
    val certification: CertificationResponse,
    val replayed: Boolean,
    val correlationId: String,
)

data class CertificationEligibilityResponse(
    val employeeId: String,
    val employeeCode: String,
    val employeeDisplayName: String,
    val certificationId: String,
    val certificationTypeCode: String,
    val certificationLabel: String?,
    val verificationState: String,
    val validNow: Boolean,
    val validUntil: String?,
)

data class CertificationEligibilityListResponse(
    val items: List<CertificationEligibilityResponse>,
    val correlationId: String,
)

@RestController
@RequestMapping("/v1/employees")
class EmployeeHrDocumentsController(
    private val service: HrDocumentsService,
) {
    @PostMapping("/{employeeId}/documents")
    fun createDocument(
        servletRequest: HttpServletRequest,
        @PathVariable employeeId: String,
        @RequestHeader(name = "Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request: CreateEmployeeDocumentRequest,
    ): EmployeeDocumentCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId
                .toHrUuid(
                    "INVALID_OPERATION_ID",
                )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        val result =
            service.createDocument(
                CreateEmployeeDocumentCommand(
                    operationId =
                        operationId,
                    employeeId =
                        employeeId.toHrUuid(
                            "INVALID_EMPLOYEE_ID",
                        ),
                    baseEmployeeVersion =
                        request.baseEmployeeVersion,
                    documentTypeCode =
                        request.documentTypeCode,
                    documentLabel =
                        request.documentLabel,
                    issueDate =
                        request.issueDate
                            ?.toHrLocalDate(
                                "INVALID_ISSUE_DATE",
                            ),
                    expiryDate =
                        request.expiryDate
                            ?.toHrLocalDate(
                                "INVALID_EXPIRY_DATE",
                            ),
                    retentionPolicyCode =
                        request.retentionPolicyCode,
                    actorUserId =
                        context.requireHrIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return EmployeeDocumentCommandResponse(
            document =
                result.document.toResponse(),
            replayed =
                result.replayed,
            correlationId =
                context.correlationId,
        )
    }

    @GetMapping("/{employeeId}/documents")
    fun documents(
        servletRequest: HttpServletRequest,
        @PathVariable employeeId: String,
    ): EmployeeDocumentListResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        return EmployeeDocumentListResponse(
            items =
                service.documents(
                    actorUserId =
                        context.requireHrIdentityId(),
                    employeeId =
                        employeeId.toHrUuid(
                            "INVALID_EMPLOYEE_ID",
                        ),
                ).map {
                    it.toResponse()
                },
            correlationId =
                context.correlationId,
        )
    }

    @PostMapping("/{employeeId}/certifications")
    fun createCertification(
        servletRequest: HttpServletRequest,
        @PathVariable employeeId: String,
        @RequestHeader(name = "Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request: CreateCertificationRequest,
    ): CertificationCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId
                .toHrUuid(
                    "INVALID_OPERATION_ID",
                )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        val result =
            service.createCertification(
                CreateCertificationCommand(
                    operationId =
                        operationId,
                    employeeId =
                        employeeId.toHrUuid(
                            "INVALID_EMPLOYEE_ID",
                        ),
                    baseEmployeeVersion =
                        request.baseEmployeeVersion,
                    certificationTypeCode =
                        request.certificationTypeCode,
                    certificationLabel =
                        request.certificationLabel,
                    issuer =
                        request.issuer,
                    issuedAt =
                        request.issuedAt
                            ?.toHrInstant(
                                "INVALID_ISSUED_AT",
                            ),
                    validUntil =
                        request.validUntil
                            ?.toHrInstant(
                                "INVALID_VALID_UNTIL",
                            ),
                    employeeDocumentId =
                        request.employeeDocumentId
                            ?.toHrUuid(
                                "INVALID_EMPLOYEE_DOCUMENT_ID",
                            ),
                    actorUserId =
                        context.requireHrIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return CertificationCommandResponse(
            certification =
                result.certification
                    .toResponse(),
            replayed =
                result.replayed,
            correlationId =
                context.correlationId,
        )
    }

    @GetMapping("/{employeeId}/certifications")
    fun certifications(
        servletRequest: HttpServletRequest,
        @PathVariable employeeId: String,
    ): CertificationListResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        return CertificationListResponse(
            items =
                service.certifications(
                    actorUserId =
                        context.requireHrIdentityId(),
                    employeeId =
                        employeeId.toHrUuid(
                            "INVALID_EMPLOYEE_ID",
                        ),
                ).map {
                    it.toResponse()
                },
            correlationId =
                context.correlationId,
        )
    }
}

@RestController
@RequestMapping("/v1/employee-documents")
class EmployeeDocumentController(
    private val service: HrDocumentsService,
) {
    @GetMapping("/{documentId}")
    fun detail(
        servletRequest: HttpServletRequest,
        @PathVariable documentId: String,
    ): EmployeeDocumentResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        return service.document(
            actorUserId =
                context.requireHrIdentityId(),
            documentId =
                documentId.toHrUuid(
                    "INVALID_EMPLOYEE_DOCUMENT_ID",
                ),
        ).toResponse()
    }

    @PostMapping("/{documentId}/verification")
    fun verify(
        servletRequest: HttpServletRequest,
        @PathVariable documentId: String,
        @RequestHeader(name = "Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request: VerifyHrRecordRequest,
    ): EmployeeDocumentCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId
                .toHrUuid(
                    "INVALID_OPERATION_ID",
                )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        val result =
            service.verifyDocument(
                VerifyEmployeeDocumentCommand(
                    operationId =
                        operationId,
                    documentId =
                        documentId.toHrUuid(
                            "INVALID_EMPLOYEE_DOCUMENT_ID",
                        ),
                    baseVersion =
                        request.baseVersion,
                    result =
                        request.result
                            .toHrVerificationState(),
                    actorUserId =
                        context.requireHrIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return EmployeeDocumentCommandResponse(
            document =
                result.document.toResponse(),
            replayed =
                result.replayed,
            correlationId =
                context.correlationId,
        )
    }
}

@RestController
@RequestMapping("/v1/certifications")
class CertificationController(
    private val service: HrDocumentsService,
) {
    @GetMapping("/{certificationId}")
    fun detail(
        servletRequest: HttpServletRequest,
        @PathVariable certificationId: String,
    ): CertificationResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        return service.certification(
            actorUserId =
                context.requireHrIdentityId(),
            certificationId =
                certificationId.toHrUuid(
                    "INVALID_CERTIFICATION_ID",
                ),
        ).toResponse()
    }

    @PostMapping("/{certificationId}/verification")
    fun verify(
        servletRequest: HttpServletRequest,
        @PathVariable certificationId: String,
        @RequestHeader(name = "Idempotency-Key")
        idempotencyKey: String,
        @RequestBody
        request: VerifyHrRecordRequest,
    ): CertificationCommandResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        val operationId =
            request.operationId
                .toHrUuid(
                    "INVALID_OPERATION_ID",
                )
        IdempotencyKeyContract
            .requireMatches(
                idempotencyKey,
                operationId,
            )

        val result =
            service.verifyCertification(
                VerifyCertificationCommand(
                    operationId =
                        operationId,
                    certificationId =
                        certificationId
                            .toHrUuid(
                                "INVALID_CERTIFICATION_ID",
                            ),
                    baseVersion =
                        request.baseVersion,
                    result =
                        request.result
                            .toHrVerificationState(),
                    actorUserId =
                        context.requireHrIdentityId(),
                    correlationId =
                        context.correlationId,
                ),
            )

        return CertificationCommandResponse(
            certification =
                result.certification
                    .toResponse(),
            replayed =
                result.replayed,
            correlationId =
                context.correlationId,
        )
    }
}

@RestController
@RequestMapping("/v1/certification-eligibility")
class CertificationEligibilityController(
    private val service: HrDocumentsService,
) {
    @GetMapping
    fun eligibility(
        servletRequest: HttpServletRequest,
        @RequestParam organizationId: String,
        @RequestParam(defaultValue = "500")
        limit: Int,
    ): CertificationEligibilityListResponse {
        val context =
            HiltechRequestContext.current(
                servletRequest,
            )
        return CertificationEligibilityListResponse(
            items =
                service.eligibility(
                    actorUserId =
                        context.requireHrIdentityId(),
                    organizationId =
                        organizationId.toHrUuid(
                            "INVALID_ORGANIZATION_ID",
                        ),
                    limit = limit,
                ).map {
                    CertificationEligibilityResponse(
                        employeeId =
                            it.employeeId.toString(),
                        employeeCode =
                            it.employeeCode,
                        employeeDisplayName =
                            it.employeeDisplayName,
                        certificationId =
                            it.certificationId
                                .toString(),
                        certificationTypeCode =
                            it.certificationTypeCode,
                        certificationLabel =
                            it.certificationLabel,
                        verificationState =
                            it.verificationState.name,
                        validNow =
                            it.validNow,
                        validUntil =
                            it.validUntil?.toString(),
                    )
                },
            correlationId =
                context.correlationId,
        )
    }
}

private fun EmployeeDocumentSnapshot
    .toResponse():
    EmployeeDocumentResponse =
    EmployeeDocumentResponse(
        documentId =
            documentId.toString(),
        organizationId =
            organizationId.toString(),
        employeeId =
            employeeId.toString(),
        employeeCode =
            employeeCode,
        employeeDisplayName =
            employeeDisplayName,
        documentTypeCode =
            documentTypeCode,
        documentLabel =
            documentLabel,
        issueDate =
            issueDate?.toString(),
        expiryDate =
            expiryDate?.toString(),
        verificationState =
            verificationState.name,
        verifiedAt =
            verifiedAt?.toString(),
        verifiedByUserId =
            verifiedByUserId?.toString(),
        evidenceId =
            evidenceId?.toString(),
        evidenceStorageState =
            evidenceStorageState,
        retentionPolicyCode =
            retentionPolicyCode,
        version = version,
    )

private fun CertificationSnapshot
    .toResponse():
    CertificationResponse =
    CertificationResponse(
        certificationId =
            certificationId.toString(),
        organizationId =
            organizationId.toString(),
        employeeId =
            employeeId.toString(),
        employeeCode =
            employeeCode,
        employeeDisplayName =
            employeeDisplayName,
        certificationTypeCode =
            certificationTypeCode,
        certificationLabel =
            certificationLabel,
        issuer = issuer,
        issuedAt =
            issuedAt?.toString(),
        validUntil =
            validUntil?.toString(),
        verificationState =
            verificationState.name,
        verifiedAt =
            verifiedAt?.toString(),
        verifiedByUserId =
            verifiedByUserId?.toString(),
        employeeDocumentId =
            employeeDocumentId
                ?.toString(),
        documentVerificationState =
            documentVerificationState
                ?.name,
        documentEvidenceStorageState =
            documentEvidenceStorageState,
        version = version,
    )

private fun String.toHrUuid(
    code: String,
): UUID =
    runCatching {
        UUID.fromString(this)
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "A People identifier is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun String.toHrLocalDate(
    code: String,
): LocalDate =
    runCatching {
        LocalDate.parse(this)
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "A People date is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun String.toHrInstant(
    code: String,
): Instant =
    runCatching {
        Instant.parse(this)
    }.getOrElse {
        throw ProductApiException(
            code = code,
            message =
                "A People timestamp is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun String.toHrVerificationState():
    HrVerificationState =
    runCatching {
        HrVerificationState.valueOf(
            trim().uppercase(),
        )
    }.getOrElse {
        throw ProductApiException(
            code =
                "INVALID_VERIFICATION_RESULT",
            message =
                "Verification result is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }

private fun com.hiltech.server.platform.HiltechRequestContextSnapshot
    .requireHrIdentityId(): UUID =
    identityId
        ?.let {
            runCatching {
                UUID.fromString(it)
            }.getOrNull()
        }
        ?: throw ProductApiException(
            code = "UNAUTHENTICATED",
            message =
                "Authentication is required.",
            status =
                HttpStatus.UNAUTHORIZED,
        )
