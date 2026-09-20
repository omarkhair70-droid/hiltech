package com.hiltech.server.people

import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.documents.EmployeeDocumentEvidenceTargetPort
import com.hiltech.server.documents.EvidenceLifecycleService
import com.hiltech.server.documents.EvidenceStorageAccessPort
import com.hiltech.server.documents.EvidenceTargetAuthorizationPort
import com.hiltech.server.documents.FinalizeEvidenceRequest
import com.hiltech.server.documents.ReserveEvidenceUploadRequest
import com.hiltech.server.documents.persistence.JdbcEvidencePersistence
import com.hiltech.server.documents.storage.EvidenceFinalizeVerification
import com.hiltech.server.documents.storage.EvidenceObjectStoragePort
import com.hiltech.server.documents.storage.EvidenceStorageProperties
import com.hiltech.server.documents.storage.EvidenceUploadSpec
import com.hiltech.server.documents.storage.SignedEvidenceDownloadTarget
import com.hiltech.server.documents.storage.SignedEvidenceUploadTarget
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class HrDocumentsPostgresEvidenceContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_HR_DOCUMENTS_CONTRACT_TEST",
        ) == "1"

    private val dbUrl =
        System.getenv("HILTECH_DB_URL")
            ?: "jdbc:postgresql://localhost:5432/hiltech"
    private val dbUser =
        System.getenv("HILTECH_DB_USER")
            ?: "hiltech"
    private val dbPassword =
        System.getenv("HILTECH_DB_PASSWORD")
            ?: "hiltech"
    private val migrationPath =
        System.getenv("HILTECH_MIGRATIONS_PATH")
            ?: "database/migrations"

    @Test
    fun hrDocumentsCertificationsAndEmployeeEvidenceStayPrivateVersionSafeAndIdempotent() {
        assumeTrue(enabled)

        Flyway.configure()
            .dataSource(
                dbUrl,
                dbUser,
                dbPassword,
            )
            .locations(
                "filesystem:$migrationPath",
            )
            .load()
            .migrate()

        val dataSource =
            DriverManagerDataSource(
                dbUrl,
                dbUser,
                dbPassword,
            )
        val jdbc =
            JdbcTemplate(dataSource)
        val transactionManager =
            DataSourceTransactionManager(
                dataSource,
            )
        val now =
            Instant.parse(
                "2026-09-20T02:00:00Z",
            )
        val clock =
            Clock.fixed(
                now,
                ZoneOffset.UTC,
            )
        val ids =
            seed(
                jdbc = jdbc,
                at =
                    now.minusSeconds(600),
            )
        val authorization =
            fixtureAuthorization(
                adminIdentityId =
                    ids.adminIdentity,
                memberIdentityId =
                    ids.memberIdentity,
                expectedOrganizationId =
                    ids.organizationId,
            )
        val persistence =
            JdbcHrDocumentsPersistence(
                jdbc,
            )
        val telemetry =
            HiltechTelemetryRuntime(
                openTelemetry =
                    OpenTelemetry.noop(),
                closeAction = {},
            )
        val published =
            mutableListOf<Any>()
        val publisher =
            ApplicationEventPublisher {
                event ->
                published += event
            }

        try {
            val idempotency =
                JdbcIdempotentCommandExecutor(
                    jdbc = jdbc,
                    transactionManager =
                        transactionManager,
                    clock = clock,
                    telemetry = telemetry,
                )
            val hrService =
                HrDocumentsService(
                    persistence =
                        persistence,
                    peopleAuthorization =
                        authorization,
                    selfServicePolicy =
                        denySelfServicePolicy(),
                    idempotency =
                        idempotency,
                    audit =
                        JdbcAuditEventWriter(
                            jdbc,
                        ),
                    events =
                        publisher,
                    clock = clock,
                )

            val createDocumentOperation =
                UUID.randomUUID()
            val created =
                hrService.createDocument(
                    CreateEmployeeDocumentCommand(
                        operationId =
                            createDocumentOperation,
                        employeeId =
                            ids.employeeOne,
                        baseEmployeeVersion = 1,
                        documentTypeCode =
                            "id_copy",
                        documentLabel =
                            "Private identity copy",
                        issueDate =
                            LocalDate.parse(
                                "2026-01-01",
                            ),
                        expiryDate = null,
                        retentionPolicyCode =
                            null,
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-doc-create",
                    ),
                )

            assertFalse(created.replayed)
            assertEquals(
                "ID_COPY",
                created.document
                    .documentTypeCode,
            )
            assertEquals(
                HrVerificationState.UNVERIFIED,
                created.document
                    .verificationState,
            )
            assertNull(
                created.document.evidenceId,
            )

            val replay =
                hrService.createDocument(
                    CreateEmployeeDocumentCommand(
                        operationId =
                            createDocumentOperation,
                        employeeId =
                            ids.employeeOne,
                        baseEmployeeVersion = 1,
                        documentTypeCode =
                            "id_copy",
                        documentLabel =
                            "Private identity copy",
                        issueDate =
                            LocalDate.parse(
                                "2026-01-01",
                            ),
                        expiryDate = null,
                        retentionPolicyCode =
                            null,
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-doc-create",
                    ),
                )
            assertTrue(replay.replayed)
            assertEquals(
                created.document.documentId,
                replay.document.documentId,
            )

            val privateDenied =
                assertThrows<
                    ProductApiException
                > {
                    hrService.documents(
                        actorUserId =
                            ids.memberIdentity,
                        employeeId =
                            ids.employeeOne,
                    )
                }
            assertEquals(
                "PEOPLE_ACCESS_DENIED",
                privateDenied.code,
            )

            val targetAdapter:
                EmployeeDocumentEvidenceTargetPort =
                PeopleEmployeeDocumentEvidenceTargetAdapter(
                    persistence =
                        persistence,
                    authorization =
                        authorization,
                    selfServicePolicy =
                        denySelfServicePolicy(),
                    clock = clock,
                )
            val fakeStorage =
                FixtureEvidenceStorage(
                    clock,
                )
            val evidenceService =
                EvidenceLifecycleService(
                    persistence =
                        JdbcEvidencePersistence(
                            jdbc,
                        ),
                    targetAuthorization =
                        denyWorkOrderAuthorization(),
                    employeeDocumentTarget =
                        targetAdapter,
                    idempotency =
                        idempotency,
                    projectionWriter =
                        AuthorizationProjectionIntentWriter {
                            error(
                                "EmployeeDocument Evidence must not project WorkOrder authorization tuples.",
                            )
                        },
                    audit =
                        JdbcAuditEventWriter(
                            jdbc,
                        ),
                    storageAccess =
                        EvidenceStorageAccessPort {
                            fakeStorage
                        },
                    storageProperties =
                        EvidenceStorageProperties(
                            enabled = true,
                            endpoint =
                                "https://unused.test",
                            region = "test-1",
                            accessKey = "test",
                            secretKey = "test",
                            bucket = "test",
                            uploadExpirySeconds =
                                600,
                            downloadExpirySeconds =
                                300,
                        ),
                    events =
                        publisher,
                    clock = clock,
                )

            val pdfBytes =
                (
                    "%PDF-1.7\n" +
                        "HILTECH private HR fixture\n" +
                        "%%EOF\n"
                ).toByteArray()
            val pdfSha =
                sha256Hex(pdfBytes)
            val reserveOperation =
                UUID.randomUUID()
            val reserveRequest =
                ReserveEvidenceUploadRequest(
                    operationId =
                        reserveOperation
                            .toString(),
                    targetType =
                        "EMPLOYEE_DOCUMENT",
                    targetId =
                        created.document
                            .documentId
                            .toString(),
                    workOrderId = null,
                    evidenceRequirementKey =
                        "EMPLOYEE_DOCUMENT_BINARY",
                    evidenceTypeCode =
                        "HR_DOCUMENT",
                    contentType =
                        "application/pdf",
                    originalFileName =
                        "identity-private.pdf",
                    sizeBytes =
                        pdfBytes.size.toLong(),
                    expectedSha256 =
                        pdfSha,
                    capturedAt =
                        now.toString(),
                )

            val unauthorizedReserve =
                assertThrows<
                    ProductApiException
                > {
                    evidenceService.reserve(
                        actorIdentityId =
                            ids.memberIdentity,
                        idempotencyHeader =
                            reserveOperation
                                .toString(),
                        correlationId =
                            "corr-unauthorized-reserve",
                        request =
                            reserveRequest,
                    )
                }
            assertEquals(
                "OBJECT_NOT_VISIBLE",
                unauthorizedReserve.code,
            )

            val reserved =
                evidenceService.reserve(
                    actorIdentityId =
                        ids.adminIdentity,
                    idempotencyHeader =
                        reserveOperation
                            .toString(),
                    correlationId =
                        "corr-reserve",
                    request =
                        reserveRequest,
                )

            assertFalse(
                reserved.replayed,
            )
            assertEquals(
                "EMPLOYEE_DOCUMENT",
                reserved.evidence
                    .targetType,
            )
            assertEquals(
                created.document
                    .documentId
                    .toString(),
                reserved.evidence
                    .targetId,
            )
            assertNull(
                reserved.evidence
                    .workOrderId,
            )
            assertEquals(
                "HIGHLY_RESTRICTED",
                reserved.evidence
                    .classificationCode,
            )
            assertEquals(
                "INTERNAL_ONLY",
                reserved.evidence
                    .clientVisibilityMode,
            )

            val reservedReplay =
                evidenceService.reserve(
                    actorIdentityId =
                        ids.adminIdentity,
                    idempotencyHeader =
                        reserveOperation
                            .toString(),
                    correlationId =
                        "corr-reserve",
                    request =
                        reserveRequest,
                )
            assertTrue(
                reservedReplay.replayed,
            )
            assertEquals(
                reserved.evidence
                    .evidenceId,
                reservedReplay.evidence
                    .evidenceId,
            )

            val attachedBeforeFinalize =
                requireNotNull(
                    persistence.loadDocument(
                        created.document
                            .documentId,
                    ),
                )
            assertEquals(
                reserved.evidence.evidenceId,
                attachedBeforeFinalize
                    .evidenceId
                    ?.toString(),
            )
            assertEquals(
                "RESERVED",
                attachedBeforeFinalize
                    .evidenceStorageState,
            )
            assertEquals(
                2,
                attachedBeforeFinalize.version,
            )

            val verifyTooSoon =
                assertThrows<
                    ProductApiException
                > {
                    hrService.verifyDocument(
                        VerifyEmployeeDocumentCommand(
                            operationId =
                                UUID.randomUUID(),
                            documentId =
                                created.document
                                    .documentId,
                            baseVersion = 2,
                            result =
                                HrVerificationState
                                    .VERIFIED,
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-doc-too-soon",
                        ),
                    )
                }
            assertEquals(
                "DOCUMENT_EVIDENCE_NOT_READY",
                verifyTooSoon.code,
            )

            val finalizeOperation =
                UUID.randomUUID()
            val finalized =
                evidenceService.finalize(
                    actorIdentityId =
                        ids.adminIdentity,
                    evidenceId =
                        UUID.fromString(
                            reserved.evidence
                                .evidenceId,
                        ),
                    idempotencyHeader =
                        finalizeOperation
                            .toString(),
                    correlationId =
                        "corr-finalize",
                    request =
                        FinalizeEvidenceRequest(
                            operationId =
                                finalizeOperation
                                    .toString(),
                            uploadSessionId =
                                reserved.evidence
                                    .uploadSessionId,
                            expectedSha256 =
                                pdfSha,
                            expectedSizeBytes =
                                pdfBytes.size
                                    .toLong(),
                        ),
                )
            assertEquals(
                "READY",
                finalized.evidence
                    .storageState,
            )
            assertEquals(
                "EMPLOYEE_DOCUMENT",
                finalized.evidence
                    .targetType,
            )

            val ordinaryMetadata =
                assertThrows<
                    ProductApiException
                > {
                    evidenceService.metadata(
                        actorIdentityId =
                            ids.memberIdentity,
                        evidenceId =
                            UUID.fromString(
                                reserved.evidence
                                    .evidenceId,
                            ),
                    )
                }
            assertEquals(
                "OBJECT_NOT_VISIBLE",
                ordinaryMetadata.code,
            )

            val adminMetadata =
                evidenceService.metadata(
                    actorIdentityId =
                        ids.adminIdentity,
                    evidenceId =
                        UUID.fromString(
                            reserved.evidence
                                .evidenceId,
                        ),
                )
            assertEquals(
                "READY",
                adminMetadata.storageState,
            )

            val directDownload =
                assertThrows<
                    ProductApiException
                > {
                    evidenceService.downloadTarget(
                        actorIdentityId =
                            ids.adminIdentity,
                        evidenceId =
                            UUID.fromString(
                                reserved.evidence
                                    .evidenceId,
                            ),
                        correlationId =
                            "corr-download",
                    )
                }
            assertEquals(
                "EVIDENCE_DIRECT_DOWNLOAD_FORBIDDEN",
                directDownload.code,
            )
            assertEquals(
                0,
                fakeStorage.downloadTargetCalls,
            )

            val verifiedDocument =
                hrService.verifyDocument(
                    VerifyEmployeeDocumentCommand(
                        operationId =
                            UUID.randomUUID(),
                        documentId =
                            created.document
                                .documentId,
                        baseVersion = 2,
                        result =
                            HrVerificationState
                                .VERIFIED,
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-doc-verify",
                    ),
                )
            assertEquals(
                HrVerificationState.VERIFIED,
                verifiedDocument.document
                    .verificationState,
            )
            assertEquals(
                3,
                verifiedDocument.document
                    .version,
            )

            val staleDocumentVerify =
                assertThrows<
                    ProductApiException
                > {
                    hrService.verifyDocument(
                        VerifyEmployeeDocumentCommand(
                            operationId =
                                UUID.randomUUID(),
                            documentId =
                                created.document
                                    .documentId,
                            baseVersion = 2,
                            result =
                                HrVerificationState
                                    .REJECTED,
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-doc-stale",
                        ),
                    )
                }
            assertEquals(
                "EMPLOYEE_DOCUMENT_VERSION_CONFLICT",
                staleDocumentVerify.code,
            )
            assertEquals(
                3,
                staleDocumentVerify
                    .currentVersion,
            )

            val crossEmployeeDocument =
                assertThrows<
                    ProductApiException
                > {
                    hrService.createCertification(
                        CreateCertificationCommand(
                            operationId =
                                UUID.randomUUID(),
                            employeeId =
                                ids.employeeTwo,
                            baseEmployeeVersion = 1,
                            certificationTypeCode =
                                "SAFETY",
                            certificationLabel =
                                "Safety",
                            issuer =
                                "Fixture",
                            issuedAt =
                                now.minusSeconds(
                                    86_400,
                                ),
                            validUntil =
                                now.plusSeconds(
                                    86_400,
                                ),
                            employeeDocumentId =
                                created.document
                                    .documentId,
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-cert-cross-employee",
                        ),
                    )
                }
            assertEquals(
                "CERTIFICATION_DOCUMENT_MISMATCH",
                crossEmployeeDocument.code,
            )

            val certification =
                hrService.createCertification(
                    CreateCertificationCommand(
                        operationId =
                            UUID.randomUUID(),
                        employeeId =
                            ids.employeeOne,
                        baseEmployeeVersion = 1,
                        certificationTypeCode =
                            "safety",
                        certificationLabel =
                            "Site Safety",
                        issuer =
                            "Fixture Authority",
                        issuedAt =
                            now.minusSeconds(
                                86_400,
                            ),
                        validUntil =
                            now.plusSeconds(
                                86_400,
                            ),
                        employeeDocumentId =
                            created.document
                                .documentId,
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-cert-create",
                    ),
                )
            assertEquals(
                "SAFETY",
                certification.certification
                    .certificationTypeCode,
            )

            val verifiedCertification =
                hrService.verifyCertification(
                    VerifyCertificationCommand(
                        operationId =
                            UUID.randomUUID(),
                        certificationId =
                            certification
                                .certification
                                .certificationId,
                        baseVersion = 1,
                        result =
                            HrVerificationState
                                .VERIFIED,
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-cert-verify",
                    ),
                )
            assertEquals(
                HrVerificationState.VERIFIED,
                verifiedCertification
                    .certification
                    .verificationState,
            )

            val expiredCertification =
                hrService.createCertification(
                    CreateCertificationCommand(
                        operationId =
                            UUID.randomUUID(),
                        employeeId =
                            ids.employeeOne,
                        baseEmployeeVersion = 1,
                        certificationTypeCode =
                            "EXPIRED_TEST",
                        certificationLabel =
                            "Expired fixture",
                        issuer = null,
                        issuedAt =
                            now.minusSeconds(
                                172_800,
                            ),
                        validUntil =
                            now.minusSeconds(
                                86_400,
                            ),
                        employeeDocumentId =
                            null,
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-expired-create",
                    ),
                )
            hrService.verifyCertification(
                VerifyCertificationCommand(
                    operationId =
                        UUID.randomUUID(),
                    certificationId =
                        expiredCertification
                            .certification
                            .certificationId,
                    baseVersion = 1,
                    result =
                        HrVerificationState
                            .VERIFIED,
                    actorUserId =
                        ids.adminIdentity,
                    correlationId =
                        "corr-expired-verify",
                ),
            )

            val eligibility =
                hrService.eligibility(
                    actorUserId =
                        ids.memberIdentity,
                    organizationId =
                        ids.organizationId,
                    limit = 100,
                )
            val currentEligibility =
                eligibility.single {
                    it.certificationId ==
                        certification
                            .certification
                            .certificationId
                }
            val expiredEligibility =
                eligibility.single {
                    it.certificationId ==
                        expiredCertification
                            .certification
                            .certificationId
                }

            assertTrue(
                currentEligibility.validNow,
            )
            assertFalse(
                expiredEligibility.validNow,
            )
            assertEquals(
                "EMP-001",
                currentEligibility
                    .employeeCode,
            )

            val eventDump =
                published.joinToString("|")
            assertFalse(
                eventDump.contains(
                    "identity-private.pdf",
                ),
            )
            assertTrue(
                published.any {
                    it is
                        EmployeeDocumentCreated
                },
            )
            assertTrue(
                published.any {
                    it is
                        EmployeeDocumentVerified
                },
            )
            assertTrue(
                published.any {
                    it is CertificationCreated
                },
            )
            assertTrue(
                published.any {
                    it is CertificationVerified
                },
            )
        } finally {
            telemetry.close()
        }
    }

    private fun fixtureAuthorization(
        adminIdentityId: UUID,
        memberIdentityId: UUID,
        expectedOrganizationId: UUID,
    ): PeopleAuthorizationPort =
        object : PeopleAuthorizationPort {
            override fun canManagePeople(
                actorUserId: UUID,
                organizationId:
                    UUID,
            ): Boolean =
                actorUserId ==
                    adminIdentityId &&
                    organizationId ==
                    expectedOrganizationId

            override fun canViewDirectory(
                actorUserId: UUID,
                organizationId:
                    UUID,
            ): Boolean =
                organizationId ==
                    expectedOrganizationId &&
                    actorUserId in setOf(
                        adminIdentityId,
                        memberIdentityId,
                    )
        }

    private fun denyWorkOrderAuthorization():
        EvidenceTargetAuthorizationPort =
        object :
            EvidenceTargetAuthorizationPort {
            override fun canReserveForWorkOrder(
                identityId: UUID,
                workOrderId: UUID,
            ): Boolean = false

            override fun canFinalizeEvidence(
                identityId: UUID,
                evidenceId: UUID,
                workOrderId: UUID,
                creatorIdentityId: UUID,
            ): Boolean = false

            override fun canViewEvidence(
                identityId: UUID,
                evidenceId: UUID,
                workOrderId: UUID,
                creatorIdentityId: UUID,
            ): Boolean = false

            override fun canDownloadEvidence(
                identityId: UUID,
                evidenceId: UUID,
                workOrderId: UUID,
                creatorIdentityId: UUID,
            ): Boolean = false
        }

    private fun seed(
        jdbc: JdbcTemplate,
        at: Instant,
    ): FixtureIds {
        val organizationId =
            UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO organization (
                id,
                organization_code,
                legal_name,
                display_name,
                organization_type,
                status,
                created_at,
                version
            )
            VALUES (
                ?, ?,
                'HR Contract',
                'HR Contract',
                'HILTECH',
                'ACTIVE',
                ?, 1
            )
            """.trimIndent(),
            organizationId,
            "HR-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            at.atOffset(
                ZoneOffset.UTC,
            ),
        )

        val personOne =
            insertPerson(
                jdbc,
                "Worker One",
                at,
            )
        val personTwo =
            insertPerson(
                jdbc,
                "Worker Two",
                at,
            )
        val adminPerson =
            insertPerson(
                jdbc,
                "HR Admin",
                at,
            )
        val memberPerson =
            insertPerson(
                jdbc,
                "Ordinary Member",
                at,
            )

        val employeeOne =
            insertEmployee(
                jdbc,
                organizationId,
                personOne,
                "EMP-001",
                at,
            )
        val employeeTwo =
            insertEmployee(
                jdbc,
                organizationId,
                personTwo,
                "EMP-002",
                at,
            )

        val adminIdentity =
            insertIdentity(
                jdbc,
                organizationId,
                adminPerson,
                at,
            )
        val memberIdentity =
            insertIdentity(
                jdbc,
                organizationId,
                memberPerson,
                at,
            )
        insertMembership(
            jdbc,
            organizationId,
            adminIdentity,
            at,
        )
        insertMembership(
            jdbc,
            organizationId,
            memberIdentity,
            at,
        )

        return FixtureIds(
            organizationId =
                organizationId,
            adminIdentity =
                adminIdentity,
            memberIdentity =
                memberIdentity,
            employeeOne =
                employeeOne,
            employeeTwo =
                employeeTwo,
        )
    }

    private fun insertPerson(
        jdbc: JdbcTemplate,
        displayName: String,
        at: Instant,
    ): UUID {
        val id =
            UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO person (
                id,
                display_name,
                legal_name,
                mobile,
                email,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, NULL,
                NULL, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            id,
            displayName,
            at.atOffset(
                ZoneOffset.UTC,
            ),
            at.atOffset(
                ZoneOffset.UTC,
            ),
        )
        return id
    }

    private fun insertEmployee(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        personId: UUID,
        employeeCode: String,
        at: Instant,
    ): UUID {
        val id =
            UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO employee (
                id,
                organization_id,
                person_id,
                employee_code,
                state,
                hire_date,
                end_date,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, ?,
                'ACTIVE',
                NULL, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            id,
            organizationId,
            personId,
            employeeCode,
            at.atOffset(
                ZoneOffset.UTC,
            ),
            at.atOffset(
                ZoneOffset.UTC,
            ),
        )
        return id
    }

    private fun insertIdentity(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        personId: UUID,
        at: Instant,
    ): UUID {
        val id =
            UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO user_identity (
                id,
                auth_provider,
                auth_subject,
                person_id,
                status,
                primary_organization_id,
                created_at,
                version
            )
            VALUES (
                ?, 'contract',
                ?, ?, 'ACTIVE',
                ?, ?, 1
            )
            """.trimIndent(),
            id,
            "hr-" + id,
            personId,
            organizationId,
            at.atOffset(
                ZoneOffset.UTC,
            ),
        )
        return id
    }

    private fun insertMembership(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        identityId: UUID,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO organization_membership (
                id,
                organization_id,
                user_identity_id,
                membership_type,
                role_label,
                state,
                valid_from,
                valid_until,
                invited_by,
                version
            )
            VALUES (
                ?, ?, ?,
                'EMPLOYEE',
                NULL,
                'ACTIVE',
                ?, NULL,
                NULL, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            organizationId,
            identityId,
            at.atOffset(
                ZoneOffset.UTC,
            ),
        )
    }

    private fun sha256Hex(
        bytes: ByteArray,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it)
            }

    private data class FixtureIds(
        val organizationId: UUID,
        val adminIdentity: UUID,
        val memberIdentity: UUID,
        val employeeOne: UUID,
        val employeeTwo: UUID,
    )

    private class FixtureEvidenceStorage(
        private val clock: Clock,
    ) : EvidenceObjectStoragePort {
        var downloadTargetCalls: Int = 0
            private set

        override fun createUploadTarget(
            spec: EvidenceUploadSpec,
        ): SignedEvidenceUploadTarget =
            SignedEvidenceUploadTarget(
                objectKey =
                    spec.objectKey,
                uploadUrl =
                    "https://upload.test/" +
                        spec.objectKey,
                requiredHeaders =
                    emptyMap(),
                expiresAt =
                    clock.instant()
                        .plusSeconds(300),
            )

        override fun verifyUploadedObject(
            spec: EvidenceUploadSpec,
        ): EvidenceFinalizeVerification =
            EvidenceFinalizeVerification.Verified(
                objectKey =
                    spec.objectKey,
                sizeBytes =
                    spec.expectedSizeBytes,
                sha256Hex =
                    spec.expectedSha256Hex,
                providerChecksumSha256 =
                    null,
                detectedContentType =
                    spec.contentType,
            )

        override fun createDownloadTarget(
            objectKey: String,
        ): SignedEvidenceDownloadTarget {
            downloadTargetCalls += 1
            return SignedEvidenceDownloadTarget(
                downloadUrl =
                    "https://download.test/" +
                        objectKey,
                expiresAt =
                    clock.instant()
                        .plusSeconds(300),
            )
        }
    }
    private fun denySelfServicePolicy():
        OnboardingSelfServicePolicyPort =
        object :
            OnboardingSelfServicePolicyPort {
            override fun ownEmployee(
                identityId: UUID,
                organizationId: UUID,
                at: Instant,
            ): OnboardingEmployeeContext? =
                null

            override fun canViewEmployeeDocument(
                identityId: UUID,
                organizationId: UUID,
                employeeId: UUID,
                documentTypeCode: String,
                at: Instant,
            ): Boolean =
                false

            override fun canSubmitEmployeeDocument(
                identityId: UUID,
                organizationId: UUID,
                employeeId: UUID,
                documentTypeCode: String,
                at: Instant,
            ): Boolean =
                false
        }

}
