package com.hiltech.server.inbox

import com.hiltech.server.approval.ApprovalApproved
import com.hiltech.server.approval.ApprovalAuthorizationPort
import com.hiltech.server.approval.ApprovalAuthorizationRelations
import com.hiltech.server.approval.ApprovalChangeRequested
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.ApprovalRejected
import com.hiltech.server.approval.ApprovalRequested
import com.hiltech.server.approval.ApprovalSuperseded
import com.hiltech.server.approval.JdbcApprovalPersistence
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.FailClosedAuthorizationAdapter
import com.hiltech.server.security.HiltechOpenFgaProperties
import com.hiltech.server.security.JdbcAuthorizationProjectionGuard
import com.hiltech.server.security.JdkOpenFgaHttpTransport
import com.hiltech.server.security.OpenFgaGateway
import com.hiltech.server.security.OpenFgaMutationResult
import com.hiltech.server.security.OpenFgaTuple
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class InboxWorkQueuePostgresOpenFgaContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_INBOX_CONTRACT_TEST",
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
    fun inboxAndWorkQueueStaySourceAuthorizedIdempotentAndDeterministic() {
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
        val now =
            Instant.parse(
                "2026-09-19T20:30:00.987654321Z",
            )
        val clock =
            Clock.fixed(
                now,
                ZoneOffset.UTC,
            )
        val ids =
            seedBase(
                jdbc = jdbc,
                now =
                    now.minusSeconds(600),
            )

        val properties =
            HiltechOpenFgaProperties(
                enabled = true,
                apiUrl = fgaApiUrl,
                storeId = fgaStoreId,
                authorizationModelId =
                    fgaModelId,
            )
        properties
            .validateEnabledConfiguration()

        val gateway =
            OpenFgaGateway(
                properties = properties,
                transport =
                    JdkOpenFgaHttpTransport(
                        properties,
                    ),
            )
        val check =
            FailClosedAuthorizationAdapter(
                guard =
                    JdbcAuthorizationProjectionGuard(
                        jdbc,
                    ),
                openFgaGateway =
                    gateway,
            )
        val approvalAuthorization =
            object :
                ApprovalAuthorizationPort {
                override fun canDecide(
                    actorUserId: UUID,
                    approvalRequestId: UUID,
                    assignmentPrincipalType:
                        ApprovalPrincipalType,
                    assignmentPrincipalId:
                        UUID,
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

        val approval =
            JdbcApprovalPersistence(jdbc)
        val projection =
            JdbcInboxProjection(
                jdbc = jdbc,
                clock = clock,
            )
        val listener =
            ApprovalInboxProjectionListener(
                approval = approval,
                projection = projection,
            )
        val sourceAccess =
            ApprovalInboxSourceAccess(
                approval = approval,
                authorization =
                    approvalAuthorization,
            )
        val store =
            JdbcInboxReadStore(jdbc)
        val telemetry =
            HiltechTelemetryRuntime(
                openTelemetry =
                    OpenTelemetry.noop(),
                closeAction = {},
            )
        val codec =
            InboxCursorCodec(
                "inbox-contract-signing-key-20260919-strong",
            )
        val service =
            InboxService(
                store = store,
                sourceAccess =
                    sourceAccess,
                idempotency =
                    JdbcIdempotentCommandExecutor(
                        jdbc = jdbc,
                        transactionManager =
                            txManager,
                        clock = clock,
                        telemetry =
                            telemetry,
                    ),
                cursorCodec = codec,
                clock = clock,
            )

        try {
            val preciseCursor =
                InboxCursor(
                    surface = "INBOX",
                    actorUserId =
                        ids.ownerOne,
                    asOf =
                        Instant.parse(
                            "2026-09-19T20:30:00.123456789Z",
                        ),
                    stateFilter = "OPEN",
                    readFilter = "UNREAD",
                    boundaryAt =
                        Instant.parse(
                            "2026-09-19T20:29:59.987654321Z",
                        ),
                    itemId =
                        UUID.randomUUID(),
                )
            assertEquals(
                preciseCursor,
                codec.decode(
                    codec.encode(
                        preciseCursor,
                    ),
                ),
                "Inbox cursor must preserve full Instant precision.",
            )

            val requestA =
                seedApproval(
                    jdbc = jdbc,
                    ids = ids,
                    principalType = "USER",
                    principalId =
                        ids.ownerOne,
                    createdAt =
                        now.minusSeconds(30)
                            .plusNanos(111),
                )
            val requestB =
                seedApproval(
                    jdbc = jdbc,
                    ids = ids,
                    principalType = "USER",
                    principalId =
                        ids.ownerOne,
                    createdAt =
                        now.minusSeconds(20)
                            .plusNanos(222),
                )
            val requestC =
                seedApproval(
                    jdbc = jdbc,
                    ids = ids,
                    principalType = "USER",
                    principalId =
                        ids.ownerOne,
                    createdAt =
                        now.minusSeconds(10)
                            .plusNanos(333),
                )
            val requestD =
                seedApproval(
                    jdbc = jdbc,
                    ids = ids,
                    principalType = "USER",
                    principalId =
                        ids.ownerOne,
                    createdAt =
                        now.minusSeconds(5)
                            .plusNanos(444),
                )

            listOf(
                requestA,
                requestB,
                requestC,
                requestD,
            ).forEach { request ->
                applyApprovalApproverTuple(
                    gateway = gateway,
                    request = request,
                )
                listener.on(
                    requestedEvent(
                        request,
                    ),
                )
            }

            val beforeReplayVersion =
                inboxVersion(
                    jdbc,
                    requestA.requestId,
                )
            listener.on(
                requestedEvent(
                    requestA,
                ),
            )
            assertEquals(
                beforeReplayVersion,
                inboxVersion(
                    jdbc,
                    requestA.requestId,
                ),
                "Exact Approval event replay must be a projection no-op.",
            )
            assertEquals(
                1,
                inboxCount(
                    jdbc,
                    requestA.requestId,
                ),
            )

            val queue1 =
                service.workQueue(
                    actorUserId =
                        ids.ownerOne,
                    rawCursor = null,
                    requestedLimit = 2,
                    correlationId =
                        "corr-queue-1",
                )
            assertEquals(
                2,
                queue1.items.size,
            )
            assertNotNull(
                queue1.nextCursor,
            )
            val queue2 =
                service.workQueue(
                    actorUserId =
                        ids.ownerOne,
                    rawCursor =
                        queue1.nextCursor,
                    requestedLimit = 2,
                    correlationId =
                        "corr-queue-2",
                )
            assertEquals(
                queue1.asOf,
                queue2.asOf,
            )
            assertEquals(
                listOf(
                    requestA.requestId,
                    requestB.requestId,
                    requestC.requestId,
                    requestD.requestId,
                ),
                (
                    queue1.items +
                        queue2.items
                ).map {
                    UUID.fromString(
                        it.sourceId,
                    )
                },
                "Work Queue must be deterministic oldest-first without duplicate/skip.",
            )

            val inbox1 =
                service.inbox(
                    actorUserId =
                        ids.ownerOne,
                    rawCursor = null,
                    requestedLimit = 2,
                    stateFilterRaw =
                        "OPEN",
                    readFilterRaw = null,
                    correlationId =
                        "corr-inbox-1",
                )
            val inbox2 =
                service.inbox(
                    actorUserId =
                        ids.ownerOne,
                    rawCursor =
                        inbox1.nextCursor,
                    requestedLimit = 2,
                    stateFilterRaw =
                        "OPEN",
                    readFilterRaw = null,
                    correlationId =
                        "corr-inbox-2",
                )
            assertEquals(
                listOf(
                    requestD.requestId,
                    requestC.requestId,
                    requestB.requestId,
                    requestA.requestId,
                ),
                (
                    inbox1.items +
                        inbox2.items
                ).map {
                    UUID.fromString(
                        it.sourceId,
                    )
                },
                "Inbox must be deterministic newest-first without duplicate/skip.",
            )

            val itemB =
                inboxId(
                    jdbc,
                    requestB.requestId,
                )
            val readOperation =
                UUID.randomUUID()
            val firstRead =
                service.setReadState(
                    actorUserId =
                        ids.ownerOne,
                    inboxItemId = itemB,
                    operationId =
                        readOperation,
                    read = true,
                    correlationId =
                        "corr-read",
                )
            val readReplay =
                service.setReadState(
                    actorUserId =
                        ids.ownerOne,
                    inboxItemId = itemB,
                    operationId =
                        readOperation,
                    read = true,
                    correlationId =
                        "corr-read",
                )
            assertTrue(firstRead.read)
            assertFalse(
                firstRead.replayed,
            )
            assertTrue(
                readReplay.replayed,
            )
            assertEquals(
                "PENDING",
                approvalState(
                    jdbc,
                    requestB.requestId,
                ),
                "Read state must not mutate Approval business truth.",
            )
            assertEquals(
                "OPEN",
                inboxState(
                    jdbc,
                    requestB.requestId,
                ),
                "Read state must not resolve the source-linked Inbox item.",
            )

            val unread =
                service.setReadState(
                    actorUserId =
                        ids.ownerOne,
                    inboxItemId = itemB,
                    operationId =
                        UUID.randomUUID(),
                    read = false,
                    correlationId =
                        "corr-unread",
                )
            assertFalse(unread.read)
            assertFalse(
                service.one(
                    actorUserId =
                        ids.ownerOne,
                    inboxItemId = itemB,
                ).read,
            )

            assertTrue(
                service.workQueue(
                    actorUserId =
                        ids.outsider,
                    rawCursor = null,
                    requestedLimit = 50,
                    correlationId =
                        "corr-outsider",
                ).items.isEmpty(),
                "Cross-organization actor must not receive HILTECH Inbox items.",
            )

            val tampered =
                requireNotNull(inbox1.nextCursor)
                    .split('.', limit = 2)
                    .let { parts ->
                        val signature = parts.getOrNull(1)
                            ?: error("Expected a signed Inbox cursor.")
                        val replacement = if (signature.first() == 'A') 'B' else 'A'
                        "${parts[0]}.$replacement${signature.drop(1)}"
                    }
            assertThrows<
                ProductApiException
            > {
                service.inbox(
                    actorUserId =
                        ids.ownerOne,
                    rawCursor = tampered,
                    requestedLimit = 2,
                    stateFilterRaw =
                        "OPEN",
                    readFilterRaw = null,
                    correlationId =
                        "corr-tampered",
                )
            }

            assertThrows<
                ProductApiException
            > {
                service.inbox(
                    actorUserId =
                        ids.ownerOne,
                    rawCursor =
                        inbox1.nextCursor,
                    requestedLimit = 2,
                    stateFilterRaw =
                        "RESOLVED",
                    readFilterRaw = null,
                    correlationId =
                        "corr-filter-mismatch",
                )
            }

            val unsupported =
                InboxItemRecord(
                    id = UUID.randomUUID(),
                    organizationId =
                        ids.organization,
                    producerKey =
                        "unsupported:test",
                    sourceType =
                        "UNSUPPORTED_SOURCE",
                    sourceId =
                        UUID.randomUUID(),
                    sourceVersion = 1,
                    actionKey =
                        "UNSUPPORTED_ACTION",
                    targetPrincipalType =
                        InboxTargetPrincipalType.USER,
                    targetPrincipalId =
                        ids.ownerOne,
                    state =
                        InboxItemState.OPEN,
                    safeTitleCode =
                        "UNSUPPORTED_ITEM",
                    safeSummary = null,
                    createdAt = now,
                    updatedAt = now,
                    resolvedAt = null,
                    correlationId = null,
                )
            assertFalse(
                sourceAccess.canRead(
                    actorUserId =
                        ids.ownerOne,
                    item = unsupported,
                    at = now,
                ),
            )
            assertFalse(
                sourceAccess.canAct(
                    actorUserId =
                        ids.ownerOne,
                    item = unsupported,
                    at = now,
                ),
            )

            replaceOwnerAuthority(
                jdbc = jdbc,
                ids = ids,
                newPrincipalType = "USER",
                newPrincipalId =
                    ids.ownerTwo,
                at = now,
            )
            assertThrows<
                ProductApiException
            > {
                service.one(
                    actorUserId =
                        ids.ownerOne,
                    inboxItemId =
                        inboxId(
                            jdbc,
                            requestA.requestId,
                        ),
                )
            }
            assertTrue(
                service.workQueue(
                    actorUserId =
                        ids.ownerOne,
                    rawCursor = null,
                    requestedLimit = 50,
                    correlationId =
                        "corr-stale-authority",
                ).items.isEmpty(),
                "Stale queue projection must not survive current authority replacement.",
            )

            terminalize(
                jdbc = jdbc,
                request = requestA,
                state = "APPROVED",
                assignmentState =
                    "ACTED",
                stepState =
                    "COMPLETED",
                at = now.plusSeconds(1),
            )
            listener.on(
                ApprovalApproved(
                    eventId =
                        UUID.randomUUID(),
                    approvalRequestId =
                        requestA.requestId,
                    organizationId =
                        ids.organization,
                    subjectType =
                        "CONTRACT_EXCEPTION",
                    subjectId =
                        requestA.subjectId,
                    subjectVersion = 1,
                    actorIdentityId =
                        ids.ownerOne,
                    occurredAt =
                        now.plusSeconds(1),
                    correlationId =
                        "corr-approved",
                ),
            )
            assertEquals(
                "RESOLVED",
                inboxState(
                    jdbc,
                    requestA.requestId,
                ),
            )

            terminalize(
                jdbc = jdbc,
                request = requestB,
                state = "REJECTED",
                assignmentState =
                    "ACTED",
                stepState =
                    "COMPLETED",
                at = now.plusSeconds(2),
            )
            listener.on(
                ApprovalRejected(
                    eventId =
                        UUID.randomUUID(),
                    approvalRequestId =
                        requestB.requestId,
                    organizationId =
                        ids.organization,
                    subjectType =
                        "CONTRACT_EXCEPTION",
                    subjectId =
                        requestB.subjectId,
                    subjectVersion = 1,
                    actorIdentityId =
                        ids.ownerOne,
                    occurredAt =
                        now.plusSeconds(2),
                    correlationId =
                        "corr-rejected",
                ),
            )
            assertEquals(
                "RESOLVED",
                inboxState(
                    jdbc,
                    requestB.requestId,
                ),
            )

            terminalize(
                jdbc = jdbc,
                request = requestC,
                state =
                    "CHANGE_REQUESTED",
                assignmentState =
                    "ACTED",
                stepState =
                    "COMPLETED",
                at = now.plusSeconds(3),
            )
            listener.on(
                ApprovalChangeRequested(
                    eventId =
                        UUID.randomUUID(),
                    approvalRequestId =
                        requestC.requestId,
                    organizationId =
                        ids.organization,
                    subjectType =
                        "CONTRACT_EXCEPTION",
                    subjectId =
                        requestC.subjectId,
                    subjectVersion = 1,
                    actorIdentityId =
                        ids.ownerOne,
                    occurredAt =
                        now.plusSeconds(3),
                    correlationId =
                        "corr-change",
                ),
            )
            assertEquals(
                "RESOLVED",
                inboxState(
                    jdbc,
                    requestC.requestId,
                ),
            )

            terminalize(
                jdbc = jdbc,
                request = requestD,
                state =
                    "SUPERSEDED",
                assignmentState =
                    "REVOKED",
                stepState =
                    "SUPERSEDED",
                at = now.plusSeconds(4),
            )
            val supersededEvent =
                ApprovalSuperseded(
                    eventId =
                        UUID.randomUUID(),
                    approvalRequestId =
                        requestD.requestId,
                    organizationId =
                        ids.organization,
                    subjectType =
                        "CONTRACT_EXCEPTION",
                    subjectId =
                        requestD.subjectId,
                    subjectVersion = 1,
                    actorIdentityId =
                        ids.ownerOne,
                    occurredAt =
                        now.plusSeconds(4),
                    correlationId =
                        "corr-superseded",
                )
            listener.on(
                supersededEvent,
            )
            val resolvedVersion =
                inboxVersion(
                    jdbc,
                    requestD.requestId,
                )
            listener.on(
                supersededEvent,
            )
            assertEquals(
                resolvedVersion,
                inboxVersion(
                    jdbc,
                    requestD.requestId,
                ),
                "Terminal event replay must not duplicate resolution effect.",
            )
            assertEquals(
                "RESOLVED",
                inboxState(
                    jdbc,
                    requestD.requestId,
                ),
            )

            listener.on(
                requestedEvent(
                    requestD,
                ),
            )
            assertEquals(
                "RESOLVED",
                inboxState(
                    jdbc,
                    requestD.requestId,
                ),
                "Delayed ApprovalRequested redelivery must not reopen a terminal source.",
            )

            val team =
                seedTeam(
                    jdbc = jdbc,
                    organizationId =
                        ids.organization,
                    memberUserId =
                        ids.teamMember,
                    managerUserId =
                        ids.ownerTwo,
                    at =
                        now.minusSeconds(60),
                )
            replaceOwnerAuthority(
                jdbc = jdbc,
                ids = ids,
                newPrincipalType =
                    "TEAM",
                newPrincipalId =
                    team.teamId,
                at = now,
            )

            assertApplied(
                gateway.apply(
                    OpenFgaTuple(
                        subjectType = "user",
                        subjectId =
                            ids.teamMember
                                .toString(),
                        relation = "member",
                        objectType = "team",
                        objectId =
                            team.teamId
                                .toString(),
                    ),
                    com.hiltech.server.security.AuthorizationDesiredState.PRESENT,
                ),
            )

            val teamRequest =
                seedApproval(
                    jdbc = jdbc,
                    ids = ids,
                    principalType = "TEAM",
                    principalId =
                        team.teamId,
                    createdAt =
                        now.minusSeconds(1)
                            .plusNanos(555),
                )
            applyApprovalApproverTuple(
                gateway = gateway,
                request = teamRequest,
            )
            listener.on(
                requestedEvent(
                    teamRequest,
                ),
            )

            val teamQueue =
                service.workQueue(
                    actorUserId =
                        ids.teamMember,
                    rawCursor = null,
                    requestedLimit = 20,
                    correlationId =
                        "corr-team",
                )
            assertEquals(
                listOf(
                    teamRequest.requestId
                        .toString(),
                ),
                teamQueue.items.map {
                    it.sourceId
                },
                "TEAM-targeted action must resolve through current team membership without duplicating source truth.",
            )

            jdbc.update(
                """
                UPDATE team_membership
                SET valid_until = ?,
                    version = version + 1
                WHERE id = ?
                """.trimIndent(),
                now.minusSeconds(1)
                    .atOffset(
                        ZoneOffset.UTC,
                    ),
                team.membershipId,
            )
            assertTrue(
                service.workQueue(
                    actorUserId =
                        ids.teamMember,
                    rawCursor = null,
                    requestedLimit = 20,
                    correlationId =
                        "corr-team-ended",
                ).items.isEmpty(),
                "Current team membership loss must hide stale TEAM-targeted queue projection immediately.",
            )

            val unsafeColumns =
                jdbc.queryForList(
                    """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name IN (
                          'inbox_item',
                          'inbox_user_state'
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
                unsafeColumns.isEmpty(),
                "Inbox persistence must keep only safe attention/source references.",
            )

            assertEquals(
                5,
                jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM inbox_item
                    WHERE organization_id = ?
                    """.trimIndent(),
                    Int::class.java,
                    ids.organization,
                ),
                "Exactly one logical Inbox row must exist per projected Approval action.",
            )
        } finally {
            telemetry.close()
        }
    }

    private fun requestedEvent(
        request: SeedApproval,
    ): ApprovalRequested =
        ApprovalRequested(
            eventId =
                request.requestedEventId,
            approvalRequestId =
                request.requestId,
            organizationId =
                request.organizationId,
            subjectType =
                "CONTRACT_EXCEPTION",
            subjectId =
                request.subjectId,
            subjectVersion = 1,
            policyKey =
                "EXCEPTION_OWNER_FINAL",
            policyVersion = 1,
            authorityKey =
                "OWNER_FINAL",
            requesterIdentityId =
                request.requesterUserId,
            occurredAt =
                request.createdAt,
            correlationId =
                "corr-" +
                    request.requestId,
        )

    private fun applyApprovalApproverTuple(
        gateway: OpenFgaGateway,
        request: SeedApproval,
    ) {
        val principalType =
            ApprovalPrincipalType.valueOf(
                request.principalType,
            )
        assertApplied(
            gateway.apply(
                ApprovalAuthorizationRelations
                    .approver(
                        principalType =
                            principalType,
                        principalId =
                            request.principalId,
                        approvalRequestId =
                            request.requestId,
                    ),
                com.hiltech.server.security.AuthorizationDesiredState.PRESENT,
            ),
        )
    }

    private fun assertApplied(
        result: OpenFgaMutationResult,
    ) {
        assertTrue(
            result is
                OpenFgaMutationResult.Applied,
            "OpenFGA fixture mutation must apply: $result",
        )
    }

    private fun seedBase(
        jdbc: JdbcTemplate,
        now: Instant,
    ): SeedIds {
        val organization =
            UUID.randomUUID()
        val outsiderOrganization =
            UUID.randomUUID()
        val finance =
            UUID.randomUUID()
        val ownerOne =
            UUID.randomUUID()
        val ownerTwo =
            UUID.randomUUID()
        val teamMember =
            UUID.randomUUID()
        val outsider =
            UUID.randomUUID()
        val at =
            now.atOffset(
                ZoneOffset.UTC,
            )

        insertOrganization(
            jdbc,
            organization,
            "INBOX",
            at,
        )
        insertOrganization(
            jdbc,
            outsiderOrganization,
            "INBOX-OUT",
            at,
        )

        listOf(
            finance to organization,
            ownerOne to organization,
            ownerTwo to organization,
            teamMember to organization,
            outsider to
                outsiderOrganization,
        ).forEach { pair ->
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
                pair.first,
                "inbox-" +
                    pair.first,
                pair.second,
                at,
            )
        }

        listOf(
            finance,
            ownerOne,
            ownerTwo,
            teamMember,
        ).forEach { user ->
            insertMembership(
                jdbc = jdbc,
                organizationId =
                    organization,
                userId = user,
                at = at,
            )
        }
        insertMembership(
            jdbc = jdbc,
            organizationId =
                outsiderOrganization,
            userId = outsider,
            at = at,
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
            organization,
            ownerOne,
            at.minusMinutes(1),
            ownerOne,
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
                1,
                'ACTIVE',
                true,
                'SINGLE',
                'OWNER_FINAL',
                false,
                ?, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            organization,
            at.minusMinutes(1),
            ownerOne,
            at,
        )

        return SeedIds(
            organization =
                organization,
            outsiderOrganization =
                outsiderOrganization,
            finance = finance,
            ownerOne = ownerOne,
            ownerTwo = ownerTwo,
            teamMember =
                teamMember,
            outsider = outsider,
        )
    }

    private fun insertOrganization(
        jdbc: JdbcTemplate,
        id: UUID,
        prefix: String,
        at: java.time.OffsetDateTime,
    ) {
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
                'Inbox Contract',
                'Inbox Contract',
                'HILTECH',
                'ACTIVE',
                ?, 1
            )
            """.trimIndent(),
            id,
            prefix +
                "-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            at,
        )
    }

    private fun insertMembership(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        userId: UUID,
        at: java.time.OffsetDateTime,
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
                'contract-only',
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

    private fun seedApproval(
        jdbc: JdbcTemplate,
        ids: SeedIds,
        principalType: String,
        principalId: UUID,
        createdAt: Instant,
    ): SeedApproval {
        val requestId =
            UUID.randomUUID()
        val subjectId =
            UUID.randomUUID()
        val stepId =
            UUID.randomUUID()
        val assignmentId =
            UUID.randomUUID()
        val at =
            createdAt.atOffset(
                ZoneOffset.UTC,
            )

        jdbc.update(
            """
            INSERT INTO approval_request (
                id,
                organization_id,
                subject_type,
                subject_id,
                subject_version,
                policy_key,
                policy_version,
                requester_user_id,
                state,
                reason_code,
                safe_reason_summary,
                created_at,
                completed_at,
                correlation_id,
                version
            )
            VALUES (
                ?, ?,
                'CONTRACT_EXCEPTION',
                ?, 1,
                'EXCEPTION_OWNER_FINAL',
                1, ?,
                'PENDING',
                'NON_ROUTINE_EXCEPTION',
                'Exception requires final authority.',
                ?, NULL, ?, 1
            )
            """.trimIndent(),
            requestId,
            ids.organization,
            subjectId,
            ids.finance,
            at,
            "corr-" + requestId,
        )

        jdbc.update(
            """
            INSERT INTO approval_step (
                id,
                approval_request_id,
                sequence_number,
                mode,
                authority_key,
                state,
                required_count
            )
            VALUES (
                ?, ?, 1,
                'SINGLE',
                'OWNER_FINAL',
                'PENDING', 1
            )
            """.trimIndent(),
            stepId,
            requestId,
        )

        jdbc.update(
            """
            INSERT INTO approval_assignment (
                id,
                approval_request_id,
                approval_step_id,
                principal_type,
                principal_user_id,
                principal_team_id,
                state,
                assigned_at,
                acted_at
            )
            VALUES (
                ?, ?, ?, ?,
                ?, ?,
                'ASSIGNED',
                ?, NULL
            )
            """.trimIndent(),
            assignmentId,
            requestId,
            stepId,
            principalType,
            principalId
                .takeIf {
                    principalType ==
                        "USER"
                },
            principalId
                .takeIf {
                    principalType ==
                        "TEAM"
                },
            at,
        )

        return SeedApproval(
            requestId = requestId,
            organizationId =
                ids.organization,
            requesterUserId =
                ids.finance,
            subjectId = subjectId,
            stepId = stepId,
            assignmentId =
                assignmentId,
            principalType =
                principalType,
            principalId =
                principalId,
            createdAt =
                createdAt,
            requestedEventId =
                UUID.randomUUID(),
        )
    }

    private fun terminalize(
        jdbc: JdbcTemplate,
        request: SeedApproval,
        state: String,
        assignmentState: String,
        stepState: String,
        at: Instant,
    ) {
        val timestamp =
            at.atOffset(
                ZoneOffset.UTC,
            )

        jdbc.update(
            """
            UPDATE approval_assignment
            SET state = ?,
                acted_at =
                    CASE
                        WHEN ? = 'ACTED'
                        THEN ?
                        ELSE acted_at
                    END
            WHERE id = ?
            """.trimIndent(),
            assignmentState,
            assignmentState,
            timestamp,
            request.assignmentId,
        )
        jdbc.update(
            """
            UPDATE approval_step
            SET state = ?
            WHERE id = ?
            """.trimIndent(),
            stepState,
            request.stepId,
        )
        jdbc.update(
            """
            UPDATE approval_request
            SET state = ?,
                completed_at = ?,
                version = version + 1
            WHERE id = ?
            """.trimIndent(),
            state,
            timestamp,
            request.requestId,
        )
    }

    private fun replaceOwnerAuthority(
        jdbc: JdbcTemplate,
        ids: SeedIds,
        newPrincipalType: String,
        newPrincipalId: UUID,
        at: Instant,
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
            at.atOffset(
                ZoneOffset.UTC,
            ),
            ids.organization,
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
                ?,
                ?, ?,
                ?, NULL,
                true,
                ?, ?, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            ids.organization,
            newPrincipalType,
            newPrincipalId
                .takeIf {
                    newPrincipalType ==
                        "USER"
                },
            newPrincipalId
                .takeIf {
                    newPrincipalType ==
                        "TEAM"
                },
            at.minusSeconds(1)
                .atOffset(
                    ZoneOffset.UTC,
                ),
            ids.ownerTwo,
            at.atOffset(
                ZoneOffset.UTC,
            ),
        )
    }

    private fun seedTeam(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        memberUserId: UUID,
        managerUserId: UUID,
        at: Instant,
    ): TeamSeed {
        val teamId =
            UUID.randomUUID()
        val membershipId =
            UUID.randomUUID()

        jdbc.update(
            """
            INSERT INTO team (
                id,
                organization_id,
                code,
                name,
                parent_team_id,
                manager_user_identity_id,
                active,
                version
            )
            VALUES (
                ?, ?, ?,
                'Inbox Contract Team',
                NULL, ?,
                true, 1
            )
            """.trimIndent(),
            teamId,
            organizationId,
            "INBOX-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            managerUserId,
        )

        jdbc.update(
            """
            INSERT INTO team_membership (
                id,
                team_id,
                user_identity_id,
                role_in_team,
                valid_from,
                valid_until,
                version
            )
            VALUES (
                ?, ?, ?,
                'MEMBER',
                ?, NULL, 1
            )
            """.trimIndent(),
            membershipId,
            teamId,
            memberUserId,
            at.atOffset(
                ZoneOffset.UTC,
            ),
        )

        return TeamSeed(
            teamId = teamId,
            membershipId =
                membershipId,
        )
    }

    private fun inboxCount(
        jdbc: JdbcTemplate,
        requestId: UUID,
    ): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM inbox_item
            WHERE source_type =
                  'APPROVAL_REQUEST'
              AND source_id = ?
            """.trimIndent(),
            Int::class.java,
            requestId,
        ) ?: 0

    private fun inboxId(
        jdbc: JdbcTemplate,
        requestId: UUID,
    ): UUID =
        requireNotNull(
            jdbc.queryForObject(
                """
                SELECT id
                FROM inbox_item
                WHERE source_type =
                      'APPROVAL_REQUEST'
                  AND source_id = ?
                """.trimIndent(),
                UUID::class.java,
                requestId,
            ),
        )

    private fun inboxState(
        jdbc: JdbcTemplate,
        requestId: UUID,
    ): String =
        requireNotNull(
            jdbc.queryForObject(
                """
                SELECT state
                FROM inbox_item
                WHERE source_type =
                      'APPROVAL_REQUEST'
                  AND source_id = ?
                """.trimIndent(),
                String::class.java,
                requestId,
            ),
        )

    private fun inboxVersion(
        jdbc: JdbcTemplate,
        requestId: UUID,
    ): Long =
        requireNotNull(
            jdbc.queryForObject(
                """
                SELECT version
                FROM inbox_item
                WHERE source_type =
                      'APPROVAL_REQUEST'
                  AND source_id = ?
                """.trimIndent(),
                Long::class.java,
                requestId,
            ),
        )

    private fun approvalState(
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

    private data class SeedIds(
        val organization: UUID,
        val outsiderOrganization: UUID,
        val finance: UUID,
        val ownerOne: UUID,
        val ownerTwo: UUID,
        val teamMember: UUID,
        val outsider: UUID,
    )

    private data class SeedApproval(
        val requestId: UUID,
        val organizationId: UUID,
        val requesterUserId: UUID,
        val subjectId: UUID,
        val stepId: UUID,
        val assignmentId: UUID,
        val principalType: String,
        val principalId: UUID,
        val createdAt: Instant,
        val requestedEventId: UUID,
    )

    private data class TeamSeed(
        val teamId: UUID,
        val membershipId: UUID,
    )
}
