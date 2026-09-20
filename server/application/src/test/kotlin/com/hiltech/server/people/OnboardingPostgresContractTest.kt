package com.hiltech.server.people

import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.util.UUID

class OnboardingPostgresContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_ONBOARDING_CONTRACT_TEST",
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
    fun onboardingPinsPolicyDerivesRealBlockersAndActivatesExactlyOnce() {
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
                    "2026-09-20T03:30:00Z",
                ),
                ZoneOffset.UTC,
            )
        val at =
            clock.instant()
                .minusSeconds(600)
        val ids =
            seedFixture(
                jdbc = jdbc,
                at = at,
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
            val service =
                OnboardingService(
                    persistence =
                        JdbcOnboardingPersistence(
                            jdbc,
                        ),
                    authorization =
                        FixturePeopleAuthorization(
                            allowed =
                                setOf(
                                    ids.adminIdentity to
                                        ids.organizationId,
                                    ids.otherAdminIdentity to
                                        ids.otherOrganizationId,
                                ),
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
                    audit =
                        JdbcAuditEventWriter(
                            jdbc,
                        ),
                    events = publisher,
                    clock = clock,
                )

            val startOperation =
                UUID.randomUUID()
            val started =
                service.start(
                    StartOnboardingCommand(
                        operationId =
                            startOperation,
                        employeeId =
                            ids.employeeId,
                        baseEmployeeVersion = 1,
                        onboardingPolicyRevisionId =
                            ids.policyRevisionId,
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-onboarding-start",
                    ),
                )

            assertFalse(started.replayed)
            assertEquals(
                OnboardingCaseState.OPEN,
                started.onboarding.state,
            )
            assertFalse(
                started.onboarding
                    .blockingSatisfied,
            )
            assertEquals(
                OnboardingRequirementStatus
                    .NEEDS_EMPLOYEE,
                status(
                    started.onboarding,
                    "MOBILE",
                ),
            )
            assertEquals(
                OnboardingRequirementStatus
                    .WAITING_HILTECH,
                status(
                    started.onboarding,
                    "IDENTITY",
                ),
            )
            assertEquals(
                OnboardingRequirementStatus
                    .WAITING_HILTECH,
                status(
                    started.onboarding,
                    "ASSIGNMENT",
                ),
            )
            assertEquals(
                OnboardingRequirementStatus
                    .NEEDS_EMPLOYEE,
                status(
                    started.onboarding,
                    "ID_COPY",
                ),
            )
            assertEquals(
                OnboardingRequirementStatus
                    .WAITING_HILTECH,
                status(
                    started.onboarding,
                    "HR_CONFIRM",
                ),
            )

            val replay =
                service.start(
                    StartOnboardingCommand(
                        operationId =
                            startOperation,
                        employeeId =
                            ids.employeeId,
                        baseEmployeeVersion = 1,
                        onboardingPolicyRevisionId =
                            ids.policyRevisionId,
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-onboarding-start",
                    ),
                )
            assertTrue(replay.replayed)
            assertEquals(
                started.onboarding.caseId,
                replay.onboarding.caseId,
            )

            supersedePolicyAndInsertRevisionTwo(
                jdbc = jdbc,
                ids = ids,
                at = clock.instant(),
            )

            val pinned =
                service.adminCase(
                    actorUserId =
                        ids.adminIdentity,
                    employeeId =
                        ids.employeeId,
                )
            assertEquals(
                ids.policyRevisionId,
                pinned.policyConfigRevisionId,
            )
            assertEquals(
                1,
                pinned.policyRevisionNumber,
            )
            assertEquals(
                6,
                pinned.requirements.size,
            )

            val sourceBackedManual =
                assertThrows<
                    ProductApiException
                > {
                    service.resolveRequirement(
                        ResolveOnboardingRequirementCommand(
                            operationId =
                                UUID.randomUUID(),
                            onboardingCaseId =
                                pinned.caseId,
                            baseVersion =
                                pinned.caseVersion,
                            requirementKey =
                                "MOBILE",
                            resolution =
                                OnboardingManualResolutionType
                                    .SATISFIED,
                            reason = null,
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-manual-source",
                        ),
                    )
                }
            assertEquals(
                "ONBOARDING_MANUAL_RESOLUTION_NOT_ALLOWED",
                sourceBackedManual.code,
            )

            val waiverWithoutReason =
                assertThrows<
                    ProductApiException
                > {
                    service.resolveRequirement(
                        ResolveOnboardingRequirementCommand(
                            operationId =
                                UUID.randomUUID(),
                            onboardingCaseId =
                                pinned.caseId,
                            baseVersion =
                                pinned.caseVersion,
                            requirementKey =
                                "HR_CONFIRM",
                            resolution =
                                OnboardingManualResolutionType
                                    .WAIVED,
                            reason = null,
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-waiver-no-reason",
                        ),
                    )
                }
            assertEquals(
                "ONBOARDING_WAIVER_REASON_REQUIRED",
                waiverWithoutReason.code,
            )

            val resolved =
                service.resolveRequirement(
                    ResolveOnboardingRequirementCommand(
                        operationId =
                            UUID.randomUUID(),
                        onboardingCaseId =
                            pinned.caseId,
                        baseVersion =
                            pinned.caseVersion,
                        requirementKey =
                            "HR_CONFIRM",
                        resolution =
                            OnboardingManualResolutionType
                                .SATISFIED,
                        reason = null,
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-manual-resolve",
                    ),
                )
            assertEquals(
                2,
                resolved.onboarding.caseVersion,
            )
            assertEquals(
                OnboardingRequirementStatus
                    .SATISFIED,
                status(
                    resolved.onboarding,
                    "HR_CONFIRM",
                ),
            )

            val blocked =
                assertThrows<
                    ProductApiException
                > {
                    service.activate(
                        ActivateEmployeeOnboardingCommand(
                            operationId =
                                UUID.randomUUID(),
                            onboardingCaseId =
                                resolved.onboarding
                                    .caseId,
                            caseBaseVersion = 2,
                            employeeBaseVersion = 1,
                            actorUserId =
                                ids.adminIdentity,
                            correlationId =
                                "corr-activate-blocked",
                        ),
                    )
                }
            assertEquals(
                "ONBOARDING_BLOCKERS_REMAIN",
                blocked.code,
            )

            satisfyAuthoritativeRequirements(
                jdbc = jdbc,
                ids = ids,
                at = clock.instant(),
            )

            val ready =
                service.adminCase(
                    actorUserId =
                        ids.adminIdentity,
                    employeeId =
                        ids.employeeId,
                )
            assertTrue(
                ready.blockingSatisfied,
            )
            assertEquals(
                OnboardingRequirementStatus
                    .SATISFIED,
                status(ready, "MOBILE"),
            )
            assertEquals(
                OnboardingRequirementStatus
                    .SATISFIED,
                status(ready, "IDENTITY"),
            )
            assertEquals(
                OnboardingRequirementStatus
                    .SATISFIED,
                status(ready, "ASSIGNMENT"),
            )
            assertEquals(
                OnboardingRequirementStatus
                    .SATISFIED,
                status(ready, "ID_COPY"),
            )
            assertEquals(
                OnboardingRequirementStatus
                    .SATISFIED,
                status(ready, "SAFETY_CERT"),
            )

            val own =
                service.own(
                    actorUserId =
                        ids.workerIdentity,
                    organizationId =
                        ids.organizationId,
                )
            assertEquals(
                ready.caseId,
                own.caseId,
            )
            assertTrue(
                own.requirements.all {
                    it.requirementKey in
                        setOf(
                            "MOBILE",
                            "IDENTITY",
                            "ASSIGNMENT",
                            "ID_COPY",
                            "SAFETY_CERT",
                            "HR_CONFIRM",
                        )
                },
            )

            val activationOperation =
                UUID.randomUUID()
            val activated =
                service.activate(
                    ActivateEmployeeOnboardingCommand(
                        operationId =
                            activationOperation,
                        onboardingCaseId =
                            ready.caseId,
                        caseBaseVersion =
                            ready.caseVersion,
                        employeeBaseVersion =
                            ready.employeeVersion,
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-activate",
                    ),
                )
            assertFalse(activated.replayed)
            assertEquals(
                OnboardingCaseState.ACTIVATED,
                activated.onboarding.state,
            )
            assertEquals(
                EmployeeState.ACTIVE,
                activated.onboarding
                    .employeeState,
            )

            val activationReplay =
                service.activate(
                    ActivateEmployeeOnboardingCommand(
                        operationId =
                            activationOperation,
                        onboardingCaseId =
                            ready.caseId,
                        caseBaseVersion =
                            ready.caseVersion,
                        employeeBaseVersion =
                            ready.employeeVersion,
                        actorUserId =
                            ids.adminIdentity,
                        correlationId =
                            "corr-activate",
                    ),
                )
            assertTrue(
                activationReplay.replayed,
            )
            assertEquals(
                OnboardingCaseState.ACTIVATED,
                activationReplay.onboarding
                    .state,
            )

            val otherOrgPolicy =
                assertThrows<
                    ProductApiException
                > {
                    service.start(
                        StartOnboardingCommand(
                            operationId =
                                UUID.randomUUID(),
                            employeeId =
                                ids.otherEmployeeId,
                            baseEmployeeVersion = 1,
                            onboardingPolicyRevisionId =
                                ids.policyRevisionTwoId,
                            actorUserId =
                                ids.otherAdminIdentity,
                            correlationId =
                                "corr-cross-org-policy",
                        ),
                    )
                }
            assertEquals(
                "ONBOARDING_POLICY_ORGANIZATION_MISMATCH",
                otherOrgPolicy.code,
            )

            val denied =
                OnboardingService(
                    persistence =
                        JdbcOnboardingPersistence(
                            jdbc,
                        ),
                    authorization =
                        FixturePeopleAuthorization(
                            allowed =
                                emptySet(),
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
                    audit =
                        JdbcAuditEventWriter(
                            jdbc,
                        ),
                    events = publisher,
                    clock = clock,
                )
            val deniedRead =
                assertThrows<
                    ProductApiException
                > {
                    denied.adminCase(
                        actorUserId =
                            ids.workerIdentity,
                        employeeId =
                            ids.employeeId,
                    )
                }
            assertEquals(
                "PEOPLE_ACCESS_DENIED",
                deniedRead.code,
            )

            assertTrue(
                published.any {
                    it is OnboardingStarted
                },
            )
            assertTrue(
                published.any {
                    it is EmployeeActivated
                },
            )
            assertEquals(
                1,
                jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM onboarding_case
                    WHERE employee_id = ?
                    """.trimIndent(),
                    Int::class.java,
                    ids.employeeId,
                ),
            )
        } finally {
            telemetry.close()
        }
    }

    private fun status(
        view: OnboardingCaseView,
        key: String,
    ): OnboardingRequirementStatus =
        requireNotNull(
            view.requirements
                .firstOrNull {
                    it.requirementKey == key
                },
        ).status

    private fun seedFixture(
        jdbc: JdbcTemplate,
        at: Instant,
    ): FixtureIds {
        val org =
            UUID.randomUUID()
        val otherOrg =
            UUID.randomUUID()
        val admin =
            UUID.randomUUID()
        val otherAdmin =
            UUID.randomUUID()
        val worker =
            UUID.randomUUID()
        val person =
            UUID.randomUUID()
        val otherPerson =
            UUID.randomUUID()
        val employee =
            UUID.randomUUID()
        val otherEmployee =
            UUID.randomUUID()
        val policy =
            UUID.randomUUID()
        val policyTwo =
            UUID.randomUUID()

        insertOrganization(
            jdbc,
            org,
            "HILTECH-" +
                org.toString().take(8),
            at,
        )
        insertOrganization(
            jdbc,
            otherOrg,
            "OTHER-" +
                otherOrg.toString().take(8),
            at,
        )
        insertIdentity(
            jdbc,
            admin,
            "admin-" + admin,
            at,
        )
        insertIdentity(
            jdbc,
            otherAdmin,
            "admin-" + otherAdmin,
            at,
        )
        insertIdentity(
            jdbc,
            worker,
            "worker-" + worker,
            at,
        )
        insertMembership(
            jdbc,
            org,
            admin,
            at,
        )
        insertMembership(
            jdbc,
            org,
            worker,
            at,
        )
        insertMembership(
            jdbc,
            otherOrg,
            otherAdmin,
            at,
        )
        insertPerson(
            jdbc,
            person,
            "موظف جديد",
            at,
        )
        insertPerson(
            jdbc,
            otherPerson,
            "موظف شركة أخرى",
            at,
        )
        insertEmployee(
            jdbc,
            employee,
            org,
            person,
            "ONB-" +
                employee.toString().take(8),
            at,
        )
        insertEmployee(
            jdbc,
            otherEmployee,
            otherOrg,
            otherPerson,
            "ONB-" +
                otherEmployee
                    .toString()
                    .take(8),
            at,
        )

        insertPolicy(
            jdbc = jdbc,
            id = policy,
            organizationId = org,
            revision = 1,
            createdBy = admin,
            at = at,
        )
        insertRequirement(
            jdbc,
            policy,
            "MOBILE",
            "PROFILE_FIELD",
            "رقم الموبايل",
            "EMPLOYEE",
            blocking = true,
            waiverAllowed = false,
            employeeMaySubmit = false,
            evidenceRequired = false,
            profileFieldCode = "MOBILE",
        )
        insertRequirement(
            jdbc,
            policy,
            "IDENTITY",
            "IDENTITY_READY",
            "حساب HILTECH",
            "HILTECH",
            true,
            false,
            false,
            false,
        )
        insertRequirement(
            jdbc,
            policy,
            "ASSIGNMENT",
            "WORKFORCE_ASSIGNMENT",
            "الفريق والمسؤول",
            "HILTECH",
            true,
            false,
            false,
            false,
        )
        insertRequirement(
            jdbc,
            policy,
            "ID_COPY",
            "EMPLOYEE_DOCUMENT",
            "مستند الهوية",
            "EMPLOYEE",
            true,
            false,
            true,
            true,
            documentTypeCode =
                "ID_COPY",
        )
        insertRequirement(
            jdbc,
            policy,
            "SAFETY_CERT",
            "CERTIFICATION",
            "شهادة السلامة",
            "HILTECH",
            false,
            false,
            false,
            false,
            certificationTypeCode =
                "SAFETY",
        )
        insertRequirement(
            jdbc,
            policy,
            "HR_CONFIRM",
            "MANUAL_CONFIRMATION",
            "مراجعة شؤون العاملين",
            "HILTECH",
            true,
            true,
            false,
            false,
            manualConfirmationCode =
                "HR_REVIEW",
        )

        return FixtureIds(
            organizationId = org,
            otherOrganizationId =
                otherOrg,
            adminIdentity = admin,
            otherAdminIdentity =
                otherAdmin,
            workerIdentity = worker,
            personId = person,
            employeeId = employee,
            otherEmployeeId =
                otherEmployee,
            policyRevisionId = policy,
            policyRevisionTwoId =
                policyTwo,
        )
    }

    private fun supersedePolicyAndInsertRevisionTwo(
        jdbc: JdbcTemplate,
        ids: FixtureIds,
        at: Instant,
    ) {
        jdbc.update(
            """
            UPDATE config_revision
            SET lifecycle_state = 'SUPERSEDED',
                version = version + 1
            WHERE id = ?
            """.trimIndent(),
            ids.policyRevisionId,
        )
        insertPolicy(
            jdbc = jdbc,
            id =
                ids.policyRevisionTwoId,
            organizationId =
                ids.organizationId,
            revision = 2,
            createdBy =
                ids.adminIdentity,
            at = at,
        )
        insertRequirement(
            jdbc,
            ids.policyRevisionTwoId,
            "EMAIL",
            "PROFILE_FIELD",
            "البريد",
            "EMPLOYEE",
            true,
            false,
            false,
            false,
            profileFieldCode = "EMAIL",
        )
    }

    private fun satisfyAuthoritativeRequirements(
        jdbc: JdbcTemplate,
        ids: FixtureIds,
        at: Instant,
    ) {
        jdbc.update(
            """
            UPDATE person
            SET mobile = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
            """.trimIndent(),
            "01000000000",
            at.atOffset(ZoneOffset.UTC),
            ids.personId,
        )
        jdbc.update(
            """
            UPDATE user_identity
            SET person_id = ?,
                version = version + 1
            WHERE id = ?
            """.trimIndent(),
            ids.personId,
            ids.workerIdentity,
        )
        jdbc.update(
            """
            INSERT INTO workforce_assignment (
                id,
                organization_id,
                employee_id,
                team_id,
                role_code,
                role_label,
                reports_to_employee_id,
                state,
                effective_from,
                effective_to,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, NULL,
                'FIELD_TECH',
                'Field Technician',
                NULL,
                'ACTIVE',
                ?, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            ids.organizationId,
            ids.employeeId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )

        val documentId =
            UUID.randomUUID()
        val evidenceId =
            UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO evidence (
                id,
                organization_id,
                target_type,
                target_id,
                work_order_id,
                evidence_requirement_key,
                evidence_policy_id,
                evidence_policy_revision,
                evidence_type_code,
                content_type,
                original_file_name,
                size_bytes,
                sha256,
                captured_at,
                client_occurred_at,
                captured_by_user_id,
                source_device_id,
                instruction_revision,
                work_order_version_at_capture,
                storage_state,
                object_key_ref,
                finalized_at,
                classification_code,
                client_visibility_mode,
                supersedes_evidence_id,
                created_at,
                version,
                security_scan_class
            )
            VALUES (
                ?, ?,
                'EMPLOYEE_DOCUMENT',
                ?, NULL,
                'ONBOARDING_ID_COPY',
                NULL, NULL,
                'HR_DOCUMENT',
                'application/pdf',
                'fixture.pdf',
                128,
                ?,
                ?,
                NULL,
                ?,
                NULL, NULL, NULL,
                'READY',
                'fixture/object',
                ?,
                'HIGHLY_RESTRICTED',
                'INTERNAL_ONLY',
                NULL,
                ?,
                1,
                'GENERATED_TRUSTED_FORMAT'
            )
            """.trimIndent(),
            evidenceId,
            ids.organizationId,
            documentId,
            "a".repeat(64),
            at.atOffset(ZoneOffset.UTC),
            ids.adminIdentity,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )
        jdbc.update(
            """
            INSERT INTO employee_document (
                id,
                organization_id,
                employee_id,
                document_type_code,
                document_label,
                issue_date,
                expiry_date,
                verification_state,
                verified_at,
                verified_by_user_id,
                evidence_id,
                retention_policy_code,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?,
                'ID_COPY',
                'Identity fixture',
                NULL, NULL,
                'VERIFIED',
                ?, ?,
                ?,
                NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            documentId,
            ids.organizationId,
            ids.employeeId,
            at.atOffset(ZoneOffset.UTC),
            ids.adminIdentity,
            evidenceId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )
        jdbc.update(
            """
            INSERT INTO certification (
                id,
                organization_id,
                employee_id,
                certification_type_code,
                certification_label,
                issuer,
                issued_at,
                valid_until,
                verification_state,
                verified_at,
                verified_by_user_id,
                employee_document_id,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?,
                'SAFETY',
                'Safety fixture',
                'HILTECH TEST',
                ?, ?,
                'VERIFIED',
                ?, ?,
                NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            ids.organizationId,
            ids.employeeId,
            at.minusSeconds(86_400)
                .atOffset(ZoneOffset.UTC),
            at.plusSeconds(86_400)
                .atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
            ids.adminIdentity,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
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
                ?, ?, ?, ?,
                'HILTECH',
                'ACTIVE',
                ?, 1
            )
            """.trimIndent(),
            id,
            code,
            code,
            code,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertIdentity(
        jdbc: JdbcTemplate,
        id: UUID,
        subject: String,
        at: Instant,
    ) {
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
                last_authenticated_at,
                version
            )
            VALUES (
                ?,
                'https://id.test',
                ?,
                NULL,
                'ACTIVE',
                NULL,
                ?,
                NULL,
                1
            )
            """.trimIndent(),
            id,
            subject,
            at.atOffset(ZoneOffset.UTC),
        )
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
                'INTERNAL',
                NULL,
                'ACTIVE',
                ?, NULL,
                NULL,
                1
            )
            """.trimIndent(),
            UUID.randomUUID(),
            organizationId,
            identityId,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertPerson(
        jdbc: JdbcTemplate,
        id: UUID,
        name: String,
        at: Instant,
    ) {
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
            name,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertEmployee(
        jdbc: JdbcTemplate,
        id: UUID,
        organizationId: UUID,
        personId: UUID,
        code: String,
        at: Instant,
    ) {
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
                'PREBOARDING',
                NULL, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            id,
            organizationId,
            personId,
            code,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun insertPolicy(
        jdbc: JdbcTemplate,
        id: UUID,
        organizationId: UUID,
        revision: Int,
        createdBy: UUID,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO config_revision (
                id,
                scope_type,
                scope_organization_id,
                family,
                code,
                name,
                description,
                lifecycle_state,
                revision_number,
                effective_from,
                effective_to,
                supersedes_id,
                change_reason,
                created_at,
                created_by,
                activated_at,
                activated_by,
                version
            )
            VALUES (
                ?,
                'ORGANIZATION',
                ?,
                'onboarding-policies',
                'EMPLOYEE_BASELINE',
                'Employee onboarding',
                NULL,
                'ACTIVE',
                ?,
                ?,
                NULL,
                NULL,
                NULL,
                ?,
                ?,
                ?,
                ?,
                1
            )
            """.trimIndent(),
            id,
            organizationId,
            revision,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
            createdBy,
            at.atOffset(ZoneOffset.UTC),
            createdBy,
        )
        jdbc.update(
            """
            INSERT INTO onboarding_policy (
                config_revision_id
            )
            VALUES (?)
            """.trimIndent(),
            id,
        )
    }

    private fun insertRequirement(
        jdbc: JdbcTemplate,
        policyId: UUID,
        key: String,
        type: String,
        label: String,
        responsibility: String,
        blocking: Boolean,
        waiverAllowed: Boolean,
        employeeMaySubmit: Boolean,
        evidenceRequired: Boolean,
        profileFieldCode: String? = null,
        documentTypeCode: String? = null,
        certificationTypeCode: String? = null,
        manualConfirmationCode: String? = null,
    ) {
        jdbc.update(
            """
            INSERT INTO onboarding_policy_requirement (
                id,
                config_revision_id,
                requirement_key,
                requirement_type,
                label,
                responsibility,
                blocking,
                waiver_allowed,
                self_service_visible,
                employee_may_submit,
                evidence_required,
                profile_field_code,
                document_type_code,
                certification_type_code,
                manual_confirmation_code,
                sort_order
            )
            VALUES (
                ?, ?, ?, ?, ?, ?,
                ?, ?,
                true,
                ?, ?,
                ?, ?, ?, ?,
                ?
            )
            """.trimIndent(),
            UUID.randomUUID(),
            policyId,
            key,
            type,
            label,
            responsibility,
            blocking,
            waiverAllowed,
            employeeMaySubmit,
            evidenceRequired,
            profileFieldCode,
            documentTypeCode,
            certificationTypeCode,
            manualConfirmationCode,
            key.hashCode()
                .and(Int.MAX_VALUE),
        )
    }

    private data class FixtureIds(
        val organizationId: UUID,
        val otherOrganizationId: UUID,
        val adminIdentity: UUID,
        val otherAdminIdentity: UUID,
        val workerIdentity: UUID,
        val personId: UUID,
        val employeeId: UUID,
        val otherEmployeeId: UUID,
        val policyRevisionId: UUID,
        val policyRevisionTwoId: UUID,
    )

    private class FixturePeopleAuthorization(
        private val allowed:
            Set<Pair<UUID, UUID>>,
    ) : PeopleAuthorizationPort {
        override fun canManagePeople(
            actorUserId: UUID,
            organizationId: UUID,
        ): Boolean =
            actorUserId to organizationId in
                allowed

        override fun canViewDirectory(
            actorUserId: UUID,
            organizationId: UUID,
        ): Boolean =
            actorUserId to organizationId in
                allowed
    }
}
