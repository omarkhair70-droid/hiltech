package com.hiltech.server.payroll

import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.people.JdbcPeoplePersistence
import com.hiltech.server.security.JdbcRoleTeamSourceAuthority
import java.math.BigDecimal
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class Payroll9bDraftPostgresContractTest {
    @Test
    fun draftsRequireApprovedPolicySourcePreserveRevisionsAndNeverApproveOrPay() {
        assumeTrue(System.getenv("HILTECH_PAYROLL_9A_CONTRACT_TEST") == "1")
        val url = System.getenv("HILTECH_DB_URL") ?: "jdbc:postgresql://localhost:5432/hiltech"
        val username = System.getenv("HILTECH_DB_USER") ?: "hiltech"
        val password = System.getenv("HILTECH_DB_PASSWORD") ?: "hiltech"
        val path = System.getenv("HILTECH_MIGRATIONS_PATH") ?: "database/migrations"
        Flyway.configure().dataSource(url, username, password)
            .locations("filesystem:" + path).load().migrate()

        val datasource = DriverManagerDataSource(url, username, password)
        val jdbc = JdbcTemplate(datasource)
        val tx = TransactionTemplate(DataSourceTransactionManager(datasource))
        val at = Instant.parse("2026-11-23T12:00:00Z")
        val clock = Clock.fixed(at, ZoneOffset.UTC)
        val actor = UUID.randomUUID()
        val org = UUID.randomUUID()
        val person = UUID.randomUUID()
        val employee = UUID.randomUUID()
        val employment = UUID.randomUUID()
        val time = at.atOffset(ZoneOffset.UTC)
        jdbc.update(
            """
            INSERT INTO organization (
                id, organization_code, legal_name, display_name,
                organization_type, status, created_at
            ) VALUES (?, ?, 'Synthetic Legal', 'Synthetic Company', 'HILTECH', 'ACTIVE', ?)
            """.trimIndent(),
            org, "P9B_" + org.toString().substring(0, 12).uppercase(), time,
        )
        jdbc.update(
            """
            INSERT INTO user_identity (
                id, auth_provider, auth_subject, status, primary_organization_id, created_at
            ) VALUES (?, 'payroll-test', ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            actor, actor.toString(), org, time,
        )
        jdbc.update(
            "INSERT INTO person (id, display_name, created_at, updated_at) VALUES (?, 'Synthetic Staff', ?, ?)",
            person, time, time,
        )
        jdbc.update(
            """
            INSERT INTO employee (
                id, organization_id, person_id, employee_code, state,
                hire_date, created_at, updated_at
            ) VALUES (?, ?, ?, 'P9B_TEST', 'ACTIVE', ?, ?, ?)
            """.trimIndent(),
            employee, org, person, LocalDate.parse("2026-10-01"), time, time,
        )
        jdbc.update(
            """
            INSERT INTO employment (
                id, employee_id, employment_type_code, start_date, state,
                created_at, updated_at
            ) VALUES (?, ?, 'FULL_TIME', ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            employment, employee, LocalDate.parse("2026-10-01"), time, time,
        )

        // A historical assignment ending mid-period remains valid for that
        // period; do not compare against today's ACTIVE assignment instead.
        val historicalAssignment = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO workforce_assignment (
                id, organization_id, employee_id, role_code, state,
                effective_from, effective_to, created_at, updated_at
            ) VALUES (?, ?, ?, 'TECHNICIAN', 'ENDED', ?, ?, ?, ?)
            """.trimIndent(),
            historicalAssignment, org, employee,
            Instant.parse("2026-11-01T00:00:00Z").atOffset(ZoneOffset.UTC),
            Instant.parse("2026-11-12T20:00:00Z").atOffset(ZoneOffset.UTC),
            time, time,
        )

        jdbc.update("UPDATE user_identity SET person_id = ? WHERE id = ?", person, actor)
        val membership = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO organization_membership (
                id, organization_id, user_identity_id, membership_type,
                state, valid_from
            ) VALUES (?, ?, ?, 'EMPLOYEE', 'ACTIVE', ?)
            """.trimIndent(),
            membership, org, actor, time.minusDays(1),
        )
        val permission = object : Payroll9aAuthorityPort {
            override fun canRead(actorId: UUID, organizationId: UUID) =
                actorId == actor && organizationId == org
            override fun canPrepare(actorId: UUID, organizationId: UUID) =
                canRead(actorId, organizationId)
            override fun canReview(actorId: UUID, organizationId: UUID) =
                canRead(actorId, organizationId)
        }
        val a = Payroll9aService(jdbc, permission, AuditEventWriter.NOOP, clock)
        val periodId = tx.execute {
            UUID.fromString(a.create(
                actor, Payroll9aPeriodCommand(
                    UUID.randomUUID(), org, "P9B_2026_11",
                    LocalDate.parse("2026-11-01"),
                    LocalDate.parse("2026-11-30"),
                    LocalDate.parse("2026-11-22"), null,
                ), "p9b-test",
            ).first.period.periodId)
        }!!
        tx.execute { a.captureInputs(actor, periodId, "p9b-test") }

        val noSource = Payroll9bDraftService(
            jdbc, a, permission,
            StaticListableBeanFactory().getBeanProvider(PayrollApprovedInputSourcePort::class.java),
            AuditEventWriter.NOOP, clock,
        )
        assertThrows<ProductApiException> {
            tx.execute { noSource.create(periodId, UUID.randomUUID(), actor, "p9b-test") }
        }
        val policy = PayrollValidatedPolicy(
            organizationId = org, policyVersionId = UUID.randomUUID(),
            policyVersion = 1, currency = "EGP", fractionalDigits = 2,
            roundingMode = RoundingMode.HALF_EVEN,
            companyAuthorizationReference = "synthetic-p9b-policy",
            statutoryPolicyReference = "synthetic-statutory-reviewed",
            statutoryVerified = true,
            deductionLimitPolicyReference = null, deductionLimitsVerified = false,
        )
        val component = PayrollApprovedComponent(
            organizationId = org, employeeId = employee, componentCode = "SYNTHETIC_BASE",
            kind = PayrollComponentKind.EARNING,
            sourceDomain = "SYNTHETIC_FIXTURE", sourceId = UUID.randomUUID(),
            sourceVersion = 1, sourceApprovalReference = "SYNTHETIC_APPROVAL",
            approval = PayrollInputApproval.APPROVED,
            currency = "EGP", amount = "100.005",
        )
        var attestationVersion = 1L
        val sources = object : PayrollApprovedInputSourcePort {
            override fun verifiedFor(
                organizationId: UUID, payrollPeriodId: UUID, employeeId: UUID,
            ) = PayrollVerifiedEmployeeInputs(
                coverageReceipts = PayrollInputCoverageDomain.entries.map { domain ->
                    PayrollInputCoverageReceipt(
                        organizationId = org,
                        payrollPeriodId = periodId,
                        employeeId = employee,
                        domain = domain,
                        sourceId = UUID.nameUUIDFromBytes(
                            ("synthetic-covered-domain:" + domain.name).toByteArray(),
                        ),
                        sourceVersion = attestationVersion,
                        approval = PayrollInputApproval.APPROVED,
                        sourceApprovalReference = "synthetic-reviewed-" + domain.name,
                        complete = true,
                    )
                },
                components = listOf(component),
            )

            override fun policyFor(
                organizationId: UUID, payrollPeriodId: UUID,
            ) = policy
        }
        val factory = StaticListableBeanFactory(mapOf("payrollSources" to sources))
        val service = Payroll9bDraftService(
            jdbc, a, permission,
            factory.getBeanProvider(PayrollApprovedInputSourcePort::class.java),
            AuditEventWriter.NOOP, clock,
        )
        val operation = UUID.randomUUID()
        val first = tx.execute { service.create(periodId, operation, actor, "p9b-test") }!!
        assertFalse(first.second)
        assertEquals("DRAFT", first.first.state)
        assertEquals(1, first.first.generation)
        assertEquals(1, first.first.lines.size)
        assertEquals("100.000000", first.first.net)
        assertFalse(first.first.approvalAllowed)
        assertFalse(first.first.paymentAllowed)
        val again = tx.execute { service.create(periodId, operation, actor, "p9b-test") }!!
        assertTrue(again.second)
        assertEquals(first.first.runVersionId, again.first.runVersionId)
        assertThrows<ProductApiException> {
            service.read(UUID.randomUUID(), UUID.fromString(first.first.runVersionId))
        }

        val newer = tx.execute {
            service.create(periodId, UUID.randomUUID(), actor, "p9b-test")
        }!!
        assertEquals(2, newer.first.generation)
        assertNotNull(newer.first.sourceFingerprint)
        val history = service.history(actor, periodId)
        assertEquals(2, history.items.size)
        assertEquals(2, history.items.first().generation)
        assertEquals(1, history.items.first().employeeCount)
        val guard = PayrollRunApprovalVersionSource(
            jdbc, factory.getBeanProvider(PayrollApprovedInputSourcePort::class.java),
        )
        val previousSubject = ApprovalSubjectRef(
            org, "PAYROLL_RUN_VERSION", UUID.fromString(first.first.runVersionId), 1,
        )
        val currentSubject = ApprovalSubjectRef(
            org, "PAYROLL_RUN_VERSION", UUID.fromString(newer.first.runVersionId), 1,
        )
        assertFalse(guard.isCurrent(previousSubject))
        assertTrue(guard.isCurrent(currentSubject))
        // A future assignment after the cutoff must not rewrite history.
        val futureAssignment = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO workforce_assignment (
                id, organization_id, employee_id, role_code, state,
                effective_from, created_at, updated_at
            ) VALUES (?, ?, ?, 'TECHNICIAN', 'ACTIVE', ?, ?, ?)
            """.trimIndent(),
            futureAssignment, org, employee,
            Instant.parse("2026-11-24T09:00:00Z").atOffset(ZoneOffset.UTC),
            time, time,
        )
        assertTrue(guard.isCurrent(currentSubject))
        assertFalse(a.detail(actor, periodId).employees.single().sourceChanged)
        assertFalse(guard.isCurrent(currentSubject.copy(organizationId = UUID.randomUUID())))
        assertFalse(guard.isCurrent(currentSubject.copy(subjectVersion = 2)))
        assertThrows<Exception> {
            jdbc.update("UPDATE payroll_run_version SET gross = 999 WHERE id = ?",
                UUID.fromString(first.first.runVersionId))
        }
        assertThrows<Exception> {
            jdbc.update("DELETE FROM payroll_run_employee_line WHERE run_version_id = ?",
                UUID.fromString(first.first.runVersionId))
        }

        // Routine payroll release is signed by a policy-authorized reviewer,
        // does not create a payment, and cannot bypass the latest source guard.
        // Real database-backed company policy fixture; no implicit global
        // owner escalation and no production policy seeded anywhere.
        jdbc.update(
            """
            INSERT INTO approval_policy_version (
                id, organization_id, policy_key, version_number,
                lifecycle_state, approval_required, step_mode,
                effective_from, created_by_user_id, created_at
            ) VALUES (?, ?, 'PAYROLL_REVIEW_TEST', 1, 'ACTIVE',
                     false, 'NO_APPROVAL_REQUIRED', ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), org, time.minusDays(2), actor, time,
        )
        // The API check is not the only authority wall: PostgreSQL itself
        // also verifies a current Payroll REVIEW grant and company membership.
        jdbc.update(
            """
            INSERT INTO payroll_authority_binding (
                id, organization_id, identity_id, capability,
                valid_from, active, source_approval_ref, created_at
            ) VALUES (?, ?, ?, 'REVIEW', ?, true, 'SYNTHETIC_APPROVED_PAYROLL_REVIEW', ?)
            """.trimIndent(),
            UUID.randomUUID(), org, actor, time.minusDays(1), time,
        )
        var policyAvailable = false
        var decisionVersion = 1
        var permittedSubject = currentSubject
        var verifiedCorrection: PayrollVerifiedCorrectionAuthorization? = null
        val correctionProvider = object : PayrollVerifiedCorrectionSourcePort {
            override fun verifiedFor(
                organizationId: UUID, originalRunVersionId: UUID,
                replacementRunVersionId: UUID,
            ): PayrollVerifiedCorrectionAuthorization? = verifiedCorrection
        }
        val policyDecision = object : PayrollRunReleaseDecisionPort {
            override fun resolve(
                actorId: UUID, subject: ApprovalSubjectRef, policyKey: String,
            ): PayrollRunPolicyDecision? =
                if (policyAvailable && actorId == actor &&
                    subject == permittedSubject && policyKey == "PAYROLL_REVIEW_TEST"
                ) PayrollRunPolicyDecision(
                    "ROUTINE_AUTHORIZED", policyKey, decisionVersion, null,
                ) else null
        }
        val releases = Payroll9bRunReleaseService(
            jdbc, service, permission, guard, policyDecision,
            Payroll9cCorrectionService(
                jdbc,
                StaticListableBeanFactory(mapOf("verifiedCorrection" to correctionProvider))
                    .getBeanProvider(PayrollVerifiedCorrectionSourcePort::class.java),
                AuditEventWriter.NOOP, clock,
            ),
            AuditEventWriter.NOOP, clock, "PAYROLL_REVIEW_TEST",
        )
        assertThrows<ProductApiException> {
            tx.execute {
                releases.release(actor, currentSubject.subjectId, UUID.randomUUID(), "p9b-release")
            }
        }
        policyAvailable = true
        assertThrows<ProductApiException> {
            tx.execute {
                releases.release(actor, previousSubject.subjectId, UUID.randomUUID(), "p9b-release")
            }
        }
        val userWithoutPayrollGrant = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO user_identity (
                id, auth_provider, auth_subject, status, primary_organization_id, created_at
            ) VALUES (?, 'payroll-test', ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            userWithoutPayrollGrant, userWithoutPayrollGrant.toString(), org, time,
        )
        jdbc.update(
            """
            INSERT INTO organization_membership (
                id, organization_id, user_identity_id, membership_type,
                state, valid_from
            ) VALUES (?, ?, ?, 'EMPLOYEE', 'ACTIVE', ?)
            """.trimIndent(),
            UUID.randomUUID(), org, userWithoutPayrollGrant,
            time.minusDays(2),
        )
        // A direct DB write using a valid active company user still cannot
        // create a payroll release without the separately approved REVIEW grant.
        assertThrows<Exception> {
            jdbc.update(
                """
                INSERT INTO payroll_run_release (
                    id, operation_id, organization_id, run_version_id,
                    run_source_fingerprint, policy_key, policy_version,
                    decision_path, released_by_user_id, released_at
                ) VALUES (?, ?, ?, ?, ?, 'PAYROLL_REVIEW_TEST', 1,
                          'ROUTINE_AUTHORIZED', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(), UUID.randomUUID(), org,
                UUID.fromString(newer.first.runVersionId),
                newer.first.sourceFingerprint, userWithoutPayrollGrant, time,
            )
        }
        // DB evaluates reviewer authorization at real execution time,
        // not at a caller-chosen historical event timestamp. Expired
        // grants must not be resurrected by backdating released_at.
        jdbc.update(
            """
            INSERT INTO payroll_authority_binding (
                id, organization_id, identity_id, capability,
                valid_from, valid_until, active, source_approval_ref, created_at
            ) VALUES (?, ?, ?, 'REVIEW', ?, ?, true, 'SYNTHETIC_EXPIRED_REVIEW', ?)
            """.trimIndent(),
            UUID.randomUUID(), org, userWithoutPayrollGrant,
            time.minusDays(2), time.minusDays(1), time,
        )
        assertThrows<Exception> {
            jdbc.update(
                """
                INSERT INTO payroll_run_release (
                    id, operation_id, organization_id, run_version_id,
                    run_source_fingerprint, policy_key, policy_version,
                    decision_path, released_by_user_id, released_at
                ) VALUES (?, ?, ?, ?, ?, 'PAYROLL_REVIEW_TEST', 1,
                          'ROUTINE_AUTHORIZED', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(), UUID.randomUUID(), org,
                UUID.fromString(newer.first.runVersionId),
                newer.first.sourceFingerprint, userWithoutPayrollGrant,
                time.minusDays(1).minusSeconds(1),
            )
        }
        val releaseOperation = UUID.randomUUID()
        val released = tx.execute {
            releases.release(actor, currentSubject.subjectId, releaseOperation, "p9b-release")
        }!!
        assertEquals("RELEASED_FOR_FINANCE_REVIEW", released.status)
        assertEquals("ROUTINE_AUTHORIZED", released.decisionPath)
        assertFalse(released.replayed)
        assertTrue(tx.execute {
            releases.release(actor, currentSubject.subjectId, releaseOperation, "p9b-release")
        }!!.replayed)
        assertThrows<ProductApiException> {
            tx.execute {
                releases.release(UUID.randomUUID(), currentSubject.subjectId,
                    UUID.randomUUID(), "p9b-release")
            }
        }
        assertThrows<ProductApiException> {
            tx.execute {
                releases.release(actor, currentSubject.subjectId,
                    UUID.randomUUID(), "p9b-release")
            }
        }
        assertThrows<Exception> {
            jdbc.update(
                "UPDATE payroll_run_release SET policy_version = 99 WHERE id = ?",
                UUID.fromString(released.releaseId),
            )
        }
        // A direct SQL attempt cannot release an obsolete generation even
        // with a current routine policy and a legitimate organization user.
        assertThrows<Exception> {
            jdbc.update(
                """
                INSERT INTO payroll_run_release (
                    id, operation_id, organization_id, run_version_id,
                    run_source_fingerprint, policy_key, policy_version,
                    decision_path, released_by_user_id, released_at
                ) VALUES (?, ?, ?, ?, ?, 'PAYROLL_REVIEW_TEST', 1,
                          'ROUTINE_AUTHORIZED', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(), UUID.randomUUID(), org,
                UUID.fromString(first.first.runVersionId),
                first.first.sourceFingerprint, actor, time,
            )
        }
        val realPublication = ApprovalBackedPayrollPublication(
            guard, policyDecision, jdbc, "PAYROLL_REVIEW_TEST",
        )
        assertTrue(realPublication.canPublish(
            org, currentSubject.subjectId, newer.first.sourceFingerprint,
        ))
        assertFalse(realPublication.canPublish(
            org, currentSubject.subjectId, "invalid-snapshot",
        ))
        assertFalse(realPublication.canPublish(
            UUID.randomUUID(), currentSubject.subjectId, newer.first.sourceFingerprint,
        ))
        // Finance's canonical REVIEW INTAKE is still unimplemented in real
        // 8C. The test substitutes a synthetic in-transaction accepting port
        // and verifies exact payout-manifest integrity and replay semantics.
        val reviewPublisher = StaticListableBeanFactory(
            mapOf("approvedRelease" to realPublication),
        ).getBeanProvider(PayrollRunPublicationPort::class.java)
        val noFinanceHandoff = Payroll9cFinanceHandoffService(
            jdbc, service, permission, reviewPublisher,
            StaticListableBeanFactory().getBeanProvider(
                PayrollFinanceReviewIntakePort::class.java,
            ),
            AuditEventWriter.NOOP, clock,
        )
        assertThrows<ProductApiException> {
            tx.execute {
                noFinanceHandoff.handoff(
                    actor, currentSubject.subjectId, UUID.randomUUID(), "synthetic-finance-handoff",
                )
            }
        }
        val acceptedOperations = mutableMapOf<UUID, PayrollFinanceReviewReceipt>()
        var acceptedManifest: PayrollFinanceReviewManifest? = null
        val reviewIntake = object : PayrollFinanceReviewIntakePort {
            override fun acceptForReview(
                manifest: PayrollFinanceReviewManifest,
            ): PayrollFinanceReviewReceipt {
                acceptedManifest = manifest
                val prior = acceptedOperations[manifest.operationId]
                if (prior != null) {
                    assertEquals(prior.manifestSha256, manifest.manifestSha256)
                    return prior.copy(replayed = true)
                }
                val receipt = PayrollFinanceReviewReceipt(
                    organizationId = manifest.organizationId,
                    runVersionId = manifest.runVersionId,
                    releaseId = manifest.releaseId,
                    operationId = manifest.operationId,
                    financeReviewBatchId = UUID.randomUUID(),
                    manifestSha256 = manifest.manifestSha256,
                    currency = manifest.currency,
                    payableTotal = manifest.payableTotal,
                    lineCount = manifest.lines.size,
                    positiveTransferCount = manifest.positiveTransferCount,
                    state = "REVIEW_REQUIRED",
                    replayed = false,
                )
                acceptedOperations[manifest.operationId] = receipt
                return receipt
            }
        }
        val financeReviewHandoff = Payroll9cFinanceHandoffService(
            jdbc, service, permission, reviewPublisher,
            StaticListableBeanFactory(mapOf("syntheticFinanceIntake" to reviewIntake))
                .getBeanProvider(PayrollFinanceReviewIntakePort::class.java),
            AuditEventWriter.NOOP, clock,
        )
        val handoffOperation = UUID.randomUUID()
        val handoff = tx.execute {
            financeReviewHandoff.handoff(
                actor, currentSubject.subjectId, handoffOperation, "synthetic-finance-handoff",
            )
        }!!
        assertEquals("REVIEW_REQUIRED", handoff.state)
        assertFalse(handoff.bankExecutionAvailable)
        assertFalse(handoff.replayed)
        assertEquals(1, handoff.employeeCount)
        assertEquals(1, handoff.transferCandidates)
        assertEquals(newer.first.sourceFingerprint, acceptedManifest?.runSourceFingerprint)
        assertEquals(newer.first.net, acceptedManifest?.payableTotal?.toPlainString())
        assertTrue(tx.execute {
            financeReviewHandoff.handoff(
                actor, currentSubject.subjectId, handoffOperation, "synthetic-finance-handoff",
            )
        }!!.replayed)
        assertEquals(1, acceptedOperations.size)
        assertThrows<ProductApiException> {
            tx.execute {
                financeReviewHandoff.handoff(
                    UUID.randomUUID(), currentSubject.subjectId, UUID.randomUUID(),
                    "synthetic-finance-handoff",
                )
            }
        }

        decisionVersion = 2
        assertFalse(realPublication.canPublish(
            org, currentSubject.subjectId, newer.first.sourceFingerprint,
        ))
        decisionVersion = 1

        // 9C: even with a draft ledger, no payslip is visible unless a
        // separately approved publication source and Finance-result source exist.
        val notPublished = Payroll9cPayslipService(
            jdbc, JdbcPeoplePersistence(jdbc), JdbcRoleTeamSourceAuthority(jdbc),
            StaticListableBeanFactory().getBeanProvider(PayrollRunPublicationPort::class.java),
            StaticListableBeanFactory().getBeanProvider(PayrollFinancePaymentResultsPort::class.java),
            clock,
        )
        assertTrue(notPublished.own(actor, org, "p9c-private").items.isEmpty())
        val publishedRunId = UUID.fromString(newer.first.runVersionId)
        val instructionId = UUID.randomUUID()
        val approval: PayrollRunPublicationPort = realPublication
        val financeInstruction = PayrollExpectedPayment(
            organizationId = org, runVersionId = publishedRunId,
            employeeId = employee, financeInstructionId = instructionId,
            financeInstructionVersion = 1,
            amount = BigDecimal("100.00"), currency = "EGP",
        )
        var expectedFinance = listOf(financeInstruction)
        var paymentEvents = emptyList<PayrollFinanceResult>()
        val finance = object : PayrollFinancePaymentResultsPort {
            override fun expectedPayments(organizationId: UUID, runVersionId: UUID) =
                expectedFinance
            override fun paymentEvents(organizationId: UUID, runVersionId: UUID) =
                paymentEvents
        }
        val self = Payroll9cPayslipService(
            jdbc, JdbcPeoplePersistence(jdbc), JdbcRoleTeamSourceAuthority(jdbc),
            StaticListableBeanFactory(mapOf("approvedPublication" to approval))
                .getBeanProvider(PayrollRunPublicationPort::class.java),
            StaticListableBeanFactory(mapOf("financeReference" to finance))
                .getBeanProvider(PayrollFinancePaymentResultsPort::class.java),
            clock,
        )
        val financeReviewer = Payroll9cRunFinanceStatusService(
            service, permission,
            StaticListableBeanFactory(mapOf("approvedPublication" to approval))
                .getBeanProvider(PayrollRunPublicationPort::class.java),
            StaticListableBeanFactory(mapOf("financeReference" to finance))
                .getBeanProvider(PayrollFinancePaymentResultsPort::class.java),
        )
        val notReleased = Payroll9cRunFinanceStatusService(
            service, permission,
            StaticListableBeanFactory().getBeanProvider(PayrollRunPublicationPort::class.java),
            StaticListableBeanFactory().getBeanProvider(PayrollFinancePaymentResultsPort::class.java),
        )
        assertEquals(
            "NOT_RELEASED",
            notReleased.status(actor, publishedRunId).status,
        )
        val unknownFinance = financeReviewer.status(actor, publishedRunId)
        assertEquals("REQUIRES_REVIEW", unknownFinance.status)
        assertEquals(1, unknownFinance.unresolvedCount)
        assertEquals("UNKNOWN", unknownFinance.employeeOutcomes.single().outcome)
        assertThrows<ProductApiException> {
            financeReviewer.status(UUID.randomUUID(), publishedRunId)
        }
        val pending = self.own(actor, org, "p9c-private")
        assertEquals(1, pending.items.size)
        assertEquals(employee.toString(), pending.items.single().employeeId)
        assertEquals(newer.first.runVersionId, pending.items.single().runVersionId)
        assertEquals("UNKNOWN", pending.items.single().paymentStatus)
        paymentEvents = listOf(
            PayrollFinanceResult(
                organizationId = org, runVersionId = publishedRunId,
                employeeId = employee, financeInstructionId = instructionId,
                financeInstructionVersion = 1, outcomeEventId = UUID.randomUUID(),
                outcome = PayrollFinanceOutcome.SUCCESS, financeSourceVersion = 1,
                financeEvidenceReference = "synthetic-confirmed-finance-proof",
                observedAt = at,
            ),
        )
        assertEquals("SUCCESS", self.own(actor, org, "p9c-private").items.single().paymentStatus)
        assertEquals("PAID", financeReviewer.status(actor, publishedRunId).status)
        // A recorded SUCCESS event never excuses a mismatched Finance batch:
        // both own payslip and admin run-status must fail closed.
        expectedFinance = listOf(financeInstruction.copy(amount = BigDecimal("101.00")))
        assertThrows<ProductApiException> {
            self.own(actor, org, "p9c-private")
        }
        assertThrows<ProductApiException> {
            financeReviewer.status(actor, publishedRunId)
        }
        expectedFinance = listOf(financeInstruction.copy(
            financeInstructionVersion = 0,
        ))
        assertThrows<ProductApiException> {
            self.own(actor, org, "p9c-private")
        }
        expectedFinance = listOf(financeInstruction)
        assertEquals("SUCCESS", self.own(actor, org, "p9c-private").items.single().paymentStatus)
        paymentEvents = paymentEvents + paymentEvents.first().copy(
            outcomeEventId = UUID.randomUUID(),
            outcome = PayrollFinanceOutcome.RETURNED, financeSourceVersion = 2,
            observedAt = at.plusSeconds(30),
        )
        assertEquals("RETURNED", self.own(actor, org, "p9c-private").items.single().paymentStatus)
        assertEquals("REQUIRES_REVIEW", financeReviewer.status(actor, publishedRunId).status)
        // An unapproved new draft cannot erase the already released
        // employee payslip while all authoritative source versions agree.
        val unapprovedCorrection = tx.execute {
            service.create(periodId, UUID.randomUUID(), actor, "p9b-review-only")
        }!!
        assertEquals(3, unapprovedCorrection.first.generation)
        assertFalse(guard.isCurrent(currentSubject))
        assertTrue(guard.isCurrentForPublication(currentSubject))
        assertEquals(
            newer.first.runVersionId,
            self.own(actor, org, "p9c-private").items.single().runVersionId,
        )
        assertEquals("RETURNED", self.own(actor, org, "p9c-private").items.single().paymentStatus)

        // A same-source draft cannot masquerade as a correction even
        // when its reviewer has valid Payroll REVIEW and company policy.
        permittedSubject = ApprovalSubjectRef(
            org, "PAYROLL_RUN_VERSION",
            UUID.fromString(unapprovedCorrection.first.runVersionId), 1,
        )
        assertThrows<ProductApiException> {
            tx.execute {
                releases.release(
                    actor, UUID.fromString(unapprovedCorrection.first.runVersionId),
                    UUID.randomUUID(), "p9c-correction",
                )
            }
        }
        // Changing an authoritative FINANCE coverage version makes the
        // correction materially different without fabricating salary figures.
        attestationVersion = 2L
        val corrected = tx.execute {
            service.create(periodId, UUID.randomUUID(), actor, "p9c-correction")
        }!!
        assertEquals(4, corrected.first.generation)
        assertFalse(newer.first.sourceFingerprint == corrected.first.sourceFingerprint)
        val correctedRun = UUID.fromString(corrected.first.runVersionId)
        permittedSubject = ApprovalSubjectRef(org, "PAYROLL_RUN_VERSION", correctedRun, 1)
        assertTrue(guard.isCurrent(permittedSubject))
        assertThrows<ProductApiException> {
            tx.execute { releases.release(actor, correctedRun, UUID.randomUUID(), "p9c-correction") }
        }
        // Finance's signed reversal/adjustment authority is an independent
        // source. Only after it exists can the new version be released.
        verifiedCorrection = PayrollVerifiedCorrectionAuthorization(
            organizationId = org,
            originalRunVersionId = publishedRunId,
            replacementRunVersionId = correctedRun,
            originalSourceFingerprint = newer.first.sourceFingerprint,
            replacementSourceFingerprint = corrected.first.sourceFingerprint,
            approval = PayrollInputApproval.APPROVED,
            reasonCode = "APPROVED_INPUT_CORRECTION",
            reasonEvidenceReference = "synthetic-approved-hr-correction",
            reasonEvidenceVersion = 1,
            financeDisposition = PayrollFinanceCorrectionDisposition.REVERSAL_VERIFIED,
            financeClearanceReference = "synthetic-finance-reversal-confirmed",
            financeClearanceVersion = 2,
        )
        // Even a database writer with a current Payroll REVIEW grant and
        // valid routine company policy cannot skip the HR+Finance correction
        // ledger by writing a second release directly.
        assertThrows<Exception> {
            jdbc.update(
                """
                INSERT INTO payroll_run_release (
                    id, operation_id, organization_id, run_version_id,
                    run_source_fingerprint, policy_key, policy_version,
                    decision_path, released_by_user_id, released_at
                ) VALUES (?, ?, ?, ?, ?, 'PAYROLL_REVIEW_TEST', 1,
                          'ROUTINE_AUTHORIZED', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(), UUID.randomUUID(), org,
                correctedRun, corrected.first.sourceFingerprint, actor, time,
            )
        }
        val correctionOperation = UUID.randomUUID()
        val correctedRelease = tx.execute {
            releases.release(actor, correctedRun, correctionOperation, "p9c-correction")
        }!!
        assertEquals("RELEASED_FOR_FINANCE_REVIEW", correctedRelease.status)
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM payroll_run_correction WHERE replacement_run_version_id = ?",
            Int::class.java, correctedRun,
        ))
        assertTrue(tx.execute {
            releases.release(actor, correctedRun, correctionOperation, "p9c-correction")
        }!!.replayed)
        // An historical retry of the ORIGINAL release must remain replayable.
        assertTrue(tx.execute {
            releases.release(actor, publishedRunId, releaseOperation, "p9b-release")
        }!!.replayed)
        assertFalse(realPublication.canPublish(
            org, publishedRunId, newer.first.sourceFingerprint,
        ))
        assertThrows<Exception> {
            jdbc.update(
                "DELETE FROM payroll_run_correction WHERE replacement_run_version_id = ?",
                correctedRun,
            )
        }
        assertThrows<Exception> {
            jdbc.update(
                """
                INSERT INTO payroll_run_correction (
                    id, operation_id, organization_id, original_run_version_id,
                    original_release_id, replacement_run_version_id,
                    original_source_fingerprint, replacement_source_fingerprint,
                    reason_code, reason_evidence_ref, reason_evidence_version,
                    finance_disposition, finance_clearance_ref, finance_clearance_version,
                    recorded_by_user_id, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'APPROVED_INPUT_CORRECTION',
                          'synthetic-source', 1, 'REVERSAL_VERIFIED',
                          'synthetic-finance', 1, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(), UUID.randomUUID(), org,
                UUID.fromString(first.first.runVersionId),
                UUID.fromString(released.releaseId),
                correctedRun, first.first.sourceFingerprint,
                corrected.first.sourceFingerprint, actor, time,
            )
        }

        // Unknown and former identities have no other employee's payslip.
        assertThrows<ProductApiException> {
            self.own(UUID.randomUUID(), org, "p9c-private")
        }
        jdbc.update("UPDATE organization_membership SET state = 'ENDED' WHERE id = ?", membership)
        assertThrows<ProductApiException> {
            self.own(actor, org, "p9c-private")
        }

        jdbc.update(
            "UPDATE workforce_assignment SET version = version + 1, updated_at = ? WHERE id = ?",
            time.plusSeconds(1), historicalAssignment,
        )
        assertFalse(guard.isCurrent(currentSubject))
        // Coverage change without any change to a monetary component
        // must also invalidate a previously released payroll run.
        attestationVersion = 2
        assertFalse(guard.isCurrentForPublication(currentSubject))
        jdbc.update("UPDATE employee SET version = version + 1 WHERE id = ?", employee)
        assertFalse(guard.isCurrent(currentSubject))
        assertThrows<ProductApiException> {
            tx.execute {
                service.create(periodId, UUID.randomUUID(), actor, "p9b-test")
            }
        }
    }
}
