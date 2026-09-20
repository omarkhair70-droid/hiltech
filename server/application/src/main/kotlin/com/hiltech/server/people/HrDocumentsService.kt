package com.hiltech.server.people

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Locale
import java.util.UUID

@Component
class HrDocumentsService(
    private val persistence:
        HrDocumentsPersistencePort,
    private val peopleAuthorization:
        PeopleAuthorizationPort,
    private val idempotency:
        IdempotentCommandExecutor,
    private val audit:
        AuditEventWriter,
    private val events:
        ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun createDocument(
        command: CreateEmployeeDocumentCommand,
    ): EmployeeDocumentCommandResult {
        requirePositiveVersion(
            command.baseEmployeeVersion,
        )

        val employee =
            requireEmployee(
                command.employeeId,
            )
        requireManage(
            command.actorUserId,
            employee.organizationId,
        )

        val documentTypeCode =
            normalizeCode(
                command.documentTypeCode,
                "INVALID_DOCUMENT_TYPE_CODE",
            )
        val documentLabel =
            optionalText(
                command.documentLabel,
                160,
                "INVALID_DOCUMENT_LABEL",
            )
        val retentionPolicyCode =
            command.retentionPolicyCode
                ?.let {
                    normalizeCode(
                        it,
                        "INVALID_RETENTION_POLICY_CODE",
                    )
                }

        if (
            command.issueDate != null &&
            command.expiryDate != null &&
            command.expiryDate <
                command.issueDate
        ) {
            throw hrError(
                code =
                    "DOCUMENT_DATE_RANGE_INVALID",
                message =
                    "Document expiry cannot be before issue date.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        val documentId =
            deterministicId(
                "employee-document",
                command.operationId,
            )
        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    command.employeeId,
                    command.baseEmployeeVersion,
                    documentTypeCode,
                    documentLabel,
                    command.issueDate,
                    command.expiryDate,
                    retentionPolicyCode,
                ).joinToString("|"),
            )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId =
                        command.operationId,
                    actorUserId =
                        command.actorUserId,
                    commandType =
                        "PEOPLE_CREATE_EMPLOYEE_DOCUMENT",
                    targetType =
                        "EMPLOYEE_DOCUMENT",
                    targetId =
                        documentId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val fresh =
                    requireEmployee(
                        command.employeeId,
                    )
                requireManage(
                    command.actorUserId,
                    fresh.organizationId,
                )
                requireVersion(
                    expected =
                        command.baseEmployeeVersion,
                    current =
                        fresh.employeeVersion,
                    code =
                        "EMPLOYEE_VERSION_CONFLICT",
                )

                val now =
                    clock.instant()
                try {
                    persistence.insertDocument(
                        documentId =
                            documentId,
                        organizationId =
                            fresh.organizationId,
                        employeeId =
                            fresh.employeeId,
                        documentTypeCode =
                            documentTypeCode,
                        documentLabel =
                            documentLabel,
                        issueDate =
                            command.issueDate,
                        expiryDate =
                            command.expiryDate,
                        retentionPolicyCode =
                            retentionPolicyCode,
                        at = now,
                    )
                } catch (
                    failure:
                        DataIntegrityViolationException,
                ) {
                    throw hrError(
                        code =
                            "EMPLOYEE_DOCUMENT_CONFLICT",
                        message =
                            "The employee document conflicts with current People state.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "EMPLOYEE_DOCUMENT_CREATED",
                        targetType =
                            "EMPLOYEE_DOCUMENT",
                        targetId =
                            documentId,
                        newStateRef =
                            "UNVERIFIED",
                        safeDiffJson =
                            """{"fields":["documentTypeCode","issueDate","expiryDate","retentionPolicyCode"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                events.publishEvent(
                    EmployeeDocumentCreated(
                        documentId =
                            documentId,
                        organizationId =
                            fresh.organizationId,
                        employeeId =
                            fresh.employeeId,
                        documentTypeCode =
                            documentTypeCode,
                        sourceVersion = 1,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "EMPLOYEE_DOCUMENT_CREATED",
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "documentId",
                                documentId.toString(),
                            )
                        }.toString(),
                )
            }

        return EmployeeDocumentCommandResult(
            document =
                requireDocument(
                    documentId,
                ),
            replayed =
                execution.replayed,
        )
    }

    fun verifyDocument(
        command: VerifyEmployeeDocumentCommand,
    ): EmployeeDocumentCommandResult {
        requirePositiveVersion(
            command.baseVersion,
        )
        requireVerificationResult(
            command.result,
        )

        val initial =
            requireDocument(
                command.documentId,
            )
        requireManage(
            command.actorUserId,
            initial.organizationId,
        )

        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    command.documentId,
                    command.baseVersion,
                    command.result,
                ).joinToString("|"),
            )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId =
                        command.operationId,
                    actorUserId =
                        command.actorUserId,
                    commandType =
                        "PEOPLE_VERIFY_EMPLOYEE_DOCUMENT",
                    targetType =
                        "EMPLOYEE_DOCUMENT",
                    targetId =
                        command.documentId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val current =
                    requireDocument(
                        command.documentId,
                    )
                requireManage(
                    command.actorUserId,
                    current.organizationId,
                )
                requireVersion(
                    expected =
                        command.baseVersion,
                    current =
                        current.version,
                    code =
                        "EMPLOYEE_DOCUMENT_VERSION_CONFLICT",
                )

                if (
                    command.result ==
                    HrVerificationState.VERIFIED
                ) {
                    current.evidenceId?.let {
                        evidenceId ->
                        if (
                            !persistence
                                .evidenceIsReadyForDocument(
                                    documentId =
                                        current.documentId,
                                    evidenceId =
                                        evidenceId,
                                )
                        ) {
                            throw hrError(
                                code =
                                    "DOCUMENT_EVIDENCE_NOT_READY",
                                message =
                                    "Supporting Evidence must be READY before document verification.",
                                status =
                                    HttpStatus.CONFLICT,
                            )
                        }
                    }
                }

                val now =
                    clock.instant()
                if (
                    !persistence.verifyDocument(
                        documentId =
                            current.documentId,
                        expectedVersion =
                            command.baseVersion,
                        result =
                            command.result,
                        actorUserId =
                            command.actorUserId,
                        at = now,
                    )
                ) {
                    throw versionConflict(
                        "EMPLOYEE_DOCUMENT_VERSION_CONFLICT",
                        current.version,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "EMPLOYEE_DOCUMENT_VERIFIED",
                        targetType =
                            "EMPLOYEE_DOCUMENT",
                        targetId =
                            current.documentId,
                        previousStateRef =
                            current
                                .verificationState
                                .name,
                        newStateRef =
                            command.result.name,
                        safeDiffJson =
                            """{"fields":["verificationState"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                events.publishEvent(
                    EmployeeDocumentVerified(
                        documentId =
                            current.documentId,
                        organizationId =
                            current.organizationId,
                        employeeId =
                            current.employeeId,
                        documentTypeCode =
                            current.documentTypeCode,
                        verificationState =
                            command.result,
                        sourceVersion =
                            current.version + 1,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "EMPLOYEE_DOCUMENT_" +
                            command.result.name,
                )
            }

        return EmployeeDocumentCommandResult(
            document =
                requireDocument(
                    command.documentId,
                ),
            replayed =
                execution.replayed,
        )
    }

    fun createCertification(
        command: CreateCertificationCommand,
    ): CertificationCommandResult {
        requirePositiveVersion(
            command.baseEmployeeVersion,
        )

        val employee =
            requireEmployee(
                command.employeeId,
            )
        requireManage(
            command.actorUserId,
            employee.organizationId,
        )

        val typeCode =
            normalizeCode(
                command.certificationTypeCode,
                "INVALID_CERTIFICATION_TYPE_CODE",
            )
        val label =
            optionalText(
                command.certificationLabel,
                160,
                "INVALID_CERTIFICATION_LABEL",
            )
        val issuer =
            optionalText(
                command.issuer,
                200,
                "INVALID_CERTIFICATION_ISSUER",
            )

        if (
            command.issuedAt != null &&
            command.validUntil != null &&
            command.validUntil <
                command.issuedAt
        ) {
            throw hrError(
                code =
                    "CERTIFICATION_DATE_RANGE_INVALID",
                message =
                    "Certification validity cannot end before it was issued.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        command.employeeDocumentId?.let {
            documentId ->
            val document =
                requireDocument(
                    documentId,
                )
            if (
                document.organizationId !=
                employee.organizationId ||
                document.employeeId !=
                employee.employeeId
            ) {
                throw hrError(
                    code =
                        "CERTIFICATION_DOCUMENT_MISMATCH",
                    message =
                        "Supporting document must belong to the same employee.",
                    status =
                        HttpStatus.CONFLICT,
                )
            }
        }

        val certificationId =
            deterministicId(
                "certification",
                command.operationId,
            )
        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    command.employeeId,
                    command.baseEmployeeVersion,
                    typeCode,
                    label,
                    issuer,
                    command.issuedAt,
                    command.validUntil,
                    command.employeeDocumentId,
                ).joinToString("|"),
            )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId =
                        command.operationId,
                    actorUserId =
                        command.actorUserId,
                    commandType =
                        "PEOPLE_CREATE_CERTIFICATION",
                    targetType =
                        "CERTIFICATION",
                    targetId =
                        certificationId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val fresh =
                    requireEmployee(
                        command.employeeId,
                    )
                requireManage(
                    command.actorUserId,
                    fresh.organizationId,
                )
                requireVersion(
                    expected =
                        command.baseEmployeeVersion,
                    current =
                        fresh.employeeVersion,
                    code =
                        "EMPLOYEE_VERSION_CONFLICT",
                )

                command.employeeDocumentId
                    ?.let { documentId ->
                        val document =
                            requireDocument(
                                documentId,
                            )
                        if (
                            document.organizationId !=
                            fresh.organizationId ||
                            document.employeeId !=
                            fresh.employeeId
                        ) {
                            throw hrError(
                                code =
                                    "CERTIFICATION_DOCUMENT_MISMATCH",
                                message =
                                    "Supporting document must belong to the same employee.",
                                status =
                                    HttpStatus.CONFLICT,
                            )
                        }
                    }

                val now =
                    clock.instant()
                try {
                    persistence
                        .insertCertification(
                            certificationId =
                                certificationId,
                            organizationId =
                                fresh.organizationId,
                            employeeId =
                                fresh.employeeId,
                            certificationTypeCode =
                                typeCode,
                            certificationLabel =
                                label,
                            issuer = issuer,
                            issuedAt =
                                command.issuedAt,
                            validUntil =
                                command.validUntil,
                            employeeDocumentId =
                                command
                                    .employeeDocumentId,
                            at = now,
                        )
                } catch (
                    failure:
                        DataIntegrityViolationException,
                ) {
                    throw hrError(
                        code =
                            "CERTIFICATION_CONFLICT",
                        message =
                            "The certification conflicts with current People state.",
                        status =
                            HttpStatus.CONFLICT,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "CERTIFICATION_CREATED",
                        targetType =
                            "CERTIFICATION",
                        targetId =
                            certificationId,
                        newStateRef =
                            "UNVERIFIED",
                        safeDiffJson =
                            """{"fields":["certificationTypeCode","issuedAt","validUntil","employeeDocumentId"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                events.publishEvent(
                    CertificationCreated(
                        certificationId =
                            certificationId,
                        organizationId =
                            fresh.organizationId,
                        employeeId =
                            fresh.employeeId,
                        certificationTypeCode =
                            typeCode,
                        sourceVersion = 1,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "CERTIFICATION_CREATED",
                )
            }

        return CertificationCommandResult(
            certification =
                requireCertification(
                    certificationId,
                ),
            replayed =
                execution.replayed,
        )
    }

    fun verifyCertification(
        command: VerifyCertificationCommand,
    ): CertificationCommandResult {
        requirePositiveVersion(
            command.baseVersion,
        )
        requireVerificationResult(
            command.result,
        )

        val initial =
            requireCertification(
                command.certificationId,
            )
        requireManage(
            command.actorUserId,
            initial.organizationId,
        )

        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    command.certificationId,
                    command.baseVersion,
                    command.result,
                ).joinToString("|"),
            )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId =
                        command.operationId,
                    actorUserId =
                        command.actorUserId,
                    commandType =
                        "PEOPLE_VERIFY_CERTIFICATION",
                    targetType =
                        "CERTIFICATION",
                    targetId =
                        command.certificationId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        command.correlationId,
                ),
            ) {
                val current =
                    requireCertification(
                        command.certificationId,
                    )
                requireManage(
                    command.actorUserId,
                    current.organizationId,
                )
                requireVersion(
                    expected =
                        command.baseVersion,
                    current =
                        current.version,
                    code =
                        "CERTIFICATION_VERSION_CONFLICT",
                )

                if (
                    command.result ==
                    HrVerificationState.VERIFIED
                ) {
                    current.employeeDocumentId
                        ?.let {
                            if (
                                current.documentVerificationState !=
                                HrVerificationState.VERIFIED
                            ) {
                                throw hrError(
                                    code =
                                        "CERTIFICATION_DOCUMENT_NOT_VERIFIED",
                                    message =
                                        "Supporting employee document must be VERIFIED.",
                                    status =
                                        HttpStatus.CONFLICT,
                                )
                            }

                            if (
                                current.documentEvidenceStorageState !=
                                    null &&
                                current.documentEvidenceStorageState !=
                                    "READY"
                            ) {
                                throw hrError(
                                    code =
                                        "CERTIFICATION_DOCUMENT_EVIDENCE_NOT_READY",
                                    message =
                                        "Supporting document Evidence must be READY.",
                                    status =
                                        HttpStatus.CONFLICT,
                                )
                            }
                        }
                }

                val now =
                    clock.instant()
                if (
                    !persistence
                        .verifyCertification(
                            certificationId =
                                current.certificationId,
                            expectedVersion =
                                command.baseVersion,
                            result =
                                command.result,
                            actorUserId =
                                command.actorUserId,
                            at = now,
                        )
                ) {
                    throw versionConflict(
                        "CERTIFICATION_VERSION_CONFLICT",
                        current.version,
                    )
                }

                audit.append(
                    AuditEventRecord(
                        actorUserId =
                            command.actorUserId,
                        action =
                            "CERTIFICATION_VERIFIED",
                        targetType =
                            "CERTIFICATION",
                        targetId =
                            current.certificationId,
                        previousStateRef =
                            current.verificationState
                                .name,
                        newStateRef =
                            command.result.name,
                        safeDiffJson =
                            """{"fields":["verificationState"]}""",
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )
                events.publishEvent(
                    CertificationVerified(
                        certificationId =
                            current.certificationId,
                        organizationId =
                            current.organizationId,
                        employeeId =
                            current.employeeId,
                        certificationTypeCode =
                            current
                                .certificationTypeCode,
                        verificationState =
                            command.result,
                        sourceVersion =
                            current.version + 1,
                        actorUserId =
                            command.actorUserId,
                        occurredAt = now,
                        correlationId =
                            command.correlationId,
                    ),
                )

                IdempotentCommandOutcome(
                    resultCode =
                        "CERTIFICATION_" +
                            command.result.name,
                )
            }

        return CertificationCommandResult(
            certification =
                requireCertification(
                    command.certificationId,
                ),
            replayed =
                execution.replayed,
        )
    }

    fun documents(
        actorUserId: UUID,
        employeeId: UUID,
    ): List<EmployeeDocumentSnapshot> {
        val employee =
            requireEmployee(employeeId)
        requireManage(
            actorUserId,
            employee.organizationId,
        )
        return persistence.listDocuments(
            employeeId,
        )
    }

    fun document(
        actorUserId: UUID,
        documentId: UUID,
    ): EmployeeDocumentSnapshot {
        val value =
            requireDocument(documentId)
        requireManage(
            actorUserId,
            value.organizationId,
        )
        return value
    }

    fun certifications(
        actorUserId: UUID,
        employeeId: UUID,
    ): List<CertificationSnapshot> {
        val employee =
            requireEmployee(employeeId)
        requireManage(
            actorUserId,
            employee.organizationId,
        )
        return persistence.listCertifications(
            employeeId,
        )
    }

    fun certification(
        actorUserId: UUID,
        certificationId: UUID,
    ): CertificationSnapshot {
        val value =
            requireCertification(
                certificationId,
            )
        requireManage(
            actorUserId,
            value.organizationId,
        )
        return value
    }

    fun eligibility(
        actorUserId: UUID,
        organizationId: UUID,
        limit: Int,
    ): List<CertificationEligibilitySnapshot> {
        if (
            !peopleAuthorization
                .canViewDirectory(
                    actorUserId =
                        actorUserId,
                    organizationId =
                        organizationId,
                )
        ) {
            throw forbidden()
        }

        return persistence.eligibility(
            organizationId =
                organizationId,
            at = clock.instant(),
            limit = limit,
        )
    }

    private fun requireEmployee(
        employeeId: UUID,
    ): HrEmployeeContext =
        persistence.employee(employeeId)
            ?: throw hrError(
                code = "EMPLOYEE_NOT_FOUND",
                message =
                    "The employee does not exist.",
                status =
                    HttpStatus.NOT_FOUND,
            )

    private fun requireDocument(
        documentId: UUID,
    ): EmployeeDocumentSnapshot =
        persistence.loadDocument(
            documentId,
        ) ?: throw hrError(
            code =
                "EMPLOYEE_DOCUMENT_NOT_FOUND",
            message =
                "The employee document does not exist.",
            status =
                HttpStatus.NOT_FOUND,
        )

    private fun requireCertification(
        certificationId: UUID,
    ): CertificationSnapshot =
        persistence.loadCertification(
            certificationId,
        ) ?: throw hrError(
            code =
                "CERTIFICATION_NOT_FOUND",
            message =
                "The certification does not exist.",
            status =
                HttpStatus.NOT_FOUND,
        )

    private fun requireManage(
        actorUserId: UUID,
        organizationId: UUID,
    ) {
        if (
            !peopleAuthorization
                .canManagePeople(
                    actorUserId =
                        actorUserId,
                    organizationId =
                        organizationId,
                )
        ) {
            throw forbidden()
        }
    }

    private fun forbidden():
        ProductApiException =
        hrError(
            code = "PEOPLE_ACCESS_DENIED",
            message =
                "You are not authorized for this People action.",
            status =
                HttpStatus.FORBIDDEN,
        )

    private fun requirePositiveVersion(
        value: Long,
    ) {
        if (value < 1) {
            throw hrError(
                code =
                    "INVALID_BASE_VERSION",
                message =
                    "baseVersion must be positive.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
    }

    private fun requireVersion(
        expected: Long,
        current: Long,
        code: String,
    ) {
        if (expected != current) {
            throw versionConflict(
                code,
                current,
            )
        }
    }

    private fun requireVerificationResult(
        value: HrVerificationState,
    ) {
        if (
            value !in setOf(
                HrVerificationState.VERIFIED,
                HrVerificationState.REJECTED,
            )
        ) {
            throw hrError(
                code =
                    "INVALID_VERIFICATION_RESULT",
                message =
                    "Verification result must be VERIFIED or REJECTED.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
    }

    private fun normalizeCode(
        value: String,
        code: String,
    ): String {
        val normalized =
            value.trim()
                .uppercase(Locale.ROOT)
        if (
            !Regex(
                "^[A-Z][A-Z0-9_-]{0,79}$",
            ).matches(normalized)
        ) {
            throw hrError(
                code = code,
                message =
                    "A People code is invalid.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
        return normalized
    }

    private fun optionalText(
        value: String?,
        max: Int,
        code: String,
    ): String? {
        val normalized =
            value?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
        if (normalized.length > max) {
            throw hrError(
                code = code,
                message =
                    "A People field exceeds its allowed length.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
        return normalized
    }

    private fun deterministicId(
        prefix: String,
        operationId: UUID,
    ): UUID =
        UUID.nameUUIDFromBytes(
            "$prefix:$operationId"
                .toByteArray(
                    StandardCharsets.UTF_8,
                ),
        )

    private fun versionConflict(
        code: String,
        current: Long,
    ): ProductApiException =
        hrError(
            code = code,
            message =
                "The record changed. Refresh before retrying.",
            status =
                HttpStatus.CONFLICT,
            currentVersion =
                current,
        )

    private fun hrError(
        code: String,
        message: String,
        status: HttpStatus,
        currentVersion: Long? = null,
    ): ProductApiException =
        ProductApiException(
            code = code,
            message = message,
            status = status,
            currentVersion =
                currentVersion,
        )
}
