package com.hiltech.server.platform.command

import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import com.hiltech.server.telemetry.SafeTelemetry
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Component
class JdbcIdempotentCommandExecutor(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    private val telemetry: HiltechTelemetryRuntime,
) : IdempotentCommandExecutor {
    private val transaction =
        TransactionTemplate(
            transactionManager,
        )

    override fun execute(
        spec: IdempotentCommandSpec,
        action: () -> IdempotentCommandOutcome,
    ): IdempotentCommandExecution {
        val span =
            telemetry.tracer
                .spanBuilder(
                    "hiltech.command",
                )
                .setAllAttributes(
                    SafeTelemetry.attributes(
                        mapOf(
                            "hiltech.correlation_id" to
                                spec.correlationId,
                            "hiltech.operation_id" to
                                spec.operationId.toString(),
                            "hiltech.command_type" to
                                spec.commandType,
                            "hiltech.object_type" to
                                spec.targetType,
                            "hiltech.module" to
                                "platform.command",
                        ),
                    ),
                )
                .startSpan()

        try {
            val execution =
                requireNotNull(
                    transaction.execute {
                        executeInTransaction(
                            spec = spec,
                            action = action,
                        )
                    },
                )

            span.setAllAttributes(
                SafeTelemetry.attributes(
                    mapOf(
                        "hiltech.result_code" to
                            execution.outcome
                                .resultCode,
                    ),
                ),
            )

            return execution
        } catch (
            failure: ProductApiException,
        ) {
            span.setAllAttributes(
                SafeTelemetry.attributes(
                    mapOf(
                        "hiltech.error_code" to
                            failure.code,
                    ),
                ),
            )
            throw failure
        } finally {
            span.end()
        }
    }

    private fun executeInTransaction(
        spec: IdempotentCommandSpec,
        action: () -> IdempotentCommandOutcome,
    ): IdempotentCommandExecution {
        val startedAt =
            clock.instant()

        val inserted =
            jdbc.update(
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
                    ?, ?, ?, ?, ?, ?,
                    'STARTED',
                    NULL,
                    NULL,
                    ?,
                    NULL,
                    ?
                )
                ON CONFLICT (operation_id)
                DO NOTHING
                """.trimIndent(),
                spec.operationId,
                spec.actorUserId,
                spec.commandType,
                spec.targetType,
                spec.targetId,
                spec.requestFingerprint,
                startedAt.atOffset(
                    ZoneOffset.UTC,
                ),
                spec.correlationId,
            )

        if (inserted == 0) {
            return replayExisting(
                spec,
            )
        }

        val outcome = action()

        val applied =
            jdbc.update(
                """
                UPDATE idempotent_operation
                SET state = 'APPLIED',
                    result_code = ?,
                    result_payload =
                        CAST(? AS jsonb),
                    completed_at = ?
                WHERE operation_id = ?
                  AND state = 'STARTED'
                """.trimIndent(),
                outcome.resultCode,
                outcome.resultPayloadJson,
                clock.instant()
                    .atOffset(
                        ZoneOffset.UTC,
                    ),
                spec.operationId,
            )

        check(applied == 1) {
            "Idempotency record was not in STARTED state."
        }

        return IdempotentCommandExecution(
            outcome = outcome,
            replayed = false,
        )
    }

    private fun replayExisting(
        spec: IdempotentCommandSpec,
    ): IdempotentCommandExecution {
        val existing =
            jdbc.query(
                """
                SELECT
                    actor_user_id,
                    command_type,
                    target_type,
                    target_id,
                    request_fingerprint,
                    state,
                    result_code,
                    result_payload::text
                        AS result_payload
                FROM idempotent_operation
                WHERE operation_id = ?
                """.trimIndent(),
                { rs, _ ->
                    ExistingOperation(
                        actorUserId =
                            rs.getObject(
                                "actor_user_id",
                                UUID::class.java,
                            ),
                        commandType =
                            rs.getString(
                                "command_type",
                            ),
                        targetType =
                            rs.getString(
                                "target_type",
                            ),
                        targetId =
                            rs.getObject(
                                "target_id",
                                UUID::class.java,
                            ),
                        requestFingerprint =
                            rs.getString(
                                "request_fingerprint",
                            ),
                        state =
                            rs.getString("state"),
                        resultCode =
                            rs.getString(
                                "result_code",
                            ),
                        resultPayloadJson =
                            rs.getString(
                                "result_payload",
                            ),
                    )
                },
                spec.operationId,
            ).singleOrNull()
                ?: throw ProductApiException(
                    code =
                        "IDEMPOTENCY_STATE_UNAVAILABLE",
                    message =
                        "The prior operation state is unavailable.",
                    status =
                        HttpStatus.SERVICE_UNAVAILABLE,
                    retryable = true,
                )

        validateSameSemanticOperation(
            spec = spec,
            existing = existing,
        )

        return when (existing.state) {
            "APPLIED" ->
                IdempotentCommandExecution(
                    outcome =
                        IdempotentCommandOutcome(
                            resultCode =
                                requireNotNull(
                                    existing.resultCode,
                                ),
                            resultPayloadJson =
                                existing
                                    .resultPayloadJson,
                        ),
                    replayed = true,
                )

            "STARTED" ->
                throw ProductApiException(
                    code =
                        "OPERATION_IN_PROGRESS",
                    message =
                        "This operation is already in progress.",
                    status =
                        HttpStatus.CONFLICT,
                    retryable = true,
                )

            "FAILED" ->
                throw ProductApiException(
                    code =
                        "OPERATION_PREVIOUSLY_FAILED",
                    message =
                        "This operation already has a terminal failure.",
                    status =
                        HttpStatus.CONFLICT,
                    retryable = false,
                )

            else ->
                throw ProductApiException(
                    code =
                        "IDEMPOTENCY_STATE_UNAVAILABLE",
                    message =
                        "The prior operation state is unavailable.",
                    status =
                        HttpStatus.SERVICE_UNAVAILABLE,
                    retryable = true,
                )
        }
    }

    private fun validateSameSemanticOperation(
        spec: IdempotentCommandSpec,
        existing: ExistingOperation,
    ) {
        val same =
            existing.actorUserId ==
                spec.actorUserId &&
                existing.commandType ==
                spec.commandType &&
                existing.targetType ==
                spec.targetType &&
                existing.targetId ==
                spec.targetId &&
                existing.requestFingerprint ==
                spec.requestFingerprint

        if (!same) {
            throw ProductApiException(
                code =
                    "IDEMPOTENCY_KEY_REUSED",
                message =
                    "This operation ID is already bound to another command.",
                status =
                    HttpStatus.CONFLICT,
            )
        }
    }

    private data class ExistingOperation(
        val actorUserId: UUID,
        val commandType: String,
        val targetType: String?,
        val targetId: UUID?,
        val requestFingerprint: String?,
        val state: String,
        val resultCode: String?,
        val resultPayloadJson: String?,
    )
}
