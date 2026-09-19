package com.hiltech.server.notifications

import com.hiltech.server.approval.ApprovalAuthorizationPort
import com.hiltech.server.approval.ApprovalAuthorizationRelations
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.ApprovalRequested
import com.hiltech.server.approval.JdbcApprovalPersistence
import com.hiltech.server.security.AuthorizationCheckRequest
import com.hiltech.server.security.AuthorizationDesiredState
import com.hiltech.server.security.FailClosedAuthorizationAdapter
import com.hiltech.server.security.HiltechOpenFgaProperties
import com.hiltech.server.security.JdbcAuthorizationProjectionGuard
import com.hiltech.server.security.JdkOpenFgaHttpTransport
import com.hiltech.server.security.OpenFgaGateway
import com.hiltech.server.security.OpenFgaMutationResult
import com.hiltech.server.security.OpenFgaTuple
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class NotificationPostgresOpenFgaContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_NOTIFICATION_CONTRACT_TEST",
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
    fun notificationFoundationIsSourceAuthorizedSafeAndProviderNeutral() {
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
        val now =
            Instant.parse(
                "2026-09-20T00:45:00.987654321Z",
            )
        val clock =
            Clock.fixed(
                now,
                ZoneOffset.UTC,
            )
        val ids =
            seedBase(
                jdbc = jdbc,
                at = now.minusSeconds(600),
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
        val persistence =
            JdbcNotificationPersistence(
                jdbc,
            )
        val listener =
            ApprovalNotificationProjectionListener(
                approval = approval,
                projection = persistence,
            )
        val sourceAccess =
            ApprovalNotificationSourceAccess(
                approval = approval,
                authorization =
                    approvalAuthorization,
            )

        val acceptedProvider =
            RecordingProvider(
                NotificationDeliveryResult(
                    providerKey =
                        "TEST_RECORDING",
                    outcome =
                        NotificationDeliveryOutcome
                            .ACCEPTED,
                    providerMessageId =
                        "test-message-1",
                ),
            )
        val acceptedService =
            NotificationDispatchService(
                persistence = persistence,
                sourceAccess = sourceAccess,
                deliveryProvider =
                    providerOf(
                        acceptedProvider,
                    ),
                clock = clock,
            )

        val acceptedRequest =
            seedApproval(
                jdbc = jdbc,
                ids = ids,
                principalType = "USER",
                principalId =
                    ids.ownerOne,
                at =
                    now.minusSeconds(60),
            )
        applyApprovalTuple(
            gateway = gateway,
            request = acceptedRequest,
        )
        val acceptedEvent =
            requestedEvent(
                acceptedRequest,
            )

        listener.on(acceptedEvent)
        listener.on(acceptedEvent)

        assertEquals(
            1,
            intentCount(
                jdbc,
                acceptedRequest.requestId,
            ),
            "ApprovalRequested redelivery must create one semantic Notification intent.",
        )

        val acceptedIntent =
            requireNotNull(
                intentFor(
                    jdbc,
                    acceptedRequest.requestId,
                ),
            )
        assertEquals(
            "PENDING",
            acceptedIntent.policyState,
        )
        assertEquals(
            "ACTION_REQUIRED",
            acceptedIntent.notificationClass,
        )
        assertEquals(
            "APPROVAL_DECISION_REQUIRED",
            acceptedIntent.templateCode,
        )
        assertNull(
            acceptedIntent.safePreview,
            "Minimal foundation must not copy Approval reason/body into a lock-screen payload.",
        )
        assertEquals(
            ids.ownerOne,
            acceptedIntent.recipient,
        )

        val acceptedDispatch =
            acceptedService.dispatch(
                notificationIntentId =
                    acceptedIntent.id,
                channel =
                    NotificationChannel.PUSH,
                recipientTargetRef =
                    "test-recipient-ref",
                correlationId =
                    "corr-notification-accepted",
            )
        assertEquals(
            NotificationDispatchStatus.DISPATCHED,
            acceptedDispatch.status,
        )
        assertEquals(
            "DISPATCHED",
            intentState(
                jdbc,
                acceptedIntent.id,
            ),
        )
        assertEquals(
            1,
            attemptCount(
                jdbc,
                acceptedIntent.id,
            ),
        )
        assertEquals(
            "PENDING",
            approvalState(
                jdbc,
                acceptedRequest.requestId,
            ),
            "Notification dispatch must not mutate Approval business truth.",
        )
        assertEquals(
            1,
            acceptedProvider.requests.size,
        )
        val outbound =
            acceptedProvider.requests.single()
        assertEquals(
            "APPROVAL_DECISION_REQUIRED",
            outbound.templateCode,
        )
        assertNull(
            outbound.safePreview,
        )
        assertEquals(
            acceptedRequest.requestId,
            outbound.deepLinkId,
        )
        assertFalse(
            outbound.recipientTargetRef
                .contains(
                    acceptedRequest.subjectId
                        .toString(),
                ),
            "Delivery target must not embed arbitrary source payload.",
        )

        val failedRequest =
            seedApproval(
                jdbc = jdbc,
                ids = ids,
                principalType = "USER",
                principalId =
                    ids.ownerOne,
                at =
                    now.minusSeconds(50),
            )
        applyApprovalTuple(
            gateway = gateway,
            request = failedRequest,
        )
        listener.on(
            requestedEvent(
                failedRequest,
            ),
        )
        val failedIntent =
            requireNotNull(
                intentFor(
                    jdbc,
                    failedRequest.requestId,
                ),
            )
        val failedProvider =
            RecordingProvider(
                NotificationDeliveryResult(
                    providerKey =
                        "TEST_RECORDING",
                    outcome =
                        NotificationDeliveryOutcome
                            .FAILED_RETRYABLE,
                    failureCode =
                        "TEMPORARY_FAILURE",
                ),
            )
        val failedService =
            NotificationDispatchService(
                persistence = persistence,
                sourceAccess = sourceAccess,
                deliveryProvider =
                    providerOf(
                        failedProvider,
                    ),
                clock = clock,
            )
        val failedDispatch =
            failedService.dispatch(
                notificationIntentId =
                    failedIntent.id,
                channel =
                    NotificationChannel.PUSH,
                recipientTargetRef =
                    "test-recipient-ref",
                correlationId =
                    "corr-notification-failed",
            )
        assertEquals(
            NotificationDispatchStatus
                .FAILED_RETRYABLE,
            failedDispatch.status,
        )
        assertEquals(
            "PENDING",
            intentState(
                jdbc,
                failedIntent.id,
            ),
            "Retryable provider failure must not pretend Notification delivery succeeded.",
        )
        assertEquals(
            1,
            attemptCount(
                jdbc,
                failedIntent.id,
            ),
        )
        assertEquals(
            "PENDING",
            approvalState(
                jdbc,
                failedRequest.requestId,
            ),
        )

        val noProviderRequest =
            seedApproval(
                jdbc = jdbc,
                ids = ids,
                principalType = "USER",
                principalId =
                    ids.ownerOne,
                at =
                    now.minusSeconds(40),
            )
        applyApprovalTuple(
            gateway = gateway,
            request = noProviderRequest,
        )
        listener.on(
            requestedEvent(
                noProviderRequest,
            ),
        )
        val noProviderIntent =
            requireNotNull(
                intentFor(
                    jdbc,
                    noProviderRequest.requestId,
                ),
            )
        val noProviderService =
            NotificationDispatchService(
                persistence = persistence,
                sourceAccess = sourceAccess,
                deliveryProvider =
                    providerOf(null),
                clock = clock,
            )
        val noProviderDispatch =
            noProviderService.dispatch(
                notificationIntentId =
                    noProviderIntent.id,
                channel =
                    NotificationChannel.PUSH,
                recipientTargetRef =
                    "unused",
                correlationId =
                    "corr-no-provider",
            )
        assertEquals(
            NotificationDispatchStatus.NO_PROVIDER,
            noProviderDispatch.status,
        )
        assertEquals(
            "PENDING",
            intentState(
                jdbc,
                noProviderIntent.id,
            ),
        )
        assertEquals(
            0,
            attemptCount(
                jdbc,
                noProviderIntent.id,
            ),
            "No configured production provider must never create a fake delivery attempt.",
        )

        val staleRequest =
            seedApproval(
                jdbc = jdbc,
                ids = ids,
                principalType = "USER",
                principalId =
                    ids.ownerOne,
                at =
                    now.minusSeconds(30),
            )
        applyApprovalTuple(
            gateway = gateway,
            request = staleRequest,
        )
        listener.on(
            requestedEvent(
                staleRequest,
            ),
        )
        val staleIntent =
            requireNotNull(
                intentFor(
                    jdbc,
                    staleRequest.requestId,
                ),
            )
        replaceOwnerAuthority(
            jdbc = jdbc,
            ids = ids,
            newOwner = ids.ownerTwo,
            at = now.minusSeconds(1),
        )
        val staleDispatch =
            acceptedService.dispatch(
                notificationIntentId =
                    staleIntent.id,
                channel =
                    NotificationChannel.PUSH,
                recipientTargetRef =
                    "test-recipient-ref",
                correlationId =
                    "corr-stale-authority",
            )
        assertEquals(
            NotificationDispatchStatus
                .SUPPRESSED_STALE,
            staleDispatch.status,
        )
        assertEquals(
            "SUPPRESSED",
            intentState(
                jdbc,
                staleIntent.id,
            ),
        )
        assertEquals(
            "SOURCE_NOT_ACTIONABLE",
            suppressionReason(
                jdbc,
                staleIntent.id,
            ),
        )
        assertEquals(
            0,
            attemptCount(
                jdbc,
                staleIntent.id,
            ),
        )

        replaceOwnerAuthority(
            jdbc = jdbc,
            ids = ids,
            newOwner = ids.ownerOne,
            at = now.plusSeconds(1),
        )

        val terminalRequest =
            seedApproval(
                jdbc = jdbc,
                ids = ids,
                principalType = "USER",
                principalId =
                    ids.ownerOne,
                at =
                    now.minusSeconds(20),
            )
        applyApprovalTuple(
            gateway = gateway,
            request = terminalRequest,
        )
        listener.on(
            requestedEvent(
                terminalRequest,
            ),
        )
        val terminalIntent =
            requireNotNull(
                intentFor(
                    jdbc,
                    terminalRequest.requestId,
                ),
            )
        terminalize(
            jdbc = jdbc,
            request = terminalRequest,
            at = now.plusSeconds(2),
        )
        val terminalDispatch =
            acceptedService.dispatch(
                notificationIntentId =
                    terminalIntent.id,
                channel =
                    NotificationChannel.PUSH,
                recipientTargetRef =
                    "test-recipient-ref",
                correlationId =
                    "corr-terminal",
            )
        assertEquals(
            NotificationDispatchStatus
                .SUPPRESSED_STALE,
            terminalDispatch.status,
        )
        assertEquals(
            0,
            attemptCount(
                jdbc,
                terminalIntent.id,
            ),
        )

        val team =
            seedTeam(
                jdbc = jdbc,
                organizationId =
                    ids.organization,
                managerUserId =
                    ids.ownerOne,
            )
        val teamRequest =
            seedApproval(
                jdbc = jdbc,
                ids = ids,
                principalType = "TEAM",
                principalId =
                    team,
                at =
                    now.minusSeconds(10),
            )
        listener.on(
            requestedEvent(
                teamRequest,
            ),
        )
        assertEquals(
            0,
            intentCount(
                jdbc,
                teamRequest.requestId,
            ),
            "TEAM assignment must not fan out Notification delivery in Slice 06.",
        )

        val crossOrgRequest =
            seedApproval(
                jdbc = jdbc,
                ids = ids,
                principalType = "USER",
                principalId =
                    ids.ownerOne,
                at =
                    now.minusSeconds(5),
            )
        applyApprovalTuple(
            gateway = gateway,
            request = crossOrgRequest,
        )
        listener.on(
            requestedEvent(
                crossOrgRequest,
            ),
        )
        val crossOrgIntent =
            requireNotNull(
                intentFor(
                    jdbc,
                    crossOrgRequest.requestId,
                ),
            )
        jdbc.update(
            """
            UPDATE notification_intent
            SET recipient_user_identity_id = ?
            WHERE id = ?
            """.trimIndent(),
            ids.outsider,
            crossOrgIntent.id,
        )
        val crossOrgDispatch =
            acceptedService.dispatch(
                notificationIntentId =
                    crossOrgIntent.id,
                channel =
                    NotificationChannel.PUSH,
                recipientTargetRef =
                    "test-recipient-ref",
                correlationId =
                    "corr-cross-org",
            )
        assertEquals(
            NotificationDispatchStatus
                .SUPPRESSED_STALE,
            crossOrgDispatch.status,
        )
        assertEquals(
            0,
            attemptCount(
                jdbc,
                crossOrgIntent.id,
            ),
        )

        val unsafeColumns =
            jdbc.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN (
                      'notification_intent',
                      'notification_delivery_attempt'
                  )
                  AND (
                      column_name ILIKE '%access_token%'
                      OR column_name ILIKE '%refresh_token%'
                      OR column_name ILIKE '%secret%'
                      OR column_name ILIKE '%storage_key%'
                      OR column_name ILIKE '%raw_filename%'
                      OR column_name ILIKE '%subject_payload%'
                      OR column_name ILIKE '%subject_body%'
                      OR column_name ILIKE '%salary%'
                      OR column_name ILIKE '%bank_account%'
                  )
                """.trimIndent(),
                String::class.java,
            )
        assertTrue(
            unsafeColumns.isEmpty(),
            "Notification persistence must not create sensitive-payload or provider-secret columns.",
        )
    }

    private fun providerOf(
        provider: NotificationDeliveryPort?,
    ) =
        StaticListableBeanFactory()
            .also { factory ->
                if (provider != null) {
                    factory.addBean(
                        "notificationDeliveryPort",
                        provider,
                    )
                }
            }
            .getBeanProvider(
                NotificationDeliveryPort::class.java,
            )

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
                "corr-notify-" +
                    request.requestId,
        )

    private fun applyApprovalTuple(
        gateway: OpenFgaGateway,
        request: SeedApproval,
    ) {
        val result =
            gateway.apply(
                ApprovalAuthorizationRelations
                    .approver(
                        principalType =
                            ApprovalPrincipalType.valueOf(
                                request.principalType,
                            ),
                        principalId =
                            request.principalId,
                        approvalRequestId =
                            request.requestId,
                    ),
                AuthorizationDesiredState.PRESENT,
            )
        assertTrue(
            result is
                OpenFgaMutationResult.Applied,
            "OpenFGA Approval fixture must apply: $result",
        )
    }

    private fun seedBase(
        jdbc: JdbcTemplate,
        at: Instant,
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
        val outsider =
            UUID.randomUUID()

        insertOrganization(
            jdbc = jdbc,
            id = organization,
            code = "NOTIFY",
            at = at,
        )
        insertOrganization(
            jdbc = jdbc,
            id = outsiderOrganization,
            code = "NOTIFY-OUT",
            at = at,
        )

        listOf(
            finance to organization,
            ownerOne to organization,
            ownerTwo to organization,
            outsider to outsiderOrganization,
        ).forEach { (user, org) ->
            insertUser(
                jdbc = jdbc,
                userId = user,
                organizationId = org,
                at = at,
            )
            insertMembership(
                jdbc = jdbc,
                organizationId = org,
                userId = user,
                at = at,
            )
        }

        insertOwnerAuthority(
            jdbc = jdbc,
            organizationId = organization,
            ownerId = ownerOne,
            createdBy = ownerOne,
            at = at,
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
            at.minusSeconds(60)
                .atOffset(ZoneOffset.UTC),
            ownerOne,
            at.atOffset(ZoneOffset.UTC),
        )

        return SeedIds(
            organization = organization,
            outsiderOrganization =
                outsiderOrganization,
            finance = finance,
            ownerOne = ownerOne,
            ownerTwo = ownerTwo,
            outsider = outsider,
        )
    }

    private fun insertOrganization(
        jdbc: JdbcTemplate,
        id: UUID,
        code: String,
        at: Instant,
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
                'Notification Contract',
                'Notification Contract',
                'HILTECH',
                'ACTIVE',
                ?, 1
            )
            """.trimIndent(),
            id,
            code + "-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertUser(
        jdbc: JdbcTemplate,
        userId: UUID,
        organizationId: UUID,
        at: Instant,
    ) {
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
            "notification-" + userId,
            organizationId,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertMembership(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        userId: UUID,
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
                'contract-only',
                'ACTIVE',
                ?, NULL,
                NULL, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            organizationId,
            userId,
            at.minusSeconds(60)
                .atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertOwnerAuthority(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        ownerId: UUID,
        createdBy: UUID,
        at: Instant,
    ) {
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
            ownerId,
            at.minusSeconds(60)
                .atOffset(ZoneOffset.UTC),
            createdBy,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun seedApproval(
        jdbc: JdbcTemplate,
        ids: SeedIds,
        principalType: String,
        principalId: UUID,
        at: Instant,
    ): SeedApproval {
        val requestId =
            UUID.randomUUID()
        val subjectId =
            UUID.randomUUID()
        val stepId =
            UUID.randomUUID()
        val assignmentId =
            UUID.randomUUID()
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

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
                'Sensitive source context must not be copied.',
                ?, NULL, ?, 1
            )
            """.trimIndent(),
            requestId,
            ids.organization,
            subjectId,
            ids.finance,
            timestamp,
            "corr-notify-" + requestId,
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
                    principalType == "USER"
                },
            principalId
                .takeIf {
                    principalType == "TEAM"
                },
            timestamp,
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
            createdAt = at,
            requestedEventId =
                UUID.randomUUID(),
        )
    }

    private fun seedTeam(
        jdbc: JdbcTemplate,
        organizationId: UUID,
        managerUserId: UUID,
    ): UUID {
        val teamId =
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
                'Notification Contract Team',
                NULL, ?,
                true, 1
            )
            """.trimIndent(),
            teamId,
            organizationId,
            "NOTIFY-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            managerUserId,
        )
        return teamId
    }

    private fun replaceOwnerAuthority(
        jdbc: JdbcTemplate,
        ids: SeedIds,
        newOwner: UUID,
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
            at.atOffset(ZoneOffset.UTC),
            ids.organization,
        )

        insertOwnerAuthority(
            jdbc = jdbc,
            organizationId =
                ids.organization,
            ownerId = newOwner,
            createdBy = ids.ownerTwo,
            at = at.plusMillis(1),
        )
    }

    private fun terminalize(
        jdbc: JdbcTemplate,
        request: SeedApproval,
        at: Instant,
    ) {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        jdbc.update(
            """
            UPDATE approval_assignment
            SET state = 'ACTED',
                acted_at = ?
            WHERE id = ?
            """.trimIndent(),
            timestamp,
            request.assignmentId,
        )
        jdbc.update(
            """
            UPDATE approval_step
            SET state = 'COMPLETED'
            WHERE id = ?
            """.trimIndent(),
            request.stepId,
        )
        jdbc.update(
            """
            UPDATE approval_request
            SET state = 'APPROVED',
                completed_at = ?,
                version = version + 1
            WHERE id = ?
            """.trimIndent(),
            timestamp,
            request.requestId,
        )
    }

    private fun intentCount(
        jdbc: JdbcTemplate,
        requestId: UUID,
    ): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM notification_intent
            WHERE source_type =
                  'APPROVAL_REQUEST'
              AND source_id = ?
            """.trimIndent(),
            Int::class.java,
            requestId,
        ) ?: 0

    private fun intentFor(
        jdbc: JdbcTemplate,
        requestId: UUID,
    ): IntentRow? =
        jdbc.query(
            """
            SELECT
                id,
                recipient_user_identity_id,
                notification_class,
                template_code,
                safe_preview,
                policy_state
            FROM notification_intent
            WHERE source_type =
                  'APPROVAL_REQUEST'
              AND source_id = ?
            """.trimIndent(),
            { rs, _ ->
                IntentRow(
                    id =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    recipient =
                        rs.getObject(
                            "recipient_user_identity_id",
                            UUID::class.java,
                        ),
                    notificationClass =
                        rs.getString(
                            "notification_class",
                        ),
                    templateCode =
                        rs.getString(
                            "template_code",
                        ),
                    safePreview =
                        rs.getString(
                            "safe_preview",
                        ),
                    policyState =
                        rs.getString(
                            "policy_state",
                        ),
                )
            },
            requestId,
        ).singleOrNull()

    private fun intentState(
        jdbc: JdbcTemplate,
        intentId: UUID,
    ): String =
        requireNotNull(
            jdbc.queryForObject(
                """
                SELECT policy_state
                FROM notification_intent
                WHERE id = ?
                """.trimIndent(),
                String::class.java,
                intentId,
            ),
        )

    private fun suppressionReason(
        jdbc: JdbcTemplate,
        intentId: UUID,
    ): String? =
        jdbc.queryForObject(
            """
            SELECT suppression_reason
            FROM notification_intent
            WHERE id = ?
            """.trimIndent(),
            String::class.java,
            intentId,
        )

    private fun attemptCount(
        jdbc: JdbcTemplate,
        intentId: UUID,
    ): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM notification_delivery_attempt
            WHERE notification_intent_id = ?
            """.trimIndent(),
            Int::class.java,
            intentId,
        ) ?: 0

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

    private class RecordingProvider(
        private val result:
            NotificationDeliveryResult,
    ) : NotificationDeliveryPort {
        val requests =
            mutableListOf<
                NotificationDeliveryRequest
            >()

        override fun deliver(
            request: NotificationDeliveryRequest,
        ): NotificationDeliveryResult {
            requests += request
            return result
        }
    }

    private data class SeedIds(
        val organization: UUID,
        val outsiderOrganization: UUID,
        val finance: UUID,
        val ownerOne: UUID,
        val ownerTwo: UUID,
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

    private data class IntentRow(
        val id: UUID,
        val recipient: UUID,
        val notificationClass: String,
        val templateCode: String,
        val safePreview: String?,
        val policyState: String,
    )
}
