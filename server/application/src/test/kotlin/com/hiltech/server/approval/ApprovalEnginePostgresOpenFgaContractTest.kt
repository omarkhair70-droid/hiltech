package com.hiltech.server.approval

import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.AuthorizationProjectionProcessResult
import com.hiltech.server.security.AuthorizationProjectionProcessor
import com.hiltech.server.security.AuthorizationProjectionRetryPolicy
import com.hiltech.server.security.FailClosedAuthorizationAdapter
import com.hiltech.server.security.HiltechOpenFgaProperties
import com.hiltech.server.security.JdbcAuthorizationProjectionGuard
import com.hiltech.server.security.JdbcAuthorizationProjectionIntentWriter
import com.hiltech.server.security.JdbcAuthorizationProjectionStore
import com.hiltech.server.security.JdkOpenFgaHttpTransport
import com.hiltech.server.security.OpenFgaGateway
import com.hiltech.server.security.OpenFgaTuple
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ApprovalEnginePostgresOpenFgaContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_APPROVAL_CONTRACT_TEST",
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
    private val fgaApiUrl =
        System.getenv("HILTECH_FGA_API_URL")
            ?: ""
    private val fgaStoreId =
        System.getenv("HILTECH_FGA_STORE_ID")
            ?: ""
    private val fgaModelId =
        System.getenv("HILTECH_FGA_MODEL_ID")
            ?: ""

    @Test
    fun minimalApprovalIsExceptionDrivenVersionSafeAndCurrentAuthorityBound() {
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
        val txManager =
            DataSourceTransactionManager(
                dataSource,
            )
        val clock =
            Clock.fixed(
                Instant.parse(
                    "2026-09-19T18:30:00Z",
                ),
                ZoneOffset.UTC,
            )
        val ids =
            seed(
                jdbc = jdbc,
                now =
                    clock.instant()
                        .minusSeconds(300),
            )

        val fgaProperties =
            HiltechOpenFgaProperties(
                enabled = true,
                apiUrl = fgaApiUrl,
                storeId = fgaStoreId,
                authorizationModelId =
                    fgaModelId,
            )
        val gateway =
            OpenFgaGateway(
                properties =
                    fgaProperties,
                transport =
                    JdkOpenFgaHttpTransport(
                        fgaProperties,
                    ),
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
                    txManager,
                properties =
                    fgaProperties,
                retryPolicy =
                    AuthorizationProjectionRetryPolicy(),
            )
        val projector =
            AuthorizationProjectionProcessor(
                store = projectionStore,
                openFga = gateway,
                properties =
                    fgaProperties,
            )
        val check =
            FailClosedAuthorizationAdapter(
                guard =
                    JdbcAuthorizationProjectionGuard(
                        jdbc,
                    ),
                openFgaGateway = gateway,
            )
        val approvalAuthorization =
            object : ApprovalAuthorizationPort {
                override fun canDecide(
                    actorUserId: UUID,
                    approvalRequestId: UUID,
                    assignmentPrincipalType:
                        ApprovalPrincipalType,
                    assignmentPrincipalId: UUID,
                ): Boolean =
                    check.isAllowed(
                        AuthorizationCheckRequest(
                            checkTuple =
                                OpenFgaTuple(
                                    subjectType =
                                        "user",
                                    subjectId =
                                        actorUserId
                                            .toString(),
                                    relation =
                                        "can_decide",
                                    objectType =
                                        "approval_request",
                                    objectId =
                                        approvalRequestId
                                            .toString(),
                                ),
                            failClosedGuardTuples =
                                listOf(
                                    ApprovalAuthorizationRelations
                                        .approver(
                                            principalType =
                                                assignmentPrincipalType,
                                            principalId =
                                                assignmentPrincipalId,
                                            approvalRequestId =
                                                approvalRequestId,
                                        ),
                                ),
                        ),
                    )
            }

        val staleSubjects =
            mutableSetOf<UUID>()
        val subjectGuard =
            ApprovalSubjectVersionGuard {
                subject ->
                subject.subjectId !in
                    staleSubjects
            }
        val published =
            mutableListOf<Any>()
        val publisher =
            ApplicationEventPublisher {
                event ->
                published += event
            }
        val telemetry =
            HiltechTelemetryRuntime(
                openTelemetry =
                    OpenTelemetry.noop(),
                closeAction = {},
            )

        try {
            val service =
                ApprovalEngineService(
                    persistence =
                        JdbcApprovalPersistence(
                            jdbc,
                        ),
                    idempotency =
                        JdbcIdempotentCommandExecutor(
                            jdbc = jdbc,
                            transactionManager =
                                txManager,
                            clock = clock,
                            telemetry =
                                telemetry,
                        ),
                    projectionWriter =
                        projectionWriter,
                    authorization =
                        approvalAuthorization,
                    subjectVersionGuard =
                        subjectGuard,
                    audit =
                        JdbcAuditEventWriter(
                            jdbc,
                        ),
                    events = publisher,
                    clock = clock,
                )
            val readService =
                ApprovalReadService(
                    persistence =
                        JdbcApprovalPersistence(
                            jdbc,
                        ),
                    authorization =
                        approvalAuthorization,
                    cursorCodec =
                        ApprovalCursorCodec(
                            "approval-contract-signing-key-20260919-strong",
                        ),
                    clock = clock,
                )
            val preciseCursor =
                ApprovalCursor(
                    actorUserId =
                        ids.ownerTwoUserId,
                    asOf =
                        Instant.parse(
                            "2026-09-19T18:30:00.123456789Z",
                        ),
                    afterCreatedAt =
                        Instant.parse(
                            "2026-09-19T18:29:59.987654321Z",
                        ),
                    afterRequestId =
                        UUID.randomUUID(),
                )
            assertEquals(
                preciseCursor,
                ApprovalCursorCodec(
                    "approval-contract-signing-key-20260919-strong",
                ).decode(
                    ApprovalCursorCodec(
                        "approval-contract-signing-key-20260919-strong",
                    ).encode(
                        preciseCursor,
                    ),
                ),
                "Approval cursor must preserve full Instant precision.",
            )

            val routineEvaluation =
                service.evaluate(
                    actorUserId =
                        ids.financeUserId,
                    organizationId =
                        ids.organizationId,
                    policyKey =
                        "ROUTINE_FINANCE_ADMIN",
                )
            assertTrue(
                routineEvaluation is
                    ApprovalEvaluationResult
                        .NoApprovalRequired,
            )

            val routineRequest =
                service.requestException(
                    actorUserId =
                        ids.financeUserId,
                    operationId =
                        UUID.randomUUID(),
                    subject =
                        subject(
                            ids.organizationId,
                        ),
                    policyKey =
                        "ROUTINE_FINANCE_ADMIN",
                    reasonCode =
                        "ROUTINE_OPERATION",
                    safeReasonSummary =
                        null,
                    correlationId =
                        "corr-routine",
                )
            assertFalse(
                routineRequest.approvalRequired,
            )
            assertNull(
                routineRequest
                    .approvalRequestId,
            )
            assertEquals(
                0,
                approvalCount(
                    jdbc,
                    ids.organizationId,
                ),
            )

            val firstSubject =
                subject(
                    ids.organizationId,
                )
            val createOperationId =
                UUID.randomUUID()
            val first =
                service.requestException(
                    actorUserId =
                        ids.financeUserId,
                    operationId =
                        createOperationId,
                    subject = firstSubject,
                    policyKey =
                        "EXCEPTION_OWNER_FINAL",
                    reasonCode =
                        "NON_ROUTINE_EXCEPTION",
                    safeReasonSummary =
                        "Unusual case requires final authority.",
                    correlationId =
                        "corr-create-first",
                )
            assertTrue(
                first.approvalRequired,
            )
            assertFalse(first.replayed)
            assertNotNull(
                first.approvalRequestId,
            )

            val replay =
                service.requestException(
                    actorUserId =
                        ids.financeUserId,
                    operationId =
                        createOperationId,
                    subject = firstSubject,
                    policyKey =
                        "EXCEPTION_OWNER_FINAL",
                    reasonCode =
                        "NON_ROUTINE_EXCEPTION",
                    safeReasonSummary =
                        "Unusual case requires final authority.",
                    correlationId =
                        "corr-create-first",
                )
            assertTrue(replay.replayed)
            assertEquals(
                first.approvalRequestId,
                replay.approvalRequestId,
            )
            assertEquals(
                1,
                approvalCount(
                    jdbc,
                    ids.organizationId,
                ),
            )

            drainProjector(
                projector = projector,
                now = clock.instant(),
            )

            val outsider =
                assertThrows<
                    ProductApiException
                >(
                    "outsider decision must be hidden",
                ) {
                    service.decide(
                        actorUserId =
                            ids.outsiderUserId,
                        operationId =
                            UUID.randomUUID(),
                        approvalRequestId =
                            requireNotNull(
                                first.approvalRequestId,
                            ),
                        decision =
                            ApprovalDecisionType
                                .APPROVE,
                        comment = null,
                        correlationId =
                            "corr-outsider",
                    )
                }
            assertEquals(
                "OBJECT_NOT_VISIBLE",
                outsider.code,
            )
            val outsiderRead =
                assertThrows<
                    ProductApiException
                >(
                    "outsider read must be hidden",
                ) {
                    readService.one(
                        actorUserId =
                            ids.outsiderUserId,
                        approvalRequestId =
                            requireNotNull(
                                first.approvalRequestId,
                            ),
                    )
                }
            assertEquals(
                "OBJECT_NOT_VISIBLE",
                outsiderRead.code,
                "An active identity from another organization must not read this Approval.",
            )

            replaceOwnerAuthority(
                jdbc = jdbc,
                ids = ids,
                now = clock.instant(),
            )

            val staleAuthority =
                assertThrows<
                    ProductApiException
                >(
                    "stale approval authority must be rejected",
                ) {
                    service.decide(
                        actorUserId =
                            ids.ownerOneUserId,
                        operationId =
                            UUID.randomUUID(),
                        approvalRequestId =
                            requireNotNull(
                                first.approvalRequestId,
                            ),
                        decision =
                            ApprovalDecisionType
                                .APPROVE,
                        comment = null,
                        correlationId =
                            "corr-stale-owner",
                    )
                }
            assertEquals(
                "APPROVAL_AUTHORITY_CHANGED",
                staleAuthority.code,
                "Current PostgreSQL authority must defeat a stale OpenFGA approver tuple.",
            )
            val staleRead =
                assertThrows<
                    ProductApiException
                >(
                    "stale approval authority read must disappear",
                ) {
                    readService.one(
                        actorUserId =
                            ids.ownerOneUserId,
                        approvalRequestId =
                            requireNotNull(
                                first.approvalRequestId,
                            ),
                    )
                }
            assertEquals(
                "OBJECT_NOT_VISIBLE",
                staleRead.code,
                "Changed authority must disappear from assigned Approval reads immediately.",
            )

            val selfDecision =
                service.requestException(
                    actorUserId =
                        ids.ownerTwoUserId,
                    operationId =
                        UUID.randomUUID(),
                    subject =
                        subject(
                            ids.organizationId,
                        ),
                    policyKey =
                        "EXCEPTION_OWNER_FINAL",
                    reasonCode =
                        "OWNER_DIRECT_DECISION",
                    safeReasonSummary =
                        null,
                    correlationId =
                        "corr-owner-direct",
                )
            assertFalse(
                selfDecision.approvalRequired,
                "Current final authority must not create a fake self-approval request.",
            )

            val secondSubject =
                subject(
                    ids.organizationId,
                )
            val second =
                service.requestException(
                    actorUserId =
                        ids.financeUserId,
                    operationId =
                        UUID.randomUUID(),
                    subject =
                        secondSubject,
                    policyKey =
                        "EXCEPTION_OWNER_FINAL",
                    reasonCode =
                        "NON_ROUTINE_EXCEPTION",
                    safeReasonSummary =
                        null,
                    correlationId =
                        "corr-create-second",
                )
            val pageSubject =
                subject(
                    ids.organizationId,
                )
            val pageRequest =
                service.requestException(
                    actorUserId =
                        ids.financeUserId,
                    operationId =
                        UUID.randomUUID(),
                    subject =
                        pageSubject,
                    policyKey =
                        "EXCEPTION_OWNER_FINAL",
                    reasonCode =
                        "NON_ROUTINE_EXCEPTION",
                    safeReasonSummary =
                        "Second pending item for cursor proof.",
                    correlationId =
                        "corr-create-page",
                )
            drainProjector(
                projector = projector,
                now = clock.instant(),
            )

            val visibleOne =
                readService.one(
                    actorUserId =
                        ids.ownerTwoUserId,
                    approvalRequestId =
                        requireNotNull(
                            second.approvalRequestId,
                        ),
                )
            assertEquals(
                "PENDING",
                visibleOne.state,
            )

            val firstPage =
                readService.assigned(
                    actorUserId =
                        ids.ownerTwoUserId,
                    rawCursor = null,
                    requestedLimit = 1,
                    correlationId =
                        "corr-page-1",
                )
            assertEquals(
                1,
                firstPage.items.size,
            )
            assertNotNull(
                firstPage.nextCursor,
            )
            val secondPage =
                readService.assigned(
                    actorUserId =
                        ids.ownerTwoUserId,
                    rawCursor =
                        firstPage.nextCursor,
                    requestedLimit = 1,
                    correlationId =
                        "corr-page-2",
                )
            assertEquals(
                1,
                secondPage.items.size,
            )
            assertEquals(
                firstPage.asOf,
                secondPage.asOf,
            )
            assertEquals(
                setOf(
                    requireNotNull(
                        second.approvalRequestId,
                    ).toString(),
                    requireNotNull(
                        pageRequest.approvalRequestId,
                    ).toString(),
                ),
                (
                    firstPage.items +
                        secondPage.items
                ).map {
                    it.approvalRequestId
                }.toSet(),
                "Assigned pagination must not duplicate, skip or leak the stale old-authority request.",
            )

            val tamperedCursor =
                requireNotNull(firstPage.nextCursor)
                    .split('.', limit = 2)
                    .let { parts ->
                        val signature = parts.getOrNull(1)
                            ?: error("Expected a signed Approval cursor.")
                        val replacement = if (signature.first() == 'A') 'B' else 'A'
                        "${parts[0]}.$replacement${signature.drop(1)}"
                    }
            val invalidCursor =
                assertThrows<
                    ProductApiException
                >(
                    "tampered approval cursor must be rejected",
                ) {
                    readService.assigned(
                        actorUserId =
                            ids.ownerTwoUserId,
                        rawCursor =
                            tamperedCursor,
                        requestedLimit = 1,
                        correlationId =
                            "corr-page-tampered",
                    )
                }
            assertEquals(
                "CURSOR_INVALID",
                invalidCursor.code,
            )

            val decideOperationId =
                UUID.randomUUID()
            val approved =
                service.decide(
                    actorUserId =
                        ids.ownerTwoUserId,
                    operationId =
                        decideOperationId,
                    approvalRequestId =
                        requireNotNull(
                            second.approvalRequestId,
                        ),
                    decision =
                        ApprovalDecisionType
                            .APPROVE,
                    comment = null,
                    correlationId =
                        "corr-approve",
                )
            assertEquals(
                ApprovalRequestState.APPROVED,
                approved.state,
            )
            assertFalse(approved.replayed)

            val approveReplay =
                service.decide(
                    actorUserId =
                        ids.ownerTwoUserId,
                    operationId =
                        decideOperationId,
                    approvalRequestId =
                        requireNotNull(
                            second.approvalRequestId,
                        ),
                    decision =
                        ApprovalDecisionType
                            .APPROVE,
                    comment = null,
                    correlationId =
                        "corr-approve",
                )
            assertTrue(
                approveReplay.replayed,
            )
            assertEquals(
                1,
                decisionCount(
                    jdbc,
                    requireNotNull(
                        second.approvalRequestId,
                    ),
                ),
            )

            val conflicting =
                assertThrows<
                    ProductApiException
                >(
                    "second approval decision must be rejected",
                ) {
                    service.decide(
                        actorUserId =
                            ids.ownerTwoUserId,
                        operationId =
                            UUID.randomUUID(),
                        approvalRequestId =
                            requireNotNull(
                                second
                                    .approvalRequestId,
                            ),
                        decision =
                            ApprovalDecisionType
                                .REJECT,
                        comment =
                            "Different decision.",
                        correlationId =
                            "corr-conflict",
                    )
                }
            assertEquals(
                "APPROVAL_ALREADY_HANDLED",
                conflicting.code,
            )

            val rejectRequest =
                service.requestException(
                    actorUserId =
                        ids.financeUserId,
                    operationId =
                        UUID.randomUUID(),
                    subject =
                        subject(
                            ids.organizationId,
                        ),
                    policyKey =
                        "EXCEPTION_OWNER_FINAL",
                    reasonCode =
                        "NON_ROUTINE_EXCEPTION",
                    safeReasonSummary =
                        null,
                    correlationId =
                        "corr-create-reject",
                )
            drainProjector(
                projector = projector,
                now = clock.instant(),
            )
            val rejected =
                service.decide(
                    actorUserId =
                        ids.ownerTwoUserId,
                    operationId =
                        UUID.randomUUID(),
                    approvalRequestId =
                        requireNotNull(
                            rejectRequest
                                .approvalRequestId,
                        ),
                    decision =
                        ApprovalDecisionType.REJECT,
                    comment =
                        "Rejected with a required reason.",
                    correlationId =
                        "corr-reject",
                )
            assertEquals(
                ApprovalRequestState.REJECTED,
                rejected.state,
            )

            val changeRequest =
                service.requestException(
                    actorUserId =
                        ids.financeUserId,
                    operationId =
                        UUID.randomUUID(),
                    subject =
                        subject(
                            ids.organizationId,
                        ),
                    policyKey =
                        "EXCEPTION_OWNER_FINAL",
                    reasonCode =
                        "NON_ROUTINE_EXCEPTION",
                    safeReasonSummary =
                        null,
                    correlationId =
                        "corr-create-change",
                )
            drainProjector(
                projector = projector,
                now = clock.instant(),
            )
            val changeRequested =
                service.decide(
                    actorUserId =
                        ids.ownerTwoUserId,
                    operationId =
                        UUID.randomUUID(),
                    approvalRequestId =
                        requireNotNull(
                            changeRequest
                                .approvalRequestId,
                        ),
                    decision =
                        ApprovalDecisionType
                            .REQUEST_CHANGE,
                    comment =
                        "Please change the exceptional item.",
                    correlationId =
                        "corr-request-change",
                )
            assertEquals(
                ApprovalRequestState
                    .CHANGE_REQUESTED,
                changeRequested.state,
            )

            val raceRequest =
                service.requestException(
                    actorUserId =
                        ids.financeUserId,
                    operationId =
                        UUID.randomUUID(),
                    subject =
                        subject(
                            ids.organizationId,
                        ),
                    policyKey =
                        "EXCEPTION_OWNER_FINAL",
                    reasonCode =
                        "NON_ROUTINE_EXCEPTION",
                    safeReasonSummary =
                        null,
                    correlationId =
                        "corr-create-race",
                )
            drainProjector(
                projector = projector,
                now = clock.instant(),
            )
            val raceRequestId =
                requireNotNull(
                    raceRequest.approvalRequestId,
                )
            val start =
                CountDownLatch(1)
            val outcomes =
                Collections.synchronizedList(
                    mutableListOf<Any>(),
                )
            val pool =
                Executors.newFixedThreadPool(2)
            try {
                val approveFuture =
                    pool.submit {
                        start.await()
                        try {
                            outcomes +=
                                service.decide(
                                    actorUserId =
                                        ids.ownerTwoUserId,
                                    operationId =
                                        UUID.randomUUID(),
                                    approvalRequestId =
                                        raceRequestId,
                                    decision =
                                        ApprovalDecisionType
                                            .APPROVE,
                                    comment = null,
                                    correlationId =
                                        "corr-race-approve",
                                )
                        } catch (
                            failure:
                                ProductApiException
                        ) {
                            outcomes += failure
                        }
                    }
                val rejectFuture =
                    pool.submit {
                        start.await()
                        try {
                            outcomes +=
                                service.decide(
                                    actorUserId =
                                        ids.ownerTwoUserId,
                                    operationId =
                                        UUID.randomUUID(),
                                    approvalRequestId =
                                        raceRequestId,
                                    decision =
                                        ApprovalDecisionType
                                            .REJECT,
                                    comment =
                                        "Concurrent reject.",
                                    correlationId =
                                        "corr-race-reject",
                                )
                        } catch (
                            failure:
                                ProductApiException
                        ) {
                            outcomes += failure
                        }
                    }

                start.countDown()
                approveFuture.get(
                    15,
                    TimeUnit.SECONDS,
                )
                rejectFuture.get(
                    15,
                    TimeUnit.SECONDS,
                )
            } finally {
                pool.shutdownNow()
            }

            assertEquals(
                1,
                outcomes.count {
                    it is
                        ApprovalDecisionResult
                },
                "Exactly one concurrent Approval decision must become authoritative.",
            )
            val raceFailure =
                outcomes.single {
                    it is ProductApiException
                } as ProductApiException
            assertEquals(
                "APPROVAL_ALREADY_HANDLED",
                raceFailure.code,
            )
            assertEquals(
                1,
                decisionCount(
                    jdbc,
                    raceRequestId,
                ),
            )
            assertTrue(
                requestState(
                    jdbc,
                    raceRequestId,
                ) in
                    setOf(
                        "APPROVED",
                        "REJECTED",
                    ),
            )

            val thirdSubject =
                subject(
                    ids.organizationId,
                )
            val third =
                service.requestException(
                    actorUserId =
                        ids.financeUserId,
                    operationId =
                        UUID.randomUUID(),
                    subject =
                        thirdSubject,
                    policyKey =
                        "EXCEPTION_OWNER_FINAL",
                    reasonCode =
                        "NON_ROUTINE_EXCEPTION",
                    safeReasonSummary =
                        null,
                    correlationId =
                        "corr-create-third",
                )
            drainProjector(
                projector = projector,
                now = clock.instant(),
            )
            staleSubjects +=
                thirdSubject.subjectId

            val superseded =
                assertThrows<
                    ProductApiException
                >(
                    "stale approval subject must be superseded",
                ) {
                    service.decide(
                        actorUserId =
                            ids.ownerTwoUserId,
                        operationId =
                            UUID.randomUUID(),
                        approvalRequestId =
                            requireNotNull(
                                third.approvalRequestId,
                            ),
                        decision =
                            ApprovalDecisionType
                                .APPROVE,
                        comment = null,
                        correlationId =
                            "corr-stale-subject",
                    )
                }
            assertEquals(
                "APPROVAL_SUPERSEDED",
                superseded.code,
            )
            assertEquals(
                "SUPERSEDED",
                requestState(
                    jdbc,
                    requireNotNull(
                        third.approvalRequestId,
                    ),
                ),
            )
            assertEquals(
                0,
                decisionCount(
                    jdbc,
                    requireNotNull(
                        third.approvalRequestId,
                    ),
                ),
            )

            val missingReason =
                assertThrows<
                    ProductApiException
                >(
                    "change request without reason must be rejected",
                ) {
                    service.decide(
                        actorUserId =
                            ids.ownerTwoUserId,
                        operationId =
                            UUID.randomUUID(),
                        approvalRequestId =
                            UUID.randomUUID(),
                        decision =
                            ApprovalDecisionType
                                .REQUEST_CHANGE,
                        comment = null,
                        correlationId =
                            "corr-no-reason",
                    )
                }
            assertEquals(
                "APPROVAL_REASON_REQUIRED",
                missingReason.code,
            )

            assertTrue(
                published.any {
                    it is ApprovalRequested
                },
            )
            assertTrue(
                published.any {
                    it is ApprovalApproved
                },
            )
            assertTrue(
                published.any {
                    it is ApprovalSuperseded
                },
            )

            val approvalAuditCount =
                jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM audit_event
                    WHERE target_type =
                          'ApprovalRequest'
                      AND target_id IS NOT NULL
                    """.trimIndent(),
                    Int::class.java,
                ) ?: 0
            assertTrue(
                approvalAuditCount >= 4,
            )

            val unsafeApprovalColumns =
                jdbc.queryForList(
                    """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name IN (
                          'approval_authority_binding',
                          'approval_policy_version',
                          'approval_request',
                          'approval_step',
                          'approval_assignment',
                          'approval_decision'
                      )
                      AND (
                          column_name ILIKE '%token%'
                          OR column_name ILIKE '%secret%'
                          OR column_name ILIKE '%storage_key%'
                          OR column_name ILIKE '%raw_filename%'
                          OR column_name ILIKE '%sha256%'
                          OR column_name ILIKE '%file_bytes%'
                          OR column_name ILIKE '%subject_payload%'
                          OR column_name ILIKE '%subject_body%'
                      )
                    """.trimIndent(),
                    String::class.java,
                )
            assertTrue(
                unsafeApprovalColumns.isEmpty(),
                "Approval persistence must contain safe references/decision metadata, not sensitive subject payloads or credentials.",
            )
        } finally {
            telemetry.close()
        }
    }

    private fun drainProjector(
        projector:
            AuthorizationProjectionProcessor,
        now: Instant,
    ) {
        repeat(20) { attempt ->
            val result =
                projector.processOne(
                    now.plusSeconds(
                        attempt.toLong(),
                    ),
                )
            if (
                result ==
                AuthorizationProjectionProcessResult
                    .NO_WORK
            ) {
                return
            }
        }
        error(
            "Authorization projector did not drain within the contract bound.",
        )
    }

    private fun subject(
        organizationId: UUID,
    ): ApprovalSubjectRef =
        ApprovalSubjectRef(
            organizationId =
                organizationId,
            subjectType =
                "CONTRACT_EXCEPTION",
            subjectId =
                UUID.randomUUID(),
            subjectVersion = 1,
        )

    private fun approvalCount(
        jdbc: JdbcTemplate,
        organizationId: UUID,
    ): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM approval_request
            WHERE organization_id = ?
            """.trimIndent(),
            Int::class.java,
            organizationId,
        ) ?: 0

    private fun decisionCount(
        jdbc: JdbcTemplate,
        requestId: UUID,
    ): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM approval_decision
            WHERE approval_request_id = ?
            """.trimIndent(),
            Int::class.java,
            requestId,
        ) ?: 0

    private fun requestState(
        jdbc: JdbcTemplate,
        requestId: UUID,
    ): String =
        requireNotNull(
            jdbc.queryForObject(
                """
                SELECT state
                FROM approval_request
                WHERE id = ?
                """.trimIndent(),
                String::class.java,
                requestId,
            ),
        )

    private fun replaceOwnerAuthority(
        jdbc: JdbcTemplate,
        ids: SeedIds,
        now: Instant,
    ) {
        jdbc.update(
            """
            UPDATE approval_authority_binding
            SET active = false,
                effective_to = ?,
                version = version + 1
            WHERE organization_id = ?
              AND authority_key =
                  'OWNER_FINAL'
              AND active = true
            """.trimIndent(),
            now.atOffset(ZoneOffset.UTC),
            ids.organizationId,
        )

        jdbc.update(
            """
            INSERT INTO approval_authority_binding (
                id,
                organization_id,
                authority_key,
                principal_type,
                principal_user_id,
                principal_team_id,
                effective_from,
                effective_to,
                active,
                created_by_user_id,
                created_at,
                version
            )
            VALUES (
                ?, ?,
                'OWNER_FINAL',
                'USER',
                ?, NULL,
                ?, NULL,
                true,
                ?, ?, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            ids.organizationId,
            ids.ownerTwoUserId,
            now.minusSeconds(1)
                .atOffset(ZoneOffset.UTC),
            ids.ownerOneUserId,
            now.atOffset(ZoneOffset.UTC),
        )
    }

    private fun seed(
        jdbc: JdbcTemplate,
        now: Instant,
    ): SeedIds {
        val organizationId =
            UUID.randomUUID()
        val outsiderOrganizationId =
            UUID.randomUUID()
        val financeUserId =
            UUID.randomUUID()
        val ownerOneUserId =
            UUID.randomUUID()
        val ownerTwoUserId =
            UUID.randomUUID()
        val outsiderUserId =
            UUID.randomUUID()
        val at =
            now.atOffset(
                ZoneOffset.UTC,
            )

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
                'Approval Contract',
                'Approval Contract',
                'HILTECH',
                'ACTIVE',
                ?, 1
            )
            """.trimIndent(),
            organizationId,
            "APR-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            at,
        )

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
                'Approval Outsider Contract',
                'Approval Outsider Contract',
                'HILTECH',
                'ACTIVE',
                ?, 1
            )
            """.trimIndent(),
            outsiderOrganizationId,
            "APR-OUT-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            at,
        )

        listOf(
            financeUserId,
            ownerOneUserId,
            ownerTwoUserId,
            outsiderUserId,
        ).forEach { userId ->
            jdbc.update(
                """
                INSERT INTO user_identity (
                    id,
                    auth_provider,
                    auth_subject,
                    status,
                    primary_organization_id,
                    created_at,
                    version
                )
                VALUES (
                    ?, 'contract',
                    ?, 'ACTIVE',
                    ?, ?, 1
                )
                """.trimIndent(),
                userId,
                "approval-$userId",
                if (
                    userId ==
                    outsiderUserId
                ) {
                    outsiderOrganizationId
                } else {
                    organizationId
                },
                at,
            )
        }

        listOf(
            financeUserId,
            ownerOneUserId,
            ownerTwoUserId,
        ).forEach { userId ->
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
                    'descriptive-only',
                    'ACTIVE',
                    ?, NULL,
                    NULL, 1
                )
                """.trimIndent(),
                UUID.randomUUID(),
                organizationId,
                userId,
                at.minusMinutes(1),
            )
        }

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
                'other-organization-only',
                'ACTIVE',
                ?, NULL,
                NULL, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            outsiderOrganizationId,
            outsiderUserId,
            at.minusMinutes(1),
        )

        jdbc.update(
            """
            INSERT INTO approval_authority_binding (
                id,
                organization_id,
                authority_key,
                principal_type,
                principal_user_id,
                principal_team_id,
                effective_from,
                effective_to,
                active,
                created_by_user_id,
                created_at,
                version
            )
            VALUES (
                ?, ?,
                'OWNER_FINAL',
                'USER',
                ?, NULL,
                ?, NULL,
                true,
                ?, ?, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            organizationId,
            ownerOneUserId,
            at.minusMinutes(1),
            ownerOneUserId,
            at,
        )

        jdbc.update(
            """
            INSERT INTO approval_policy_version (
                id,
                organization_id,
                policy_key,
                version_number,
                lifecycle_state,
                approval_required,
                step_mode,
                authority_key,
                reason_required,
                effective_from,
                effective_to,
                created_by_user_id,
                created_at,
                version
            )
            VALUES (
                ?, ?,
                'ROUTINE_FINANCE_ADMIN',
                1, 'ACTIVE',
                false,
                'NO_APPROVAL_REQUIRED',
                NULL, false,
                ?, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            organizationId,
            at.minusMinutes(1),
            ownerOneUserId,
            at,
        )

        jdbc.update(
            """
            INSERT INTO approval_policy_version (
                id,
                organization_id,
                policy_key,
                version_number,
                lifecycle_state,
                approval_required,
                step_mode,
                authority_key,
                reason_required,
                effective_from,
                effective_to,
                created_by_user_id,
                created_at,
                version
            )
            VALUES (
                ?, ?,
                'EXCEPTION_OWNER_FINAL',
                1, 'ACTIVE',
                true,
                'SINGLE',
                'OWNER_FINAL',
                false,
                ?, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            organizationId,
            at.minusMinutes(1),
            ownerOneUserId,
            at,
        )

        return SeedIds(
            organizationId =
                organizationId,
            financeUserId =
                financeUserId,
            ownerOneUserId =
                ownerOneUserId,
            ownerTwoUserId =
                ownerTwoUserId,
            outsiderUserId =
                outsiderUserId,
        )
    }

    private data class SeedIds(
        val organizationId: UUID,
        val financeUserId: UUID,
        val ownerOneUserId: UUID,
        val ownerTwoUserId: UUID,
        val outsiderUserId: UUID,
    )
}
