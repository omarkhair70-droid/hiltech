package com.hiltech.server.platform.command

import com.hiltech.server.platform.http.ProductApiException
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IdempotentCommandPostgresContractTest {
    private val enabled =
        System.getenv(
            "HILTECH_COMMAND_RUNTIME_CONTRACT_TEST",
        ) == "1"
    private val url =
        System.getenv("HILTECH_DB_URL")
            ?: "jdbc:postgresql://localhost:5432/hiltech"
    private val user =
        System.getenv("HILTECH_DB_USER")
            ?: "hiltech"
    private val password =
        System.getenv("HILTECH_DB_PASSWORD")
            ?: "hiltech"
    private val migrationPath =
        System.getenv("HILTECH_MIGRATIONS_PATH")
            ?: "database/migrations"

    @Test
    fun concurrentDuplicateExecutesOneSideEffectAndReplaysPriorResult() {
        assumeTrue(enabled)

        val fixture = fixture()
        val operationId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                "command=Probe|target=$targetId|value=42",
            )
        val start = CountDownLatch(1)
        val pool =
            Executors.newFixedThreadPool(2)

        try {
            val tasks =
                (1..2).map {
                    pool.submit<
                        IdempotentCommandExecution
                    > {
                        start.await(
                            10,
                            TimeUnit.SECONDS,
                        )
                        fixture.executor.execute(
                            spec =
                                IdempotentCommandSpec(
                                    operationId =
                                        operationId,
                                    actorUserId =
                                        fixture.actorOne,
                                    commandType =
                                        "Phase2Probe",
                                    targetType =
                                        "ContractProbe",
                                    targetId =
                                        targetId,
                                    requestFingerprint =
                                        fingerprint,
                                    correlationId =
                                        "corr-concurrent",
                                ),
                        ) {
                            Thread.sleep(150)
                            fixture.jdbc.update(
                                """
                                INSERT INTO audit_event (
                                    id,
                                    actor_user_id,
                                    action,
                                    target_type,
                                    target_id,
                                    occurred_at,
                                    correlation_id
                                )
                                VALUES (?, ?, ?, ?, ?, ?, ?)
                                """.trimIndent(),
                                UUID.randomUUID(),
                                fixture.actorOne,
                                "PHASE2_PROBE_APPLIED",
                                "ContractProbe",
                                targetId,
                                fixture.now
                                    .atOffset(
                                        ZoneOffset.UTC,
                                    ),
                                "corr-concurrent",
                            )

                            IdempotentCommandOutcome(
                                resultCode =
                                    "ACCEPTED",
                                resultPayloadJson =
                                    """{"state":"APPLIED","version":1}""",
                            )
                        }
                    }
                }

            start.countDown()

            val results =
                tasks.map {
                    it.get(
                        20,
                        TimeUnit.SECONDS,
                    )
                }

            assertEquals(
                1,
                results.count {
                    !it.replayed
                },
            )
            assertEquals(
                1,
                results.count {
                    it.replayed
                },
            )
            assertTrue(
                results.all {
                    it.outcome.resultCode ==
                        "ACCEPTED"
                },
            )

            val sideEffects =
                fixture.jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM audit_event
                    WHERE action =
                        'PHASE2_PROBE_APPLIED'
                      AND target_id = ?
                    """.trimIndent(),
                    Int::class.java,
                    targetId,
                )
            assertEquals(
                1,
                sideEffects,
            )

            val duplicate =
                fixture.executor.execute(
                    spec =
                        IdempotentCommandSpec(
                            operationId =
                                operationId,
                            actorUserId =
                                fixture.actorOne,
                            commandType =
                                "Phase2Probe",
                            targetType =
                                "ContractProbe",
                            targetId =
                                targetId,
                            requestFingerprint =
                                fingerprint,
                            correlationId =
                                "corr-duplicate",
                        ),
                ) {
                    error(
                        "duplicate replay executed side effect",
                    )
                }

            assertTrue(
                duplicate.replayed,
            )
            assertEquals(
                "ACCEPTED",
                duplicate.outcome.resultCode,
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun changedFingerprintAndCrossActorReuseFailClosed() {
        assumeTrue(enabled)

        val fixture = fixture()
        val operationId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val originalFingerprint =
            IdempotencyKeyContract.fingerprint(
                "value=one",
            )

        fixture.executor.execute(
            spec =
                IdempotentCommandSpec(
                    operationId =
                        operationId,
                    actorUserId =
                        fixture.actorOne,
                    commandType =
                        "Phase2Probe",
                    targetType =
                        "ContractProbe",
                    targetId =
                        targetId,
                    requestFingerprint =
                        originalFingerprint,
                    correlationId =
                        "corr-original",
                ),
        ) {
            IdempotentCommandOutcome(
                resultCode = "ACCEPTED",
                resultPayloadJson =
                    """{"value":"one"}""",
            )
        }

        val changed =
            assertThrows<
                ProductApiException
            > {
                fixture.executor.execute(
                    spec =
                        IdempotentCommandSpec(
                            operationId =
                                operationId,
                            actorUserId =
                                fixture.actorOne,
                            commandType =
                                "Phase2Probe",
                            targetType =
                                "ContractProbe",
                            targetId =
                                targetId,
                            requestFingerprint =
                                IdempotencyKeyContract
                                    .fingerprint(
                                        "value=two",
                                    ),
                            correlationId =
                                "corr-changed",
                        ),
                ) {
                    error(
                        "changed fingerprint executed",
                    )
                }
            }

        assertEquals(
            "IDEMPOTENCY_KEY_REUSED",
            changed.code,
        )

        val crossActor =
            assertThrows<
                ProductApiException
            > {
                fixture.executor.execute(
                    spec =
                        IdempotentCommandSpec(
                            operationId =
                                operationId,
                            actorUserId =
                                fixture.actorTwo,
                            commandType =
                                "Phase2Probe",
                            targetType =
                                "ContractProbe",
                            targetId =
                                targetId,
                            requestFingerprint =
                                originalFingerprint,
                            correlationId =
                                "corr-other-actor",
                        ),
                ) {
                    error(
                        "cross actor replay executed",
                    )
                }
            }

        assertEquals(
            "IDEMPOTENCY_KEY_REUSED",
            crossActor.code,
        )
    }

    @Test
    fun preexistingStartedOperationDoesNotBlindlyRerun() {
        assumeTrue(enabled)

        val fixture = fixture()
        val operationId = UUID.randomUUID()
        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                "ambiguous",
            )

        fixture.jdbc.update(
            """
            INSERT INTO idempotent_operation (
                operation_id,
                actor_user_id,
                command_type,
                target_type,
                target_id,
                request_fingerprint,
                state,
                result_code,
                result_payload,
                created_at,
                completed_at,
                correlation_id
            )
            VALUES (
                ?, ?, 'Phase2Probe',
                'ContractProbe', NULL, ?,
                'STARTED', NULL, NULL,
                ?, NULL, 'corr-started'
            )
            """.trimIndent(),
            operationId,
            fixture.actorOne,
            fingerprint,
            fixture.now
                .atOffset(
                    ZoneOffset.UTC,
                ),
        )

        var executed = false
        val failure =
            assertThrows<
                ProductApiException
            > {
                fixture.executor.execute(
                    spec =
                        IdempotentCommandSpec(
                            operationId =
                                operationId,
                            actorUserId =
                                fixture.actorOne,
                            commandType =
                                "Phase2Probe",
                            targetType =
                                "ContractProbe",
                            requestFingerprint =
                                fingerprint,
                            correlationId =
                                "corr-retry-started",
                        ),
                ) {
                    executed = true
                    IdempotentCommandOutcome(
                        "ACCEPTED",
                    )
                }
            }

        assertEquals(
            "OPERATION_IN_PROGRESS",
            failure.code,
        )
        assertTrue(
            failure.retryable,
        )
        assertFalse(executed)
    }

    @Test
    fun idempotencyHeaderMustMatchBodyOperationId() {
        val operationId =
            UUID.randomUUID()

        assertEquals(
            operationId,
            IdempotencyKeyContract
                .requireMatches(
                    operationId.toString(),
                    operationId,
                ),
        )

        val mismatch =
            assertThrows<
                ProductApiException
            > {
                IdempotencyKeyContract
                    .requireMatches(
                        UUID.randomUUID()
                            .toString(),
                        operationId,
                    )
            }

        assertEquals(
            "IDEMPOTENCY_KEY_MISMATCH",
            mismatch.code,
        )
    }

    private fun fixture(): Fixture {
        Flyway.configure()
            .dataSource(
                url,
                user,
                password,
            )
            .locations(
                "filesystem:$migrationPath",
            )
            .load()
            .migrate()

        val dataSource =
            DriverManagerDataSource(
                url,
                user,
                password,
            )
        val jdbc =
            JdbcTemplate(dataSource)
        val now =
            Instant.parse(
                "2026-09-19T09:00:00Z",
            )

        val organizationId =
            UUID.randomUUID()
        val actorOne =
            UUID.randomUUID()
        val actorTwo =
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
                'Phase 2 Contract',
                'Phase 2 Contract',
                'HILTECH',
                'ACTIVE',
                ?,
                1
            )
            """.trimIndent(),
            organizationId,
            "P2-" +
                UUID.randomUUID()
                    .toString()
                    .take(8),
            now.atOffset(
                ZoneOffset.UTC,
            ),
        )

        fun insertActor(
            actorId: UUID,
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
                    ?,
                    'phase2-contract',
                    ?,
                    'ACTIVE',
                    ?,
                    ?,
                    1
                )
                """.trimIndent(),
                actorId,
                "subject-" + actorId,
                organizationId,
                now.atOffset(
                    ZoneOffset.UTC,
                ),
            )
        }

        insertActor(actorOne)
        insertActor(actorTwo)

        val executor =
            JdbcIdempotentCommandExecutor(
                jdbc = jdbc,
                transactionManager =
                    DataSourceTransactionManager(
                        dataSource,
                    ),
                clock =
                    Clock.fixed(
                        now,
                        ZoneOffset.UTC,
                    ),
            )

        return Fixture(
            jdbc = jdbc,
            executor = executor,
            actorOne = actorOne,
            actorTwo = actorTwo,
            now = now,
        )
    }

    private data class Fixture(
        val jdbc: JdbcTemplate,
        val executor:
            JdbcIdempotentCommandExecutor,
        val actorOne: UUID,
        val actorTwo: UUID,
        val now: Instant,
    )
}
