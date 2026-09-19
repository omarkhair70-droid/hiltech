package com.hiltech.server.documents

import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.documents.persistence.JdbcEvidencePersistence
import com.hiltech.server.documents.storage.EvidenceStorageProperties
import com.hiltech.server.documents.storage.S3CompatibleEvidenceObjectStorage
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.AuthorizationProjectionIntent
import com.hiltech.server.security.AuthorizationProjectionProcessResult
import com.hiltech.server.security.AuthorizationProjectionProcessor
import com.hiltech.server.security.AuthorizationProjectionRetryPolicy
import com.hiltech.server.security.FailClosedAuthorizationAdapter
import com.hiltech.server.security.HiltechOpenFgaProperties
import com.hiltech.server.security.JdbcAuthorizationProjectionGuard
import com.hiltech.server.security.JdbcAuthorizationProjectionIntentWriter
import com.hiltech.server.security.JdbcAuthorizationProjectionStore
import com.hiltech.server.security.JdbcRoleTeamSourceAuthority
import com.hiltech.server.security.JdkOpenFgaHttpTransport
import com.hiltech.server.security.OpenFgaGateway
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class EvidenceLifecyclePostgresOpenFgaS3ContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_EVIDENCE_LIFECYCLE_CONTRACT_TEST",
        ) == "1"

    private val dbUrl =
        System.getenv("HILTECH_DB_URL")
            ?: "jdbc:postgresql://localhost:5432/hiltech"
    private val dbUser =
        System.getenv("HILTECH_DB_USER") ?: "hiltech"
    private val dbPassword =
        System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
    private val migrationPath =
        System.getenv("HILTECH_MIGRATIONS_PATH")
            ?: "database/migrations"

    private val fgaApiUrl =
        System.getenv("HILTECH_FGA_API_URL") ?: ""
    private val fgaStoreId =
        System.getenv("HILTECH_FGA_STORE_ID") ?: ""
    private val fgaModelId =
        System.getenv("HILTECH_FGA_MODEL_ID") ?: ""

    private val s3Endpoint =
        System.getenv("S3_ENDPOINT")
            ?: "http://127.0.0.1:5000"
    private val s3Region =
        System.getenv("AWS_REGION") ?: "us-east-1"
    private val s3AccessKey =
        System.getenv("AWS_ACCESS_KEY_ID") ?: "test"
    private val s3SecretKey =
        System.getenv("AWS_SECRET_ACCESS_KEY") ?: "test"

    @Test
    fun workOrderEvidenceLifecycleIsAuthorizedIdempotentAndIntegritySafe() {
        assumeTrue(enabled)

        val now =
            Instant.parse("2026-09-19T09:30:00Z")
        val fixture =
            fixture(now)

        fixture.use {
            assertFalse(
                fixture.targetAuthorization
                    .canReserveForWorkOrder(
                        identityId =
                            fixture.actorId,
                        workOrderId =
                            fixture.workOrderId,
                    ),
                "Pending assignment projection must deny before OpenFGA catches up.",
            )

            drain(
                fixture.processor,
                now.plusSeconds(1),
            )

            assertTrue(
                fixture.targetAuthorization
                    .canReserveForWorkOrder(
                        identityId =
                            fixture.actorId,
                        workOrderId =
                            fixture.workOrderId,
                    ),
            )

            proveUnsupportedAndUnauthorizedDeny(
                fixture,
                now,
            )

            val jpeg =
                jpegBytes(4_096)
            val jpegReserve =
                reserve(
                    fixture = fixture,
                    operationId =
                        UUID.randomUUID(),
                    requirementKey =
                        "after-photo",
                    evidenceType =
                        "PHOTO",
                    contentType =
                        "image/jpeg",
                    bytes = jpeg,
                    capturedAt = now,
                )

            assertEquals(
                "RESERVED",
                jpegReserve.response.evidence
                    .storageState,
            )
            assertEquals(
                "INTERNAL",
                jpegReserve.response.evidence
                    .classificationCode,
            )
            assertEquals(
                "INTERNAL_ONLY",
                jpegReserve.response.evidence
                    .clientVisibilityMode,
            )

            val duplicate =
                fixture.service.reserve(
                    actorIdentityId =
                        fixture.actorId,
                    idempotencyHeader =
                        jpegReserve.operationId,
                    correlationId =
                        "corr-reserve-duplicate",
                    request =
                        jpegReserve.request,
                )
            assertTrue(duplicate.replayed)
            assertEquals(
                jpegReserve.response.evidence
                    .evidenceId,
                duplicate.evidence
                    .evidenceId,
            )
            assertEquals(
                jpegReserve.response.evidence
                    .uploadSessionId,
                duplicate.evidence
                    .uploadSessionId,
            )

            val changedRequest =
                jpegReserve.request.copy(
                    originalFileName =
                        "different.jpg",
                )
            val changed =
                assertThrows<
                    ProductApiException
                > {
                    fixture.service.reserve(
                        actorIdentityId =
                            fixture.actorId,
                        idempotencyHeader =
                            jpegReserve.operationId,
                        correlationId =
                            "corr-reserve-changed",
                        request =
                            changedRequest,
                    )
                }
            assertEquals(
                "IDEMPOTENCY_KEY_REUSED",
                changed.code,
            )

            put(
                jpegReserve.response.upload,
                "image/jpeg",
                jpeg,
            )
            val jpegFinal =
                finalize(
                    fixture = fixture,
                    reserve =
                        jpegReserve,
                    operationId =
                        UUID.randomUUID(),
                )
            assertEquals(
                "READY",
                jpegFinal.evidence
                    .storageState,
            )

            drain(
                fixture.processor,
                now.plusSeconds(3),
            )

            proveArbitraryFileQuarantines(
                fixture,
                now.plusSeconds(10),
            )
            proveNativeSignatureMismatchRejects(
                fixture,
                now.plusSeconds(20),
            )
            proveMissingObjectRetryable(
                fixture,
                now.plusSeconds(30),
            )
            proveExpiredSessionDenies(
                fixture,
                now.plusSeconds(40),
            )
            proveReassignmentReevaluatesSourceTruth(
                fixture,
                now.plusSeconds(50),
            )
            proveTerminalWorkOrderDeniesFinalize(
                fixture,
                now.plusSeconds(60),
            )

            val auditCount =
                fixture.jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM audit_event
                    WHERE action IN (
                        'EVIDENCE_UPLOAD_RESERVED',
                        'EVIDENCE_UPLOAD_FINALIZED'
                    )
                    """.trimIndent(),
                    Int::class.java,
                ) ?: 0
            assertTrue(auditCount >= 4)

            val leaked =
                fixture.jdbc.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM audit_event
                        WHERE action LIKE 'EVIDENCE_%'
                          AND (
                              safe_diff::text ILIKE '%http%'
                              OR safe_diff::text ILIKE '%token%'
                              OR safe_diff::text ILIKE '%secret%'
                          )
                    )
                    """.trimIndent(),
                    Boolean::class.java,
                ) ?: false
            assertFalse(leaked)
        }
    }

    private fun proveUnsupportedAndUnauthorizedDeny(
        fixture: Fixture,
        now: Instant,
    ) {
        val unsupportedOp =
            UUID.randomUUID()
        val unsupported =
            assertThrows<
                ProductApiException
            > {
                fixture.service.reserve(
                    actorIdentityId =
                        fixture.actorId,
                    idempotencyHeader =
                        unsupportedOp.toString(),
                    correlationId =
                        "corr-unsupported",
                    request =
                        reserveRequest(
                            operationId =
                                unsupportedOp,
                            targetType =
                                "PROJECT",
                            targetId =
                                fixture.projectId,
                            workOrderId = null,
                            requirementKey =
                                "after-photo",
                            evidenceType =
                                "PHOTO",
                            contentType =
                                "image/jpeg",
                            bytes =
                                jpegBytes(128),
                            capturedAt = now,
                        ),
                )
            }
        assertEquals(
            "EVIDENCE_TARGET_UNSUPPORTED",
            unsupported.code,
        )

        val unauthorizedOp =
            UUID.randomUUID()
        val unauthorized =
            assertThrows<
                ProductApiException
            > {
                fixture.service.reserve(
                    actorIdentityId =
                        fixture.unassignedActorId,
                    idempotencyHeader =
                        unauthorizedOp.toString(),
                    correlationId =
                        "corr-unauthorized",
                    request =
                        reserveRequest(
                            operationId =
                                unauthorizedOp,
                            targetType =
                                "WORK_ORDER",
                            targetId =
                                fixture.workOrderId,
                            workOrderId =
                                fixture.workOrderId,
                            requirementKey =
                                "after-photo",
                            evidenceType =
                                "PHOTO",
                            contentType =
                                "image/jpeg",
                            bytes =
                                jpegBytes(128),
                            capturedAt = now,
                        ),
                )
            }
        assertEquals(
            "OBJECT_NOT_VISIBLE",
            unauthorized.code,
        )
    }

    private fun proveArbitraryFileQuarantines(
        fixture: Fixture,
        at: Instant,
    ) {
        val pdf =
            "%PDF-1.7\nHILTECH\n"
                .encodeToByteArray()
        val reserve =
            reserve(
                fixture = fixture,
                operationId =
                    UUID.randomUUID(),
                requirementKey =
                    "site-attachment",
                evidenceType =
                    "DOCUMENT",
                contentType =
                    "application/pdf",
                bytes = pdf,
                capturedAt = at,
            )
        put(
            reserve.response.upload,
            "application/pdf",
            pdf,
        )
        val finalized =
            finalize(
                fixture = fixture,
                reserve = reserve,
                operationId =
                    UUID.randomUUID(),
            )
        assertEquals(
            "QUARANTINED",
            finalized.evidence.storageState,
        )
    }

    private fun proveNativeSignatureMismatchRejects(
        fixture: Fixture,
        at: Instant,
    ) {
        val png =
            pngBytes(512)
        val reserve =
            reserve(
                fixture = fixture,
                operationId =
                    UUID.randomUUID(),
                requirementKey =
                    "after-photo",
                evidenceType =
                    "PHOTO",
                contentType =
                    "image/jpeg",
                bytes = png,
                capturedAt = at,
            )
        put(
            reserve.response.upload,
            "image/jpeg",
            png,
        )
        val finalized =
            finalize(
                fixture = fixture,
                reserve = reserve,
                operationId =
                    UUID.randomUUID(),
            )
        assertEquals(
            "REJECTED",
            finalized.evidence.storageState,
        )
    }

    private fun proveMissingObjectRetryable(
        fixture: Fixture,
        at: Instant,
    ) {
        val bytes =
            jpegBytes(256)
        val reserve =
            reserve(
                fixture = fixture,
                operationId =
                    UUID.randomUUID(),
                requirementKey =
                    "after-photo",
                evidenceType =
                    "PHOTO",
                contentType =
                    "image/jpeg",
                bytes = bytes,
                capturedAt = at,
            )

        val operationId =
            UUID.randomUUID()
        val failure =
            assertThrows<
                ProductApiException
            > {
                fixture.service.finalize(
                    actorIdentityId =
                        fixture.actorId,
                    evidenceId =
                        UUID.fromString(
                            reserve.response
                                .evidence
                                .evidenceId,
                        ),
                    idempotencyHeader =
                        operationId.toString(),
                    correlationId =
                        "corr-missing-object",
                    request =
                        FinalizeEvidenceRequest(
                            operationId =
                                operationId.toString(),
                            uploadSessionId =
                                reserve.response
                                    .evidence
                                    .uploadSessionId,
                            expectedSha256 =
                                reserve.response
                                    .evidence.sha256,
                            expectedSizeBytes =
                                reserve.response
                                    .evidence.sizeBytes,
                        ),
                )
            }
        assertTrue(failure.retryable)
        assertEquals(
            "EVIDENCE_OBJECT_NOT_FOUND",
            failure.code,
        )
    }

    private fun proveExpiredSessionDenies(
        fixture: Fixture,
        at: Instant,
    ) {
        val bytes =
            jpegBytes(300)
        val reserve =
            reserve(
                fixture = fixture,
                operationId =
                    UUID.randomUUID(),
                requirementKey =
                    "after-photo",
                evidenceType =
                    "PHOTO",
                contentType =
                    "image/jpeg",
                bytes = bytes,
                capturedAt = at,
            )
        put(
            reserve.response.upload,
            "image/jpeg",
            bytes,
        )

        val originalNow =
            fixture.clock.instant()
        fixture.clock.set(
            Instant.parse(
                reserve.response
                    .upload
                    .expiresAt,
            ).plusSeconds(1),
        )

        try {
            val operationId =
                UUID.randomUUID()
            val failure =
                assertThrows<
                    ProductApiException
                > {
                    fixture.service.finalize(
                        actorIdentityId =
                            fixture.actorId,
                        evidenceId =
                            UUID.fromString(
                                reserve.response
                                    .evidence
                                    .evidenceId,
                            ),
                        idempotencyHeader =
                            operationId.toString(),
                        correlationId =
                            "corr-expired",
                        request =
                            FinalizeEvidenceRequest(
                                operationId =
                                    operationId.toString(),
                                uploadSessionId =
                                    reserve.response
                                        .evidence
                                        .uploadSessionId,
                                expectedSha256 =
                                    reserve.response
                                        .evidence.sha256,
                                expectedSizeBytes =
                                    reserve.response
                                        .evidence.sizeBytes,
                            ),
                    )
                }
            assertEquals(
                "EVIDENCE_UPLOAD_SESSION_EXPIRED",
                failure.code,
            )
        } finally {
            fixture.clock.set(
                originalNow,
            )
        }
    }

    private fun proveReassignmentReevaluatesSourceTruth(
        fixture: Fixture,
        at: Instant,
    ) {
        val bytes =
            jpegBytes(700)
        val pending =
            reserve(
                fixture = fixture,
                operationId =
                    UUID.randomUUID(),
                requirementKey =
                    "after-photo",
                evidenceType =
                    "PHOTO",
                contentType =
                    "image/jpeg",
                bytes = bytes,
                capturedAt = at,
            )
        put(
            pending.response.upload,
            "image/jpeg",
            bytes,
        )

        drain(
            fixture.processor,
            at.plusSeconds(1),
        )

        fixture.jdbc.update(
            """
            UPDATE work_assignment
            SET state = 'ENDED',
                valid_until = ?,
                version = version + 1
            WHERE id = ?
            """.trimIndent(),
            at.minusSeconds(1)
                .atOffset(
                    ZoneOffset.UTC,
                ),
            fixture.assignmentId,
        )

        val newOp =
            UUID.randomUUID()
        val denied =
            assertThrows<
                ProductApiException
            > {
                fixture.service.reserve(
                    actorIdentityId =
                        fixture.actorId,
                    idempotencyHeader =
                        newOp.toString(),
                    correlationId =
                        "corr-after-reassignment",
                    request =
                        reserveRequest(
                            operationId =
                                newOp,
                            targetType =
                                "WORK_ORDER",
                            targetId =
                                fixture.workOrderId,
                            workOrderId =
                                fixture.workOrderId,
                            requirementKey =
                                "after-photo",
                            evidenceType =
                                "PHOTO",
                            contentType =
                                "image/jpeg",
                            bytes =
                                jpegBytes(100),
                            capturedAt = at,
                        ),
                )
            }
        assertEquals(
            "OBJECT_NOT_VISIBLE",
            denied.code,
        )

        val finalized =
            finalize(
                fixture = fixture,
                reserve = pending,
                operationId =
                    UUID.randomUUID(),
            )
        assertEquals(
            "READY",
            finalized.evidence.storageState,
            "Existing creator relation may finalize its own reserved Evidence after reassignment.",
        )

        fixture.jdbc.update(
            """
            UPDATE work_assignment
            SET state = 'ACTIVE',
                valid_until = NULL,
                version = version + 1
            WHERE id = ?
            """.trimIndent(),
            fixture.assignmentId,
        )
    }

    private fun proveTerminalWorkOrderDeniesFinalize(
        fixture: Fixture,
        at: Instant,
    ) {
        val bytes =
            jpegBytes(900)
        val reserve =
            reserve(
                fixture = fixture,
                operationId =
                    UUID.randomUUID(),
                requirementKey =
                    "after-photo",
                evidenceType =
                    "PHOTO",
                contentType =
                    "image/jpeg",
                bytes = bytes,
                capturedAt = at,
            )
        put(
            reserve.response.upload,
            "image/jpeg",
            bytes,
        )

        fixture.jdbc.update(
            """
            UPDATE work_order
            SET lifecycle_state = 'CANCELLED',
                version = version + 1,
                updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            at.atOffset(
                ZoneOffset.UTC,
            ),
            fixture.workOrderId,
        )

        val operationId =
            UUID.randomUUID()
        val failure =
            assertThrows<
                ProductApiException
            > {
                fixture.service.finalize(
                    actorIdentityId =
                        fixture.actorId,
                    evidenceId =
                        UUID.fromString(
                            reserve.response
                                .evidence
                                .evidenceId,
                        ),
                    idempotencyHeader =
                        operationId.toString(),
                    correlationId =
                        "corr-cancelled",
                    request =
                        FinalizeEvidenceRequest(
                            operationId =
                                operationId.toString(),
                            uploadSessionId =
                                reserve.response
                                    .evidence
                                    .uploadSessionId,
                            expectedSha256 =
                                reserve.response
                                    .evidence.sha256,
                            expectedSizeBytes =
                                reserve.response
                                    .evidence.sizeBytes,
                        ),
                )
            }
        assertEquals(
            "WORK_ORDER_TERMINAL",
            failure.code,
        )
    }

    private fun reserve(
        fixture: Fixture,
        operationId: UUID,
        requirementKey: String,
        evidenceType: String,
        contentType: String,
        bytes: ByteArray,
        capturedAt: Instant,
    ): Reserved {
        val request =
            reserveRequest(
                operationId = operationId,
                targetType = "WORK_ORDER",
                targetId = fixture.workOrderId,
                workOrderId = fixture.workOrderId,
                requirementKey = requirementKey,
                evidenceType = evidenceType,
                contentType = contentType,
                bytes = bytes,
                capturedAt = capturedAt,
            )
        val response =
            try {
                fixture.service.reserve(
                    actorIdentityId =
                        fixture.actorId,
                    idempotencyHeader =
                        operationId.toString(),
                    correlationId =
                        "corr-reserve-" +
                            operationId,
                    request = request,
                )
            } catch (
                failure: DataIntegrityViolationException,
            ) {
                throw AssertionError(
                    "Evidence reserve DB contract failed: " +
                        failure.mostSpecificCause.message,
                    failure,
                )
            }
        return Reserved(
            operationId =
                operationId.toString(),
            request = request,
            response = response,
        )
    }

    private fun finalize(
        fixture: Fixture,
        reserve: Reserved,
        operationId: UUID,
    ): FinalizeEvidenceResponse =
        fixture.service.finalize(
            actorIdentityId =
                fixture.actorId,
            evidenceId =
                UUID.fromString(
                    reserve.response.evidence
                        .evidenceId,
                ),
            idempotencyHeader =
                operationId.toString(),
            correlationId =
                "corr-finalize-" +
                    operationId,
            request =
                FinalizeEvidenceRequest(
                    operationId =
                        operationId.toString(),
                    uploadSessionId =
                        reserve.response
                            .evidence
                            .uploadSessionId,
                    expectedSha256 =
                        reserve.response
                            .evidence.sha256,
                    expectedSizeBytes =
                        reserve.response
                            .evidence.sizeBytes,
                ),
        )

    private fun reserveRequest(
        operationId: UUID,
        targetType: String,
        targetId: UUID,
        workOrderId: UUID?,
        requirementKey: String,
        evidenceType: String,
        contentType: String,
        bytes: ByteArray,
        capturedAt: Instant,
    ): ReserveEvidenceUploadRequest =
        ReserveEvidenceUploadRequest(
            operationId =
                operationId.toString(),
            targetType = targetType,
            targetId =
                targetId.toString(),
            workOrderId =
                workOrderId?.toString(),
            evidenceRequirementKey =
                requirementKey,
            evidenceTypeCode =
                evidenceType,
            contentType =
                contentType,
            originalFileName =
                "field-evidence.bin",
            sizeBytes =
                bytes.size.toLong(),
            expectedSha256 =
                sha256Hex(bytes),
            capturedAt =
                capturedAt.toString(),
            clientOccurredAt =
                capturedAt.toString(),
        )

    private fun fixture(
        now: Instant,
    ): Fixture {
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
        val transaction =
            TransactionTemplate(
                transactionManager,
            )
        val clock =
            MutableTestClock(
                current = now,
            )

        val fgaProperties =
            HiltechOpenFgaProperties(
                enabled = true,
                apiUrl = fgaApiUrl,
                storeId = fgaStoreId,
                authorizationModelId =
                    fgaModelId,
            )
        fgaProperties
            .validateEnabledConfiguration()

        val gateway =
            OpenFgaGateway(
                properties =
                    fgaProperties,
                transport =
                    JdkOpenFgaHttpTransport(
                        fgaProperties,
                    ),
            )
        val guard =
            JdbcAuthorizationProjectionGuard(
                jdbc,
            )
        val authorization =
            FailClosedAuthorizationAdapter(
                guard = guard,
                openFgaGateway = gateway,
            )
        val projectionWriter =
            JdbcAuthorizationProjectionIntentWriter(
                jdbc = jdbc,
                properties =
                    fgaProperties,
            )
        val projectionStore =
            JdbcAuthorizationProjectionStore(
                jdbc = jdbc,
                transactionManager =
                    transactionManager,
                properties =
                    fgaProperties,
                retryPolicy =
                    AuthorizationProjectionRetryPolicy(),
            )
        val processor =
            AuthorizationProjectionProcessor(
                store =
                    projectionStore,
                openFga = gateway,
                properties =
                    fgaProperties,
            )

        val ids =
            seedBusinessContext(
                jdbc = jdbc,
                at = now,
            )

        transaction.executeWithoutResult {
            projectionWriter.write(
                AuthorizationProjectionIntent(
                    eventId =
                        UUID.randomUUID(),
                    tuple =
                        EvidenceAuthorizationRelations
                            .workOrderAssignedUser(
                                identityId =
                                    ids.actorId,
                                workOrderId =
                                    ids.workOrderId,
                            ),
                    desiredState =
                        AuthorizationDesiredState
                            .PRESENT,
                    sourceType =
                        "WorkAssignment",
                    sourceId =
                        ids.assignmentId
                            .toString(),
                    sourceVersion = 1,
                    eventType =
                        "WORK_ASSIGNMENT_AUTHORITY_CHANGED",
                    occurredAt = now,
                ),
            )
        }

        val bucket =
            "hiltech-evidence-lifecycle-" +
                UUID.randomUUID()
                    .toString()
                    .take(8)
        val bucketClient =
            S3Client.builder()
                .endpointOverride(
                    URI.create(s3Endpoint),
                )
                .region(
                    Region.of(s3Region),
                )
                .credentialsProvider(
                    StaticCredentialsProvider
                        .create(
                            AwsBasicCredentials
                                .create(
                                    s3AccessKey,
                                    s3SecretKey,
                                ),
                        ),
                )
                .forcePathStyle(true)
                .httpClientBuilder(
                    UrlConnectionHttpClient
                        .builder(),
                )
                .build()
        bucketClient.createBucket(
            CreateBucketRequest.builder()
                .bucket(bucket)
                .build(),
        )

        val storageProperties =
            EvidenceStorageProperties(
                enabled = true,
                endpoint = s3Endpoint,
                region = s3Region,
                accessKey =
                    s3AccessKey,
                secretKey =
                    s3SecretKey,
                bucket = bucket,
                uploadExpirySeconds = 600,
                downloadExpirySeconds = 300,
            )
        val storage =
            S3CompatibleEvidenceObjectStorage(
                properties =
                    storageProperties,
                clock = clock,
            )

        val targetAuthorization =
            JdbcEvidenceTargetAuthorization(
                jdbc = jdbc,
                authorization =
                    authorization,
                teamAuthority =
                    JdbcRoleTeamSourceAuthority(
                        jdbc,
                    ),
                clock = clock,
            )
        val persistence =
            JdbcEvidencePersistence(jdbc)
        val idempotency =
            JdbcIdempotentCommandExecutor(
                jdbc = jdbc,
                transactionManager =
                    transactionManager,
                clock = clock,
                telemetry =
                    HiltechTelemetryRuntime(
                        openTelemetry =
                            OpenTelemetry.noop(),
                        closeAction = {},
                    ),
            )
        val service =
            EvidenceLifecycleService(
                persistence =
                    persistence,
                targetAuthorization =
                    targetAuthorization,
                idempotency =
                    idempotency,
                projectionWriter =
                    projectionWriter,
                audit =
                    JdbcAuditEventWriter(jdbc),
                storageAccess =
                    EvidenceStorageAccessPort {
                        storage
                    },
                storageProperties =
                    storageProperties,
                clock = clock,
            )

        return Fixture(
            jdbc = jdbc,
            processor = processor,
            targetAuthorization =
                targetAuthorization,
            service = service,
            storage = storage,
            bucketClient =
                bucketClient,
            clock = clock,
            actorId = ids.actorId,
            unassignedActorId =
                ids.unassignedActorId,
            assignmentId =
                ids.assignmentId,
            projectId = ids.projectId,
            workOrderId =
                ids.workOrderId,
            now = now,
        )
    }

    private fun seedBusinessContext(
        jdbc: JdbcTemplate,
        at: Instant,
    ): SeedIds {
        val organizationId =
            UUID.randomUUID()
        val actorId =
            UUID.randomUUID()
        val unassignedActorId =
            UUID.randomUUID()
        val projectId =
            UUID.randomUUID()
        val siteId =
            UUID.randomUUID()
        val workOrderId =
            UUID.randomUUID()
        val assignmentId =
            UUID.randomUUID()

        val workTypeId =
            UUID.randomUUID()
        val assignmentPolicyId =
            UUID.randomUUID()
        val readinessPolicyId =
            UUID.randomUUID()
        val evidencePolicyId =
            UUID.randomUUID()
        val reviewPolicyId =
            UUID.randomUUID()
        val bindingId =
            UUID.randomUUID()
        val instructionId =
            UUID.randomUUID()

        jdbc.update(
            """
            INSERT INTO organization (
                id, organization_code,
                legal_name, display_name,
                organization_type, status,
                created_at, version
            )
            VALUES (
                ?, ?, 'Evidence Contract',
                'Evidence Contract',
                'HILTECH', 'ACTIVE', ?, 1
            )
            """.trimIndent(),
            organizationId,
            "EVID-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            at.atOffset(
                ZoneOffset.UTC,
            ),
        )

        listOf(
            actorId,
            unassignedActorId,
        ).forEach { identityId ->
            jdbc.update(
                """
                INSERT INTO user_identity (
                    id, auth_provider,
                    auth_subject, status,
                    primary_organization_id,
                    created_at, version
                )
                VALUES (
                    ?, 'contract',
                    ?, 'ACTIVE',
                    ?, ?, 1
                )
                """.trimIndent(),
                identityId,
                "evidence-" +
                    identityId,
                organizationId,
                at.atOffset(
                    ZoneOffset.UTC,
                ),
            )

            jdbc.update(
                """
                INSERT INTO organization_membership (
                    id, organization_id,
                    user_identity_id,
                    membership_type,
                    role_label, state,
                    valid_from, valid_until,
                    invited_by, version
                )
                VALUES (
                    ?, ?, ?,
                    'EMPLOYEE',
                    'descriptive-only',
                    'ACTIVE',
                    ?, NULL,
                    NULL, 1
                )
                """.trimIndent(),
                UUID.randomUUID(),
                organizationId,
                identityId,
                at.minusSeconds(60)
                    .atOffset(
                        ZoneOffset.UTC,
                    ),
            )
        }

        jdbc.update(
            """
            INSERT INTO project (
                id, organization_id,
                project_code, name,
                client_organization_id,
                lifecycle_state,
                project_manager_id,
                created_at, created_by,
                updated_at, version
            )
            VALUES (
                ?, ?, ?, 'Evidence Project',
                ?, 'ACTIVE',
                ?, ?, ?, ?, 1
            )
            """.trimIndent(),
            projectId,
            organizationId,
            "PRJ-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            organizationId,
            actorId,
            at.atOffset(
                ZoneOffset.UTC,
            ),
            actorId,
            at.atOffset(
                ZoneOffset.UTC,
            ),
        )

        jdbc.update(
            """
            INSERT INTO site (
                id, client_organization_id,
                site_code, name,
                status,
                created_at, created_by,
                updated_at, version
            )
            VALUES (
                ?, ?, ?,
                'Evidence Site',
                'ACTIVE',
                ?, ?, ?, 1
            )
            """.trimIndent(),
            siteId,
            organizationId,
            "SITE-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            at.atOffset(
                ZoneOffset.UTC,
            ),
            actorId,
            at.atOffset(
                ZoneOffset.UTC,
            ),
        )

        fun config(
            id: UUID,
            family: String,
            code: String,
        ) {
            jdbc.update(
                """
                INSERT INTO config_revision (
                    id, scope_type,
                    scope_organization_id,
                    family, code, name,
                    lifecycle_state,
                    revision_number,
                    created_at, created_by,
                    activated_at, activated_by,
                    version
                )
                VALUES (
                    ?, 'ORGANIZATION',
                    ?, ?, ?, ?,
                    'ACTIVE', 1,
                    ?, ?, ?, ?, 1
                )
                """.trimIndent(),
                id,
                organizationId,
                family,
                code,
                code,
                at.atOffset(
                    ZoneOffset.UTC,
                ),
                actorId,
                at.atOffset(
                    ZoneOffset.UTC,
                ),
                actorId,
            )
        }

        config(
            workTypeId,
            "work-types",
            "work-type",
        )
        config(
            assignmentPolicyId,
            "assignment-policies",
            "assignment",
        )
        config(
            readinessPolicyId,
            "readiness-policies",
            "readiness",
        )
        config(
            evidencePolicyId,
            "evidence-policies",
            "evidence",
        )
        config(
            reviewPolicyId,
            "review-policies",
            "review",
        )

        jdbc.update(
            """
            INSERT INTO evidence_policy (
                config_revision_id,
                system_max_size_bytes
            )
            VALUES (?, 16777216)
            """.trimIndent(),
            evidencePolicyId,
        )

        jdbc.update(
            """
            INSERT INTO evidence_policy_requirement (
                id,
                config_revision_id,
                requirement_key,
                evidence_type_code,
                stage,
                min_count,
                max_count,
                offline_capture_allowed,
                allowed_content_types,
                classification_code,
                retention_policy_code,
                client_visibility_mode,
                reviewer_relationship_code,
                security_scan_class
            )
            VALUES (
                ?, ?,
                'after-photo',
                'PHOTO',
                'BEFORE_SUBMIT',
                1, NULL, true,
                ARRAY['image/jpeg']::varchar(160)[],
                'INTERNAL',
                NULL,
                'INTERNAL_ONLY',
                NULL,
                'NATIVE_MEDIA'
            ),
            (
                ?, ?,
                'site-attachment',
                'DOCUMENT',
                'BEFORE_SUBMIT',
                0, NULL, true,
                ARRAY['application/pdf']::varchar(160)[],
                'RESTRICTED',
                NULL,
                'INTERNAL_ONLY',
                NULL,
                'ARBITRARY_FILE'
            )
            """.trimIndent(),
            UUID.randomUUID(),
            evidencePolicyId,
            UUID.randomUUID(),
            evidencePolicyId,
        )

        jdbc.update(
            """
            INSERT INTO work_order (
                id, work_order_code,
                project_id, site_id,
                title, lifecycle_state,
                readiness_state,
                priority_code,
                created_at, created_by,
                updated_at, version
            )
            VALUES (
                ?, ?, ?, ?,
                'Evidence WorkOrder',
                'DRAFT',
                'READY',
                'NORMAL',
                ?, ?, ?, 1
            )
            """.trimIndent(),
            workOrderId,
            "WO-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            projectId,
            siteId,
            at.atOffset(
                ZoneOffset.UTC,
            ),
            actorId,
            at.atOffset(
                ZoneOffset.UTC,
            ),
        )

        jdbc.update(
            """
            INSERT INTO work_policy_binding (
                id, work_order_id,
                binding_revision,
                work_type_definition_id,
                work_type_revision,
                assignment_policy_id,
                assignment_policy_revision,
                readiness_policy_id,
                readiness_policy_revision,
                evidence_policy_id,
                evidence_policy_revision,
                review_policy_id,
                review_policy_revision,
                binding_created_at,
                binding_created_by
            )
            VALUES (
                ?, ?, 1,
                ?, 1,
                ?, 1,
                ?, 1,
                ?, 1,
                ?, 1,
                ?, ?
            )
            """.trimIndent(),
            bindingId,
            workOrderId,
            workTypeId,
            assignmentPolicyId,
            readinessPolicyId,
            evidencePolicyId,
            reviewPolicyId,
            at.atOffset(
                ZoneOffset.UTC,
            ),
            actorId,
        )

        jdbc.update(
            """
            INSERT INTO work_instruction_revision (
                id, work_order_id,
                instruction_revision,
                payload_schema_version,
                structured_payload_json,
                summary_text,
                created_at, created_by,
                correlation_id
            )
            VALUES (
                ?, ?, 1, 1,
                '{}'::jsonb,
                'Evidence contract',
                ?, ?,
                'corr-instruction'
            )
            """.trimIndent(),
            instructionId,
            workOrderId,
            at.atOffset(
                ZoneOffset.UTC,
            ),
            actorId,
        )

        jdbc.update(
            """
            UPDATE work_order
            SET lifecycle_state =
                    'IN_PROGRESS',
                current_policy_binding_id = ?,
                current_instruction_revision_id = ?,
                updated_at = ?,
                version = 2
            WHERE id = ?
            """.trimIndent(),
            bindingId,
            instructionId,
            at.atOffset(
                ZoneOffset.UTC,
            ),
            workOrderId,
        )

        jdbc.update(
            """
            INSERT INTO work_assignment (
                id, work_order_id,
                target_type, target_id,
                lead, assigned_at,
                assigned_by,
                valid_from, valid_until,
                state,
                source_operation_id,
                version
            )
            VALUES (
                ?, ?,
                'USER', ?,
                true, ?,
                ?,
                ?, NULL,
                'ACTIVE',
                ?, 1
            )
            """.trimIndent(),
            assignmentId,
            workOrderId,
            actorId,
            at.atOffset(
                ZoneOffset.UTC,
            ),
            actorId,
            at.minusSeconds(60)
                .atOffset(
                    ZoneOffset.UTC,
                ),
            UUID.randomUUID(),
        )

        return SeedIds(
            actorId = actorId,
            unassignedActorId =
                unassignedActorId,
            projectId = projectId,
            workOrderId =
                workOrderId,
            assignmentId =
                assignmentId,
        )
    }

    private fun put(
        upload: EvidenceUploadTargetResponse,
        contentType: String,
        bytes: ByteArray,
    ) {
        val connection =
            URI(upload.uploadUrl)
                .toURL()
                .openConnection()
                as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        upload.requiredHeaders
            .forEach { (name, values) ->
                if (
                    !name.equals(
                        "host",
                        ignoreCase = true,
                    ) &&
                    !name.equals(
                        "content-length",
                        ignoreCase = true,
                    )
                ) {
                    values.forEach { value ->
                        connection.addRequestProperty(
                            name,
                            value,
                        )
                    }
                }
            }

        connection.setRequestProperty(
            "Content-Type",
            contentType,
        )
        connection.setFixedLengthStreamingMode(
            bytes.size,
        )
        connection.outputStream.use {
            it.write(bytes)
        }

        val status =
            connection.responseCode
        if (status >= 400) {
            val body =
                connection.errorStream
                    ?.use {
                        it.readBytes()
                    }
            connection.disconnect()
            error(
                "Signed Evidence PUT failed: HTTP " +
                    status +
                    " body=" +
                    (body?.decodeToString()
                        ?: ""),
            )
        }

        connection.inputStream
            ?.use { it.readBytes() }
        connection.disconnect()
    }

    private fun drain(
        processor:
            AuthorizationProjectionProcessor,
        at: Instant,
    ) {
        repeat(100) { index ->
            when (
                processor.processOne(
                    at.plusMillis(
                        index.toLong(),
                    ),
                )
            ) {
                AuthorizationProjectionProcessResult
                    .NO_WORK ->
                    return

                AuthorizationProjectionProcessResult
                    .RETRY_SCHEDULED,
                AuthorizationProjectionProcessResult
                    .FAILED_PERMANENT ->
                    error(
                        "Authorization projection did not converge.",
                    )

                else ->
                    Unit
            }
        }
        error(
            "Authorization projection queue did not drain.",
        )
    }

    private fun sha256Hex(
        bytes: ByteArray,
    ): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it)
            }

    private fun jpegBytes(
        size: Int,
    ): ByteArray {
        require(size >= 4)
        return ByteArray(size) {
            ((it * 29 + 11) and 0xff)
                .toByte()
        }.also {
            it[0] = 0xff.toByte()
            it[1] = 0xd8.toByte()
            it[2] = 0xff.toByte()
            it[it.lastIndex] =
                0xd9.toByte()
        }
    }

    private fun pngBytes(
        size: Int,
    ): ByteArray {
        require(size >= 16)
        val value =
            ByteArray(size) {
                ((it * 17 + 3) and 0xff)
                    .toByte()
            }
        val signature =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4e,
                0x47,
                0x0d,
                0x0a,
                0x1a,
                0x0a,
            )
        signature.copyInto(value)
        return value
    }

    private data class Reserved(
        val operationId: String,
        val request:
            ReserveEvidenceUploadRequest,
        val response:
            ReserveEvidenceUploadResponse,
    )

    private data class SeedIds(
        val actorId: UUID,
        val unassignedActorId: UUID,
        val projectId: UUID,
        val workOrderId: UUID,
        val assignmentId: UUID,
    )

    private class MutableTestClock(
        private var current: Instant,
        private val zone: java.time.ZoneId =
            ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone():
            java.time.ZoneId =
            zone

        override fun withZone(
            zone: java.time.ZoneId,
        ): Clock =
            MutableTestClock(
                current = current,
                zone = zone,
            )

        override fun instant():
            Instant =
            current

        fun set(
            value: Instant,
        ) {
            current = value
        }
    }

    private data class Fixture(
        val jdbc: JdbcTemplate,
        val processor:
            AuthorizationProjectionProcessor,
        val targetAuthorization:
            EvidenceTargetAuthorizationPort,
        val service:
            EvidenceLifecycleService,
        val storage:
            S3CompatibleEvidenceObjectStorage,
        val bucketClient: S3Client,
        val clock: MutableTestClock,
        val actorId: UUID,
        val unassignedActorId: UUID,
        val assignmentId: UUID,
        val projectId: UUID,
        val workOrderId: UUID,
        val now: Instant,
    ) : AutoCloseable {
        override fun close() {
            storage.close()
            bucketClient.close()
        }
    }
}
